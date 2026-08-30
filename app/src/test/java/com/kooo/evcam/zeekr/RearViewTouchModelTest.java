package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * 手指划过整个窗口高度 = 取景从最上挪到最下。
     *
     * <p>用 0..1 的比例而不是像素，正是为了让这条在<b>任何窗口形状下</b>都成立：
     * 框越扁能挪的实际距离越大，但「划满一屏 = 挪到底」的手感不该跟着变。</p>
     */
    @Test
    public void dragMovesTheViewOneToOneWithTheFinger() {
        assertEquals(-1f, RearViewTouchModel.panShiftForDrag(400, 400, 0.75f), EPS);
        assertEquals(-0.5f, RearViewTouchModel.panShiftForDrag(200, 400, 0.75f), EPS);
    }

    @Test
    public void dragDownRevealsWhatIsAbove() {
        // 手指往下拖 = 想看画面上方 = 取景往上走（pan 减小）
        assertTrue("往下拖，取景应当上移",
                RearViewTouchModel.panShiftForDrag(50, 400, 0.75f) < 0);
        assertTrue("往上拖，取景应当下移",
                RearViewTouchModel.panShiftForDrag(-50, 400, 0.75f) > 0);
    }

    /** 方框已经全看到了，拖动不该有任何反应 —— 不是卡住，是没有别的可看。 */
    @Test
    public void dragIsInertWhenThereIsNothingLeftToPan() {
        assertEquals(0f, RearViewTouchModel.panShiftForDrag(300, 400, 0f), EPS);
        assertEquals(0f, RearViewTouchModel.panShiftForDrag(-300, 400, 0f), EPS);
    }

    @Test
    public void dragIsInertWithoutAHeight() {
        assertEquals(0f, RearViewTouchModel.panShiftForDrag(100, 0, 0.75f), EPS);
    }

    /** 拖到底也不能把取景推出画面。 */
    @Test
    public void draggingCannotPushTheViewportOutOfTheFrame() {
        RearViewGeometry.Viewport base = RearViewGeometry.Viewport.forWindow(1600, 400, 0.5f);
        for (int dy = -2000; dy <= 2000; dy += 137) {
            float shift = RearViewTouchModel.panShiftForDrag(dy, 400, base.verticalHeadroom());
            float pan = RearViewGeometry.clampPan(0.5f + shift);
            RearViewGeometry.Viewport moved =
                    RearViewGeometry.Viewport.forWindow(1600, 400, pan);
            assertTrue("上边越界: " + moved, moved.y >= -EPS);
            assertTrue("下边越界: " + moved, moved.y + moved.height <= 1f + EPS);
        }
    }

    // ---------- 左右划换一路 ----------

    @Test
    public void theDominantAxisDecidesWhatTheDragMeans() {
        assertTrue("横多于竖 -> 横向", RearViewTouchModel.isHorizontalIntent(50, 10));
        assertFalse("竖多于横 -> 纵向", RearViewTouchModel.isHorizontalIntent(10, 50));
        assertFalse("相等时按纵向处理，取景是更常用的那个",
                RearViewTouchModel.isHorizontalIntent(30, 30));
    }

    /** 划得够远就算数，不必也划得快。 */
    @Test
    public void aLongEnoughSwipeCountsWithoutSpeed() {
        assertTrue(RearViewTouchModel.isDeliberateSwipe(90, 0f, 90, MIN_FLING));
        assertTrue(RearViewTouchModel.isDeliberateSwipe(-90, 0f, 90, MIN_FLING));
        assertTrue(RearViewTouchModel.isDeliberateSwipe(500, 0f, 90, MIN_FLING));
    }

    /** 划得够快也算数，不必划到那么远。 */
    @Test
    public void aFastEnoughFlickCountsWithoutDistance() {
        assertTrue(RearViewTouchModel.isDeliberateSwipe(30, MIN_FLING, 90, MIN_FLING));
        assertTrue(RearViewTouchModel.isDeliberateSwipe(-30, -MIN_FLING, 90, MIN_FLING));
    }

    /** 又慢又短不算 —— 换路是明确的动作，不该被手指轻微横移触发。 */
    @Test
    public void aShortSlowNudgeDoesNotSwitchLanes() {
        assertFalse(RearViewTouchModel.isDeliberateSwipe(30, 0f, 90, MIN_FLING));
        assertFalse(RearViewTouchModel.isDeliberateSwipe(30, MIN_FLING - 1, 90, MIN_FLING));
        assertFalse(RearViewTouchModel.isDeliberateSwipe(0, 0f, 90, MIN_FLING));
    }

    /** 划出去又拉回来：速度方向和位移方向相反，不该算数。 */
    @Test
    public void flickingBackTheOtherWayDoesNotCount() {
        assertFalse(RearViewTouchModel.isDeliberateSwipe(30, -MIN_FLING * 3, 90, MIN_FLING));
        assertFalse(RearViewTouchModel.isDeliberateSwipe(-30, MIN_FLING * 3, 90, MIN_FLING));
    }

    // ---------- 贴边停靠 ----------

    private static final int SCREEN = 3200;
    private static final int WIDTH = 480;
    private static final float MIN_FLING = 400f;

    private RearViewTouchModel.Dock dockAt(int x, float velocity) {
        return RearViewTouchModel.deliberateDock(x, WIDTH, SCREEN, velocity, MIN_FLING);
    }

    /**
     * 这条是整个规则的重点，也是上一版被抱怨的地方：
     * 窗口整个还在屏幕里时，不管拖到多靠边、甩得多快，都不许贴边。
     */
    @Test
    public void aFullyVisibleWindowNeverDocks() {
        int[] positions = {0, 1, 40, 1000, SCREEN - WIDTH - 1, SCREEN - WIDTH};
        for (int x : positions) {
            assertEquals("x=" + x + " 时窗口仍完整可见，不该贴边",
                    RearViewTouchModel.Dock.NONE, dockAt(x, 0f));
            assertEquals("x=" + x + " 快速甩动也不该贴边",
                    RearViewTouchModel.Dock.NONE, dockAt(x, -5000f));
            assertEquals("x=" + x + " 快速甩动也不该贴边",
                    RearViewTouchModel.Dock.NONE, dockAt(x, 5000f));
        }
    }

    /** 推出去一半以上：不用甩，松手就该收起来。 */
    @Test
    public void pushingMostOfItOffScreenDocksWithoutAFling() {
        assertEquals(RearViewTouchModel.Dock.LEFT, dockAt(-WIDTH / 2, 0f));
        assertEquals(RearViewTouchModel.Dock.RIGHT, dockAt(SCREEN - WIDTH / 2, 0f));
    }

    /** 只推出去一点点、又没甩，应当留在原地 —— 这多半是手滑。 */
    @Test
    public void nudgingItSlightlyOffScreenIsNotEnough() {
        assertEquals(RearViewTouchModel.Dock.NONE, dockAt(-10, 0f));
        assertEquals(RearViewTouchModel.Dock.NONE, dockAt(SCREEN - WIDTH + 10, 0f));
    }

    /** 但推出去一点再朝那个方向甩一下，就算数了。 */
    @Test
    public void aFlingTowardsTheEdgeDocksOnceItHasStartedLeaving() {
        assertEquals(RearViewTouchModel.Dock.LEFT, dockAt(-10, -MIN_FLING));
        assertEquals(RearViewTouchModel.Dock.RIGHT, dockAt(SCREEN - WIDTH + 10, MIN_FLING));
    }

    /** 朝反方向甩不该贴边 —— 那是想把它拉回来。 */
    @Test
    public void aFlingAwayFromTheEdgeDoesNotDock() {
        assertEquals(RearViewTouchModel.Dock.NONE, dockAt(-10, MIN_FLING * 4));
        assertEquals(RearViewTouchModel.Dock.NONE,
                dockAt(SCREEN - WIDTH + 10, -MIN_FLING * 4));
    }

    /** 慢慢挪出去一点点不算甩。 */
    @Test
    public void aSlowDragBelowTheFlingThresholdDoesNotDock() {
        assertEquals(RearViewTouchModel.Dock.NONE, dockAt(-10, -MIN_FLING + 1));
        assertEquals(RearViewTouchModel.Dock.NONE,
                dockAt(SCREEN - WIDTH + 10, MIN_FLING - 1));
    }

    /**
     * 贴边条件必须是<b>拖得到</b>的。
     *
     * <p>拖动时 {@code clampX} 会保证至少留出 PEEK 宽度不被推出去，
     * 所以最多只能推出 {@code width - PEEK}。要是这个上限还够不到
     * 贴边所需的比例，条件就永远不成立 —— 这正是上一版贴边完全没反应的原因
     * （当时阈值比 PEEK 还小），同一个坑不该踩第二次。</p>
     */
    @Test
    public void theDockConditionIsActuallyReachableWhileDragging() {
        int peek = 72;                 // RearViewMirrorView.PEEK_WIDTH_PX
        int smallest = 240;            // AppConfig.REARVIEW_MIN_SIZE

        for (int width : new int[]{smallest, 480, 720, 1600}) {
            int furthestLeft = RearViewTouchModel.clampX(
                    -100000, width, SCREEN, peek);
            int furthestRight = RearViewTouchModel.clampX(
                    100000, width, SCREEN, peek);

            assertEquals("宽 " + width + " 时应当能一路推到左边贴住",
                    RearViewTouchModel.Dock.LEFT,
                    RearViewTouchModel.deliberateDock(
                            furthestLeft, width, SCREEN, 0f, MIN_FLING));
            assertEquals("宽 " + width + " 时应当能一路推到右边贴住",
                    RearViewTouchModel.Dock.RIGHT,
                    RearViewTouchModel.deliberateDock(
                            furthestRight, width, SCREEN, 0f, MIN_FLING));
        }
    }

    @Test
    public void nonsenseGeometryIsSurvivable() {
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.deliberateDock(0, 0, SCREEN, 0f, MIN_FLING));
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.deliberateDock(0, WIDTH, 0, 0f, MIN_FLING));
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
