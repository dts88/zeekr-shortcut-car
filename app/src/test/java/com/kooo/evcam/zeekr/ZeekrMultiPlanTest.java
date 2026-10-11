package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

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

    // ================================================================= 照存下的映射建（2.11 阶段 6）

    /** 完整的那一份：极氪 7X 列表完整时分出来的。 */
    private static ZeekrMultiPlan.Stored stored7x() {
        return ZeekrMultiPlan.Stored.of(ZeekrMultiPlan.build(ZEEKR_IDS, "2", null, null, null), 1280, 5140,
                ZeekrMultiPlan.Stored.overridesKey(null, null, null));
    }

    /** 列表完整：照列表分，它就是该存下来的那一份；存着的不一样也照列表（存着的是上一次完整时的样子）。 */
    @Test
    public void aCompleteListIsUsedAndIsTheOneToStore() {
        ZeekrMultiPlan.Stored other = new ZeekrMultiPlan.Stored("5", "6", "7", true, 1280, 5140,
                ZeekrMultiPlan.Stored.overridesKey(null, null, null));
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(ZEEKR_IDS, "2", null, null, null, other);

        assertFalse(plan.fromStored);
        assertTrue(plan.complete());
        assertEquals("2", plan.compositeId);
        assertEquals("0", plan.cabin1Id);
        assertEquals("1", plan.cabin2Id);
        assertTrue(plan.missing.isEmpty());
    }

    /**
     * 车机拿着后座舱（相机 1）时冷启动：它不在相机列表里。照存下的建，三路都在，相机 1 记成「列表里现在没有」——
     * 交给通道调度判「不在」，后座舱那一格不会因此没了。
     */
    @Test
    public void aCameraMissingFromTheListIsStillBuiltFromTheStoredMapping() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(new String[]{"0", "2"}, "2", null, null, null, stored7x());

        assertTrue(plan.fromStored);
        assertFalse("照存下的建的不再存一遍", plan.complete());
        assertEquals("2", plan.compositeId);
        assertTrue(plan.compositeIsReal);
        assertEquals("0", plan.cabin1Id);
        assertEquals("1", plan.cabin2Id);
        assertEquals(3, plan.assignedCount());
        assertEquals(Collections.singletonList("1"), plan.missing);
    }

    /** 环视（合成流）也不在列表里：照存下的建，它仍是真合成流（探测不到它，存下的记得）。 */
    @Test
    public void theStoredMappingRemembersTheCompositeWhenItIsNotListed() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(new String[]{"0", "1"}, null, null, null, null, stored7x());

        assertTrue(plan.fromStored);
        assertEquals("2", plan.compositeId);
        assertTrue(plan.compositeIsReal);
        assertEquals(Collections.singletonList("2"), plan.missing);
    }

    /** 第一次装（没存过）：照列表分，少一路就少一路（大纲 §6 风险 13）。 */
    @Test
    public void withNothingStoredAnIncompleteListIsUsedAsItIs() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(new String[]{"0", "2"}, "2", null, null, null, null);

        assertFalse(plan.fromStored);
        assertFalse(plan.complete());
        assertEquals("2", plan.compositeId);
        assertEquals("0", plan.cabin1Id);
        assertNull(plan.cabin2Id);
    }

    /** 手动指定变了：存下的那一份作废，按当时的列表重算。 */
    @Test
    public void changedOverridesVoidTheStoredMapping() {
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(new String[]{"0", "2"}, "2", null, "2", null, stored7x());

        assertFalse(plan.fromStored);
        assertNull(plan.cabin2Id);
    }

    /** 存下的不完整（两路指到同一个相机）不用。 */
    @Test
    public void anIncompleteStoredMappingIsNotUsed() {
        ZeekrMultiPlan.Stored broken = new ZeekrMultiPlan.Stored("2", "0", "0", true, 1280, 5140,
                ZeekrMultiPlan.Stored.overridesKey(null, null, null));
        assertFalse(broken.complete());
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(new String[]{"0", "2"}, "2", null, null, null, broken);
        assertFalse(plan.fromStored);
    }

    /** 编成一行、再读回来是同一份；没存过、格式不对的读回来是 null（当没存过）。 */
    @Test
    public void theStoredMappingRoundTripsAndRejectsGarbage() {
        ZeekrMultiPlan.Stored stored = new ZeekrMultiPlan.Stored("2", "0", "1", true, 1280, 5140,
                ZeekrMultiPlan.Stored.overridesKey("2", null, "1"));
        ZeekrMultiPlan.Stored back = ZeekrMultiPlan.Stored.decode(stored.encode());

        assertEquals(stored.encode(), back.encode());
        assertEquals("2", back.compositeId);
        assertEquals("0", back.cabin1Id);
        assertEquals("1", back.cabin2Id);
        assertTrue(back.compositeIsReal);
        assertEquals(1280, back.compositeWidth);
        assertEquals(5140, back.compositeHeight);
        assertEquals("2||1", back.overrides);

        assertNull(ZeekrMultiPlan.Stored.decode(null));
        assertNull(ZeekrMultiPlan.Stored.decode(""));
        assertNull(ZeekrMultiPlan.Stored.decode("v0;2;0;1;1;1280x5140;||"));
        assertNull(ZeekrMultiPlan.Stored.decode("v1;2;0;1;1;wide;||"));
        assertNull(ZeekrMultiPlan.Stored.decode("v1;2;0;1"));
    }

    /**
     * 合成流这一次没探测到、列表却完整：照列表分出来的是普通相机顶替环视、座舱挪位 —— 不能盖掉存着的那一份好的；
     * 还没存过能用的（没存过、手动指定变了）才存它（审查 2026-10-11）。探测到了照常存；照存下的建的不存。
     */
    @Test
    public void aPlanWithoutTheDetectedCompositeDoesNotReplaceAGoodStoredMapping() {
        String noOverrides = ZeekrMultiPlan.Stored.overridesKey(null, null, null);
        ZeekrMultiPlan missed = ZeekrMultiPlan.build(ZEEKR_IDS, null, null, null, null);
        assertTrue(missed.complete());
        assertFalse(missed.compositeIsReal);
        assertEquals("0", missed.compositeId);

        assertFalse(missed.worthStoring(stored7x(), noOverrides));
        assertTrue(missed.worthStoring(null, noOverrides));
        assertTrue("手动指定变了，存着的那一份本来就作废",
                missed.worthStoring(stored7x(), ZeekrMultiPlan.Stored.overridesKey("2", null, null)));

        ZeekrMultiPlan detected = ZeekrMultiPlan.build(ZEEKR_IDS, "2", null, null, null);
        assertTrue(detected.worthStoring(stored7x(), noOverrides));
        assertTrue(detected.worthStoring(null, noOverrides));

        ZeekrMultiPlan fromStored = ZeekrMultiPlan.choose(new String[]{"0", "2"}, "2", null, null, null, stored7x());
        assertTrue(fromStored.fromStored);
        assertFalse(fromStored.worthStoring(null, noOverrides));
    }
}
