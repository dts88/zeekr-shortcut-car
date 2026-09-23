package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 按键模式那四个按钮的菱形摆法。
 *
 * <p>位置本身就是答案（上前、下后、左左、右右），所以<b>位置错了比字错了更糟</b>：
 * 字写错还能读出来不对，位置错了会让人凭肌肉记忆按错。</p>
 */
public class LaneButtonPadTest {

    private static final float DENSITY = 2f;
    private static final float TOLERANCE = 0.01f;
    private static final int WIDTH = 800;
    private static final int HEIGHT = 400;

    private static float centreX(int index) {
        float[] r = LaneButtonPad.rectFor(index, WIDTH, HEIGHT, DENSITY);
        return (r[0] + r[2]) / 2f;
    }

    private static float centreY(int index) {
        float[] r = LaneButtonPad.rectFor(index, WIDTH, HEIGHT, DENSITY);
        return (r[1] + r[3]) / 2f;
    }

    /** 前在上、后在下、左在左、右在右 —— 位置要和方位对得上。 */
    @Test
    public void positionsMatchTheDirectionsTheyMean() {
        assertTrue("前要在后的上面", centreY(LaneCycle.FRONT) < centreY(LaneCycle.REAR));
        assertTrue("左要在右的左边", centreX(LaneCycle.LEFT) < centreX(LaneCycle.RIGHT));
        assertTrue("左要在中间那一列左边", centreX(LaneCycle.LEFT) < centreX(LaneCycle.FRONT));
        assertTrue("右要在中间那一列右边", centreX(LaneCycle.RIGHT) > centreX(LaneCycle.FRONT));
    }

    /** 前和后同一列，左右各自居中在它们之间 —— 这才是菱形，不是四方块。 */
    @Test
    public void itIsADiamond() {
        assertEquals("前后同一列", centreX(LaneCycle.FRONT), centreX(LaneCycle.REAR), TOLERANCE);
        assertEquals("左右同一行", centreY(LaneCycle.LEFT), centreY(LaneCycle.RIGHT), TOLERANCE);
        float middle = (centreY(LaneCycle.FRONT) + centreY(LaneCycle.REAR)) / 2f;
        assertEquals("左右落在前后中间", middle, centreY(LaneCycle.LEFT), TOLERANCE);
    }

    /** 整组要居中。 */
    @Test
    public void thePadIsCentredHorizontally() {
        float[] left = LaneButtonPad.rectFor(LaneCycle.LEFT, WIDTH, HEIGHT, DENSITY);
        float[] right = LaneButtonPad.rectFor(LaneCycle.RIGHT, WIDTH, HEIGHT, DENSITY);
        assertEquals(left[0], WIDTH - right[2], TOLERANCE);
    }

    /** 四个一样大，相邻的之间都要留缝。 */
    @Test
    public void allTheSameSizeAndNothingTouches() {
        for (int i = 0; i < LaneButtonPad.COUNT; i++) {
            float[] r = LaneButtonPad.rectFor(i, WIDTH, HEIGHT, DENSITY);
            assertEquals(LaneButtonPad.buttonWidth(DENSITY), r[2] - r[0], TOLERANCE);
            assertEquals(LaneButtonPad.buttonHeight(DENSITY), r[3] - r[1], TOLERANCE);
        }
        float[] front = LaneButtonPad.rectFor(LaneCycle.FRONT, WIDTH, HEIGHT, DENSITY);
        float[] rear = LaneButtonPad.rectFor(LaneCycle.REAR, WIDTH, HEIGHT, DENSITY);
        float[] left = LaneButtonPad.rectFor(LaneCycle.LEFT, WIDTH, HEIGHT, DENSITY);
        float[] right = LaneButtonPad.rectFor(LaneCycle.RIGHT, WIDTH, HEIGHT, DENSITY);
        assertTrue("前后之间要有缝", rear[1] > front[3]);
        assertTrue("左和中间要有缝", front[0] > left[2]);
        assertTrue("中间和右要有缝", right[0] > front[2]);
    }

    /**
     * 按钮得够大。
     *
     * <p>下限写成 70/64 而不是钉死 78/72：这是「在车里按得着」的门槛，
     * 往上调不该惊动测试，往下掉回原来那个尺寸才该。</p>
     */
    @Test
    public void theButtonsAreBigEnoughToHitInACar() {
        assertTrue("宽不该小于 70dp", LaneButtonPad.buttonWidth(1f) >= 70f);
        assertTrue("高不该小于 64dp", LaneButtonPad.buttonHeight(1f) >= 64f);
    }

    /** 整组比锚点再高一个按钮 —— 放大之后压到画面正中，挡的正是最该看的地方。 */
    @Test
    public void thePadIsRaisedByOneButton() {
        int tall = 1600;
        float[] front = LaneButtonPad.rectFor(LaneCycle.FRONT, WIDTH, tall, DENSITY);
        assertEquals("上沿就是 padTop 算出来的那个数", LaneButtonPad.padTop(tall, DENSITY),
                front[1], TOLERANCE);
        assertEquals("正好比 0.18 那个锚点高出一个按钮",
                tall * 0.18f - LaneButtonPad.buttonHeight(DENSITY), front[1], TOLERANCE);
    }

    /** 窗口矮的时候提到顶就停住：宁可离锚点近一点，也不能把按钮推出框外。 */
    @Test
    public void raisingStopsAtTheTopEdge() {
        int shortWindow = 200;
        float top = LaneButtonPad.padTop(shortWindow, DENSITY);
        assertTrue("不能提成负的", top > 0f);
        assertEquals(top,
                LaneButtonPad.rectFor(LaneCycle.FRONT, WIDTH, shortWindow, DENSITY)[1],
                TOLERANCE);
    }

    /** 居中偏上，不是正中间 —— 正中间会挡住最该看的那部分画面。 */
    @Test
    public void thePadSitsAboveTheMiddle() {
        float[] front = LaneButtonPad.rectFor(LaneCycle.FRONT, WIDTH, HEIGHT, DENSITY);
        assertTrue("要在上半部分", front[1] < HEIGHT / 2f);
        assertTrue("但不贴顶", front[1] > 0f);
    }

    /** 缩到最小时，四个按钮仍然整个在框里 —— 这是这个模式最小尺寸的全部意义。 */
    @Test
    public void everyButtonFitsAtTheSmallestAllowedSize() {
        int min = LaneButtonPad.minWindowPx(DENSITY);
        for (int i = 0; i < LaneButtonPad.COUNT; i++) {
            float[] r = LaneButtonPad.rectFor(i, min, min, DENSITY);
            assertTrue("左边出框了: " + r[0], r[0] >= 0f);
            assertTrue("右边出框了: " + r[2], r[2] <= min);
            assertTrue("上边出框了: " + r[1], r[1] >= 0f);
            assertTrue("下边出框了: " + r[3], r[3] <= min);
        }
    }

    /** 按钮不跟着窗口缩放：窗口大一倍，按钮还是那么大。 */
    @Test
    public void buttonsDoNotGrowWithTheWindow() {
        float[] small = LaneButtonPad.rectFor(LaneCycle.REAR, 600, 300, DENSITY);
        float[] big = LaneButtonPad.rectFor(LaneCycle.REAR, 1200, 600, DENSITY);
        assertEquals(small[2] - small[0], big[2] - big[0], TOLERANCE);
        assertEquals(small[3] - small[1], big[3] - big[1], TOLERANCE);
    }

    /** 点中了返回那一个；缝里、组外都返回 -1。 */
    @Test
    public void hitTestFindsTheButtonUnderTheFinger() {
        for (int i = 0; i < LaneButtonPad.COUNT; i++) {
            assertEquals("点正中间该命中第 " + i + " 个", i,
                    LaneButtonPad.hitTest(centreX(i), centreY(i), WIDTH, HEIGHT, DENSITY));
        }
        float[] front = LaneButtonPad.rectFor(LaneCycle.FRONT, WIDTH, HEIGHT, DENSITY);
        assertEquals("组上方不算", -1,
                LaneButtonPad.hitTest(centreX(LaneCycle.FRONT), front[1] - 5,
                        WIDTH, HEIGHT, DENSITY));
        float[] left = LaneButtonPad.rectFor(LaneCycle.LEFT, WIDTH, HEIGHT, DENSITY);
        assertEquals("左上角那个空位不算", -1,
                LaneButtonPad.hitTest(centreX(LaneCycle.LEFT), front[1] + 1,
                        WIDTH, HEIGHT, DENSITY));
        assertEquals("前后之间的缝不算", -1,
                LaneButtonPad.hitTest(centreX(LaneCycle.FRONT), front[3] + 1,
                        WIDTH, HEIGHT, DENSITY));
        assertTrue("左边那个确实在那个高度", left[1] < front[3]);
    }

    @Test
    public void laneNumbersRoundTrip() {
        assertEquals(LaneCycle.FRONT, LaneButtonPad.laneFor(0));
        assertEquals(LaneCycle.REAR, LaneButtonPad.laneFor(1));
        assertEquals(LaneCycle.LEFT, LaneButtonPad.laneFor(2));
        assertEquals(LaneCycle.RIGHT, LaneButtonPad.laneFor(3));
        for (int lane = 0; lane < LaneButtonPad.COUNT; lane++) {
            assertEquals(lane, LaneButtonPad.indexForLane(LaneButtonPad.laneFor(lane)));
        }
        assertEquals(-1, LaneButtonPad.indexForLane(99));
    }

    /** 最小尺寸要随密度走：屏幕密一倍，像素数也该多一倍。 */
    @Test
    public void theMinimumFollowsScreenDensity() {
        assertTrue(LaneButtonPad.minWindowPx(3f) > LaneButtonPad.minWindowPx(2f));
        assertTrue(LaneButtonPad.minWindowPx(2f) > LaneButtonPad.minWindowPx(1f));
    }
}
