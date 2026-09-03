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

    /**
     * 「原始帧率」这一档不许印任何数字。
     *
     * <p>它的含义是「不限制」，视频流给多少录多少。印一个具体的数会让人
     * 以为那是承诺 —— 而这一档恰恰不承诺任何数值。</p>
     */
    @Test
    public void autoLabelCarriesNoNumber() {
        String label = FrameRatePolicy.autoLabel();
        for (char c = '0'; c <= '9'; c++) {
            assertTrue("「原始帧率」的标签里不该出现数字，实际: " + label,
                    label.indexOf(c) < 0);
        }
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

    /** 标签得让人看懂它是「跟随视频流」而不是某个固定档位。 */
    @Test
    public void theLabelStillReadsAsAutomatic() {
        assertTrue(FrameRatePolicy.autoLabel().startsWith("原始帧率"));
    }
}
