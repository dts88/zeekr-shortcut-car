package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RearViewTouchModel} 的单元测试。
 *
 * <p>手势换算错了，在车上只会觉得「怪」——拖起来发飘、方向反了、贴边后拉不回来 ——
 * 但说不清哪里怪，也很难复现。所以这部分的判据放在这里。</p>
 */
public class RearViewTouchModelTest {

    private static final float EPS = 1e-5f;

    // ---------- 三分区 ----------

    @Test
    public void middleThirdAdjustsCropAndTheSidesMoveTheWindow() {
        int w = 300;   // 三段各 100

        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(0, w));
        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(99, w));
        assertEquals(RearViewTouchModel.Zone.ADJUST_CROP,
                RearViewTouchModel.zoneFor(100, w));
        assertEquals(RearViewTouchModel.Zone.ADJUST_CROP,
                RearViewTouchModel.zoneFor(199, w));
        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(200, w));
        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(299, w));
    }

    @Test
    public void zoneSurvivesNonsenseWidth() {
        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(10, 0));
        assertEquals(RearViewTouchModel.Zone.MOVE_WINDOW,
                RearViewTouchModel.zoneFor(10, -5));
    }

    // ---------- 上下滑调取景 ----------

    @Test
    public void dragMovesTheViewOneToOneWithTheFinger() {
        // 取景框占该路画面的一半，窗口高 400px。
        // 手指移动 200px（窗口的一半）应当让取景框移动 0.5 * 0.5 = 0.25
        float shift = RearViewTouchModel.cropShiftForDrag(200, 400, 0.5f);
        assertEquals(-0.25f, shift, EPS);
    }

    @Test
    public void dragDownRevealsWhatIsAbove() {
        // 手指往下拖 = 想看画面上方 = 取景框往上走（y 减小）
        assertTrue("往下拖，取景框应当上移",
                RearViewTouchModel.cropShiftForDrag(50, 400, 0.5f) < 0);
        assertTrue("往上拖，取景框应当下移",
                RearViewTouchModel.cropShiftForDrag(-50, 400, 0.5f) > 0);
    }

    @Test
    public void smallerCropMovesLessPerPixel() {
        // 取景框越小，画面被放得越大，同样的手指位移对应的内容位移也该越小
        float big = Math.abs(RearViewTouchModel.cropShiftForDrag(100, 400, 0.8f));
        float small = Math.abs(RearViewTouchModel.cropShiftForDrag(100, 400, 0.2f));
        assertTrue("取景框小的时候位移应当更小", small < big);
    }

    @Test
    public void dragIsInertWithoutAHeight() {
        assertEquals(0f, RearViewTouchModel.cropShiftForDrag(100, 0, 0.5f), EPS);
    }

    /** 拖动接上取景框之后仍然不能越界。 */
    @Test
    public void draggingCannotPushTheCropOutOfTheFrame() {
        RearViewGeometry.Crop crop = new RearViewGeometry.Crop(0f, 0.3f, 1f, 0.4f);
        for (int dy = -2000; dy <= 2000; dy += 137) {
            float shift = RearViewTouchModel.cropShiftForDrag(dy, 400, crop.height);
            RearViewGeometry.Crop moved = crop.shiftedVertically(shift);
            assertTrue("y 越界: " + moved, moved.y >= -EPS);
            assertTrue("下边越界: " + moved, moved.y + moved.height <= 1f + EPS);
        }
    }

    // ---------- 贴边停靠 ----------

    @Test
    public void detectsWhichEdgeItWasDraggedTo() {
        int screen = 3200;
        int width = 480;

        assertEquals(RearViewTouchModel.Dock.LEFT,
                RearViewTouchModel.dockFor(10, width, screen, 40));
        assertEquals(RearViewTouchModel.Dock.RIGHT,
                RearViewTouchModel.dockFor(screen - width - 10, width, screen, 40));
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.dockFor(1000, width, screen, 40));
    }

    @Test
    public void dockedWindowStillPeeksOutSoItCanBeRecovered() {
        int screen = 3200;
        int width = 480;
        int peek = 60;

        int left = RearViewTouchModel.dockedX(
                RearViewTouchModel.Dock.LEFT, 10, width, screen, peek);
        assertEquals(peek - width, left);
        assertEquals("左侧应当仍露出 peek 宽度", peek, left + width);

        int right = RearViewTouchModel.dockedX(
                RearViewTouchModel.Dock.RIGHT, 2000, width, screen, peek);
        assertEquals(screen - peek, right);
        assertTrue("右侧应当仍露出 peek 宽度", screen - right == peek);
    }

    @Test
    public void noDockLeavesThePositionAlone() {
        assertEquals(1234, RearViewTouchModel.dockedX(
                RearViewTouchModel.Dock.NONE, 1234, 480, 3200, 60));
    }

    // ---------- 双指缩放窗口 ----------

    @Test
    public void pinchScalesWithinLimits() {
        assertEquals(600, RearViewTouchModel.scaledSize(400, 1.5f, 200, 1200));
        assertEquals(200, RearViewTouchModel.scaledSize(400, 0.1f, 200, 1200));
        assertEquals(1200, RearViewTouchModel.scaledSize(400, 10f, 200, 1200));
        assertEquals("非正的倍数应原样返回",
                400, RearViewTouchModel.scaledSize(400, 0f, 200, 1200));
    }

    // ---------- 夹回屏幕 ----------

    @Test
    public void clampAllowsPeekingOffTheEdgeButNoFurther() {
        int screen = 3200;
        int width = 480;
        int peek = 60;

        assertEquals(peek - width, RearViewTouchModel.clampX(-9999, width, screen, peek));
        assertEquals(screen - peek, RearViewTouchModel.clampX(9999, width, screen, peek));
        assertEquals(1000, RearViewTouchModel.clampX(1000, width, screen, peek));
    }

    @Test
    public void verticalClampKeepsItFullyOnScreen() {
        assertEquals(0, RearViewTouchModel.clampY(-100, 320, 2000));
        assertEquals(2000 - 320, RearViewTouchModel.clampY(9999, 320, 2000));
        assertEquals(500, RearViewTouchModel.clampY(500, 320, 2000));
    }

    @Test
    public void clampSurvivesAWindowBiggerThanTheScreen() {
        assertEquals(0, RearViewTouchModel.clampY(50, 3000, 2000));
        assertTrue(RearViewTouchModel.clampX(50, 4000, 3200, 60) <= 0);
    }
}
