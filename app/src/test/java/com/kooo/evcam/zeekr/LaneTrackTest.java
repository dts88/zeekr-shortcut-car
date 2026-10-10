package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link LaneTrack} 与时间轴 ↔ 真实时刻的换算。
 *
 * <p>几路画面对不齐，在车上只会看成「座舱慢了两秒」，很难看出是哪一步算错了 ——
 * 所以换算在这里锁死。</p>
 */
public class LaneTrackTest {

    private static final long T0 = 1_790_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long SEC = 1_000L;

    /** 环视：三段各 1 分钟，第二段和第三段之间有 2 秒的写文件空隙。 */
    private static RecordingTimeline.Session surround() {
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("s1.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("s2.mp4", T0 + MIN, MIN, 10),
                new RecordingTimeline.Source("s3.mp4", T0 + 2 * MIN + 2 * SEC, MIN, 10));
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);
        assertEquals(1, sessions.size());
        return sessions.get(0);
    }

    // ---------- 时间轴 ↔ 真实时刻 ----------

    @Test
    public void positionAndEpochRoundTripInsideASegment() {
        RecordingTimeline.Session session = surround();
        long position = MIN + 30 * SEC;
        long epoch = session.epochAt(position);
        assertEquals(T0 + MIN + 30 * SEC, epoch);
        assertEquals(position, session.positionAt(epoch));
    }

    @Test
    public void epochSkipsTheGapTheTimelineSqueezedOut() {
        RecordingTimeline.Session session = surround();
        // 时间轴上第 2 分钟整是第三段的开头，真实时刻晚了 2 秒
        assertEquals(T0 + 2 * MIN + 2 * SEC, session.epochAt(2 * MIN));
    }

    @Test
    public void epochInsideAGapMapsToTheNextSegmentStart() {
        RecordingTimeline.Session session = surround();
        assertEquals(2 * MIN, session.positionAt(T0 + 2 * MIN + SEC));
    }

    @Test
    public void epochOutsideTheSessionIsClamped() {
        RecordingTimeline.Session session = surround();
        assertEquals(0L, session.positionAt(T0 - 5 * SEC));
        assertEquals(session.totalDurationMs, session.positionAt(T0 + 10 * MIN));
        assertEquals(T0 + 3 * MIN + 2 * SEC, session.endEpochMs());
    }

    // ---------- 按真实时刻找文件 ----------

    /** 座舱：分段 2 分钟，比环视晚 1 秒开录，中间断过一次。 */
    private static LaneTrack cabin() {
        return LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("c2.mp4", T0 + SEC + 2 * MIN, 30 * SEC, 5),
                new RecordingTimeline.Source("c1.mp4", T0 + SEC, 2 * MIN, 5)));
    }

    @Test
    public void findsTheClipAndOffsetForAMoment() {
        LaneTrack.Hit hit = cabin().at(T0 + MIN + SEC);
        assertNotNull(hit);
        assertEquals("c1.mp4", hit.clip.path);   // 顺序打乱了也按时刻排
        assertEquals(0, hit.index);
        assertEquals(MIN, hit.offsetMs);
    }

    @Test
    public void noClipBeforeTheLaneStartedOrAfterItStopped() {
        assertNull(cabin().at(T0));                         // 比环视晚 1 秒开录
        assertNull(cabin().at(T0 + SEC + 2 * MIN + 30 * SEC));
    }

    @Test
    public void atOrAfterJumpsOverAGapToTheNextClip() {
        LaneTrack.Hit hit = cabin().atOrAfter(T0);
        assertNotNull(hit);
        assertEquals("c1.mp4", hit.clip.path);
        assertEquals(0L, hit.offsetMs);
        assertNull(cabin().atOrAfter(T0 + 10 * MIN));
    }

    @Test
    public void surroundTrackIsTheSessionItself() {
        LaneTrack track = LaneTrack.of(surround());
        assertEquals(3, track.size());
        LaneTrack.Hit hit = track.at(T0 + 2 * MIN + 12 * SEC);
        assertNotNull(hit);
        assertEquals(2, hit.index);
        assertEquals(10 * SEC, hit.offsetMs);
    }

    // ---------- 画面上放哪些（重叠）、归哪一条（删除、分享、大小） ----------
    //
    // 2026-10-10 起一路相机被别的程序占用时只停那一路、其余照录，放开后单独接回 ——
    // 停的也可能是环视。以前两件事都按「开头落在这一条里」算：环视重新开录之前就开头的
    // 座舱文件不在画面上，环视停着时座舱录下的文件不归任何一条、回放里删不掉。

    /** 两条录制：第一条就是 {@link #surround()}，第二条 10 分钟后开录，两段各 1 分钟。 */
    private static List<RecordingTimeline.Session> twoRecordings() {
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("s1.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("s2.mp4", T0 + MIN, MIN, 10),
                new RecordingTimeline.Source("s3.mp4", T0 + 2 * MIN + 2 * SEC, MIN, 10),
                new RecordingTimeline.Source("t1.mp4", T0 + 10 * MIN, MIN, 10),
                new RecordingTimeline.Source("t2.mp4", T0 + 11 * MIN, MIN, 10));
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);
        assertEquals(2, sessions.size());
        return sessions;
    }

    private static List<String> pathsOf(LaneTrack track) {
        List<String> paths = new ArrayList<>();
        for (LaneTrack.Clip clip : track.clips()) {
            paths.add(clip.path);
        }
        return paths;
    }

    @Test
    public void aClipIsShownWhereverItOverlapsTheRecording() {
        RecordingTimeline.Session session = surround();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                // 上一次录制留下的
                new RecordingTimeline.Source("old.mp4", T0 - 10 * MIN, MIN, 1),
                // 环视停过：座舱这个文件在环视重新开录之前 40 秒就开头了，后 20 秒在这一条里
                new RecordingTimeline.Source("across.mp4", T0 - 40 * SEC, MIN, 1),
                new RecordingTimeline.Source("inside.mp4", T0 + 20 * SEC, MIN, 1),
                // 环视停了之后才开头的
                new RecordingTimeline.Source("after.mp4", T0 + 3 * MIN + 2 * SEC, MIN, 1)));
        LaneTrack shown = lane.shownIn(session);
        assertEquals(Arrays.asList("across.mp4", "inside.mp4"), pathsOf(shown));
        // 环视开录那一刻，座舱放的是 across.mp4 的第 40 秒，而不是「该路此时无录像」
        LaneTrack.Hit hit = shown.at(T0);
        assertNotNull(hit);
        assertEquals("across.mp4", hit.clip.path);
        assertEquals(40 * SEC, hit.offsetMs);
    }

    @Test
    public void aClipEndingExactlyWhenTheRecordingStartsIsNotShown() {
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("touching.mp4", T0 - MIN, MIN, 1)));
        assertTrue(lane.shownIn(surround()).isEmpty());
    }

    // 2026-10-11 起（执行大纲阶段 P）归哪一条按「离它最近」算，不重叠的最多隔一个分段时长，
    // 再远的不归任何一条。下面的分段时长都是 1 分钟（SEGMENT）。以前（v2）归「开头之前最近开录的
    // 那一条」、比第一条还早的归第一条，没有上限：环视加入之前录的座舱片段会挂到上一条、可能是前一天的录制上。

    /** 这一路的一个分段时长：不重叠的文件最多隔这么远。 */
    private static final long SEGMENT = MIN;

    @Test
    public void everyClipBelongsToAtMostOneRecording() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                // 比第一条早 9 分钟就录完了：隔得超过一个分段时长，不归任何一条（以前归第一条）
                new RecordingTimeline.Source("older.mp4", T0 - 10 * MIN, MIN, 1),
                // 比环视早 2 秒开录：和这一条重叠，仍然是这一次录的
                new RecordingTimeline.Source("early.mp4", T0 - 2 * SEC, MIN, 1),
                new RecordingTimeline.Source("inside.mp4", T0 + MIN, MIN, 1),
                // 环视停了、座舱照录的第一段：离第一条的末尾 8 秒，归第一条
                new RecordingTimeline.Source("after.mp4", T0 + 3 * MIN + 10 * SEC, MIN, 1),
                // 再往后的那段：离两条都超过一个分段时长，不归任何一条（以前归前一条）
                new RecordingTimeline.Source("gap.mp4", T0 + 5 * MIN, MIN, 1),
                // 比第二条的环视早 3 秒开录：归第二条
                new RecordingTimeline.Source("early2.mp4", T0 + 10 * MIN - 3 * SEC, MIN, 1),
                // 最后一条结束 8 分钟之后才开头的：不归任何一条（以前归最后一条）
                new RecordingTimeline.Source("later.mp4", T0 + 20 * MIN, MIN, 1)));
        LaneTrack first = lane.belongingTo(sessions, 0, SEGMENT);
        LaneTrack second = lane.belongingTo(sessions, 1, SEGMENT);
        assertEquals(Arrays.asList("early.mp4", "inside.mp4", "after.mp4"), pathsOf(first));
        assertEquals(Arrays.asList("early2.mp4"), pathsOf(second));
        assertEquals("不重：两条加起来再加三个不归的，正好是全部",
                lane.size(), first.size() + second.size() + 3);
        assertEquals(3L, first.totalBytes());
    }

    /** 一次分好的和挨条挑的是同一个结果（回放列表用前者，规则只有一条）。 */
    @Test
    public void byRecordingSplitsTheSameWayAsBelongingTo() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("early.mp4", T0 - 2 * SEC, MIN, 1),
                new RecordingTimeline.Source("after.mp4", T0 + 3 * MIN + 10 * SEC, MIN, 1),
                new RecordingTimeline.Source("gap.mp4", T0 + 5 * MIN, MIN, 1),
                new RecordingTimeline.Source("near2.mp4", T0 + 9 * MIN, MIN, 1),
                new RecordingTimeline.Source("t.mp4", T0 + 11 * MIN, MIN, 1)));
        List<LaneTrack> split = lane.byRecording(sessions, SEGMENT);
        assertEquals(sessions.size(), split.size());
        for (int i = 0; i < sessions.size(); i++) {
            assertEquals(pathsOf(lane.belongingTo(sessions, i, SEGMENT)), pathsOf(split.get(i)));
        }
        assertEquals(Arrays.asList("early.mp4", "after.mp4"), pathsOf(split.get(0)));
        assertEquals(Arrays.asList("near2.mp4", "t.mp4"), pathsOf(split.get(1)));
    }

    /** 环视停着时开头、环视重新开录后还在录的座舱文件：画面上在后一条，也归后一条（以前归前一条）。 */
    @Test
    public void aClipAcrossTheSurroundRestartBelongsToTheRecordingItShowsIn() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("across.mp4", T0 + 10 * MIN - 40 * SEC, MIN, 1)));
        assertTrue(lane.shownIn(sessions.get(0)).isEmpty());
        assertEquals(1, lane.shownIn(sessions.get(1)).size());
        assertTrue(lane.belongingTo(sessions, 0, SEGMENT).isEmpty());
        assertEquals(1, lane.belongingTo(sessions, 1, SEGMENT).size());
    }

    /** 归最近的：两条录制之间的座舱文件，离哪条近归哪条。 */
    @Test
    public void aClipBetweenTwoRecordingsBelongsToTheNearerOne() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                // 离第一条的末尾 18 秒，离第二条 5 分 40 秒
                new RecordingTimeline.Source("near1.mp4", T0 + 3 * MIN + 20 * SEC, MIN, 1),
                // 环视加入之前录的：正好在第二条开头那一刻录完，离第一条快 6 分钟。
                // 以前按「开头之前最近开录的那一条」归了第一条
                new RecordingTimeline.Source("near2.mp4", T0 + 9 * MIN, MIN, 1),
                // 离第二条的开头 30 秒
                new RecordingTimeline.Source("near2b.mp4", T0 + 8 * MIN + 30 * SEC, MIN, 1)));
        // 分段 10 分钟：三个文件两条都够得着，比的只是远近
        assertEquals(Arrays.asList("near1.mp4"), pathsOf(lane.belongingTo(sessions, 0, 10 * MIN)));
        assertEquals(Arrays.asList("near2b.mp4", "near2.mp4"),
                pathsOf(lane.belongingTo(sessions, 1, 10 * MIN)));
        // 分段 1 分钟：结果一样（near1、near2b 离近的那条都在一个分段以内，near2 正挨着第二条）
        assertEquals(Arrays.asList("near1.mp4"), pathsOf(lane.belongingTo(sessions, 0, SEGMENT)));
        assertEquals(Arrays.asList("near2b.mp4", "near2.mp4"),
                pathsOf(lane.belongingTo(sessions, 1, SEGMENT)));
    }

    /** 两条录制：环视各录 1 分钟，中间隔 3 分钟（环视被别的程序拿着）；用来比「一样近」。 */
    private static List<RecordingTimeline.Session> threeMinutesApart() {
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("b.mp4", T0 + 4 * MIN, MIN, 10)));
        assertEquals(2, sessions.size());
        return sessions;
    }

    /** 一样近归前一条；和两条都重叠的，归重叠得多的那条，一样多也归前一条。 */
    @Test
    public void equallyNearGoesToTheEarlierRecording() {
        List<RecordingTimeline.Session> sessions = threeMinutesApart();
        LaneTrack between = LaneTrack.of(Arrays.asList(
                // 离前一条的末尾、后一条的开头都正好 1 分钟
                new RecordingTimeline.Source("middle.mp4", T0 + 2 * MIN, MIN, 1)));
        assertEquals(1, between.belongingTo(sessions, 0, SEGMENT).size());
        assertTrue(between.belongingTo(sessions, 1, SEGMENT).isEmpty());

        // 环视只停了 30 秒（倒车时原厂 360 拿着它），断成两条；座舱的一段跨着两条
        List<RecordingTimeline.Session> split = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("b.mp4", T0 + MIN + 30 * SEC, MIN, 10)));
        assertEquals(2, split.size());
        LaneTrack across = LaneTrack.of(Arrays.asList(
                // 和前一条重叠 10 秒、和后一条也重叠 10 秒
                new RecordingTimeline.Source("even.mp4", T0 + 50 * SEC, 50 * SEC, 1),
                // 和前一条重叠 5 秒、和后一条重叠 25 秒
                new RecordingTimeline.Source("more2.mp4", T0 + 55 * SEC, MIN, 1)));
        assertEquals(Arrays.asList("even.mp4"), pathsOf(across.belongingTo(split, 0, SEGMENT)));
        assertEquals(Arrays.asList("more2.mp4"), pathsOf(across.belongingTo(split, 1, SEGMENT)));
    }

    /** 超过一个分段时长的不归任何一条；正好一个分段时长的还算（「最多隔一个分段时长」）。 */
    @Test
    public void aClipMoreThanOneSegmentAwayBelongsToNoRecording() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        long end = sessions.get(0).endEpochMs();   // T0 + 3 分 2 秒
        LaneTrack justBefore = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("b.mp4", T0 - 2 * MIN, MIN, 1)));
        LaneTrack tooEarly = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("b.mp4", T0 - 2 * MIN - SEC, MIN, 1)));
        LaneTrack justAfter = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", end + SEGMENT, MIN, 1)));
        LaneTrack tooLate = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", end + SEGMENT + SEC, MIN, 1)));
        assertEquals(1, justBefore.belongingTo(sessions, 0, SEGMENT).size());
        assertEquals(1, justAfter.belongingTo(sessions, 0, SEGMENT).size());
        for (int i = 0; i < sessions.size(); i++) {
            assertTrue(tooEarly.belongingTo(sessions, i, SEGMENT).isEmpty());
            assertTrue(tooLate.belongingTo(sessions, i, SEGMENT).isEmpty());
        }
        assertEquals(-1, LaneTrack.recordingOf(tooLate.clip(0), sessions, SEGMENT));
    }

    /**
     * 不跨天串到别的录制：开录时原厂 360 正拿着环视，座舱先录了 3 分钟，环视才加入。
     * 那 3 分钟里离环视一个分段时长以内的归这一次，再早的不归任何一条 —— 不挂到前一天那条上。
     */
    @Test
    public void cabinFootageBeforeTheSurroundJoinedDoesNotStrayToTheDayBefore() {
        long day = 24 * 60 * MIN;
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("yesterday.mp4", T0 - day, 30 * MIN, 10),
                new RecordingTimeline.Source("today1.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("today2.mp4", T0 + MIN, MIN, 10)));
        assertEquals(2, sessions.size());
        LaneTrack rear = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("c1.mp4", T0 - 3 * MIN, MIN, 1),
                new RecordingTimeline.Source("c2.mp4", T0 - 2 * MIN, MIN, 1),
                new RecordingTimeline.Source("c3.mp4", T0 - MIN, MIN, 1),
                new RecordingTimeline.Source("c4.mp4", T0, MIN, 1)));
        assertTrue("前一天那条一个都不收", rear.belongingTo(sessions, 0, SEGMENT).isEmpty());
        assertEquals(Arrays.asList("c2.mp4", "c3.mp4", "c4.mp4"),
                pathsOf(rear.belongingTo(sessions, 1, SEGMENT)));
    }

    /** 后座舱第 1 分 11 秒被别的程序占用、停下，第 2 分 30 秒单独接回（文件名是接回那一刻）。 */
    @Test
    public void aLaneThatLeftAndRejoinedHasNoFootageInBetween() {
        LaneTrack rear = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("cut.mp4", T0 + SEC, 70 * SEC, 1),
                new RecordingTimeline.Source("rejoined.mp4", T0 + 2 * MIN + 30 * SEC, 30 * SEC, 1)))
                .shownIn(surround());
        assertEquals(2, rear.size());
        assertNotNull(rear.at(T0 + MIN));
        assertNull("停着的那段：该路此时无录像", rear.at(T0 + 2 * MIN));
        LaneTrack.Hit hit = rear.at(T0 + 2 * MIN + 40 * SEC);
        assertNotNull(hit);
        assertEquals("rejoined.mp4", hit.clip.path);
        assertEquals(10 * SEC, hit.offsetMs);
    }

    @Test
    public void aRecordingWithoutFilesOfTheLaneShowsNoneOfIt() {
        LaneTrack rear = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("elsewhere.mp4", T0 + 20 * MIN, MIN, 1)));
        assertTrue(rear.shownIn(surround()).isEmpty());
    }

    @Test
    public void unreadableClipsAreSkipped() {
        LaneTrack track = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("broken.mp4", T0, 0L, 1),
                new RecordingTimeline.Source("ok.mp4", T0 + MIN, MIN, 1)));
        assertEquals(1, track.size());
        assertTrue(LaneTrack.EMPTY.isEmpty());
    }
}
