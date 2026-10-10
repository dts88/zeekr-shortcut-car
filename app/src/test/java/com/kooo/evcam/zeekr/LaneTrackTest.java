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

    @Test
    public void everyClipBelongsToExactlyOneRecording() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                // 比第一条还早：归第一条
                new RecordingTimeline.Source("older.mp4", T0 - 10 * MIN, MIN, 1),
                // 比环视早 2 秒开录：仍然是这一次录的
                new RecordingTimeline.Source("early.mp4", T0 - 2 * SEC, MIN, 1),
                new RecordingTimeline.Source("inside.mp4", T0 + MIN, MIN, 1),
                // 环视停着、座舱照录时开头的：归前一条（以前不归任何一条）
                new RecordingTimeline.Source("gap.mp4", T0 + 5 * MIN, MIN, 1),
                // 比第二条的环视早 3 秒开录：归第二条
                new RecordingTimeline.Source("early2.mp4", T0 + 10 * MIN - 3 * SEC, MIN, 1),
                // 最后一条结束之后才开头的：归最后一条
                new RecordingTimeline.Source("later.mp4", T0 + 20 * MIN, MIN, 1)));
        LaneTrack first = lane.belongingTo(sessions, 0);
        LaneTrack second = lane.belongingTo(sessions, 1);
        assertEquals(Arrays.asList("older.mp4", "early.mp4", "inside.mp4", "gap.mp4"),
                pathsOf(first));
        assertEquals(Arrays.asList("early2.mp4", "later.mp4"), pathsOf(second));
        assertEquals("不重不漏", lane.size(), first.size() + second.size());
        assertEquals(4L, first.totalBytes());
    }

    /** 环视停着时开头、环视重新开录后还在录的座舱文件：画面上在后一条，删除时归前一条。 */
    @Test
    public void aClipAcrossTheSurroundRestartShowsInTheLaterButBelongsToTheEarlier() {
        List<RecordingTimeline.Session> sessions = twoRecordings();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("across.mp4", T0 + 10 * MIN - 40 * SEC, MIN, 1)));
        assertTrue(lane.shownIn(sessions.get(0)).isEmpty());
        assertEquals(1, lane.shownIn(sessions.get(1)).size());
        assertEquals(1, lane.belongingTo(sessions, 0).size());
        assertTrue(lane.belongingTo(sessions, 1).isEmpty());
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
