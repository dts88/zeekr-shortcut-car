package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RearViewGeometry} 的单元测试。
 *
 * <p>取景框算错的表现是「画面偏了一点」——在车上盯着一块小窗口，肉眼几乎判断不出
 * 是偏了还是本来就该这样。所以这部分必须在这里钉死。</p>
 */
public class RearViewGeometryTest {

    private static final float EPS = 1e-5f;

    /** 极氪 7X 的真实合成流尺寸。 */
    private static CompositeStreamGeometry.Plan zeekrPlan() {
        return CompositeStreamGeometry.analyse(1280, 5140);
    }

    // ---------- 蒙版的自我约束 ----------

    @Test
    public void cropClampsIntoRange() {
        RearViewGeometry.Crop c = new RearViewGeometry.Crop(-0.5f, -0.5f, 2f, 2f);
        assertEquals(0f, c.x, EPS);
        assertEquals(0f, c.y, EPS);
        assertEquals(1f, c.width, EPS);
        assertEquals(1f, c.height, EPS);
    }

    @Test
    public void cropNeverCollapsesToZero() {
        RearViewGeometry.Crop c = new RearViewGeometry.Crop(0.5f, 0.5f, 0f, -1f);
        assertEquals(RearViewGeometry.MIN_CROP_SIZE, c.width, EPS);
        assertEquals(RearViewGeometry.MIN_CROP_SIZE, c.height, EPS);
    }

    @Test
    public void cropStaysInsideWhenPushedPastTheEdge() {
        // 尺寸先夹、位置后夹：一个过大的尺寸不能把位置挤成负数
        RearViewGeometry.Crop c = new RearViewGeometry.Crop(0.9f, 0.9f, 0.4f, 0.4f);
        assertEquals(0.6f, c.x, EPS);
        assertEquals(0.6f, c.y, EPS);
        assertTrue(c.x + c.width <= 1f + EPS);
        assertTrue(c.y + c.height <= 1f + EPS);
    }

    // ---------- 上下移动：中间三分之一的上下滑 ----------

    @Test
    public void verticalShiftMovesOnlyY() {
        RearViewGeometry.Crop base = new RearViewGeometry.Crop(0.1f, 0.3f, 0.8f, 0.4f);
        RearViewGeometry.Crop moved = base.shiftedVertically(0.1f);

        assertEquals("左右不该变", base.x, moved.x, EPS);
        assertEquals("宽度不该变", base.width, moved.width, EPS);
        assertEquals("高度不该变", base.height, moved.height, EPS);
        assertEquals(0.4f, moved.y, EPS);
    }

    @Test
    public void verticalShiftStopsAtTheEdges() {
        RearViewGeometry.Crop base = new RearViewGeometry.Crop(0f, 0.3f, 1f, 0.4f);

        // 往下推过头：贴住底边，而不是滑出画面
        assertEquals(0.6f, base.shiftedVertically(10f).y, EPS);
        // 往上推过头：贴住顶边
        assertEquals(0f, base.shiftedVertically(-10f).y, EPS);
    }

    @Test
    public void headroomReportsHowFarItCanStillMove() {
        RearViewGeometry.Crop c = new RearViewGeometry.Crop(0f, 0.3f, 1f, 0.4f);
        assertEquals(0.3f, c.headroomAbove(), EPS);
        assertEquals(0.3f, c.headroomBelow(), EPS);
    }

    // ---------- 双指缩放 ----------

    @Test
    public void scaleKeepsTheCentreStill() {
        RearViewGeometry.Crop base = new RearViewGeometry.Crop(0.2f, 0.2f, 0.6f, 0.6f);
        RearViewGeometry.Crop smaller = base.scaledAboutCenter(0.5f);

        assertEquals("中心不该漂移",
                base.x + base.width / 2f, smaller.x + smaller.width / 2f, EPS);
        assertEquals(base.y + base.height / 2f, smaller.y + smaller.height / 2f, EPS);
        assertEquals(0.3f, smaller.width, EPS);
        assertEquals(0.3f, smaller.height, EPS);
    }

    @Test
    public void scaleCannotExceedTheFrameOrVanish() {
        RearViewGeometry.Crop base = new RearViewGeometry.Crop(0.2f, 0.2f, 0.6f, 0.6f);

        RearViewGeometry.Crop huge = base.scaledAboutCenter(100f);
        assertTrue(huge.width <= 1f + EPS);
        assertTrue(huge.x >= -EPS);
        assertTrue(huge.x + huge.width <= 1f + EPS);

        RearViewGeometry.Crop tiny = base.scaledAboutCenter(0.0001f);
        assertEquals(RearViewGeometry.MIN_CROP_SIZE, tiny.width, EPS);

        assertEquals("非正的倍数应原样返回", base.width,
                base.scaledAboutCenter(0f).width, EPS);
    }

    // ---------- 后方是右上那一格 ----------

    @Test
    public void rearIsTheTopRightCell() {
        // FourLaneContainer: left = (cell % 2), top = (cell / 2)
        // 所以格子 1 就是右上
        assertEquals(1, RearViewGeometry.REAR_CELL);
        assertEquals(1, RearViewGeometry.rearLaneIndex(null));
        assertEquals(1, RearViewGeometry.rearLaneIndex(new int[]{0, 1, 2, 3}));
    }

    @Test
    public void rearFollowsACustomLaneOrder() {
        // 用户把右上格换成了第 3 路，后视镜就该跟着取第 3 路
        assertEquals(3, RearViewGeometry.rearLaneIndex(new int[]{0, 3, 2, 1}));
    }

    @Test
    public void rearFallsBackWhenLaneOrderIsUnusable() {
        assertEquals(1, RearViewGeometry.rearLaneIndex(new int[]{0}));
        assertEquals(1, RearViewGeometry.rearLaneIndex(new int[]{0, 99, 2, 3}));
    }

    // ---------- 换算成着色器参数 ----------

    @Test
    public void mapsTheRearLaneOfTheRealCompositeStream() {
        CompositeStreamGeometry.Plan plan = zeekrPlan();
        assertTrue("1280x5140 应当被识别为合成流", plan.isComposite());

        RearViewGeometry.Crop crop = RearViewGeometry.Crop.full();
        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(plan, 1, crop);

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
    public void cropBecomesTheCropUniformsUntouched() {
        RearViewGeometry.Crop crop = new RearViewGeometry.Crop(0.1f, 0.35f, 0.8f, 0.45f);
        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(zeekrPlan(), 1, crop);

        assertEquals(0.1f, r.cropOffsetX, EPS);
        assertEquals(0.35f, r.cropOffsetY, EPS);
        assertEquals(0.8f, r.cropScaleX, EPS);
        assertEquals(0.45f, r.cropScaleY, EPS);
    }

    /** 不是合成流时按整幅处理，后视镜至少还能出画面而不是全黑。 */
    @Test
    public void fallsBackToTheWholeFrameWhenNotComposite() {
        CompositeStreamGeometry.Plan ordinary = CompositeStreamGeometry.analyse(1280, 720);
        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(ordinary, 1, RearViewGeometry.Crop.full());

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
        assertEquals(1f, nullPlan.cropScaleX, EPS);

        RearViewGeometry.ShaderRects badLane =
                RearViewGeometry.toShaderRects(zeekrPlan(), 99, RearViewGeometry.Crop.full());
        assertEquals(1f, badLane.laneScaleY, EPS);
    }

    /** 默认蒙版要落在画面偏下 —— 后视镜关心的是路面。 */
    @Test
    public void defaultCropLooksAtTheRoadNotTheSky() {
        RearViewGeometry.Crop c = RearViewGeometry.Crop.defaultCrop();
        assertTrue("应当偏向下半部", c.y + c.height / 2f > 0.5f);
        assertTrue(c.y >= 0f && c.y + c.height <= 1f);
    }
}
