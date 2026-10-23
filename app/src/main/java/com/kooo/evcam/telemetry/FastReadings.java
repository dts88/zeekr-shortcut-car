package com.kooo.evcam.telemetry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 快读那几个浮点信号最近一次读到的值（{@link FastReads}）：一份不可变快照，带读到的时刻。没读到的信号不在表里。
 * 哪个线程都能读（{@link Telemetry#fast}），读的时候不分配内存。
 */
public final class FastReadings {

    private static final FastReadings EMPTY = new FastReadings(new EnumMap<>(Signal.class), 0L, 0L);

    private final Map<Signal, Float> values;
    /** 读到的时刻（{@code SystemClock.elapsedRealtime}，毫秒）；空的是 0。 */
    public final long atMs;
    /** 每读一遍 +1。 */
    public final long version;

    FastReadings(Map<Signal, Float> values, long atMs, long version) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.atMs = atMs;
        this.version = version;
    }

    public static FastReadings empty() {
        return EMPTY;
    }

    /** 归一后的值（{@link Signal#decode}：车速 km/h、油门 %、刹车行程 %）；没读到是 null。 */
    public Float number(Signal signal) {
        return values.get(signal);
    }

    /** 读到多久了（毫秒）；一次都没读到是 -1。 */
    public long ageMs(long nowMs) {
        return atMs <= 0L ? -1L : Math.max(0L, nowMs - atMs);
    }
}
