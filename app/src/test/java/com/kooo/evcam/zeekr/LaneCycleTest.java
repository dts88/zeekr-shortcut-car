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

    private static final MirrorViews FOUR = MirrorViews.FOUR;
    private static final MirrorViews FRONT_REAR = MirrorViews.FRONT_REAR;
    private static final MirrorViews REAR_CABIN = MirrorViews.REAR_CABIN;

    /** 顺时针：后 → 左 → 前 → 右 → 回到后。 */
    @Test
    public void clockwiseGoesRearLeftFrontRight() {
        int lane = LaneCycle.REAR;
        int[] expected = {LaneCycle.LEFT, LaneCycle.FRONT, LaneCycle.RIGHT, LaneCycle.REAR};
        for (int step = 0; step < expected.length; step++) {
            lane = LaneCycle.next(lane, true, FOUR);
            assertEquals("第 " + (step + 1) + " 步", expected[step], lane);
        }
    }

    /** 逆时针：后 → 右 → 前 → 左 → 回到后。 */
    @Test
    public void counterClockwiseGoesRearRightFrontLeft() {
        int lane = LaneCycle.REAR;
        int[] expected = {LaneCycle.RIGHT, LaneCycle.FRONT, LaneCycle.LEFT, LaneCycle.REAR};
        for (int step = 0; step < expected.length; step++) {
            lane = LaneCycle.next(lane, false, FOUR);
            assertEquals("第 " + (step + 1) + " 步", expected[step], lane);
        }
    }

    /** 两个方向互为逆运算 —— 划过头了划回来就该回到原处。 */
    @Test
    public void theTwoDirectionsUndoEachOther() {
        for (int lane : new int[]{LaneCycle.FRONT, LaneCycle.REAR,
                LaneCycle.LEFT, LaneCycle.RIGHT}) {
            assertEquals(lane, LaneCycle.next(LaneCycle.next(lane, true, FOUR), false, FOUR));
            assertEquals(lane, LaneCycle.next(LaneCycle.next(lane, false, FOUR), true, FOUR));
        }
    }

    /** 转到底会绕回来，不该卡在某一路上。 */
    @Test
    public void theRingWrapsAround() {
        int lane = LaneCycle.REAR;
        for (int i = 0; i < 4; i++) {
            lane = LaneCycle.next(lane, true, FOUR);
        }
        assertEquals("转满一圈应当回到起点", LaneCycle.REAR, lane);
    }

    /** 前后：来回都只在这两路之间。 */
    @Test
    public void frontRearStaysBetweenTheTwo() {
        assertEquals(LaneCycle.FRONT, LaneCycle.next(LaneCycle.REAR, true, FRONT_REAR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.FRONT, true, FRONT_REAR));
        assertEquals(LaneCycle.FRONT, LaneCycle.next(LaneCycle.REAR, false, FRONT_REAR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.FRONT, false, FRONT_REAR));
    }

    /** 后视 + 后座舱：来回都只在后视和后座舱之间。 */
    @Test
    public void rearCabinStaysBetweenRearAndCabin() {
        assertEquals(LaneCycle.CABIN_REAR, LaneCycle.next(LaneCycle.REAR, true, REAR_CABIN));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.CABIN_REAR, true, REAR_CABIN));
        assertEquals(LaneCycle.CABIN_REAR, LaneCycle.next(LaneCycle.REAR, false, REAR_CABIN));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.CABIN_REAR, false, REAR_CABIN));
    }

    /**
     * 停在「左」的时候换成两路的那两档，环上没有它 —— 应当退回后视，
     * 而不是卡在一个划不动的画面上。从后座舱换回四向也一样。
     */
    @Test
    public void laneOffTheRingFallsBackToRear() {
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.LEFT, true, FRONT_REAR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.RIGHT, false, FRONT_REAR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.FRONT, true, REAR_CABIN));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.CABIN_REAR, true, FOUR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(LaneCycle.CABIN_REAR, false, FRONT_REAR));
    }

    @Test
    public void nonsenseLanesFallBackToRear() {
        assertEquals(LaneCycle.REAR, LaneCycle.next(99, true, FOUR));
        assertEquals(LaneCycle.REAR, LaneCycle.next(-1, false, FOUR));
    }

    @Test
    public void ringMembershipMatchesTheViews() {
        assertTrue(LaneCycle.isOnRing(LaneCycle.LEFT, FOUR));
        assertFalse("四向里没有后座舱", LaneCycle.isOnRing(LaneCycle.CABIN_REAR, FOUR));
        assertFalse("前后时侧视不在环上", LaneCycle.isOnRing(LaneCycle.LEFT, FRONT_REAR));
        assertTrue(LaneCycle.isOnRing(LaneCycle.REAR, FRONT_REAR));
        assertTrue(LaneCycle.isOnRing(LaneCycle.FRONT, FRONT_REAR));
        assertTrue(LaneCycle.isOnRing(LaneCycle.REAR, REAR_CABIN));
        assertTrue(LaneCycle.isOnRing(LaneCycle.CABIN_REAR, REAR_CABIN));
        assertFalse(LaneCycle.isOnRing(LaneCycle.FRONT, REAR_CABIN));
    }

    /**
     * 后视、后座舱镜像（照车内后视镜里看到的样子）—— 前视和侧视是「朝那边看过去」的画面，翻了反而与实际相反。
     */
    @Test
    public void rearAndCabinAreMirrored() {
        assertTrue(LaneCycle.isMirrored(LaneCycle.REAR));
        assertTrue(LaneCycle.isMirrored(LaneCycle.CABIN_REAR));
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
        assertEquals("后座舱", LaneCycle.labelOf(LaneCycle.CABIN_REAR));
    }

    /** 存下去的 key 是契约，写死在测试里；认不出来的回到四向。 */
    @Test
    public void viewsKeysAreFixed() {
        assertEquals("four", MirrorViews.FOUR.key);
        assertEquals("front_rear", MirrorViews.FRONT_REAR.key);
        assertEquals("rear_cabin", MirrorViews.REAR_CABIN.key);
        for (MirrorViews views : MirrorViews.values()) {
            assertEquals(views, MirrorViews.fromKey(views.key));
        }
        assertEquals(MirrorViews.FOUR, MirrorViews.fromKey(null));
        assertEquals(MirrorViews.FOUR, MirrorViews.fromKey("something_else"));
    }
}
