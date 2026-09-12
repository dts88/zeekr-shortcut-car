package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LaneOrientation} 的单元测试。
 *
 * <p>钉的是一句话：<b>用户裁掉的永远是他看到的那条边</b>。这件事只能靠推导和
 * 测试保证 —— 在车上肉眼看，「转了 90° 之后裁上边裁掉了左边」和「裁剪根本没生效」
 * 长得一模一样。</p>
 */
public class LaneOrientationTest {

    private static final float EPS = 0.0001f;

    /** 不转的时候什么都不动。 */
    @Test
    public void withoutRotationNothingMoves() {
        LaneOrientation o = LaneOrientation.sourceSpace(0,
                0.1f, 0.2f, 0.3f, 0.4f, 2f, 3f, 0.5f, 0.6f);
        assertEquals(0.1f, o.cropTop, EPS);
        assertEquals(0.2f, o.cropBottom, EPS);
        assertEquals(0.3f, o.cropLeft, EPS);
        assertEquals(0.4f, o.cropRight, EPS);
        assertEquals(2f, o.scaleX, EPS);
        assertEquals(3f, o.scaleY, EPS);
        assertEquals(0.5f, o.translateX, EPS);
        assertEquals(0.6f, o.translateY, EPS);
    }

    /**
     * 顺时针 90°：源画面的左边落在屏幕上边，所以「裁上边」要裁源画面的左边。
     */
    @Test
    public void ninetyMovesTheTopCropOntoTheSourceLeft() {
        LaneOrientation o = LaneOrientation.sourceSpace(90,
                0.1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f);
        assertEquals("裁上边应当落在源画面的左边", 0.1f, o.cropLeft, EPS);
        assertEquals(0f, o.cropTop, EPS);
        assertEquals(0f, o.cropBottom, EPS);
        assertEquals(0f, o.cropRight, EPS);
    }

    /** 顺时针 90°：源画面的上边落在屏幕右边。 */
    @Test
    public void ninetyMovesTheRightCropOntoTheSourceTop() {
        LaneOrientation o = LaneOrientation.sourceSpace(90,
                0f, 0f, 0f, 0.25f, 1f, 1f, 0f, 0f);
        assertEquals(0.25f, o.cropTop, EPS);
        assertEquals(0f, o.cropLeft, EPS);
    }

    /** 逆时针 90°（270）：源画面的左边落在屏幕下边。 */
    @Test
    public void twoSeventyMovesTheBottomCropOntoTheSourceLeft() {
        LaneOrientation o = LaneOrientation.sourceSpace(270,
                0f, 0.1f, 0f, 0f, 1f, 1f, 0f, 0f);
        assertEquals(0.1f, o.cropLeft, EPS);
        assertEquals(0f, o.cropBottom, EPS);
    }

    /** 180°：上下左右全对调。 */
    @Test
    public void oneEightyFlipsBothAxes() {
        LaneOrientation o = LaneOrientation.sourceSpace(180,
                0.1f, 0.2f, 0.3f, 0.4f, 1f, 1f, 0.5f, 0.6f);
        assertEquals(0.2f, o.cropTop, EPS);
        assertEquals(0.1f, o.cropBottom, EPS);
        assertEquals(0.4f, o.cropLeft, EPS);
        assertEquals(0.3f, o.cropRight, EPS);
        assertEquals(-0.5f, o.translateX, EPS);
        assertEquals(-0.6f, o.translateY, EPS);
    }

    /** 转四分之一圈时，横向缩放作用在纵向上。 */
    @Test
    public void quarterTurnsSwapTheScaleAxes() {
        LaneOrientation ninety = LaneOrientation.sourceSpace(90,
                0f, 0f, 0f, 0f, 2f, 3f, 0f, 0f);
        assertEquals(3f, ninety.scaleX, EPS);
        assertEquals(2f, ninety.scaleY, EPS);

        LaneOrientation oneEighty = LaneOrientation.sourceSpace(180,
                0f, 0f, 0f, 0f, 2f, 3f, 0f, 0f);
        assertEquals("转半圈横纵不对调", 2f, oneEighty.scaleX, EPS);
        assertEquals(3f, oneEighty.scaleY, EPS);
    }

    /**
     * 平移：转 90° 之后「把画面往右挪」，在源画面里是往下挪。
     *
     * <p>源画面的 +x 落在屏幕的下方，所以要往屏幕右边挪，得动源画面的 y。</p>
     */
    @Test
    public void quarterTurnsRerouteThePan() {
        LaneOrientation o = LaneOrientation.sourceSpace(90,
                0f, 0f, 0f, 0f, 1f, 1f, 0.2f, 0f);
        assertEquals(0f, o.translateX, EPS);
        assertEquals(-0.2f, o.translateY, EPS);
    }

    /** 转两次 180° 等于没转。 */
    @Test
    public void fullTurnIsNoTurn() {
        LaneOrientation o = LaneOrientation.sourceSpace(360,
                0.1f, 0.2f, 0.3f, 0.4f, 1f, 1f, 0f, 0f);
        assertEquals(0.1f, o.cropTop, EPS);
        assertEquals(0.3f, o.cropLeft, EPS);
    }

    /** 负角度和不是 90 倍数的角度都得收进四档里。 */
    @Test
    public void oddAnglesSnapToTheFourSteps() {
        assertEquals(270, LaneOrientation.normalise(-90));
        assertEquals(90, LaneOrientation.normalise(450));
        assertEquals(90, LaneOrientation.normalise(80));
        assertEquals(0, LaneOrientation.normalise(0));
        assertEquals(0, LaneOrientation.normalise(370));
        assertTrue(LaneOrientation.quarterTurn(90));
        assertTrue(LaneOrientation.quarterTurn(-90));
        assertFalse(LaneOrientation.quarterTurn(180));
        assertFalse(LaneOrientation.quarterTurn(0));
    }
}
