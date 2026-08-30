package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * {@link SnapshotDiff} 的单元测试。
 *
 * <p>这个类是「找车辆信号」的主力工具：找不到信号时，必须能确定是<b>信号真的没有</b>，
 * 而不是<b>比对本身漏了</b>。所以漏报比误报严重得多，测试重点也在这里。</p>
 */
public class SnapshotDiffTest {

    private Map<String, String> map(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void identicalSnapshotsShowNothing() {
        List<SnapshotDiff.Change> changes = SnapshotDiff.between(
                map("a", "1", "b", "2"), map("a", "1", "b", "2"));
        assertTrue(changes.isEmpty());
    }

    @Test
    public void aChangedValueIsReported() {
        List<SnapshotDiff.Change> changes = SnapshotDiff.between(
                map("gear", "P"), map("gear", "R"));
        assertEquals(1, changes.size());
        assertEquals("gear", changes.get(0).key);
        assertEquals("P", changes.get(0).before);
        assertEquals("R", changes.get(0).after);
        assertFalse(changes.get(0).isAdded());
        assertFalse(changes.get(0).isRemoved());
    }

    /** 挂挡之后才出现的属性也是线索，不能因为「之前没有」就跳过。 */
    @Test
    public void aNewKeyCountsAsAChange() {
        List<SnapshotDiff.Change> changes = SnapshotDiff.between(map(), map("gear", "R"));
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).isAdded());
        assertEquals("R", changes.get(0).after);
    }

    @Test
    public void aDisappearingKeyCountsAsAChange() {
        List<SnapshotDiff.Change> changes = SnapshotDiff.between(map("gear", "R"), map());
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).isRemoved());
        assertEquals("R", changes.get(0).before);
    }

    /** 同一个动作做两次，两份报告要能直接对照着看。 */
    @Test
    public void changesComeOutInAStableOrder() {
        List<SnapshotDiff.Change> changes = SnapshotDiff.between(
                map("zebra", "1", "alpha", "1", "middle", "1"),
                map("zebra", "2", "alpha", "2", "middle", "2"));
        assertEquals(3, changes.size());
        assertEquals("alpha", changes.get(0).key);
        assertEquals("middle", changes.get(1).key);
        assertEquals("zebra", changes.get(2).key);
    }

    @Test
    public void nullSnapshotsAreSurvivable() {
        assertTrue(SnapshotDiff.between(null, map("a", "1")).isEmpty());
        assertTrue(SnapshotDiff.between(map("a", "1"), null).isEmpty());
        assertTrue(SnapshotDiff.signalOnly(null).isEmpty());
    }

    @Test
    public void obviousNoiseIsRecognised() {
        assertTrue(SnapshotDiff.isLikelyNoise("sys.uidcpupower.something"));
        assertTrue(SnapshotDiff.isLikelyNoise("debug.foo.bar"));
        assertTrue(SnapshotDiff.isLikelyNoise("ro.runtime.uptime"));
        assertFalse(SnapshotDiff.isLikelyNoise(null));
    }

    /**
     * 关键一条：宁可留噪音，也不要把车辆相关的项滤掉。
     * 滤错的代价是「明明读得到却以为读不到」，比多看几行大得多。
     */
    @Test
    public void nothingVehicleShapedIsFilteredOut() {
        String[] mustSurvive = {
                "vehicle.gear", "car.door.status", "ecarx.vehicle.speed",
                "persist.vendor.gear", "sys.car.turnsignal", "vendor.zeekr.reverse",
        };
        for (String key : mustSurvive) {
            assertFalse("不该被当成噪音: " + key, SnapshotDiff.isLikelyNoise(key));
        }
    }

    @Test
    public void filteringRemovesOnlyTheNoise() {
        List<SnapshotDiff.Change> all = SnapshotDiff.between(
                map("debug.x", "1", "vehicle.gear", "P"),
                map("debug.x", "2", "vehicle.gear", "R"));
        assertEquals(2, all.size());
        List<SnapshotDiff.Change> kept = SnapshotDiff.signalOnly(all);
        assertEquals(1, kept.size());
        assertEquals("vehicle.gear", kept.get(0).key);
    }

    @Test
    public void changesDescribeThemselvesReadably() {
        assertTrue(new SnapshotDiff.Change("k", "P", "R").toString().contains("P"));
        assertTrue(new SnapshotDiff.Change("k", null, "R").toString().contains("R"));
        assertTrue(new SnapshotDiff.Change("k", "P", null).toString().contains("P"));
    }
}
