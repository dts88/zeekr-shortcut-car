package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ZeekrMultiPlan} 的单元测试。
 *
 * <p>三路模式黑屏时，第一个要排除的就是「槽位分配是不是错了」。
 * 这部分是纯逻辑，在这里锁死，才能把排查范围缩到分辨率与并发上。</p>
 */
public class ZeekrMultiPlanTest {

    /** 极氪 7X 上的实际情况：0=后, 1=前, 2=外接（合成流）。 */
    private static final String[] ZEEKR_IDS = {"0", "1", "2"};

    @Test
    public void putsCompositeInItsSlotAndFillsCabinsWithTheRest() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, "2", null, null, null);

        assertEquals("2", plan.compositeId);
        assertTrue(plan.compositeIsReal);
        assertEquals("0", plan.cabin1Id);
        assertEquals("1", plan.cabin2Id);
        assertEquals(3, plan.assignedCount());
    }

    @Test
    public void manualMappingWins() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, "2", "1", "2", "0");

        assertEquals("1", plan.compositeId);
        assertEquals("2", plan.cabin1Id);
        assertEquals("0", plan.cabin2Id);
        assertFalse("手动指定的不是探测到的合成流，就不该按合成流拆分",
                plan.compositeIsReal);
    }

    @Test
    public void manualMappingCanRestoreTheDetectedComposite() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, "2", "2", "0", "1");

        assertEquals("2", plan.compositeId);
        assertTrue("手动指回探测到的那一路时，仍应按合成流拆分", plan.compositeIsReal);
    }

    @Test
    public void ignoresManualMappingThatNamesAMissingCamera() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, "2", "9", null, null);

        assertEquals("指定了不存在的相机时应退回自动分配", "2", plan.compositeId);
        assertTrue(plan.compositeIsReal);
    }

    @Test
    public void borrowsAnOrdinaryCameraWhenNoCompositeWasFound() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, null, null, null, null);

        assertEquals("0", plan.compositeId);
        assertFalse("顶替上来的普通相机不能按四联合成流去拆", plan.compositeIsReal);
        assertEquals("1", plan.cabin1Id);
        assertEquals("2", plan.cabin2Id);
    }

    @Test
    public void ignoresADetectedCompositeThatIsNotActuallyAvailable() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(new String[]{"0", "1"}, "2", null, null, null);

        assertEquals("0", plan.compositeId);
        assertFalse(plan.compositeIsReal);
        assertEquals("1", plan.cabin1Id);
        assertNull(plan.cabin2Id);
    }

    @Test
    public void handlesFewerCamerasThanSlots() {
        ZeekrMultiPlan onlyComposite = ZeekrMultiPlan.build(new String[]{"2"}, "2", null, null, null);
        assertEquals("2", onlyComposite.compositeId);
        assertNull(onlyComposite.cabin1Id);
        assertNull(onlyComposite.cabin2Id);
        assertEquals(1, onlyComposite.assignedCount());
    }

    @Test
    public void handlesEmptyAndDuplicateInput() {
        ZeekrMultiPlan empty = ZeekrMultiPlan.build(new String[0], null, null, null, null);
        assertNull(empty.compositeId);
        assertEquals(0, empty.assignedCount());

        ZeekrMultiPlan nullIds = ZeekrMultiPlan.build(null, null, null, null, null);
        assertEquals(0, nullIds.assignedCount());

        // 重复 id 不应该占掉两个槽位
        ZeekrMultiPlan dupes = ZeekrMultiPlan.build(
                new String[]{"0", "0", "1"}, null, null, null, null);
        assertEquals("0", dupes.compositeId);
        assertEquals("1", dupes.cabin1Id);
        assertNull(dupes.cabin2Id);
    }

    @Test
    public void explanationNamesWhatHappened() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.build(ZEEKR_IDS, "2", null, null, null);
        assertTrue(plan.explanation.contains("可用相机"));
        assertTrue(plan.explanation.contains("合成流"));
    }
}
