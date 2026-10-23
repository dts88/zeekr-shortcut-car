package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

/** HUD 上盲区画面的位置、大小和样子（{@link HudPicture}）。 */
public class HudPictureTest {

    /**
     * 默认值（FerixzTanz 在他的 7X 上调的）放在 1440×480 上：高 235、宽 456；左灯离左边 108，右灯离右边 108；上下在剩余高度的 21%。
     */
    @Test
    public void theDefaultsOnTheSevenXHud() {
        HudPicture.Rect left = HudPicture.place(1440, 480, AppConfig.HUD_DEFAULT_HEIGHT, AppConfig.HUD_DEFAULT_SHAPE,
                AppConfig.HUD_DEFAULT_EDGE, AppConfig.HUD_DEFAULT_VERTICAL, false);
        assertEquals(235, left.height);
        assertEquals(456, left.width);
        assertEquals(108, left.left);
        assertEquals(51, left.top);
        HudPicture.Rect right = HudPicture.place(1440, 480, AppConfig.HUD_DEFAULT_HEIGHT, AppConfig.HUD_DEFAULT_SHAPE,
                AppConfig.HUD_DEFAULT_EDGE, AppConfig.HUD_DEFAULT_VERTICAL, true);
        assertEquals("右灯从右边量", 1440 - 456 - 108, right.left);
        assertEquals(left.top, right.top);
    }

    /** 离边 0 贴边、50 居中、100 贴对面；上下 0 贴顶、100 贴底。 */
    @Test
    public void edgeAndVerticalAreSharesOfTheRemainingSpace() {
        assertEquals(0, HudPicture.place(1440, 480, 50, 200, 0, 0, false).left);
        assertEquals((1440 - 480) / 2, HudPicture.place(1440, 480, 50, 200, 50, 0, false).left);
        assertEquals(1440 - 480, HudPicture.place(1440, 480, 50, 200, 100, 0, false).left);
        assertEquals(0, HudPicture.place(1440, 480, 50, 200, 100, 0, true).left);
        assertEquals(0, HudPicture.place(1440, 480, 50, 200, 0, 0, false).top);
        assertEquals(240, HudPicture.place(1440, 480, 50, 200, 0, 100, false).top);
    }

    /** 宽度最多一整个 HUD 宽，高度最多一整个 HUD 高；再小也不小于 48 px。 */
    @Test
    public void theSizeStaysOnTheHud() {
        HudPicture.Rect wide = HudPicture.place(1440, 480, 100, 300, 50, 50, false);
        assertEquals(480, wide.height);
        assertEquals(1440, wide.width);
        assertEquals(0, wide.left);
        HudPicture.Rect tiny = HudPicture.place(1440, 480, 1, 100, 0, 0, false);
        assertEquals(HudPicture.MIN_SIZE_PX, tiny.height);
        assertEquals(HudPicture.MIN_SIZE_PX, tiny.width);
    }

    /** 对比度：100 是原画面；默认 220 时 40% 灰压到 16%、枢轴不动、白还是白、黑还是黑。 */
    @Test
    public void contrastPushesTheGreysDownAroundThePivot() {
        assertTrue(HudPicture.toneIsIdentity(100));
        assertFalse(HudPicture.toneIsIdentity(AppConfig.HUD_DEFAULT_CONTRAST));
        assertEquals(102f, HudPicture.tone(100, 102f), 0.01f);
        assertEquals(HudPicture.TONE_PIVOT, HudPicture.tone(220, HudPicture.TONE_PIVOT), 0.01f);
        assertEquals(40.8f, HudPicture.tone(220, 102f), 0.01f);
        assertEquals(255f, HudPicture.tone(220, 255f), 0.01f);
        assertEquals(0f, HudPicture.tone(220, 0f), 0.01f);
        float[] m = HudPicture.toneMatrix(220);
        assertEquals(2.2f, m[0], 1e-6f);
        assertEquals(-1.2f * HudPicture.TONE_PIVOT, m[4], 1e-3f);
        assertEquals("透明度不动", 1f, m[18], 0f);
        assertEquals(0f, m[19], 0f);
    }

    /** 夜间模式在透明度上再乘一个比例；白天、读不到时不动。 */
    @Test
    public void nightDimsOnlyAtNight() {
        assertEquals(0.8f, HudPicture.opacity(80, 50, false), 1e-6f);
        assertEquals(0.4f, HudPicture.opacity(80, 50, true), 1e-6f);
        assertEquals(1f, HudPicture.opacity(100, 100, true), 1e-6f);
    }

    /** 渐隐带按短边算，最多短边的一半；0 是硬边。 */
    @Test
    public void theFadeFollowsTheShorterSide() {
        assertEquals(0f, HudPicture.fadePx(0, 456, 235), 0f);
        assertEquals(235 * 0.06f, HudPicture.fadePx(AppConfig.HUD_DEFAULT_FADE, 456, 235), 1e-3f);
        assertEquals(117.5f, HudPicture.fadePx(80, 456, 235), 1e-3f);
        assertEquals(0f, HudPicture.fadePx(10, 0, 235), 0f);
    }
}
