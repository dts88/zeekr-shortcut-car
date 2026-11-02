package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SideViewFrames}：两个框互为镜像，动哪个都只改左边那个；比例不变；左边那个不过中线。 */
public class SideViewFramesTest {

    private static final int W = 2560;
    private static final int H = 1440;
    private static final float REACH = 40f;

    /** 默认的左边那个：(77, 482) 1045 × 475。 */
    private static SideViewPlacement.Rect start() {
        return SideViewPlacement.compute(W, H, LaneCycle.LEFT);
    }

    @Test
    public void aTouchPicksTheFrameUnderIt() {
        SideViewPlacement.Rect left = start();
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, 577, 682, REACH);
        assertEquals(SideViewFrames.LEFT_FRAME, g.frame);
        assertEquals(RearViewFrameModel.MOVE, g.part);
        g = SideViewFrames.hit(left, W, 1938, 682, REACH);
        assertEquals(SideViewFrames.RIGHT_FRAME, g.frame);
        g = SideViewFrames.hit(left, W, 77, 482, REACH);
        assertEquals(SideViewFrames.LEFT_FRAME, g.frame);
        assertEquals(RearViewFrameModel.LEFT | RearViewFrameModel.TOP, g.part);
        assertTrue("两个框外面", !SideViewFrames.hit(left, W, 1280, 100, REACH).any());
    }

    @Test
    public void movingTheRightFrameMovesBothAsMirrors() {
        SideViewPlacement.Rect left = start();
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, 1938, 682, REACH);
        SideViewPlacement.Rect moved = SideViewFrames.drag(left, g, -100, 50, W, H);
        assertEquals("左边那个反着走", 177, moved.left);
        assertEquals("上下一样", 532, moved.top);
        assertEquals("右边那个跟着手指", 1438 - 100, SideViewFrames.right(moved, W).left);
    }

    @Test
    public void aCornerResizesKeepingTheAspectAndTheOppositeCorner() {
        SideViewPlacement.Rect left = start();
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, left.right(), left.bottom(), REACH);
        assertEquals(RearViewFrameModel.RIGHT | RearViewFrameModel.BOTTOM, g.part);
        SideViewPlacement.Rect r = SideViewFrames.drag(left, g, 110, 50, W, H);
        assertEquals("左上角不动", 77, r.left);
        assertEquals(482, r.top);
        assertTrue("变大了", r.height > 520 && r.height < 530);
        assertEquals("宽 ÷ 高不变", Math.round(r.height * SideViewPlacement.ASPECT), r.width);
    }

    @Test
    public void aCornerOfTheRightFrameWorksAsItsMirror() {
        SideViewPlacement.Rect left = start();
        SideViewPlacement.Rect right = SideViewFrames.right(left, W);
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, right.left, right.bottom(), REACH);
        assertEquals(SideViewFrames.RIGHT_FRAME, g.frame);
        SideViewPlacement.Rect r = SideViewFrames.drag(left, g, -110, 50, W, H);
        SideViewPlacement.Rect mirrored = SideViewFrames.right(r, W);
        assertEquals("右边那个的右上角不动", right.right(), mirrored.right());
        assertEquals(right.top, mirrored.top);
        assertTrue("变大了", mirrored.height > right.height);
    }

    @Test
    public void anEdgeResizesAroundTheMiddleOfTheOtherDirection() {
        SideViewPlacement.Rect left = start();
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, left.left + 522, left.top, REACH);
        assertEquals(RearViewFrameModel.TOP, g.part);
        SideViewPlacement.Rect r = SideViewFrames.drag(left, g, 0, -100, W, H);
        assertEquals("下边不动", left.bottom(), r.bottom());
        assertEquals("左右居中不动（再宽就出屏幕，到边为止）", 0, r.left);
        assertEquals(1199, r.width);
        assertEquals(545, r.height);
    }

    @Test
    public void theLeftFrameStopsAtTheCenterLine() {
        SideViewPlacement.Rect left = start();
        SideViewFrames.Grab g = SideViewFrames.hit(left, W, 577, 682, REACH);
        SideViewPlacement.Rect r = SideViewFrames.drag(left, g, 2000, 0, W, H);
        assertEquals((W - left.width) / 2, r.left);
        assertTrue("两个框叠在正中", Math.abs(SideViewFrames.right(r, W).left - r.left) <= 1);
    }

    @Test
    public void pinchScalesAroundTheFingersAndStaysOnScreen() {
        SideViewPlacement.Rect left = start();
        float cx = left.left + left.width / 2f;
        float cy = left.top + left.height / 2f;
        SideViewPlacement.Rect r = SideViewFrames.pinch(left, 0.5f, 0.5f, cx, cy, 1.5f, W, H);
        assertEquals(713, r.height);
        assertEquals(1569, r.width);
        assertEquals("出了左边推回来", 0, r.left);
        assertEquals(363, r.top);
    }

    @Test
    public void fitKeepsTheSizeBetweenTheLimits() {
        SideViewPlacement.Rect small = SideViewFrames.fit(0, 0, 10, W, H);
        assertEquals(Math.round(H * SideViewPlacement.MIN_HEIGHT_PERMILLE / 1000f), small.height);
        SideViewPlacement.Rect big = SideViewFrames.fit(0, 0, 5000, W, H);
        assertEquals("宽放不下按宽收", (int) Math.floor(W / SideViewPlacement.ASPECT), big.height);
        assertTrue(big.width <= W);
    }
}
