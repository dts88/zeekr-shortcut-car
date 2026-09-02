package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FrameRateMeter} 的单元测试。
 *
 * <p>时间由调用方传进来，所以这里能喂一段确定的时间轴，
 * 不用真的等两秒钟。</p>
 */
public class FrameRateMeterTest {

    /** 每 40ms 一帧就是 25fps。 */
    private static float measure(FrameRateMeter meter, int intervalMs, int frames) {
        long t = 1000L;
        for (int i = 0; i < frames; i++) {
            meter.onFrame(t);
            t += intervalMs;
        }
        return meter.fps();
    }

    @Test
    public void countsTwentyFiveFramesPerSecond() {
        FrameRateMeter meter = new FrameRateMeter(1000L);
        float fps = measure(meter, 40, 60);
        assertEquals(25f, fps, 1.5f);
    }

    @Test
    public void countsFifteenFramesPerSecond() {
        FrameRateMeter meter = new FrameRateMeter(1000L);
        float fps = measure(meter, 66, 40);
        assertEquals(15f, fps, 1.5f);
    }

    /**
     * 第一个窗口结算之前没有读数。
     *
     * <p>这一条要紧：界面上「尚未测出」和「测出来是 0」必须分得开 ——
     * 前者是还没到时候，后者是这一路根本没在出帧。</p>
     */
    @Test
    public void noReadingBeforeTheFirstWindowCloses() {
        FrameRateMeter meter = new FrameRateMeter(2000L);
        meter.onFrame(1000L);
        meter.onFrame(1100L);
        assertFalse(meter.hasReading());
        assertEquals(0f, meter.fps(), 0.001f);
        assertEquals(2, meter.totalFrames());
    }

    @Test
    public void windowClosesOnceEnoughTimeHasPassed() {
        FrameRateMeter meter = new FrameRateMeter(1000L);
        assertFalse(meter.onFrame(1000L));
        assertFalse(meter.onFrame(1500L));
        assertTrue(meter.onFrame(2100L));
        assertTrue(meter.hasReading());
    }

    @Test
    public void totalKeepsCountingAcrossWindows() {
        FrameRateMeter meter = new FrameRateMeter(1000L);
        measure(meter, 40, 100);
        assertEquals(100, meter.totalFrames());
    }

    @Test
    public void resetClearsEverything() {
        FrameRateMeter meter = new FrameRateMeter(1000L);
        measure(meter, 40, 60);
        meter.reset();
        assertFalse(meter.hasReading());
        assertEquals(0, meter.totalFrames());
    }
}
