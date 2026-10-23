package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * 快读（2026-10-21，模拟声浪）：谁要哪几个浮点读得更勤，合起来按最短的间隔读所有人要的信号。
 *
 * <p>算错的表现：声浪的油门晚 200 ms 才动（没快读上）；或者没人要了还在 20 ms 一遍地读车机服务（没停）；
 * 或者把订阅着的离散信号也拉进快读（白读）。</p>
 */
public class FastReadsTest {

    @Test
    public void oneUserGetsWhatItAskedFor() {
        FastReads f = new FastReads();
        assertNull(f.plan());
        FastReads.Plan plan = f.acquire("drive-sound", EnumSet.of(Signal.SPEED, Signal.THROTTLE_DEPTH), 50L);
        assertEquals(EnumSet.of(Signal.SPEED, Signal.THROTTLE_DEPTH), plan.signals);
        assertEquals(50L, plan.intervalMs);
        assertEquals(Arrays.asList("drive-sound"), f.names());
    }

    @Test
    public void severalUsersMergeSignalsAndTakeTheShortestInterval() {
        FastReads f = new FastReads();
        f.acquire("a", EnumSet.of(Signal.SPEED), 100L);
        FastReads.Plan plan = f.acquire("b", EnumSet.of(Signal.BRAKE_DEPTH), 40L);
        assertEquals(EnumSet.of(Signal.SPEED, Signal.BRAKE_DEPTH), plan.signals);
        assertEquals(40L, plan.intervalMs);
        plan = f.release("b");
        assertEquals("走了一个，剩下的照它自己的", EnumSet.of(Signal.SPEED), plan.signals);
        assertEquals(100L, plan.intervalMs);
        assertNull("最后一个走了：不再快读", f.release("a"));
        assertNull("没登记过也无害", f.release("nobody"));
    }

    @Test
    public void reAcquiringUnderTheSameNameReplacesTheOldRequest() {
        FastReads f = new FastReads();
        f.acquire("a", EnumSet.of(Signal.SPEED), 100L);
        FastReads.Plan plan = f.acquire("a", EnumSet.of(Signal.THROTTLE_DEPTH), 60L);
        assertEquals(EnumSet.of(Signal.THROTTLE_DEPTH), plan.signals);
        assertEquals(60L, plan.intervalMs);
    }

    @Test
    public void onlyFloatSignalsAreReadFast() {
        FastReads f = new FastReads();
        FastReads.Plan plan = f.acquire("a", EnumSet.of(Signal.SPEED, Signal.GEAR, Signal.DRIVE_MODE), 50L);
        assertEquals("离散信号本来就是订阅的，不快读", EnumSet.of(Signal.SPEED), plan.signals);
        FastReads.Plan withB = f.acquire("b", EnumSet.of(Signal.GEAR), 50L);
        assertEquals("一个浮点都没有的登记不算：还是 a 的读法", EnumSet.of(Signal.SPEED), withB.signals);
        assertEquals(Arrays.asList("a"), f.names());
    }

    @Test
    public void theIntervalIsKeptWithinSaneBounds() {
        FastReads f = new FastReads();
        assertEquals(FastReads.MIN_INTERVAL_MS, f.acquire("a", EnumSet.of(Signal.SPEED), 1L).intervalMs);
        assertEquals(EcarxSource.FLOAT_POLL_MS, f.acquire("a", EnumSet.of(Signal.SPEED), 10_000L).intervalMs);
        assertTrue(FastReads.MIN_INTERVAL_MS < EcarxSource.FLOAT_POLL_MS);
    }
}
