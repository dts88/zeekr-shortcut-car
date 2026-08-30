package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RearViewGeometry} 的单元测试。
 *
 * <p>取景算错的表现是「画面偏了一点」或者「有点变形」——在车上盯着一块小窗口，
 * 肉眼几乎判断不出是偏了还是本来就该这样。所以这部分必须在这里钉死。</p>
 *
 * <p>其中<b>比例不变</b>是最要紧的一条：后视镜里的车被拉扁或压长，
 * 会直接影响对距离的判断。</p>
 */
public class RearViewGeometryTest {

    private static final float EPS = 1e-5f;

    /** 极氪 7X 的真实合成流尺寸。 */
    private static CompositeStreamGeometry.Plan zeekrPlan() {
        return CompositeStreamGeometry.analyse(1280, 5140);
    }

    // ---------- 比例锁死 ----------

    /**
     * 整条规则的根本：源是正方形，取景块在<b>该路画面里</b>的宽高比
     * 必须等于显示框的宽高比 —— 只有这样，填满显示框时画面才不变形。
     */
    @Test
    public void theViewportAlwaysMatchesTheWindowShape() {
        int[][] windows = {
                {900, 340}, {1600, 200}, {400, 400}, {300, 900}, {3200, 480}, {120, 2000},
        };
        for (int[] window : windows) {
            RearViewGeometry.Viewport v = RearViewGeometry.Viewport.forWindow(
                    window[0], window[1], 0.5f);
            float windowAspect = (float) window[0] / window[1];
            float viewportAspect = v.width / v.height;
            assertEquals("窗口 " + window[0] + "x" + window[1] + " 的取景比例应当与之一致",
                    windowAspect, viewportAspect, 1e-3f);
        }
    }

    /** 取景块永远落在画面之内，而且至少有一边是占满的（不留黑边）。 */
    @Test
    public void theViewportFillsTheWindowWithoutLeavingTheFrame() {
        int[][] windows = {{900, 340}, {400, 400}, {300, 900}, {3200, 480}};
        for (int[] window : windows) {
            RearViewGeometry.Viewport v = RearViewGeometry.Viewport.forWindow(
                    window[0], window[1], 0.5f);
            assertTrue("左边越界", v.x >= -EPS);
            assertTrue("上边越界", v.y >= -EPS);
            assertTrue("右边越界", v.x + v.width <= 1f + EPS);
            assertTrue("下边越界", v.y + v.height <= 1f + EPS);
            assertTrue("必须有一边占满，否则就是留了黑边",
                    Math.abs(v.width - 1f) < EPS || Math.abs(v.height - 1f) < EPS);
        }
    }

    /** 扁框看到的是一条横带；方框看到的是整幅。 */
    @Test
    public void aWideWindowSeesAHorizontalStrip() {
        RearViewGeometry.Viewport wide = RearViewGeometry.Viewport.forWindow(1600, 400, 0.5f);
        assertEquals("横向应当占满", 1f, wide.width, EPS);
        assertEquals("纵向应当只有四分之一", 0.25f, wide.height, EPS);

        RearViewGeometry.Viewport square = RearViewGeometry.Viewport.forWindow(500, 500, 0.5f);
        assertEquals(1f, square.width, EPS);
        assertEquals(1f, square.height, EPS);
    }

    @Test
    public void aTallWindowSeesAVerticalStrip() {
        RearViewGeometry.Viewport tall = RearViewGeometry.Viewport.forWindow(400, 1600, 0.5f);
        assertEquals(1f, tall.height, EPS);
        assertEquals(0.25f, tall.width, EPS);
    }

    // ---------- 上下平移 ----------

    @Test
    public void panMovesTheStripUpAndDown() {
        RearViewGeometry.Viewport top = RearViewGeometry.Viewport.forWindow(1600, 400, 0f);
        RearViewGeometry.Viewport middle = RearViewGeometry.Viewport.forWindow(1600, 400, 0.5f);
        RearViewGeometry.Viewport bottom = RearViewGeometry.Viewport.forWindow(1600, 400, 1f);

        assertEquals("pan=0 应当贴着顶部", 0f, top.y, EPS);
        assertEquals("pan=1 应当贴着底部", 1f, bottom.y + bottom.height, EPS);
        assertTrue(top.y < middle.y && middle.y < bottom.y);
        assertEquals("平移不该改变看到多大一块", top.height, bottom.height, EPS);
    }

    /** 左右不跟着动 —— 看多宽由视野角度管，这里不重复给一个旋钮。 */
    @Test
    public void panNeverMovesHorizontally() {
        RearViewGeometry.Viewport top = RearViewGeometry.Viewport.forWindow(1600, 400, 0f);
        RearViewGeometry.Viewport bottom = RearViewGeometry.Viewport.forWindow(1600, 400, 1f);
        assertEquals(top.x, bottom.x, EPS);
        assertEquals(top.width, bottom.width, EPS);
    }

    @Test
    public void panIsClampedIntoRange() {
        assertEquals(0f, RearViewGeometry.clampPan(-5f), EPS);
        assertEquals(1f, RearViewGeometry.clampPan(5f), EPS);
        assertEquals(0.3f, RearViewGeometry.clampPan(0.3f), EPS);

        RearViewGeometry.Viewport v = RearViewGeometry.Viewport.forWindow(1600, 400, -3f);
        assertEquals(0f, v.y, EPS);
    }

    /** 方框已经全看到了，没有可挪的余地——这不是限制，是没有别的可挪。 */
    @Test
    public void aSquareWindowHasNothingLeftToPan() {
        RearViewGeometry.Viewport square = RearViewGeometry.Viewport.forWindow(500, 500, 0.5f);
        assertEquals(0f, square.verticalHeadroom(), EPS);

        RearViewGeometry.Viewport wide = RearViewGeometry.Viewport.forWindow(1600, 400, 0.5f);
        assertEquals(0.75f, wide.verticalHeadroom(), EPS);
        assertTrue("越扁的框可挪的越多",
                wide.verticalHeadroom() > square.verticalHeadroom());
    }

    @Test
    public void nonsenseWindowSizesFallBackToTheWholeFrame() {
        for (int[] bad : new int[][]{{0, 100}, {100, 0}, {-5, -5}}) {
            RearViewGeometry.Viewport v =
                    RearViewGeometry.Viewport.forWindow(bad[0], bad[1], 0.5f);
            assertEquals(1f, v.width, EPS);
            assertEquals(1f, v.height, EPS);
        }
    }

    // ---------- 后方是右上那一格 ----------

    @Test
    public void rearIsTheTopRightCell() {
        // FourLaneContainer: left = (cell % 2), top = (cell / 2)
        // 所以格子 1 就是右上
        assertEquals(1, RearViewGeometry.REAR_CELL);
    }

    // ---------- 换算成绘制参数 ----------

    @Test
    public void mapsTheRearLaneOfTheRealCompositeStream() {
        CompositeStreamGeometry.Plan plan = zeekrPlan();
        assertTrue("1280x5140 应当被识别为合成流", plan.isComposite());

        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(plan, 1, RearViewGeometry.Viewport.full());

        CompositeStreamGeometry.Lane lane = plan.lane(1);
        assertEquals(lane.u0, r.laneOffsetX, EPS);
        assertEquals(lane.v0, r.laneOffsetY, EPS);
        assertEquals(lane.u1 - lane.u0, r.laneScaleX, EPS);
        assertEquals(lane.v1 - lane.v0, r.laneScaleY, EPS);

        // 竖排合成流：四个画面在纵向排列，所以横向占满、纵向约四分之一
        assertEquals(1f, r.laneScaleX, 0.01f);
        assertTrue("纵向应当约为四分之一，实际 " + r.laneScaleY,
                r.laneScaleY > 0.2f && r.laneScaleY < 0.26f);
    }

    @Test
    public void theViewportPassesThroughUntouched() {
        RearViewGeometry.Viewport v = RearViewGeometry.Viewport.forWindow(1600, 400, 0.25f);
        RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(zeekrPlan(), 1, v);

        assertEquals(v.x, r.viewOffsetX, EPS);
        assertEquals(v.y, r.viewOffsetY, EPS);
        assertEquals(v.width, r.viewScaleX, EPS);
        assertEquals(v.height, r.viewScaleY, EPS);
    }

    /** 不是合成流时按整幅处理，后视镜至少还能出画面而不是全黑。 */
    @Test
    public void fallsBackToTheWholeFrameWhenNotComposite() {
        CompositeStreamGeometry.Plan ordinary = CompositeStreamGeometry.analyse(1280, 720);
        RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(
                ordinary, 1, RearViewGeometry.Viewport.full());

        assertEquals(0f, r.laneOffsetX, EPS);
        assertEquals(0f, r.laneOffsetY, EPS);
        assertEquals(1f, r.laneScaleX, EPS);
        assertEquals(1f, r.laneScaleY, EPS);
    }

    @Test
    public void handlesNullPlanAndOutOfRangeLane() {
        RearViewGeometry.ShaderRects nullPlan =
                RearViewGeometry.toShaderRects(null, 1, null);
        assertEquals(1f, nullPlan.laneScaleX, EPS);
        assertEquals(1f, nullPlan.viewScaleX, EPS);

        // 序号越界时退回整幅，后视镜至少还能出画面而不是取到隔壁那一路
        RearViewGeometry.ShaderRects badLane = RearViewGeometry.toShaderRects(
                zeekrPlan(), 99, RearViewGeometry.Viewport.full());
        assertEquals(1f, badLane.laneScaleY, EPS);
    }

    // ---------- 合成矩形：不做校正时用一个 2D 矩阵就够 ----------

    @Test
    public void combinedRectNestsTheViewportInsideTheLane() {
        CompositeStreamGeometry.Plan plan = zeekrPlan();
        CompositeStreamGeometry.Lane lane = plan.lane(1);

        // 一条扁带，落在画面下半部
        RearViewGeometry.Viewport v = RearViewGeometry.Viewport.forWindow(1600, 800, 1f);
        float[] rect = RearViewGeometry.combinedSourceRect(plan, 1, v);

        float laneW = lane.u1 - lane.u0;
        float laneH = lane.v1 - lane.v0;

        assertEquals(lane.u0 + v.x * laneW, rect[0], EPS);
        assertEquals(lane.v0 + v.y * laneH, rect[1], EPS);
        assertEquals(v.width * laneW, rect[2], EPS);
        assertEquals(v.height * laneH, rect[3], EPS);
    }

    @Test
    public void combinedRectWithTheFullViewportIsExactlyTheLane() {
        CompositeStreamGeometry.Plan plan = zeekrPlan();
        CompositeStreamGeometry.Lane lane = plan.lane(1);
        float[] rect = RearViewGeometry.combinedSourceRect(
                plan, 1, RearViewGeometry.Viewport.full());

        assertEquals(lane.u0, rect[0], EPS);
        assertEquals(lane.v0, rect[1], EPS);
        assertEquals(lane.u1 - lane.u0, rect[2], EPS);
        assertEquals(lane.v1 - lane.v0, rect[3], EPS);
    }

    /**
     * 采样绝不能越出后方那一路。
     *
     * <p>合成流里四路上下紧挨着排列，所以越界取到的是<b>隔壁摄像头的画面</b>，
     * 不是黑边 —— 这类错误在车上看着像「后视镜偶尔闪一下别的画面」，
     * 很难判断是怎么回事，必须在这里挡住。</p>
     *
     * <p>只扫后方这一路：显示哪一路不可配置，后视镜恒取右上那一格。</p>
     */
    @Test
    public void combinedRectAlwaysStaysInsideTheLane() {
        CompositeStreamGeometry.Plan plan = zeekrPlan();
        int[][] windows = {{900, 340}, {400, 400}, {300, 900}, {3200, 480}, {120, 2000}};
        for (int[] window : windows) {
            for (float pan : new float[]{0f, 0.5f, 1f, -3f, 7f}) {
                RearViewGeometry.Viewport v =
                        RearViewGeometry.Viewport.forWindow(window[0], window[1], pan);
                float[] r = RearViewGeometry.combinedSourceRect(
                        plan, RearViewGeometry.REAR_CELL, v);
                assertTrue("x 越界: " + r[0], r[0] >= -EPS);
                assertTrue("y 越界: " + r[1], r[1] >= -EPS);
                assertTrue("右边越界", r[0] + r[2] <= 1f + EPS);
                assertTrue("下边越界", r[1] + r[3] <= 1f + EPS);
                assertTrue("宽度必须为正", r[2] > 0f);
                assertTrue("高度必须为正", r[3] > 0f);
            }
        }
    }
}
