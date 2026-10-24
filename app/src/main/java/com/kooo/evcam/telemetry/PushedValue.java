package com.kooo.evcam.telemetry;

/**
 * 车机推来的一个浮点值（{@link Telemetry#pushed}）：一份不可变快照，带收到的时刻、第几次、平均隔多久。哪个线程都能读。
 */
public final class PushedValue {

    private static final PushedValue EMPTY = new PushedValue(Float.NaN, 0L, 0L, -1f);

    /** 归一后的值（{@link Signal#decode}）；空的是 NaN。 */
    public final float value;
    /** 收到的时刻（{@code SystemClock.elapsedRealtime}，毫秒）；空的是 0。 */
    public final long atMs;
    /** 这一轮快读以来第几次收到（每推一次 +1）；空的是 0。 */
    public final long count;
    /** 平均隔多久推一次（毫秒，只算相隔不到半秒的两次：踩着、开着的时候）；还不知道是 -1。 */
    public final float everyMs;

    PushedValue(float value, long atMs, long count, float everyMs) {
        this.value = value;
        this.atMs = atMs;
        this.count = count;
        this.everyMs = everyMs;
    }

    /** 又推来一次：值、时刻，次数 +1，间隔接着平均。 */
    PushedValue next(float newValue, long newAtMs) {
        float every = everyMs;
        long gap = newAtMs - atMs;
        if (count > 0L && gap > 0L && gap < 500L) {
            every = every < 0f ? gap : every + (gap - every) * 0.1f;
        }
        return new PushedValue(newValue, newAtMs, count + 1, every);
    }

    public static PushedValue empty() {
        return EMPTY;
    }
}
