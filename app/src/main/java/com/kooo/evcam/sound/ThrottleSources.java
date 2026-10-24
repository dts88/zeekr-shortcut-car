package com.kooo.evcam.sound;

/**
 * 油门的两路来源赛跑（项目所有者 2026-10-22：这几个编码的轮询提到最快，看能不能比推送早）：推送、快读谁先带来一个新值就用谁，
 * 交给 {@link PedalTrack}。快读可能读到旧值（10-21 实车：同一个新值推送平均早 100 ms 到，快读那时还是上一个），所以快读带来的值
 * 要是最近 {@link #RECENT_MS} 里已经出现过，就当它是旧的、不收；推送不会旧，变了就收。顺带记下每个新值是谁先送到、领先另一路多少。
 *
 * <p>纯计算（{@code ThrottleSourcesTest}），声音线程上每块叫一次，不分配内存。</p>
 */
final class ThrottleSources {

    /** 快读带来的值最近这么久（毫秒）出现过，就当是旧值。 */
    static final long RECENT_MS = 600L;
    private static final int HISTORY = 16;

    private final float[] seenValue = new float[HISTORY];
    private final long[] seenAt = new long[HISTORY];
    private final boolean[] seenByPush = new boolean[HISTORY];
    private final boolean[] matched = new boolean[HISTORY];
    private int head;
    private int count;
    private float last = Float.NaN;
    private long lastPushCount = -1L;
    private long lastPollVersion = Long.MIN_VALUE;

    /** {@link #offer} 返回 true 时：交给踏板的新值（NaN = 读不到）和它到的时刻。 */
    float sample = Float.NaN;
    long sampleAt;
    /** 诊断：新值由推送先送到几次、平均领先快读多少；由快读先送到几次、平均领先推送多少（毫秒，还没有是 -1）。 */
    int pushFirst;
    int pollFirst;
    float pushLeadMs = -1f;
    float pollLeadMs = -1f;

    /**
     * 每块叫一次：推送（第几次、值、时刻）、快读（版本、值、时刻）。
     *
     * @return 有新值要交给踏板（{@link #sample}、{@link #sampleAt}）
     */
    boolean offer(long pushCount, float pushValue, long pushAtMs, long pollVersion, float pollValue, long pollAtMs) {
        boolean fresh = false;
        if (pushCount != lastPushCount) {
            lastPushCount = pushCount;
            if (pushCount > 0L && !Float.isNaN(pushValue)) {
                int i = find(pushValue, pushAtMs);
                if (i >= 0 && !seenByPush[i] && !matched[i]) {
                    // 快读先带来了这个值
                    matched[i] = true;
                    pollFirst++;
                    pollLeadMs = ema(pollLeadMs, pushAtMs - seenAt[i]);
                }
                if (pushValue != last) {
                    fresh = accept(pushValue, pushAtMs, true);
                }
            }
        }
        if (pollVersion != lastPollVersion) {
            lastPollVersion = pollVersion;
            if (!Float.isNaN(pollValue)) {
                int i = find(pollValue, pollAtMs);
                if (i >= 0 && seenByPush[i] && !matched[i]) {
                    // 推送先带来了这个值
                    matched[i] = true;
                    pushFirst++;
                    pushLeadMs = ema(pushLeadMs, pollAtMs - seenAt[i]);
                }
                if (pollValue != last && i < 0) {
                    fresh |= accept(pollValue, pollAtMs, false);
                }
            } else if (lastPushCount <= 0L && !Float.isNaN(last)) {
                // 两路都读不到了
                last = Float.NaN;
                sample = Float.NaN;
                sampleAt = pollAtMs;
                fresh = true;
            }
        }
        return fresh;
    }

    private boolean accept(float value, long atMs, boolean byPush) {
        seenValue[head] = value;
        seenAt[head] = atMs;
        seenByPush[head] = byPush;
        matched[head] = false;
        head = (head + 1) % HISTORY;
        count = Math.min(HISTORY, count + 1);
        last = value;
        sample = value;
        sampleAt = atMs;
        return true;
    }

    /** 最近 {@link #RECENT_MS} 里出现过这个值的最近一次；没有是 -1。 */
    private int find(float value, long nowMs) {
        for (int k = 1; k <= count; k++) {
            int i = (head - k + HISTORY) % HISTORY;
            if (nowMs - seenAt[i] > RECENT_MS) {
                return -1;
            }
            if (seenValue[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static float ema(float previous, long sampleMs) {
        return previous < 0f ? sampleMs : previous + (sampleMs - previous) * 0.1f;
    }
}
