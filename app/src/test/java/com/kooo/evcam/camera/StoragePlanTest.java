package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 分段大小按每一路最近一个<b>写完的</b>文件量：每一路最新的那个正在写，还很小，不能拿来估。 */
    @Test
    public void theSegmentEstimateSkipsTheGroupBeingWritten() {
        List<StoragePlan.Clip> clips = new ArrayList<>(minutes(5, 300 * MB));
        clips.addAll(group("20260917_110000", MB));  // 刚开始写
        assertEquals(600 * MB, StoragePlan.estimateSegmentBytes(clips));
        assertEquals(StoragePlan.DEFAULT_SEGMENT_BYTES,
                StoragePlan.estimateSegmentBytes(group("20260917_110000", MB)));
    }

    // ---------------------------------------------------------------- 一路单独离开、接回（2026-10-10）

    /** 一组：同一分钟里环视、前座舱、后座舱三路的文件。 */
    private static List<StoragePlan.Clip> threeLanes(String stamp, long bytesEach) {
        return Arrays.asList(
                new StoragePlan.Clip(stamp + "_surround.mp4", bytesEach),
                new StoragePlan.Clip(stamp + "_cabinfront.mp4", bytesEach),
                new StoragePlan.Clip(stamp + "_cabinrear.mp4", bytesEach));
    }

    /**
     * 后座舱 09:04 那一段之后离开了录像，09:05:23 单独接回：第一个文件按接回那一刻命名，不在整分上，自成最新的一组。
     * 环视、前座舱正在写 09:05:00 那一组 —— 它成了倒数第二组。
     */
    private static List<StoragePlan.Clip> rearRejoinedOffCycle() {
        List<StoragePlan.Clip> clips = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clips.addAll(threeLanes(String.format("20261010_09%02d00", i), 300 * MB));
        }
        clips.add(new StoragePlan.Clip("20261010_090500_surround.mp4", 100 * MB));
        clips.add(new StoragePlan.Clip("20261010_090500_cabinfront.mp4", 100 * MB));
        clips.add(new StoragePlan.Clip("20261010_090523_cabinrear.mp4", MB));
        return clips;
    }

    /**
     * 最新的一组只有接回那一路的一个文件：每一路最新的那个都不删，环视、前座舱正在写的那一组不会被当成旧的。
     * 以前只留最新的一组，09:05:00 那一组（正在写）就在删除名单里。
     */
    @Test
    public void everyLanesNewestFileIsKeptWhenARejoinedLaneStartsOffCycle() {
        StoragePlan.Decision d = StoragePlan.decide(rearRejoinedOffCycle(), GB, 100 * GB, 900 * MB);
        assertEquals(StoragePlan.Verdict.DELETE, d.verdict);
        assertFalse("环视正在写", d.toDelete.contains("20261010_090500_surround.mp4"));
        assertFalse("前座舱正在写", d.toDelete.contains("20261010_090500_cabinfront.mp4"));
        assertFalse("后座舱接回之后正在写", d.toDelete.contains("20261010_090523_cabinrear.mp4"));
        assertTrue("旧的照删", d.toDelete.contains("20261010_090000_surround.mp4"));
        assertTrue(d.toDelete.contains("20261010_090400_cabinrear.mp4"));
    }

    /** 一个分段按每一路最近写完的那个文件估：三路各 300 MB。以前取倒数第二组（正在写的那一组，200 MB），估小了。 */
    @Test
    public void theSegmentEstimateSumsEachLanesLastFinishedFile() {
        assertEquals(900 * MB, StoragePlan.estimateSegmentBytes(rearRejoinedOffCycle()));
    }

    /**
     * 后座舱 09:02 那一段之后离开了录像、还没接回：它最后那个文件也算它这一路最新的，那一组整组留着
     * （多留一组，不少留正在写的）；别的旧组照删，删的都是整组。
     */
    @Test
    public void aLaneThatLeftKeepsItsLastGroupWhole() {
        List<StoragePlan.Clip> clips = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clips.addAll(threeLanes(String.format("20261010_09%02d00", i), 300 * MB));
        }
        for (int i = 3; i < 6; i++) {
            String stamp = String.format("20261010_09%02d00", i);
            clips.add(new StoragePlan.Clip(stamp + "_surround.mp4", 300 * MB));
            clips.add(new StoragePlan.Clip(stamp + "_cabinfront.mp4", 300 * MB));
        }
        StoragePlan.Decision d = StoragePlan.decide(clips, GB, 100 * GB, 600 * MB);
        assertEquals(StoragePlan.Verdict.DELETE, d.verdict);
        assertTrue(d.toDelete.contains("20261010_090000_cabinrear.mp4"));
        assertFalse("后座舱最后那个", d.toDelete.contains("20261010_090200_cabinrear.mp4"));
        assertFalse("它那一组整组留着，不留半组", d.toDelete.contains("20261010_090200_surround.mp4"));
        assertTrue(d.toDelete.contains("20261010_090300_surround.mp4"));
        assertTrue(d.toDelete.contains("20261010_090300_cabinfront.mp4"));
        assertFalse("环视、前座舱正在写", d.toDelete.contains("20261010_090500_surround.mp4"));
    }

    /** 改名之前的 _front 和现在的 _surround 是同一路：旧名字的最后一个文件不会因为「它那一路最新」一直留着。 */
    @Test
    public void theRenamedSurroundIsOneLane() {
        List<StoragePlan.Clip> clips = Arrays.asList(
                new StoragePlan.Clip("20260917_100000_front.mp4", GB),
                new StoragePlan.Clip("20260917_100100_front.mp4", GB),
                new StoragePlan.Clip("20260917_100200_surround.mp4", GB));
        StoragePlan.Decision d = StoragePlan.decide(clips, GB, 50 * GB, GB);
        assertTrue(d.toDelete.contains("20260917_100100_front.mp4"));
        assertFalse(d.toDelete.contains("20260917_100200_surround.mp4"));
    }

    // ---------------------------------------------------------------- 锁定的影像

    /** 锁定的永远不进删除名单：跳过它，从下一组接着删。 */
    @Test
    public void lockedClipsAreNeverDeleted() {
        // 10 组 × 2 路 × 500 MB = 10 GB，上限 8 GB、一段 1 GB：要腾 3 GB
        List<StoragePlan.Clip> clips = minutes(10, 500 * MB);
        Set<String> locked = new HashSet<>(Arrays.asList(
                "20260917_100000_front.mp4", "20260917_100000_back.mp4"));
        StoragePlan.Decision d = StoragePlan.decide(clips, locked, 8 * GB, 100 * GB, GB);
        assertEquals(StoragePlan.Verdict.DELETE, d.verdict);
        assertFalse(d.toDelete.contains("20260917_100000_front.mp4"));
        assertFalse(d.toDelete.contains("20260917_100000_back.mp4"));
        assertTrue(d.toDelete.contains("20260917_100100_front.mp4"));
        assertTrue("锁着的那组不能删，就多删后面一组", d.toDelete.contains("20260917_100300_back.mp4"));
        assertFalse(d.lockedFull);
    }

    /** 锁定的照样算占用：锁满了，没锁的删光也不够 —— 停录，而且说得出是锁定的原因。 */
    @Test
    public void lockedFootageThatFillsTheCapStopsRecording() {
        List<StoragePlan.Clip> clips = minutes(10, 500 * MB);
        Set<String> locked = new HashSet<>();
        for (StoragePlan.Clip clip : clips.subList(0, 16)) {
            locked.add(clip.name);
        }
        StoragePlan.Decision d = StoragePlan.decide(clips, locked, 8 * GB, 100 * GB, GB);
        assertEquals(StoragePlan.Verdict.FULL, d.verdict);
        assertTrue(d.lockedFull);
        assertFalse(d.capless);
        assertTrue(d.toDelete.isEmpty());
    }

    /** 盘快满了也一样：腾余量只能删没锁的，不够就停，原因是锁定。 */
    @Test
    public void lockedFootageThatFillsTheDiskStopsRecording() {
        List<StoragePlan.Clip> clips = minutes(4, 500 * MB);
        Set<String> locked = new HashSet<>();
        for (StoragePlan.Clip clip : clips.subList(0, 4)) {
            locked.add(clip.name);
        }
        // 上限很宽，盘只剩 0：余量 2 GB 要腾，没锁的旧录像只有一组 1 GB
        StoragePlan.Decision d = StoragePlan.decide(clips, locked, 100 * GB, 0, GB);
        assertEquals(StoragePlan.Verdict.FULL, d.verdict);
        assertTrue(d.lockedFull);
    }

    /** 没锁任何东西时和以前一模一样。 */
    @Test
    public void nothingLockedBehavesAsBefore() {
        List<StoragePlan.Clip> clips = minutes(10, 500 * MB);
        StoragePlan.Decision before = StoragePlan.decide(clips, 8 * GB, 100 * GB, GB);
        StoragePlan.Decision now = StoragePlan.decide(clips, new HashSet<String>(), 8 * GB, 100 * GB, GB);
        assertEquals(before.verdict, now.verdict);
        assertEquals(before.toDelete, now.toDelete);
    }
}
