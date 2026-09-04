package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link CompositeStreamGeometry} 的 JVM 单元测试。
 *
 * <p>这些用例把「极氪 7X 合成流长什么样」这个事实固定下来：改动拆分逻辑时，
 * 只要四个画面的像素位置对不上，测试就会失败。</p>
 */
public class CompositeStreamGeometryTest {

    /** 合成流那一路。诊断报告里是相机 2（EXTERNAL），但代码不写死 id。 */
    private static final String COMPOSITE = "2";

    /** 座舱那两路之一。 */
    private static final String CABIN = "0";

    @Before
    public void registerCompositeCamera() {
        StreamLayoutTable.setCompositeCameraId(COMPOSITE);
    }

    private static final float EPS = 1e-6f;

    // ---------- 竖排 1280x5140：实测排布 ----------

    @Test
    public void verticalCompositeSplitsIntoFourSquareLanes() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(COMPOSITE, 1280, 5140);

        assertEquals(CompositeStreamGeometry.Stacking.VERTICAL, plan.stacking);
        assertTrue("应识别出分隔带", plan.bandsDetected);
        assertEquals("5140 - 4*1280 = 20，均分给 5 条带", 4, plan.bandPx);
        assertEquals(1280, plan.laneSizePx);
        assertEquals(4, plan.laneCount());

        // 上边缘一条带，随后每个画面后再跟一条带
        int[] expectedTop = {4, 1288, 2572, 3856};
        for (int i = 0; i < 4; i++) {
            CompositeStreamGeometry.Lane lane = plan.lane(i);
            assertEquals("lane " + i + " x", 0, lane.x);
            assertEquals("lane " + i + " y", expectedTop[i], lane.y);
            assertEquals("lane " + i + " width", 1280, lane.width);
            assertEquals("lane " + i + " height", 1280, lane.height);
            assertEquals("lane " + i + " 应为正方形", 1.0f, lane.aspect(), EPS);
        }

        // 最后一个画面之后应恰好剩下一条下边缘分隔带
        CompositeStreamGeometry.Lane last = plan.lane(3);
        assertEquals(5140 - plan.bandPx, last.y + last.height);
    }

    @Test
    public void verticalLaneUvCoordinatesAreNormalised() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(COMPOSITE, 1280, 5140);
        CompositeStreamGeometry.Lane first = plan.lane(0);

        assertEquals(0f, first.u0, EPS);
        assertEquals(1f, first.u1, EPS);
        assertEquals(4f / 5140f, first.v0, EPS);
        assertEquals(1284f / 5140f, first.v1, EPS);
    }

    // ---------- 横排 5120x1280 ----------



    // ---------- 非合成流 ----------

    @Test
    public void ordinaryFrameIsNotTreatedAsComposite() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(COMPOSITE, 1920, 1080);

        assertEquals(CompositeStreamGeometry.Stacking.NOT_COMPOSITE, plan.stacking);
        assertFalse(plan.isComposite());
        assertEquals("非合成流只产出整帧一个 lane", 1, plan.laneCount());
        assertEquals(1920, plan.lane(0).width);
        assertEquals(1080, plan.lane(0).height);
    }

    @Test
    public void looksLikeCompositeMatchesKnownStreamSizes() {
        StreamLayoutTable.setCompositeCameraId(COMPOSITE);

        // 合成流那一路实测声明的两个要拆的尺寸
        assertTrue(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1280, 5140));
        assertTrue(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 3840, 2160));

        // 同一路的其他尺寸不拆
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1920, 1080));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1280, 720));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 0, 0));

        // 不是这一路的，什么尺寸都不拆
        assertFalse(CompositeStreamGeometry.looksLikeComposite(CABIN, 1280, 5140));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(CABIN, 3840, 2160));
        assertFalse("还没认出哪一路是合成流时，宁可不拆",
                CompositeStreamGeometry.looksLikeComposite(null, 1280, 5140));
    }

    // 5120x1280 和 1280x5120 那两条测试删掉了：诊断报告里这台车的三路相机
    // 都没有声明过这两个尺寸，测试它们等于在钉一个不存在的行为。真出现别的
    // 固件、别的尺寸，往 StreamLayoutTable 的表里加一行，再补对应的测试。

    // ---------- 等分与分隔带 ----------
    //
    // 原来这里有三条针对「表外尺寸也拆」的测试（1280x6000、1280x4800、640x2570）。
    // 现在表外的组合一律不拆，那三条针对的行为不存在了。等分与分隔带这两条分支
    // 由表里的两个真实尺寸各自覆盖：1280x5140 走分隔带，3840x2160 走等分。

    // ---------- 不变量 ----------

    @Test
    public void lanesNeverEscapeTheSourceFrame() {
        StreamLayoutTable.setCompositeCameraId(COMPOSITE);
        int[][] sizes = {
                {1280, 5140}, {1280, 5120}, {5120, 1280}, {3840, 2160},
                {1280, 6000}, {1280, 4800}, {640, 2570}, {1920, 1080},
        };
        for (int[] size : sizes) {
            CompositeStreamGeometry.Plan plan =
                    CompositeStreamGeometry.analyse(COMPOSITE, size[0], size[1]);
            for (int i = 0; i < plan.laneCount(); i++) {
                CompositeStreamGeometry.Lane lane = plan.lane(i);
                String where = size[0] + "x" + size[1] + " lane " + i;
                assertTrue(where + " x", lane.x >= 0);
                assertTrue(where + " y", lane.y >= 0);
                assertTrue(where + " right", lane.x + lane.width <= size[0]);
                assertTrue(where + " bottom", lane.y + lane.height <= size[1]);
                assertTrue(where + " u0", lane.u0 >= 0f && lane.u0 <= 1f);
                assertTrue(where + " v1", lane.v1 >= 0f && lane.v1 <= 1f);
                assertTrue(where + " u0<u1", lane.u0 < lane.u1);
                assertTrue(where + " v0<v1", lane.v0 < lane.v1);
            }
        }
    }

    @Test
    public void lanesDoNotOverlapAlongTheStackingAxis() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(COMPOSITE, 1280, 5140);
        for (int i = 1; i < plan.laneCount(); i++) {
            CompositeStreamGeometry.Lane previous = plan.lane(i - 1);
            CompositeStreamGeometry.Lane current = plan.lane(i);
            assertTrue("lane " + i + " 不应与前一个重叠",
                    current.y >= previous.y + previous.height);
        }
    }

    // ---------- 参数校验 ----------

    @Test
    public void rejectsNonPositiveDimensions() {
        for (int[] bad : new int[][]{{0, 100}, {100, 0}, {-1, 100}, {100, -1}}) {
            try {
                CompositeStreamGeometry.analyse(COMPOSITE, bad[0], bad[1]);
                fail("应拒绝 " + bad[0] + "x" + bad[1]);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    /**
     * 3840×2160：合成流那一路要拆，座舱那两路不能拆。
     *
     * <p>这个尺寸座舱相机也声明支持，光看尺寸判断不了 —— 拆不拆只取决于
     * 「哪一路相机 + 什么分辨率」。</p>
     */
    @Test
    public void theSixteenByNineSizeSplitsOnlyOnTheCompositeCamera() {
        StreamLayoutTable.setCompositeCameraId(COMPOSITE);

        assertTrue(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 3840, 2160));
        assertFalse("座舱那两路不能被切成四块",
                CompositeStreamGeometry.looksLikeComposite(CABIN, 3840, 2160));
        assertFalse("不知道是哪一路时不拆",
                CompositeStreamGeometry.looksLikeComposite(null, 3840, 2160));
    }

    /** 3840×2160 等分成四条 3840×540。 */
    @Test
    public void theSixteenByNineSizeSplitsIntoFourEqualLanes() {
        StreamLayoutTable.setCompositeCameraId(COMPOSITE);

        CompositeStreamGeometry.Plan plan =
                CompositeStreamGeometry.analyse(COMPOSITE, 3840, 2160);
        assertTrue(plan.isComposite());
        assertEquals(CompositeStreamGeometry.LANE_COUNT, plan.lanes.length);
        assertEquals(3840, plan.lanes[0].width);
        assertEquals(540, plan.lanes[0].height);
        assertEquals(0, plan.lanes[0].y);
        assertEquals(540, plan.lanes[1].y);
        assertEquals(1080, plan.lanes[2].y);
        assertEquals(1620, plan.lanes[3].y);
    }

    /**
     * 表里没有的组合一律不拆，长条也一样。
     *
     * <p>猜的代价不对称：不拆看到的是一条挤在一起的长条，一眼就知道不对；
     * 拆错看到的是四块被切开的画面，反而像是「功能正常」。</p>
     */
    @Test
    public void combinationsOutsideTheTableAreNeverSplit() {
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1920, 1080));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1280, 720));
        // 长条比例也不拆 —— 长宽比只用来认出哪一路是合成流，不用来决定怎么拆
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1280, 6000));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 5120, 1280));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(COMPOSITE, 1280, 5120));
    }

    /** 表里就是这台车实测确认过的两个尺寸，一个不多一个不少。 */
    @Test
    public void theTableHoldsExactlyTheConfirmedSizes() {
        int[][] sizes = StreamLayoutTable.compositeSizes();
        assertEquals(2, sizes.length);
        assertEquals(1280, sizes[0][0]);
        assertEquals(5140, sizes[0][1]);
        assertEquals(3840, sizes[1][0]);
        assertEquals(2160, sizes[1][1]);
    }

    /** 登记是进程内全局的，每条测试跑完都要还原。 */
    @After
    public void clearCompositeCamera() {
        StreamLayoutTable.reset();
    }

}
