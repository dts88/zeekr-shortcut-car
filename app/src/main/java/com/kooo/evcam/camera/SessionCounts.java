package com.kooo.evcam.camera;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份会话配好以来相机回了什么：开始了几次（{@code onCaptureStarted}）、几次结果（{@code onCaptureCompleted}）、
 * 几次失败（第一次的原因、时刻、帧号）、每一样输出丢了几次缓冲（{@code onCaptureBufferLost}）。只计数，不判该不该动、不碰相机。
 *
 * <h3>为什么要（项目所有者 2026-10-14 批准，2.11.4 第 1a 项）</h3>
 *
 * <p>2.11 以来「开了、配好了、15 秒一帧不出」的那几次（后座舱 2 次、环视 3 次），黑匣子里只有一行「15 秒没出画面」：
 * 是相机 HAL 收了请求一个都不回、是我们的输出接不住（丢缓冲、差不多每秒一次失败）、还是开始了没有结果，分不出来 ——
 * 失败计数以前只进日志、5 秒限一行（StallWatch），10-13 那一次的日志早就滚掉了。这一份照原样写进 15 秒判定那一行、
 * 和那一台设备关掉时那一行（SingleCamera），下一次没画面时那一行自己就分得出是哪一种（{@link Kind}）。</p>
 *
 * <p>相机线程写（出帧回调），主线程、相机线程读（{@link #snapshot}）：方法都上锁，回调里只是几次加法。纯 Java（{@code SessionCountsTest}）。</p>
 */
public final class SessionCounts {

    /** 这一份会话算哪一种。只对没出过画面的有意义；出过结果的就是 {@link #FRAMED}。 */
    public enum Kind {
        /** 还没配好（开了设备、会话没配好就到点了 / 关了）。 */
        NOT_CONFIGURED,
        /** 出过结果：有画面。 */
        FRAMED,
        /**
         * 一个都没回：没开始、没结果、至多一次失败、没丢缓冲 —— HAL 收了请求不回（10-12 20:57 那一次：配好后约 8 秒一次失败，
         * 原因 0、丢缓冲 0；是框架「HAL 一直没还缓冲」的那种超时）。
         */
        HAL_HOLDING,
        /**
         * 我们的输出接不住：HAL 开始出帧了，丢过缓冲（录像、预览、出帧口的消费者拿不出缓冲）。只在开始过之后才这样算
         * （2.11.5 第 D0 项）：HAL 一帧都没开始时丢的缓冲是 HAL 的错误（{@code ERROR_CAMERA_BUFFER}），不是我们接不住。
         */
        STARVING,
        /** 开始了、没有结果、没丢缓冲：HAL 在出帧，结果迟迟不回。 */
        LATE_START,
        /**
         * 相机 HAL 没出过一帧（没开始、没结果），只回了错误：失败不止一次，或者丢过缓冲（2.11.5 第 D0 项，代替以前的 FAILING）。
         * 10-14 装新版后第一次开环视「开始 0、结果 0、失败 2、丢缓冲 main-preview 1」、亮屏重开后座舱「丢缓冲 record 1」
         * 以前被写成「我们的输出接不住」：开始 0 时 HAL 连快门都没发，AOSP 12 里消费者拿不出缓冲报的是请求失败、不是丢缓冲。
         */
        HAL_FAILING
    }

    /** 某一刻的一份（不可变）。时刻都是 {@code SystemClock.uptimeMillis()}，和调度同一个钟；0 = 没有。 */
    public static final class Snapshot {
        /** 会话配好的时刻；0 = 还没配好。 */
        public final long configuredAt;
        /** 重复请求发出去的时刻；0 = 还没发。 */
        public final long repeatingAt;
        public final long starts;
        public final long results;
        public final long failures;
        /** 第一次失败：时刻、原因（{@code CaptureFailure.getReason}）、帧号；没失败过是 0 / -1 / -1。 */
        public final long firstFailAt;
        public final int firstFailReason;
        public final long firstFailFrame;
        /** 每一样输出丢了几次缓冲（SingleCamera.describeTarget 的记号：record、main-preview、mirror、sink、jpeg），按第一次丢的先后。 */
        public final Map<String, Long> lost;

        Snapshot(long configuredAt, long repeatingAt, long starts, long results, long failures, long firstFailAt,
                 int firstFailReason, long firstFailFrame, Map<String, Long> lost) {
            this.configuredAt = configuredAt;
            this.repeatingAt = repeatingAt;
            this.starts = starts;
            this.results = results;
            this.failures = failures;
            this.firstFailAt = firstFailAt;
            this.firstFailReason = firstFailReason;
            this.firstFailFrame = firstFailFrame;
            this.lost = Collections.unmodifiableMap(new LinkedHashMap<>(lost));
        }

        /** 一共丢了几次缓冲（各样输出加起来）。 */
        public long lostTotal() {
            long total = 0;
            for (long n : lost.values()) {
                total += n;
            }
            return total;
        }

        /** 算哪一种（{@link SessionCounts#classify}）。 */
        public Kind kind() {
            return classify(configuredAt != 0, starts, results, failures, lostTotal());
        }

        /** 日志用的记号，不是给人读的句子。 */
        @Override
        public String toString() {
            return kind() + " starts=" + starts + " results=" + results + " failures=" + failures + " lost=" + lost;
        }
    }

    /**
     * 一份会话算哪一种，一条规矩、先到先得（项目所有者 2026-10-15 批准，2.11.5 第 D0 项）：HAL 开始出帧以后才怪我们的输出。
     *
     * <ol>
     *   <li>没配好 → {@link Kind#NOT_CONFIGURED}；</li>
     *   <li>出过结果 → {@link Kind#FRAMED}；</li>
     *   <li>开始过 → 丢过缓冲是 {@link Kind#STARVING}（我们接不住），没丢是 {@link Kind#LATE_START}；</li>
     *   <li>没开始、至多一次失败、没丢缓冲 → {@link Kind#HAL_HOLDING}（HAL 收了请求不回）；</li>
     *   <li>其余（没开始，失败不止一次或者丢过缓冲）→ {@link Kind#HAL_FAILING}。</li>
     * </ol>
     *
     * <p>以前丢缓冲排在「开始了」前面，开始 0 的那几次也被算成我们接不住（rel_final §6）。诊断报告读存下的旧记录时也照这一条重算
     * （{@code StepStats.Entry#kind}），只有这一处判。</p>
     *
     * @param configured 会话配好了
     * @param lost       各样输出一共丢了几次缓冲
     */
    public static Kind classify(boolean configured, long starts, long results, long failures, long lost) {
        if (!configured) {
            return Kind.NOT_CONFIGURED;
        }
        if (results > 0) {
            return Kind.FRAMED;
        }
        if (starts > 0) {
            return lost > 0 ? Kind.STARVING : Kind.LATE_START;
        }
        if (failures <= 1 && lost == 0) {
            return Kind.HAL_HOLDING;
        }
        return Kind.HAL_FAILING;
    }

    /** 还没有一份会话。 */
    static final Snapshot NONE = new Snapshot(0, 0, 0, 0, 0, 0, -1, -1, Collections.<String, Long>emptyMap());

    private long configuredAt;
    private long repeatingAt;
    private long starts;
    private long results;
    private long failures;
    private long firstFailAt;
    private int firstFailReason = -1;
    private long firstFailFrame = -1;
    private final Map<String, Long> lost = new LinkedHashMap<>();

    /** 新的一台设备（开好了）：上一台的那一份不再算，会话还没配好。 */
    synchronized void clear() {
        reset(0);
    }

    /** 一份新会话配好了：从这一刻重新数。 */
    synchronized void reset(long configuredAtUptime) {
        configuredAt = configuredAtUptime;
        repeatingAt = 0;
        starts = 0;
        results = 0;
        failures = 0;
        firstFailAt = 0;
        firstFailReason = -1;
        firstFailFrame = -1;
        lost.clear();
    }

    /** 重复请求发出去了（只记这一份会话的第一次：画面调节、录像换输出那几次重发不算）。 */
    synchronized void repeating(long atUptime) {
        if (repeatingAt == 0) {
            repeatingAt = atUptime;
        }
    }

    synchronized void started() {
        starts++;
    }

    synchronized void completed() {
        results++;
    }

    synchronized void failed(long atUptime, int reason, long frameNumber) {
        if (failures == 0) {
            firstFailAt = atUptime;
            firstFailReason = reason;
            firstFailFrame = frameNumber;
        }
        failures++;
    }

    synchronized void lost(String target) {
        String key = target == null ? "?" : target;
        Long n = lost.get(key);
        lost.put(key, n == null ? 1L : n + 1);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(configuredAt, repeatingAt, starts, results, failures, firstFailAt, firstFailReason,
                firstFailFrame, lost);
    }
}
