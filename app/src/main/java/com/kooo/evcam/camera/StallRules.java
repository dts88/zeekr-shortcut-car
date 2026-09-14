package com.kooo.evcam.camera;

/**
 * 卡顿判定：多久没有信号算卡住、什么时候算恢复、一小时最多留几份报告。
 *
 * <p>纯逻辑，时间由调用方传入，见 {@code StallRulesTest}。</p>
 */
public final class StallRules {

    /**
     * 后视镜多久没有新画面算卡住。
     * 最省空间的配置也有 10fps，1.5 秒是十几帧没来 —— 人眼已经看得出「卡了」。
     */
    public static final long MIRROR_STALL_MS = 1500L;

    /**
     * 录制编码线程多久没收到帧算卡住。
     * 分段切换要收尾上一个文件、重建编码器，这期间本来就收不到帧，所以给得宽一些。
     */
    public static final long ENCODER_STALL_MS = 3000L;

    /** 主线程 / 相机线程上排着的任务多久没被执行，记一笔带栈的日志。 */
    public static final long LOOPER_STALL_MS = 1000L;

    /** 刚开始盯时给第一帧留的时间：相机刚接上、会话还在建的时候没有帧是正常的。 */
    public static final long ARM_GRACE_MS = 4000L;

    /** 一小时最多留几份完整报告。反复卡的时候头几份就够看了，不能把存储写满。 */
    public static final int MAX_REPORTS_PER_HOUR = 6;

    static final long HOUR_MS = 60L * 60L * 1000L;

    private StallRules() {
    }

    public enum Transition { NONE, STALLED, RECOVERED }

    /** 一路信号的卡顿状态，只在监测线程上读写。 */
    public static final class State {
        private boolean stalled;
        private long stalledSinceMs;

        public boolean isStalled() {
            return stalled;
        }

        /** 从什么时候开始卡的，也就是最后一次心跳的时刻。 */
        public long stalledSinceMs() {
            return stalledSinceMs;
        }
    }

    /**
     * 往前走一步。
     *
     * <p>一直卡着只报一次 STALLED，好了报一次 RECOVERED —— 否则卡住期间每次检查都会写一份报告。
     * 不再盯了（窗口收起、息屏、停止录制）也算结束。</p>
     *
     * @param watched 这一路此刻该不该有信号（窗口在屏幕上、正在录制）
     * @param ageMs   距上一次心跳多久
     */
    public static Transition step(State state, boolean watched, long ageMs, long thresholdMs, long nowMs) {
        boolean late = watched && ageMs > thresholdMs;
        if (late && !state.stalled) {
            state.stalled = true;
            state.stalledSinceMs = nowMs - ageMs;
            return Transition.STALLED;
        }
        if (!late && state.stalled) {
            state.stalled = false;
            return Transition.RECOVERED;
        }
        return Transition.NONE;
    }

    /** 报告限额：任意一小时之内最多 {@link #MAX_REPORTS_PER_HOUR} 份。只在监测线程上用。 */
    public static final class Budget {
        private final long[] stamps = new long[MAX_REPORTS_PER_HOUR];
        private int used;
        private int next;

        public boolean tryTake(long nowMs) {
            int recent = 0;
            for (int i = 0; i < used; i++) {
                if (nowMs - stamps[i] < HOUR_MS) {
                    recent++;
                }
            }
            if (recent >= stamps.length) {
                return false;
            }
            // 按时间顺序写成一个环，next 指着的永远是最旧的那份
            stamps[next] = nowMs;
            next = (next + 1) % stamps.length;
            used = Math.min(used + 1, stamps.length);
            return true;
        }
    }
}
