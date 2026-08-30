package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LaneCycle} 的单元测试。
 *
 * <p>转向搞反了在车上很难察觉 —— 划一下换到了别的画面，看着都「对」，
 * 只有真去对照车身方向才发现反了。所以顺序必须在这里钉死。</p>
 */
public class LaneCycleTest {

    /** 顺时针：后 → 左 → 前 → 右 → 回到后。 */
    @Test
    public void clockwiseGoesRearLeftFrontRight() {
        int lane = LaneCycle.REAR;
        int[] expected = {LaneCycle.LEFT, LaneCycle.FRONT, LaneCycle.RIGHT, LaneCycle.REAR};
        for (int step = 0; step < expected.length; step++) {
            lane = LaneCycle.next(lane, true, false);
            assertEquals("第 " + (step + 1) + " 步", expected[step], lane);
        }
    }

    /** 逆时针：后 → 右 → 前 → 左 → 回到后。 */
    @Test
    public void counterClockwiseGoesRearRightFrontLeft() {
        int lane = LaneCycle.REAR;
        int[] expected = {LaneCycle.RIGHT, LaneCycle.FRONT, LaneCycle.LEFT, LaneCycle.REAR};
        for (int step = 0; step < expected.length; step++) {
            lane = LaneCycle.next(lane, false, false);
            assertEquals("第 " + (step + 1) + " 步", expected[step], lane);
        }
    }

    /** 两个方向互为逆运算 —— 划过头了划回来就该回到原处。 */
    @Test
    public void theTwoDirectionsUndoEachOther() {
        for (int lane : new int[]{LaneCycle.FRONT, LaneCycle.REAR,
                LaneCycle.LEFT, LaneCycle.RIGHT}) {
            assertEquals(lane, LaneCycle.next(LaneCycle.next(lane, true, false), false, false));
            assertEquals(lane, LaneCycle.next(LaneCycle.next(lane, false, false), true, false));
        }
    }

    /** 转到底会绕回来，不该卡在某一路上。 */
    @Test
    public void theRingWrapsAround() {
        int lane = LaneCycle.REAR;
        for (int i = 0; i < 4; i++) {
            lane = LaneCycle.next(lane, true, false);
        }
        assertEquals("转满一圈应当回到起点", LaneCycle.REAR, lane);
    }

    /** 只看前后时，来回都只在这两路之间。 */
    @Test
    public void frontRearOnlyStaysBetweenTheTwo() {
        assertEquals(LaneCycle.FRONT, LaneCycle.next(LaneCycle.REAR, true, true));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.FRONT, true, true));
        assertEquals(LaneCycle.FRONT, LaneCycle.next(LaneCycle.REAR, false, true));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.FRONT, false, true));
    }

    /**
     * 停在「左」的时候打开「只看前后」，环上没有它 —— 应当退回后视，
     * 而不是卡在一个划不动的画面上。
     */
    @Test
    public void switchingToFrontRearOnlyFromASideLaneFallsBackToRear() {
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.LEFT, true, true));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.RIGHT, false, true));
    }

    @Test
    public void nonsenseLanesFallBackToRear() {
        assertEquals(LaneCycle.REAR, LaneCycle.next(99, true, false));
        assertEquals(LaneCycle.REAR, LaneCycle.next(-1, false, false));
    }

    @Test
    public void ringMembershipMatchesTheMode() {
        assertTrue(LaneCycle.isOnRing(LaneCycle.LEFT, false));
        assertFalse("只看前后时侧视不在环上", LaneCycle.isOnRing(LaneCycle.LEFT, true));
        assertTrue(LaneCycle.isOnRing(LaneCycle.REAR, true));
        assertTrue(LaneCycle.isOnRing(LaneCycle.FRONT, true));
    }

    /** 只有后视镜像 —— 前视和侧视是「朝那边看过去」的画面，翻了反而与实际相反。 */
    @Test
    public void onlyTheRearLaneIsMirrored() {
        assertTrue(LaneCycle.isMirrored(LaneCycle.REAR));
        assertFalse(LaneCycle.isMirrored(LaneCycle.FRONT));
        assertFalse(LaneCycle.isMirrored(LaneCycle.LEFT));
        assertFalse(LaneCycle.isMirrored(LaneCycle.RIGHT));
    }

    /** 后视就是 2x2 的右上格，这一条是实车确认过的。 */
    @Test
    public void rearMatchesTheGeometryConstant() {
        assertEquals(RearViewGeometry.REAR_CELL, LaneCycle.REAR);
    }

    @Test
    public void everyLaneHasALabel() {
        assertEquals("前", LaneCycle.labelOf(LaneCycle.FRONT));
        assertEquals("后", LaneCycle.labelOf(LaneCycle.REAR));
        assertEquals("左", LaneCycle.labelOf(LaneCycle.LEFT));
        assertEquals("右", LaneCycle.labelOf(LaneCycle.RIGHT));
    }
}
