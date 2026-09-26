package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * {@link SampleRing} 的单元测试。
 *
 * <p>环留少了，掉盘时补写不回来；留多了，内存涨到被系统杀掉。哪些该留、哪些该丢，钉在这里。</p>
 */
public class SampleRingTest {

    private static final long SEC = 1000L;

    private static void add(SampleRing ring, long t, boolean key, int size) {
        ring.add(t * 1000L, t, key, ByteBuffer.allocate(size), size);
    }

    /** 每秒一帧，每 3 秒一个关键帧，从 0 到 untilMs。 */
    private static SampleRing fill(long untilMs) {
        SampleRing ring = new SampleRing();
        for (long t = 0; t <= untilMs; t += SEC) {
            add(ring, t, t % (3 * SEC) == 0, 10);
        }
        return ring;
    }

    @Test
    public void keepsLastFifteenSecondsEvenWhenSynced() {
        SampleRing ring = fill(30 * SEC);
        ring.markSyncedBefore(31 * SEC);
        add(ring, 31 * SEC, false, 10);
        // 最近 15 秒要留：从 16 秒起；截到关键帧（15 秒那一个）
        List<SampleRing.Sample> kept = ring.snapshot();
        assertEquals(15 * SEC, kept.get(0).wallMs);
        assertTrue(kept.get(0).keyframe);
        assertEquals(31 * SEC, kept.get(kept.size() - 1).wallMs);
    }

    @Test
    public void keepsUnsyncedSamplesBeyondFifteenSeconds() {
        SampleRing ring = fill(40 * SEC);
        // 什么都没确认落盘：一个都不丢（还没到上限）
        assertEquals(0L, ring.snapshot().get(0).wallMs);
        assertEquals(40 * SEC, ring.spanMs());
        // 确认到 20 秒：20 秒前、且早于 25 秒（40-15）的才能丢，截到关键帧 18 秒
        ring.markSyncedBefore(20 * SEC);
        add(ring, 41 * SEC, false, 10);
        assertEquals(18 * SEC, ring.snapshot().get(0).wallMs);
    }

    @Test
    public void cutsOnlyAtKeyframes() {
        SampleRing ring = new SampleRing();
        add(ring, 0, true, 10);
        add(ring, SEC, false, 10);
        add(ring, 2 * SEC, false, 10);
        add(ring, 3 * SEC, true, 10);
        add(ring, 4 * SEC, false, 10);
        ring.markSyncedBefore(100 * SEC);
        add(ring, 30 * SEC, false, 10);
        // 0、1、2 秒能丢，3 秒是关键帧且能丢，4 秒能丢，但 4 秒后面没有关键帧 —— 截到 3 秒
        assertEquals(3 * SEC, ring.snapshot().get(0).wallMs);
    }

    @Test
    public void dropsOldestGopWhenOverByteCap() {
        SampleRing ring = new SampleRing();
        int big = (int) (SampleRing.MAX_BYTES / 4) + 1;
        add(ring, 0, true, big);
        add(ring, SEC, false, big);
        add(ring, 2 * SEC, true, big);
        add(ring, 3 * SEC, false, big);
        assertTrue(ring.bytes() <= SampleRing.MAX_BYTES);
        add(ring, 4 * SEC, true, big);
        // 超了：丢掉第一段（0、1 秒）
        assertTrue(ring.bytes() <= SampleRing.MAX_BYTES);
        assertEquals(2 * SEC, ring.snapshot().get(0).wallMs);
    }

    @Test
    public void dropsOldestGopWhenOverTimeCap() {
        SampleRing ring = fill(SampleRing.MAX_MS + 5 * SEC);
        assertTrue(ring.spanMs() <= SampleRing.MAX_MS);
        assertTrue(ring.snapshot().get(0).keyframe);
    }

    @Test
    public void waitsForFirstKeyframe() {
        SampleRing ring = new SampleRing();
        add(ring, 0, false, 10);
        add(ring, SEC, false, 10);
        assertTrue(ring.isEmpty());
        add(ring, 2 * SEC, true, 10);
        add(ring, 3 * SEC, false, 10);
        assertEquals(2, ring.snapshot().size());
        assertEquals(20L, ring.bytes());
    }

    @Test
    public void clearEmptiesEverything() {
        SampleRing ring = fill(10 * SEC);
        ring.clear();
        assertTrue(ring.isEmpty());
        assertEquals(0L, ring.bytes());
        assertEquals(0L, ring.spanMs());
    }
}
