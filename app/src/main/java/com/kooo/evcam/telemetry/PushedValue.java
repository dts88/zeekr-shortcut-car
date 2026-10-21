package com.kooo.evcam.telemetry;

/**
 * 车机推来的一个浮点值（{@link Telemetry#pushedThrottle}）：一份不可变快照，带收到的时刻和第几次。哪个线程都能读。
 */
public final class PushedValue {

    private static final PushedValue EMPTY = new PushedValue(Float.NaN, 0L, 0L);

    /** 归一后的值（{@link Signal#decode}）；空的是 NaN。 */
    public final float value;
    /** 收到的时刻（{@code SystemClock.elapsedRealtime}，毫秒）；空的是 0。 */
    public final long atMs;
    /** 这一轮快读以来第几次收到（每推一次 +1）；空的是 0。 */
    public final long count;

    PushedValue(float value, long atMs, long count) {
        this.value = value;
        this.atMs = atMs;
        this.count = count;
    }

    public static PushedValue empty() {
        return EMPTY;
    }
}
