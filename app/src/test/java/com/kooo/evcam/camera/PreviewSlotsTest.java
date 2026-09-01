package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link PreviewSlots} 的单元测试。
 *
 * <p>这里钉住的是同一处笔误咬过两次的那条规则：left 是<b>第三个槽位</b>，
 * 不是「和 right 成对的那一个」。</p>
 */
public class PreviewSlotsTest {

    @Test
    public void slotNumbersFollowTheLayoutOrder() {
        assertEquals(1, PreviewSlots.slotOf("front"));
        assertEquals(2, PreviewSlots.slotOf("back"));
        assertEquals(3, PreviewSlots.slotOf("left"));
        assertEquals(4, PreviewSlots.slotOf("right"));
    }

    @Test
    public void unknownKeyHasNoSlot() {
        assertEquals(0, PreviewSlots.slotOf("cabin"));
        assertEquals(0, PreviewSlots.slotOf(null));
    }

    /** 三路配置里 left 是存在的 —— 这一条错了就是「三路全黑」和「录少一路」。 */
    @Test
    public void threeCameraSetupHasLeftButNotRight() {
        assertTrue(PreviewSlots.exists(3, "front"));
        assertTrue(PreviewSlots.exists(3, "back"));
        assertTrue(PreviewSlots.exists(3, "left"));
        assertFalse(PreviewSlots.exists(3, "right"));
    }

    @Test
    public void oneCameraSetupHasOnlyFront() {
        assertTrue(PreviewSlots.exists(1, "front"));
        assertFalse(PreviewSlots.exists(1, "back"));
        assertFalse(PreviewSlots.exists(1, "left"));
        assertFalse(PreviewSlots.exists(1, "right"));
    }

    @Test
    public void fourCameraSetupHasAllOfThem() {
        for (String key : PreviewSlots.KEYS) {
            assertTrue(key, PreviewSlots.exists(4, key));
        }
    }

    /** 要等几路就绪，等于这套布局实际会给几路 —— 与「要录几路」无关。 */
    @Test
    public void requiredTexturesFollowsTheLayoutNotTheRecordingSelection() {
        assertEquals(1, PreviewSlots.requiredTextures(1));
        assertEquals(2, PreviewSlots.requiredTextures(2));
        assertEquals(3, PreviewSlots.requiredTextures(3));
        assertEquals(4, PreviewSlots.requiredTextures(4));
    }

    /**
     * 路数是坏值时也得能开起来。
     *
     * <p>返回 0 会让闸门在一路都没就绪时就放行，返回一个大于 4 的数
     * 则永远等不到 —— 两头都是黑屏，所以夹在 [1, 4]。</p>
     */
    @Test
    public void outOfRangeCountsStillYieldAWorkableGate() {
        assertEquals(1, PreviewSlots.requiredTextures(0));
        assertEquals(1, PreviewSlots.requiredTextures(-3));
        assertEquals(4, PreviewSlots.requiredTextures(9));
    }

    @Test
    public void cameraStartsOnlyWhenEveryLaneOfThisLayoutIsReady() {
        assertFalse(PreviewSlots.canStartCamera(2, 3));
        assertTrue(PreviewSlots.canStartCamera(3, 3));
        assertTrue("多出来的就绪回调不该把闸门关上",
                PreviewSlots.canStartCamera(4, 3));
    }
}
