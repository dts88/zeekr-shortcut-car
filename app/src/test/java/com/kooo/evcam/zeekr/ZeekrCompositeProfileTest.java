package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link ZeekrCompositeProfile} 选择逻辑的测试。
 *
 * <p>这里守住的核心安全规则是：<b>只从 HAL 真正声明的尺寸里选，一个都没有就返回
 * 「没有」</b>。凭空猜一个尺寸去开相机只会黑屏或崩溃。</p>
 */
public class ZeekrCompositeProfileTest {

    @Test
    public void prefersMeasuredVerticalCompositeOverEverythingElse() {
        int[][] declared = {
                {1920, 1080},
                {5120, 1280},
                {1280, 5140},
                {3840, 2160},
        };
        assertEquals("应优先选实测的 1280x5140", 2,
                ZeekrCompositeProfile.selectCompositeIndex(declared));
    }

    @Test
    public void fallsBackThroughKnownSizesInPreferenceOrder() {
        assertEquals(0, ZeekrCompositeProfile.selectCompositeIndex(
                new int[][]{{1280, 5120}, {5120, 1280}}));
        assertEquals(1, ZeekrCompositeProfile.selectCompositeIndex(
                new int[][]{{1920, 1080}, {5120, 1280}}));
    }

    @Test
    public void acceptsUnknownButPlausibleStripAndPrefersTheLargest() {
        int[][] declared = {
                {640, 2570},
                {1280, 5200},
                {1920, 1080},
        };
        assertEquals("同为长条时取像素最多的", 1,
                ZeekrCompositeProfile.selectCompositeIndex(declared));
    }

    @Test
    public void neverInventsASizeWhenNothingLooksLikeAComposite() {
        int[][] declared = {
                {1920, 1080},
                {1280, 800},
                {3840, 2160},
                {640, 480},
        };
        assertEquals("没有合成流候选时必须返回 -1", -1,
                ZeekrCompositeProfile.selectCompositeIndex(declared));
    }

    @Test
    public void handlesEmptyAndNullInput() {
        assertEquals(-1, ZeekrCompositeProfile.selectCompositeIndex(null));
        assertEquals(-1, ZeekrCompositeProfile.selectCompositeIndex(new int[][]{}));
        assertEquals(-1, ZeekrCompositeProfile.selectCompositeIndex(new int[][]{null, null}));
    }

    @Test
    public void knownSizeBeatsALargerUnknownStrip() {
        int[][] declared = {
                {2000, 8000},
                {1280, 5140},
        };
        assertEquals("已知实测尺寸优先于像素更多的推断尺寸", 1,
                ZeekrCompositeProfile.selectCompositeIndex(declared));
    }
}
