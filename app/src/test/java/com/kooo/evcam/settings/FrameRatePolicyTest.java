package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FrameRatePolicy} 的单元测试。
 *
 * <p>最要紧的一条是<b>界面标的数必须等于实际录的数</b> ——
 * 「显示值和实际值对不上」是这个项目反复踩过的坑，这次把它钉住。</p>
 */
public class FrameRatePolicyTest {

    /** 标签里的数字必须就是实际会用的帧率。 */
    @Test
    public void theLabelStatesTheRateThatWillActuallyBeUsed() {
        int actual = FrameRatePolicy.standardFrameRate(FrameRatePolicy.RECORDER_MAX_FPS);
        assertTrue("标签里应当出现实际帧率 " + actual + "，实际标签: " + FrameRatePolicy.autoLabel(),
                FrameRatePolicy.autoLabel().contains(String.valueOf(actual)));
    }

    /** 「原始帧率」在这台车机上就是 25 —— 因为录制链路把 25 当作硬件上限传进去。 */
    @Test
    public void autoResolvesTo25OnThisRecorder() {
        assertEquals(25, FrameRatePolicy.RECORDER_MAX_FPS);
        assertEquals(25, FrameRatePolicy.standardFrameRate(FrameRatePolicy.RECORDER_MAX_FPS));
    }

    @Test
    public void ratesNearThirtyArePassedThrough() {
        for (int fps = 25; fps <= 35; fps++) {
            assertEquals(fps, FrameRatePolicy.standardFrameRate(fps));
        }
    }

    @Test
    public void highRatesComeDownToThirtyOrBelow() {
        assertEquals(30, FrameRatePolicy.standardFrameRate(60));
        assertEquals(30, FrameRatePolicy.standardFrameRate(120));
        for (int fps = 36; fps <= 240; fps++) {
            int result = FrameRatePolicy.standardFrameRate(fps);
            assertTrue("fps=" + fps + " 得到 " + result, result >= 15 && result <= 30);
        }
    }

    @Test
    public void lowRatesAreUsedAsIs() {
        assertEquals(24, FrameRatePolicy.standardFrameRate(24));
        assertEquals(15, FrameRatePolicy.standardFrameRate(15));
    }

    @Test
    public void anUnknownRateFallsBackToThirty() {
        assertEquals(30, FrameRatePolicy.standardFrameRate(0));
        assertEquals(30, FrameRatePolicy.standardFrameRate(-5));
    }

    /** 标签得让人看懂它是「跟随默认」而不是某个固定档位。 */
    @Test
    public void theLabelStillReadsAsAutomatic() {
        assertTrue(FrameRatePolicy.autoLabel().startsWith("原始帧率"));
        assertTrue(FrameRatePolicy.autoLabel().contains("fps"));
    }
}
