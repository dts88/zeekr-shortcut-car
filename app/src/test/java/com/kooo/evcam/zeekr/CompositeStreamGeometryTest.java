package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

/**
 * {@link CompositeStreamGeometry} 的 JVM 单元测试。
 *
 * <p>这些用例把「极氪 7X 合成流长什么样」这个事实固定下来：改动拆分逻辑时，
 * 只要四个画面的像素位置对不上，测试就会失败。</p>
 */
public class CompositeStreamGeometryTest {

    private static final float EPS = 1e-6f;

    // ---------- 竖排 1280x5140：实测排布 ----------

    @Test
    public void verticalCompositeSplitsIntoFourSquareLanes() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5140);

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
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5140);
        CompositeStreamGeometry.Lane first = plan.lane(0);

        assertEquals(0f, first.u0, EPS);
        assertEquals(1f, first.u1, EPS);
        assertEquals(4f / 5140f, first.v0, EPS);
        assertEquals(1284f / 5140f, first.v1, EPS);
    }

    // ---------- 横排 5120x1280 ----------

    @Test
    public void horizontalCompositeSplitsWithoutBands() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(5120, 1280);

        assertEquals(CompositeStreamGeometry.Stacking.HORIZONTAL, plan.stacking);
        assertTrue(plan.bandsDetected);
        assertEquals("5120 = 4*1280，没有多余像素", 0, plan.bandPx);
        assertEquals(1280, plan.laneSizePx);

        int[] expectedLeft = {0, 1280, 2560, 3840};
        for (int i = 0; i < 4; i++) {
            CompositeStreamGeometry.Lane lane = plan.lane(i);
            assertEquals("lane " + i + " x", expectedLeft[i], lane.x);
            assertEquals("lane " + i + " y", 0, lane.y);
            assertEquals(1280, lane.width);
            assertEquals(1280, lane.height);
        }
    }

    @Test
    public void verticalCompositeWithoutBandsIsAlsoSupported() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5120);

        assertEquals(CompositeStreamGeometry.Stacking.VERTICAL, plan.stacking);
        assertEquals(0, plan.bandPx);
        assertEquals(0, plan.lane(0).y);
        assertEquals(3840, plan.lane(3).y);
    }

    // ---------- 非合成流 ----------

    @Test
    public void ordinaryFrameIsNotTreatedAsComposite() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1920, 1080);

        assertEquals(CompositeStreamGeometry.Stacking.NOT_COMPOSITE, plan.stacking);
        assertFalse(plan.isComposite());
        assertEquals("非合成流只产出整帧一个 lane", 1, plan.laneCount());
        assertEquals(1920, plan.lane(0).width);
        assertEquals(1080, plan.lane(0).height);
    }

    @Test
    public void looksLikeCompositeMatchesKnownStreamSizes() {
        assertTrue(CompositeStreamGeometry.looksLikeComposite(1280, 5140));
        assertTrue(CompositeStreamGeometry.looksLikeComposite(1280, 5120));
        assertTrue(CompositeStreamGeometry.looksLikeComposite(5120, 1280));

        assertFalse(CompositeStreamGeometry.looksLikeComposite(1920, 1080));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(1280, 800));
        // 3840x2160 是 16:9，光看比例判断不出来 —— 要靠声明，见下面那几条
        assertFalse(CompositeStreamGeometry.looksLikeComposite(3840, 2160));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(0, 0));
    }

    // ---------- 未知排布的回退 ----------

    @Test
    public void unknownGeometryFallsBackToEqualSplitWithoutLosingPixels() {
        // 余量 880px 远超分隔带的合理范围，应退回等分
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 6000);

        assertEquals(CompositeStreamGeometry.Stacking.VERTICAL, plan.stacking);
        assertFalse("不应误判为分隔带", plan.bandsDetected);
        assertEquals(1500, plan.laneSizePx);
        assertEquals(0, plan.lane(0).y);
        assertEquals(4500, plan.lane(3).y);
        assertEquals("等分应覆盖整帧", 6000, plan.lane(3).y + plan.lane(3).height);
    }

    @Test
    public void squashedLanesFallBackInsteadOfCroppingRealPixels() {
        // 高度小于 4 个正方形画面，slack 为负 -> 回退等分
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 4800);

        assertFalse(plan.bandsDetected);
        assertEquals(1200, plan.laneSizePx);
    }

    // ---------- 内缩 ----------

    @Test
    public void cropInsetShrinksEveryLaneOnAllFourSides() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5140, 2);

        CompositeStreamGeometry.Lane first = plan.lane(0);
        assertEquals(2, first.x);
        assertEquals(4 + 2, first.y);
        assertEquals(1280 - 4, first.width);
        assertEquals(1280 - 4, first.height);
        assertTrue(plan.note.contains("内缩"));
    }

    @Test
    public void negativeInsetIsTreatedAsZero() {
        CompositeStreamGeometry.Plan plain = CompositeStreamGeometry.analyse(1280, 5140);
        CompositeStreamGeometry.Plan negative = CompositeStreamGeometry.analyse(1280, 5140, -8);

        assertEquals(plain.lane(0).x, negative.lane(0).x);
        assertEquals(plain.lane(0).width, negative.lane(0).width);
    }

    @Test
    public void absurdInsetStillLeavesAUsableLane() {
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5140, 100_000);

        for (int i = 0; i < plan.laneCount(); i++) {
            CompositeStreamGeometry.Lane lane = plan.lane(i);
            assertTrue("lane " + i + " 宽度必须为正", lane.width >= 1);
            assertTrue("lane " + i + " 高度必须为正", lane.height >= 1);
        }
    }

    // ---------- 不变量 ----------

    @Test
    public void lanesNeverEscapeTheSourceFrame() {
        int[][] sizes = {
                {1280, 5140}, {1280, 5120}, {5120, 1280},
                {1280, 6000}, {1280, 4800}, {640, 2570}, {1920, 1080},
        };
        for (int[] size : sizes) {
            CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(size[0], size[1], 1);
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
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(1280, 5140);
        for (int i = 1; i < plan.laneCount(); i++) {
            CompositeStreamGeometry.Lane previous = plan.lane(i - 1);
            CompositeStreamGeometry.Lane current = plan.lane(i);
            assertTrue("lane " + i + " 不应与前一个重叠",
                    current.y >= previous.y + previous.height);
        }
    }

    /**
     * 车机 HAL 有时只声明一个较小的 Surface 提示尺寸，但送来的仍是同一份合成内容。
     * 归一化坐标必须与全尺寸保持一致，缩放才不会走样。
     */
    @Test
    public void normalisedWindowsAreScaleInvariant() {
        CompositeStreamGeometry.Plan full = CompositeStreamGeometry.analyse(1280, 5140);
        CompositeStreamGeometry.Plan half = CompositeStreamGeometry.analyse(640, 2570);

        for (int i = 0; i < 4; i++) {
            assertEquals("lane " + i + " v0", full.lane(i).v0, half.lane(i).v0, 1e-3f);
            assertEquals("lane " + i + " v1", full.lane(i).v1, half.lane(i).v1, 1e-3f);
        }
    }

    // ---------- 参数校验 ----------

    @Test
    public void rejectsNonPositiveDimensions() {
        for (int[] bad : new int[][]{{0, 100}, {100, 0}, {-1, 100}, {100, -1}}) {
            try {
                CompositeStreamGeometry.analyse(bad[0], bad[1]);
                fail("应拒绝 " + bad[0] + "x" + bad[1]);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    /**
     * 声明之后，16:9 也按四格竖排拆。
     *
     * <p>环视这一路除了 1280×5140 还声明支持 3840×2160，实测里面同样是四格竖排、
     * 等分、顺序一致 —— 但比例判定永远过不了那道门槛，只能靠声明。</p>
     */
    @Test
    public void aDeclaredSizeIsSplitRegardlessOfItsRatio() {
        CompositeStreamGeometry.declareComposite(3840, 2160,
                CompositeStreamGeometry.Stacking.VERTICAL);

        assertTrue(CompositeStreamGeometry.looksLikeComposite(3840, 2160));
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(3840, 2160);
        assertTrue(plan.isComposite());
        assertEquals(CompositeStreamGeometry.LANE_COUNT, plan.lanes.length);
        // 等分：2160 / 4 = 540，每一格占满整个宽度
        assertEquals(540, plan.lanes[0].height);
        assertEquals(3840, plan.lanes[0].width);
        assertEquals(0, plan.lanes[0].y);
        assertEquals(540, plan.lanes[1].y);
        assertEquals(1620, plan.lanes[3].y);
    }

    /**
     * 声明只对声明的那一个尺寸生效。
     *
     * <p>全局打开的话，三路配置里 1920×1080 的座舱相机也会被切成四格。</p>
     */
    @Test
    public void aDeclarationDoesNotLeakToOtherSizes() {
        CompositeStreamGeometry.declareComposite(3840, 2160,
                CompositeStreamGeometry.Stacking.VERTICAL);

        assertFalse(CompositeStreamGeometry.looksLikeComposite(1920, 1080));
        assertFalse(CompositeStreamGeometry.looksLikeComposite(1280, 800));
        // 原来就认得的那个尺寸不受影响
        assertTrue(CompositeStreamGeometry.looksLikeComposite(1280, 5140));
    }

    /** 撤销声明之后回到纯比例判断。 */
    @Test
    public void clearingTheDeclarationRestoresRatioOnlyBehaviour() {
        CompositeStreamGeometry.declareComposite(3840, 2160,
                CompositeStreamGeometry.Stacking.VERTICAL);
        CompositeStreamGeometry.clearDeclaration();
        assertFalse(CompositeStreamGeometry.looksLikeComposite(3840, 2160));
    }

    /** 声明一个不合法的尺寸等于没声明，不能把状态搞坏。 */
    @Test
    public void anInvalidDeclarationIsIgnored() {
        CompositeStreamGeometry.declareComposite(0, 0,
                CompositeStreamGeometry.Stacking.VERTICAL);
        assertNull(CompositeStreamGeometry.declaredSize());
        CompositeStreamGeometry.declareComposite(3840, 2160, null);
        assertNull(CompositeStreamGeometry.declaredSize());
    }

    /** 声明是进程内全局的，每条测试跑完都要还原，否则会互相影响。 */
    @After
    public void clearDeclaration() {
        CompositeStreamGeometry.clearDeclaration();
    }

}
