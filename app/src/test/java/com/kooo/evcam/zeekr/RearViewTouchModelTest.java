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

    // ---------- 贴边之后怎么拿回来 ----------

    @Test
    public void aWindowHangingOffAnEdgeCountsAsDocked() {
        assertEquals(RearViewTouchModel.Dock.RIGHT,
                RearViewTouchModel.dockedAt(SCREEN - 72, WIDTH, SCREEN));
        assertEquals(RearViewTouchModel.Dock.LEFT,
                RearViewTouchModel.dockedAt(72 - WIDTH, WIDTH, SCREEN));
    }

    @Test
    public void aFullyVisibleWindowIsNotDocked() {
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.dockedAt(0, WIDTH, SCREEN));
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.dockedAt(SCREEN - WIDTH, WIDTH, SCREEN));
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.dockedAt(1000, WIDTH, SCREEN));
    }

    /** 放回来就是整个进屏幕、贴着刚才藏进去的那条边。 */
    @Test
    public void restoringLandsFlushAgainstTheEdgeItHidBehind() {
        assertEquals(0, RearViewTouchModel.flushX(
                RearViewTouchModel.Dock.LEFT, WIDTH, SCREEN));
        assertEquals(SCREEN - WIDTH, RearViewTouchModel.flushX(
                RearViewTouchModel.Dock.RIGHT, WIDTH, SCREEN));

        // 落点必须是完整可见的，否则等于没放回来
        for (RearViewTouchModel.Dock dock : new RearViewTouchModel.Dock[]{
                RearViewTouchModel.Dock.LEFT, RearViewTouchModel.Dock.RIGHT}) {
            int x = RearViewTouchModel.flushX(dock, WIDTH, SCREEN);
            assertEquals(RearViewTouchModel.Dock.NONE,
                    RearViewTouchModel.dockedAt(x, WIDTH, SCREEN));
        }
    }

    /**
     * 关键的一条：拿回来的门槛远低于藏进去的门槛。
     *
     * <p>推出去要一半宽度（480px 的窗口是 240px），拉回来只要 60px ——
     * 而且是固定像素，窗口再大也不会更难拉。</p>
     */
    @Test
    public void pullingBackIsFarEasierThanPushingAway() {
        assertTrue("往回拉 60px 就该放回来",
                RearViewTouchModel.shouldUndock(
                        RearViewTouchModel.Dock.RIGHT, -60, 0f, 60, MIN_FLING));
        assertTrue(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.LEFT, 60, 0f, 60, MIN_FLING));

        // 同样这点位移，远不足以把它推出去
        assertEquals(RearViewTouchModel.Dock.NONE,
                RearViewTouchModel.deliberateDock(60, WIDTH, SCREEN, 0f, MIN_FLING));
    }

    /** 窗口越大越难拉回来是不能接受的，所以门槛与宽度无关。 */
    @Test
    public void theUndockThresholdIsIndependentOfWindowSize() {
        for (int width : new int[]{240, 900, 2000, 3000}) {
            assertTrue("宽 " + width + " 时也该拉得回来",
                    RearViewTouchModel.shouldUndock(
                            RearViewTouchModel.Dock.RIGHT, -60, 0f, 60, MIN_FLING));
        }
    }

    /** 往回甩一下也算，不必拉够距离。 */
    @Test
    public void anInwardFlickAlsoRestoresIt() {
        assertTrue(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.RIGHT, -10, -MIN_FLING, 60, MIN_FLING));
        assertTrue(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.LEFT, 10, MIN_FLING, 60, MIN_FLING));
    }

    /** 往外推、或者没动，不该放回来。 */
    @Test
    public void pushingFurtherOutDoesNotRestoreIt() {
        assertFalse(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.RIGHT, 200, MIN_FLING * 3, 60, MIN_FLING));
        assertFalse(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.LEFT, -200, -MIN_FLING * 3, 60, MIN_FLING));
        assertFalse(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.RIGHT, -10, 0f, 60, MIN_FLING));
    }

    @Test
    public void anUndockedWindowHasNothingToRestore() {
        assertFalse(RearViewTouchModel.shouldUndock(
                RearViewTouchModel.Dock.NONE, -500, -MIN_FLING * 5, 60, MIN_FLING));
    }

    // ---------- 滑回去用多久 ----------

    /** 甩得越快回得越快 —— 手上使了多大劲，画面就该多快跟上。 */
    @Test
    public void aFasterFlickGlidesBackSooner() {
        long slow = RearViewTouchModel.glideDurationMs(800, 1000f, 120, 380);
        long fast = RearViewTouchModel.glideDurationMs(800, 6000f, 120, 380);
        assertTrue("甩得快应当更快到位，slow=" + slow + " fast=" + fast, fast < slow);
    }

    /** 距离越远走得越久，但都夹在一个能看清的范围里。 */
    @Test
    public void glideDurationStaysWithinAUsableRange() {
        for (int distance : new int[]{0, 1, 60, 500, 3000}) {
            for (float velocity : new float[]{0f, 500f, 4000f, 40000f}) {
                long duration = RearViewTouchModel.glideDurationMs(distance, velocity, 120, 380);
                assertTrue("太短: " + duration, duration >= 120);
                assertTrue("太长: " + duration, duration <= 380);
            }
        }
    }

    /** 没有甩动时也得有个合理的速度，不能因为 velocity=0 就算出无穷久。 */
    @Test
    public void aTapRestoreStillGlidesAtASensibleSpeed() {
        long duration = RearViewTouchModel.glideDurationMs(600, 0f, 120, 380);
        assertTrue(duration >= 120 && duration <= 380);
    }

    /** 方向不影响时长，只有距离和速度影响。 */
    @Test
    public void glideDurationIgnoresDirection() {
        assertEquals(RearViewTouchModel.glideDurationMs(700, 3000f, 120, 380),
                RearViewTouchModel.glideDurationMs(-700, -3000f, 120, 380));
    }

    // ---------- 双指缩放窗口 ----------

    private static final int SCREEN_W = 3200;
    private static final int SCREEN_H = 1800;
    private static final int MIN_SIZE = 120;

    private RearViewTouchModel.PinchResult pinch(int x, int y, int w, int h,
                                                 float rx, float ry,
                                                 float focusX, float focusY, float factor) {
        return RearViewTouchModel.pinch(x, y, w, h, rx, ry, focusX, focusY, factor,
                MIN_SIZE, SCREEN_W, SCREEN_H);
    }

    /**
     * 整套手感的根本：<b>手指底下那个点，缩放前后都待在手指底下</b>。
     *
     * <p>以前固定左上角，窗口总往右下角长 —— 手在中间捏，画面却从角上撑开。</p>
     */
    @Test
    public void theGrabbedPointStaysUnderTheFingers() {
        int x = 500, y = 300, w = 800, h = 400;
        float rx = 0.5f, ry = 0.5f;                  // 从正中间捏
        float focusX = x + rx * w, focusY = y + ry * h;

        for (float factor : new float[]{0.5f, 1f, 1.5f, 2f}) {
            RearViewTouchModel.PinchResult r = pinch(x, y, w, h, rx, ry, focusX, focusY, factor);
            assertEquals("锚点横向应当不动", focusX, r.x + rx * r.width, 1f);
            assertEquals("锚点纵向应当不动", focusY, r.y + ry * r.height, 1f);
        }
    }

    /** 从角上捏也一样 —— 锚点不限于中心。 */
    @Test
    public void anOffCentreGrabIsAlsoHonoured() {
        int x = 500, y = 300, w = 800, h = 400;
        float rx = 0.2f, ry = 0.8f;
        float focusX = x + rx * w, focusY = y + ry * h;

        RearViewTouchModel.PinchResult r = pinch(x, y, w, h, rx, ry, focusX, focusY, 1.75f);
        assertEquals(focusX, r.x + rx * r.width, 1f);
        assertEquals(focusY, r.y + ry * r.height, 1f);
    }

    /** 两指不改变跨距、只是一起挪 —— 那就是纯粹的移动窗口。 */
    @Test
    public void movingBothFingersWithoutSpreadingJustMovesTheWindow() {
        int x = 500, y = 300, w = 800, h = 400;
        float rx = 0.5f, ry = 0.5f;
        float startFocusX = x + rx * w, startFocusY = y + ry * h;

        RearViewTouchModel.PinchResult r = pinch(
                x, y, w, h, rx, ry, startFocusX + 120f, startFocusY - 60f, 1f);

        assertEquals("尺寸不该变", w, r.width);
        assertEquals(h, r.height);
        assertEquals("应当跟着中点平移", x + 120, r.x, 1);
        assertEquals(y - 60, r.y, 1);
    }

    /** 比例必须守住：夹的是倍数，不是分别夹宽和高。 */
    @Test
    public void theAspectRatioSurvivesEveryLimit() {
        int w = 900, h = 300;
        float aspect = (float) w / h;
        for (float factor : new float[]{0.001f, 0.5f, 1f, 3f, 100f}) {
            RearViewTouchModel.PinchResult r = pinch(0, 0, w, h, 0.5f, 0.5f, 0f, 0f, factor);
            assertEquals("倍数 " + factor + " 时比例变了",
                    aspect, (float) r.width / r.height, 0.02f);
        }
    }

    @Test
    public void sizeStaysWithinTheAllowedRange() {
        for (float factor : new float[]{0.001f, 0.5f, 1f, 3f, 100f}) {
            RearViewTouchModel.PinchResult r =
                    pinch(0, 0, 900, 300, 0.5f, 0.5f, 0f, 0f, factor);
            assertTrue("宽太小: " + r.width, r.width >= MIN_SIZE);
            assertTrue("高太小: " + r.height, r.height >= MIN_SIZE);
            assertTrue("宽超屏: " + r.width, r.width <= SCREEN_W);
            assertTrue("高超屏: " + r.height, r.height <= SCREEN_H);
        }
    }

    @Test
    public void nonsenseStartSizesAreSurvivable() {
        RearViewTouchModel.PinchResult r = pinch(10, 20, 0, 0, 0.5f, 0.5f, 100f, 100f, 2f);
        assertEquals(10, r.x);
        assertEquals(20, r.y);
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
