package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.CodecVideoRecorder;
import com.kooo.evcam.zeekr.RecordingTimeline;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 闪远光自动锁定锁哪些文件：时间窗 [t − 10 秒, t + 10 秒] 碰到的每一路录像文件。
 *
 * <p>锁少了，出事的那一段会被自动清理删掉，而且没人会发现；所以项目所有者的例子、各路分段长度不一样、
 * 窗口卡在分段边界上、窗口在第一个文件之前、t 之后才开始的那一段、文件名比真正切换早（开录慢了、
 * 几路共用一个时间戳），都在这里钉住。一个文件的结束按 {@link LockWindow#SLACK_MS}（11 秒）放宽。</p>
 */
public class LockWindowTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60_000L;

    /** 录像从这一刻开始。本地时间中午：避开夏令时切换的那一小时，换哪个时区跑都一样。 */
    private static final long T0 = RecordingTimeline.parseStartEpochMs("20261004_120000_surround.mp4");

    private static final String SURROUND = "surround";
    private static final String CABIN_FRONT = "cabinfront";
    private static final String CABIN_REAR = "cabinrear";

    /** 录像开始后 offset 毫秒开始的那个文件。 */
    private static String clip(long offsetMs, String slot) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(T0 + offsetMs))
                + "_" + slot + ".mp4";
    }

    /** 这一路从录像开始、每 segmentMs 一个文件，共 count 个。 */
    private static List<String> clips(String slot, long segmentMs, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(clip(i * segmentMs, slot));
        }
        return names;
    }

    private static Map<String, Long> lengths(Object... slotAndMs) {
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < slotAndMs.length; i += 2) {
            map.put((String) slotAndMs[i], (Long) slotAndMs[i + 1]);
        }
        return map;
    }

    private static Map<String, Long> allOneMinute() {
        return lengths(SURROUND, MIN, CABIN_FRONT, MIN, CABIN_REAR, MIN);
    }

    /** 录像开始后 offset 毫秒闪了一下，盘上是这些文件时锁哪些。 */
    private static Set<String> lockedFor(long flashOffsetMs, List<String> names, Map<String, Long> lengths) {
        return LockWindow.around(T0 + flashOffsetMs).overlapping(names, lengths, MIN);
    }

    private static Set<String> setOf(String... names) {
        return new TreeSet<>(Arrays.asList(names));
    }

    private static List<String> threeCameras(int segments) {
        List<String> names = new ArrayList<>();
        names.addAll(clips(SURROUND, MIN, segments));
        names.addAll(clips(CABIN_FRONT, MIN, segments));
        names.addAll(clips(CABIN_REAR, MIN, segments));
        return names;
    }

    // ================================================================= 项目所有者的例子

    @Test
    public void ownersExampleLocksTheMinuteBeforeAndTheMinuteAfterOnEveryCamera() {
        // 1 分钟一段，录到 1:05 闪一下：0–1 分、1–2 分那两段，三路都锁；2 分开始的那段不碰
        Set<String> locked = lockedFor(MIN + 5 * SEC, threeCameras(3), allOneMinute());
        assertEquals(setOf(
                clip(0, SURROUND), clip(MIN, SURROUND),
                clip(0, CABIN_FRONT), clip(MIN, CABIN_FRONT),
                clip(0, CABIN_REAR), clip(MIN, CABIN_REAR)), locked);
    }

    @Test
    public void theWindowIsTenSecondsEachSide() {
        LockWindow window = LockWindow.around(T0 + MIN);
        assertEquals(T0 + MIN - 10 * SEC, window.startMs);
        assertEquals(T0 + MIN + 10 * SEC, window.endMs);
    }

    // ================================================================= t 之后才开始的那一段

    @Test
    public void aSegmentThatStartsAfterTheFlashIsLockedOnceItExists() {
        // 0:55 闪一下，窗口 [0:45, 1:05]：那一刻 1 分开始的文件还没有
        long flash = 55 * SEC;
        Set<String> firstPass = lockedFor(flash, threeCameras(1), allOneMinute());
        assertEquals(setOf(clip(0, SURROUND), clip(0, CABIN_FRONT), clip(0, CABIN_REAR)), firstPass);

        // t + 10 秒再过一会儿（第二遍）：1 分开始的那段已经建好了，也锁上；前一段照样在
        Set<String> secondPass = lockedFor(flash, threeCameras(2), allOneMinute());
        assertEquals(setOf(
                clip(0, SURROUND), clip(MIN, SURROUND),
                clip(0, CABIN_FRONT), clip(MIN, CABIN_FRONT),
                clip(0, CABIN_REAR), clip(MIN, CABIN_REAR)), secondPass);
    }

    /**
     * 第二遍什么时候锁：窗口结束之后，晚切的那一路换文件（最多晚 11 秒）、写入线程排着队再卡一次才把文件建好
     * （最多再晚 6 秒），再留 1 秒。少等了，t 之后开始的那一段第二遍时还不在盘上，就漏了。
     */
    @Test
    public void theSecondPassWaitsUntilTheLatestFileIsOnDisk() {
        assertEquals(11 * SEC, LockWindow.SLACK_MS);
        assertEquals(6 * SEC, CodecVideoRecorder.MAX_FILE_LAG_MS);
        assertEquals(LockWindow.SLACK_MS + CodecVideoRecorder.MAX_FILE_LAG_MS + SEC, LockWindow.SECOND_PASS_DELAY_MS);
        assertEquals(18 * SEC, LockWindow.SECOND_PASS_DELAY_MS);
    }

    // ================================================================= 各路分段长度不一样

    @Test
    public void camerasWithDifferentSegmentLengthsAreJudgedOnTheirOwnFiles() {
        // 环视 1 分钟一段，前座舱 3 分钟一段
        List<String> names = new ArrayList<>();
        names.addAll(clips(SURROUND, MIN, 5));
        names.addAll(clips(CABIN_FRONT, 3 * MIN, 2));
        Map<String, Long> lengths = lengths(SURROUND, MIN, CABIN_FRONT, 3 * MIN);

        // 2:30 闪：环视只锁 2 分那段；前座舱 0 分那段要到 3 分才结束，锁上
        assertEquals(setOf(clip(2 * MIN, SURROUND), clip(0, CABIN_FRONT)),
                lockedFor(2 * MIN + 30 * SEC, names, lengths));

        // 3:25 闪，窗口 [3:15, 3:35]：两路都只锁 3 分开始的那段（前一段 3:00 切走，放宽到 3:11）
        assertEquals(setOf(clip(3 * MIN, SURROUND), clip(3 * MIN, CABIN_FRONT)),
                lockedFor(3 * MIN + 25 * SEC, names, lengths));
    }

    @Test
    public void theLongSegmentStillBeingWrittenIsLocked() {
        // 前座舱 3 分钟一段，此刻还在写第一段（没有下一个文件）：按它自己的分段长度算，不按环视的
        List<String> names = new ArrayList<>();
        names.addAll(clips(SURROUND, MIN, 3));
        names.add(clip(0, CABIN_FRONT));
        Set<String> locked = lockedFor(2 * MIN + 30 * SEC, names, lengths(SURROUND, MIN, CABIN_FRONT, 3 * MIN));
        assertTrue(locked.contains(clip(0, CABIN_FRONT)));
        assertEquals(setOf(clip(2 * MIN, SURROUND), clip(0, CABIN_FRONT)), locked);
    }

    @Test
    public void lengthsKeyedByTheInternalCameraKeyCountForThatSlot() {
        // 调用方用的是接线的 key（front = 环视）
        List<String> names = Collections.singletonList(clip(0, SURROUND));
        Set<String> locked = LockWindow.around(T0 + 2 * MIN + 30 * SEC)
                .overlapping(names, lengths("front", 3 * MIN), MIN);
        assertEquals(setOf(clip(0, SURROUND)), locked);
    }

    @Test
    public void aCameraMissingFromTheTableUsesTheFallbackLength() {
        List<String> names = Collections.singletonList(clip(0, "right"));
        LockWindow window = LockWindow.around(T0 + 4 * MIN);
        assertEquals(setOf(clip(0, "right")), window.overlapping(names, allOneMinute(), 5 * MIN));
        assertTrue(window.overlapping(names, allOneMinute(), MIN).isEmpty());
    }

    // ================================================================= 分段边界

    @Test
    public void theEarlierSegmentStaysLockedUntilTheWindowStartsPastItsEndPlusTheSlack() {
        // 0 分那段 1:00 切走；同一个名字底下晚切的那一路最多晚 11 秒，所以算到 1:11
        List<String> names = clips(SURROUND, MIN, 4);
        // 1:13 闪，窗口 [1:03, 1:23]：0 分那段锁上
        assertEquals(setOf(clip(0, SURROUND), clip(MIN, SURROUND)),
                lockedFor(MIN + 13 * SEC, names, allOneMinute()));
        // 1:21 闪，窗口从 1:11 开始：正好碰到，两头都算
        assertEquals(setOf(clip(0, SURROUND), clip(MIN, SURROUND)),
                lockedFor(MIN + 21 * SEC, names, allOneMinute()));
        // 1:22 闪，窗口从 1:12 开始：0 分那段才不锁
        assertEquals(setOf(clip(MIN, SURROUND)),
                lockedFor(MIN + 22 * SEC, names, allOneMinute()));
    }

    @Test
    public void aWindowEndingRightAtTheSegmentSwitchLocksTheNextSegment() {
        // 1:50 闪，窗口 [1:40, 2:00]：2 分开始的那段正好在窗口结束那一刻开始，两头都算
        List<String> names = clips(SURROUND, MIN, 4);
        assertEquals(setOf(clip(MIN, SURROUND), clip(2 * MIN, SURROUND)),
                lockedFor(MIN + 50 * SEC, names, allOneMinute()));
        // 1:49 闪，窗口 [1:39, 1:59]：2 分那段还没开始
        assertEquals(setOf(clip(MIN, SURROUND)),
                lockedFor(MIN + 49 * SEC, names, allOneMinute()));
    }

    // ================================================================= 文件名比真正切换早

    @Test
    public void aLateFirstWriteEndsTheSegmentWhereTheNextFileStarts() {
        // 第一段的名字是开录那一刻，真正写进第一帧晚了 8 秒；分段计时从第一帧算起，1:08 才切到下一段，
        // 下一个文件名就是 1:08。0 分那段的画面到 1:08（晚切的那一路再晚 11 秒以内）
        List<String> names = Arrays.asList(clip(0, SURROUND), clip(MIN + 8 * SEC, SURROUND));
        // 1:13 闪，窗口 [1:03, 1:23]：只按「名字 + 1 分钟」算的话 0 分那段这里就漏了
        assertEquals(setOf(clip(0, SURROUND), clip(MIN + 8 * SEC, SURROUND)),
                lockedFor(MIN + 13 * SEC, names, allOneMinute()));
        // 1:29 闪，窗口从 1:19 开始：1:08 + 11 秒，正好碰到
        assertEquals(setOf(clip(0, SURROUND), clip(MIN + 8 * SEC, SURROUND)),
                lockedFor(MIN + 29 * SEC, names, allOneMinute()));
        // 1:30 闪：0 分那段才不锁
        assertEquals(setOf(clip(MIN + 8 * SEC, SURROUND)),
                lockedFor(MIN + 30 * SEC, names, allOneMinute()));
    }

    @Test
    public void aCameraSwitchingLateUnderASharedNameKeepsItsEarlierSegment() {
        // 几路分段切换时共用一个时间戳：1:00 这个名字是先切的那一路取的，后座舱 10 秒内才切过去，名字照样是 1:00。
        // 1:20 闪，窗口 [1:10, 1:30]：后座舱 0 分那段的画面可能一直到 1:10 —— 三路 0 分那段都锁上
        Set<String> locked = lockedFor(MIN + 20 * SEC, threeCameras(3), allOneMinute());
        assertTrue(locked.contains(clip(0, CABIN_REAR)));
        assertEquals(setOf(
                clip(0, SURROUND), clip(MIN, SURROUND),
                clip(0, CABIN_FRONT), clip(MIN, CABIN_FRONT),
                clip(0, CABIN_REAR), clip(MIN, CABIN_REAR)), locked);
    }

    @Test
    public void aRecordingResumedLongAfterwardsDoesNotStretchTheEarlierFile() {
        // 0:00 开录，一分钟左右停了，1:40 才又录：中间没有画面，0 分那段按标称时长算（到 1:00，放宽到 1:11）
        List<String> names = Arrays.asList(clip(0, SURROUND), clip(MIN + 40 * SEC, SURROUND));
        // 1:35 闪，窗口 [1:25, 1:45]
        assertEquals(setOf(clip(MIN + 40 * SEC, SURROUND)),
                lockedFor(MIN + 35 * SEC, names, allOneMinute()));
    }

    // ================================================================= 窗口在第一个文件之前

    @Test
    public void aWindowBeforeTheFirstFileLocksNothing() {
        List<String> names = clips(SURROUND, MIN, 2);
        assertTrue(lockedFor(-30 * SEC, names, allOneMinute()).isEmpty());
        assertTrue(LockWindow.around(T0).overlapping(Collections.<String>emptyList(), allOneMinute(), MIN)
                .isEmpty());
    }

    @Test
    public void aRecordingThatStartsInsideTheWindowIsLocked() {
        // 开录前 5 秒闪的（第二遍时录像已经接上了）：窗口 [-0:15, 0:05] 碰到第一个文件
        List<String> names = clips(SURROUND, MIN, 2);
        assertEquals(setOf(clip(0, SURROUND)), lockedFor(-5 * SEC, names, allOneMinute()));
    }

    @Test
    public void anOlderRecordingThatEndedLongAgoIsNotLocked() {
        // 十分钟前那次录像的最后一个文件：按分段长度早就结束了
        List<String> names = new ArrayList<>(clips(SURROUND, MIN, 2));
        names.add(clip(-10 * MIN, SURROUND));
        assertEquals(setOf(clip(0, SURROUND)), lockedFor(30 * SEC, names, allOneMinute()));
    }

    @Test
    public void aRestartEndsTheEarlierFileWhereTheNextOneStarts() {
        // 0:00 开录，0:20 停了又接上：0:00 那个文件只到 0:20（放宽到 0:31），不是一整分钟
        List<String> names = Arrays.asList(clip(0, SURROUND), clip(20 * SEC, SURROUND));
        assertEquals(setOf(clip(20 * SEC, SURROUND)), lockedFor(45 * SEC, names, allOneMinute()));
        assertEquals(setOf(clip(0, SURROUND), clip(20 * SEC, SURROUND)),
                lockedFor(25 * SEC, names, allOneMinute()));
    }

    // ================================================================= 文件名

    @Test
    public void onlyTheAppsOwnRecordingsCountAndDuplicatesAreHarmless() {
        List<String> names = new ArrayList<>(clips(SURROUND, MIN, 2));
        names.add(clip(0, SURROUND));  // 中转写入：内部缓存和 U 盘上各一份
        names.add("holiday.mp4");
        names.add("locked.txt");
        names.add(clip(0, SURROUND).replace(".mp4", ".jpg"));
        names.add(null);
        assertEquals(setOf(clip(0, SURROUND), clip(MIN, SURROUND)),
                lockedFor(MIN + 5 * SEC, names, allOneMinute()));
    }

    @Test
    public void legacyFrontFilesAreTheSameCameraAsSurround() {
        // 改名之前的 _front 和之后的 _surround 是同一路：0 分那个旧文件到 1 分的新文件就结束了
        List<String> names = Arrays.asList(clip(0, "front"), clip(MIN, SURROUND));
        assertEquals(setOf(clip(MIN, SURROUND)), lockedFor(MIN + 30 * SEC, names, allOneMinute()));
        assertEquals(setOf(clip(0, "front"), clip(MIN, SURROUND)),
                lockedFor(MIN + 5 * SEC, names, allOneMinute()));
    }
}
