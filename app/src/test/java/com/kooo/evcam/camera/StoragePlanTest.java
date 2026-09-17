package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link StoragePlan} 的单元测试。
 *
 * <p>这里决定的是「删掉用户 U 盘上哪些录像」—— 删错了找不回来，所以每一条规则都钉住。</p>
 */
public class StoragePlanTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;

    /** 一组：同一分钟里两路相机的文件。 */
    private static List<StoragePlan.Clip> group(String stamp, long bytesEach) {
        return Arrays.asList(
                new StoragePlan.Clip(stamp + "_front.mp4", bytesEach),
                new StoragePlan.Clip(stamp + "_back.mp4", bytesEach));
    }

    private static List<StoragePlan.Clip> minutes(int count, long bytesEach) {
        List<StoragePlan.Clip> clips = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            clips.addAll(group(String.format("20260917_10%02d00", i), bytesEach));
        }
        return clips;
    }

    // ---------------------------------------------------------------- 只认自己的文件

    @Test
    public void onlyTheAppsOwnFilesCount() {
        assertTrue(StoragePlan.isOwnClip("20260917_101500_front.mp4"));
        assertTrue(StoragePlan.isOwnClip("20260917_101500_left.mp4"));
        assertFalse("用户自己的视频不能动", StoragePlan.isOwnClip("holiday.mp4"));
        assertFalse(StoragePlan.isOwnClip("20260917_101500_front.mp4.tmp"));
        assertFalse(StoragePlan.isOwnClip("20260917_101500_front.jpg"));
        assertFalse(StoragePlan.isOwnClip("backup_20260917_101500_front.mp4"));
        assertTrue(StoragePlan.isOwnPhoto("20260917_101500_front.jpg"));
        assertFalse(StoragePlan.isOwnPhoto("IMG_0001.jpg"));
    }

    // ---------------------------------------------------------------- 不限制：一个都不删

    @Test
    public void withoutACapNothingIsEverDeleted() {
        StoragePlan.Decision plenty = StoragePlan.decide(minutes(60, 500 * MB), 0, 50 * GB, GB);
        assertEquals(StoragePlan.Verdict.OK, plenty.verdict);
        assertTrue(plenty.toDelete.isEmpty());
    }

    /** 没设上限、空间又不够：停下来，而不是替用户删录像。 */
    @Test
    public void withoutACapAFullDriveStopsRecording() {
        StoragePlan.Decision full = StoragePlan.decide(minutes(60, 500 * MB), 0, 300 * MB, GB);
        assertEquals(StoragePlan.Verdict.FULL, full.verdict);
        assertTrue(full.capless);
        assertTrue(full.toDelete.isEmpty());
    }

    // ---------------------------------------------------------------- 设了上限

    @Test
    public void underTheCapNothingHappens() {
        // 10 组 × 2 路 × 100MB = 2GB，上限 10GB，剩余 50GB
        StoragePlan.Decision decision = StoragePlan.decide(minutes(10, 100 * MB), 10 * GB, 50 * GB,
                200 * MB);
        assertEquals(StoragePlan.Verdict.OK, decision.verdict);
    }

    /** 超了上限：从最旧的组删起，删到「已用 + 下一个分段 ≤ 上限」。 */
    @Test
    public void overTheCapTheOldestGroupsGoFirst() {
        // 30 组 × 2 路 × 100MB = 6GB，一个分段 200MB，上限 5GB → 至少要删 1.2GB = 6 组
        List<StoragePlan.Clip> clips = minutes(30, 100 * MB);
        StoragePlan.Decision decision = StoragePlan.decide(clips, 5 * GB, 50 * GB, 200 * MB);
        assertEquals(StoragePlan.Verdict.DELETE, decision.verdict);
        assertTrue(decision.toDelete.contains("20260917_100000_front.mp4"));
        assertTrue(decision.toDelete.contains("20260917_100000_back.mp4"));
        assertFalse("较新的不该动", decision.toDelete.contains("20260917_102900_front.mp4"));
        long used = 30 * 2 * 100 * MB;
        assertTrue(used - decision.deleteBytes + 200 * MB <= 5 * GB);
    }

    /** 一整组一起删：不能留下半组（某一分钟只剩一路）。 */
    @Test
    public void groupsAreDeletedWhole() {
        StoragePlan.Decision decision = StoragePlan.decide(minutes(30, 100 * MB), 5 * GB, 50 * GB,
                200 * MB);
        for (String name : decision.toDelete) {
            String stamp = StoragePlan.groupOf(name);
            assertTrue("半组: " + stamp, decision.toDelete.contains(stamp + "_front.mp4"));
            assertTrue("半组: " + stamp, decision.toDelete.contains(stamp + "_back.mp4"));
        }
    }

    /** 正在写的那一组（最新的）永远不删，哪怕上限小得离谱。 */
    @Test
    public void theGroupBeingWrittenIsNeverDeleted() {
        List<StoragePlan.Clip> clips = minutes(3, GB);
        StoragePlan.Decision decision = StoragePlan.decide(clips, GB, 50 * GB, 2 * GB);
        assertFalse(decision.toDelete.contains("20260917_100200_front.mp4"));
        assertFalse(decision.toDelete.contains("20260917_100200_back.mp4"));

        // 只剩正在写的那一组：没什么可删，不报 DELETE 空单
        StoragePlan.Decision onlyCurrent = StoragePlan.decide(group("20260917_100000", GB), GB,
                50 * GB, 2 * GB);
        assertEquals(StoragePlan.Verdict.OK, onlyCurrent.verdict);
    }

    /** 上限没超，但盘快满了（盘比上限小）：同样删旧的，保住余量。 */
    @Test
    public void aDriveSmallerThanTheCapStillKeepsItsMargin() {
        // 已用 3GB，上限 100GB，剩余只有 200MB，余量 = max(512MB, 2 × 200MB) = 512MB
        StoragePlan.Decision decision = StoragePlan.decide(minutes(15, 100 * MB), 100 * GB,
                200 * MB, 200 * MB);
        assertEquals(StoragePlan.Verdict.DELETE, decision.verdict);
        assertTrue(decision.deleteBytes >= 512 * MB - 200 * MB);
    }

    /** 盘被别的东西占满：删光本应用的旧录像也腾不出余量时，不删，直接停。 */
    @Test
    public void doesNotDeleteWhenDeletingEverythingWouldNotHelp() {
        // 只有 2 组 × 2 路 × 10MB，可删的只有 20MB；缺口 400MB
        StoragePlan.Decision decision = StoragePlan.decide(minutes(2, 10 * MB), 100 * GB,
                100 * MB, 200 * MB);
        assertEquals(StoragePlan.Verdict.FULL, decision.verdict);
        assertFalse(decision.capless);
        assertTrue("白删录像没有意义", decision.toDelete.isEmpty());
    }

    // ---------------------------------------------------------------- 估算

    @Test
    public void theMarginIsTwoSegmentsButNeverTiny() {
        assertEquals(4 * GB, StoragePlan.margin(2 * GB));
        assertEquals(StoragePlan.MIN_MARGIN_BYTES, StoragePlan.margin(10 * MB));
    }

    /** 分段大小按最近一个<b>写完的</b>组量：最新那组正在写，还很小，不能拿来估。 */
    @Test
    public void theSegmentEstimateSkipsTheGroupBeingWritten() {
        List<StoragePlan.Clip> clips = new ArrayList<>(minutes(5, 300 * MB));
        clips.addAll(group("20260917_110000", MB));  // 刚开始写
        assertEquals(600 * MB, StoragePlan.estimateSegmentBytes(clips));
        assertEquals(StoragePlan.DEFAULT_SEGMENT_BYTES,
                StoragePlan.estimateSegmentBytes(group("20260917_110000", MB)));
    }
}
