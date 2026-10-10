package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 挂靠规则：环视之外的座舱文件挂在哪一段环视上（{@link LaneTrack#attachedTo}，执行大纲阶段 P，2026-10-11）。
 *
 * <p>回放进度条跟着环视（项目所有者 2026-10-10），座舱在环视之外录的那几段不在任何一刻的画面上。
 * 「锁定此刻 / 解锁」除了画面上正在放的文件，还要带上挂在此刻环视那一段上的这些；细条把它们画在那一段的一端，
 * 列表的锁定标记和删除也是同一批。挂错了，车上看到的是「锁了却删得掉」「列表标着锁定、细条上找不到」——
 * 看得出不对，看不出是哪一条判断错了，所以在这里钉死。</p>
 */
public class LaneTrackAttachTest {

    private static final long T0 = 1_790_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long SEC = 1_000L;

    /** 环视：一条录制三段各 1 分钟，第二段和第三段之间空 4 秒（还算同一条）。 */
    private static RecordingTimeline.Session session() {
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("s1.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("s2.mp4", T0 + MIN, MIN, 10),
                new RecordingTimeline.Source("s3.mp4", T0 + 2 * MIN + 4 * SEC, MIN, 10)));
        assertEquals(1, sessions.size());
        return sessions.get(0);
    }

    /** 后座舱归这一条的文件（先由 belongingTo 挑出来的那一批）。 */
    private static LaneTrack owned() {
        return LaneTrack.of(Arrays.asList(
                // 环视加入之前录的：正好在环视开头那一刻录完
                new RecordingTimeline.Source("before.mp4", T0 - MIN, MIN, 1),
                // 画面上放的
                new RecordingTimeline.Source("inside.mp4", T0 + 10 * SEC, MIN, 1),
                // 落在第二段和第三段之间那 4 秒空里：离两边都是 1 秒
                new RecordingTimeline.Source("gapMiddle.mp4", T0 + 2 * MIN + SEC, 2 * SEC, 1),
                // 也在那 4 秒空里，离第三段近
                new RecordingTimeline.Source("gapLate.mp4", T0 + 2 * MIN + 2500L, SEC, 1),
                // 环视停了之后录的
                new RecordingTimeline.Source("after.mp4", T0 + 3 * MIN + 24 * SEC, MIN, 1)));
    }

    private static LaneTrack.Clip clip(String path) {
        for (LaneTrack.Clip clip : owned().clips()) {
            if (clip.path.equals(path)) {
                return clip;
            }
        }
        throw new AssertionError(path);
    }

    private static List<String> pathsOf(LaneTrack track) {
        List<String> paths = new ArrayList<>();
        for (LaneTrack.Clip clip : track.clips()) {
            paths.add(clip.path);
        }
        return paths;
    }

    @Test
    public void aClipOnScreenIsNotAttached() {
        assertEquals(-1, LaneTrack.attachedSegment(clip("inside.mp4"), session()));
    }

    @Test
    public void anOutsideClipAttachesToTheNearestSegment() {
        RecordingTimeline.Session session = session();
        assertEquals("环视加入之前的：第一段", 0, LaneTrack.attachedSegment(clip("before.mp4"), session));
        assertEquals("环视停了之后的：最后一段", 2, LaneTrack.attachedSegment(clip("after.mp4"), session));
        assertEquals("空里离第三段近", 2, LaneTrack.attachedSegment(clip("gapLate.mp4"), session));
    }

    @Test
    public void equallyNearAttachesToTheEarlierSegment() {
        assertEquals(1, LaneTrack.attachedSegment(clip("gapMiddle.mp4"), session()));
    }

    /** 「锁定此刻」放到第几段，就带上挂在那一段上的；画面上的那些不在这一批里（它们按画面算）。 */
    @Test
    public void attachedToPicksTheOutsideClipsOfThatSegment() {
        RecordingTimeline.Session session = session();
        LaneTrack owned = owned();
        assertEquals(Arrays.asList("before.mp4"), pathsOf(owned.attachedTo(session, 0)));
        assertEquals(Arrays.asList("gapMiddle.mp4"), pathsOf(owned.attachedTo(session, 1)));
        assertEquals(Arrays.asList("gapLate.mp4", "after.mp4"), pathsOf(owned.attachedTo(session, 2)));
        assertTrue("此刻没有环视那一段：什么都不挂", owned.attachedTo(session, -1).isEmpty());
    }

    /**
     * 细条和锁定同一个规则：环视之外的文件换算到进度条上，正好落在它挂靠的那一段的一端
     * （时间轴把段与段之间的空挤掉了，空两边的那两段在进度条上是同一点）。
     */
    @Test
    public void theLockStripDrawsAnOutsideClipAtTheEdgeOfItsSegment() {
        RecordingTimeline.Session session = session();
        assertPoint(session, clip("before.mp4"), session.segments.get(0).timelineOffsetMs);
        assertPoint(session, clip("after.mp4"), session.segments.get(2).timelineEndMs());
        assertPoint(session, clip("gapMiddle.mp4"), session.segments.get(1).timelineEndMs());
        assertPoint(session, clip("gapLate.mp4"), session.segments.get(2).timelineOffsetMs);
    }

    private static void assertPoint(RecordingTimeline.Session session, LaneTrack.Clip clip, long at) {
        assertEquals(clip.path, at, session.positionAt(clip.startEpochMs));
        assertEquals(clip.path, at, session.positionAt(clip.endEpochMs()));
    }

    /**
     * 从头到尾走一遍：开录时原厂 360 正拿着环视，座舱先录了一段，环视才加入。
     * 那一段归这一条录制（离环视一个分段以内），不在画面上，挂在第一段环视上 ——
     * 放到第一段按「锁定此刻」会带上它；放到第二段不会。
     */
    @Test
    public void cabinFootageBeforeTheSurroundJoinedLocksWithTheFirstSegment() {
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("s1.mp4", T0, MIN, 10),
                new RecordingTimeline.Source("s2.mp4", T0 + MIN, MIN, 10)));
        assertEquals(1, sessions.size());
        RecordingTimeline.Session session = sessions.get(0);
        LaneTrack rear = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("early.mp4", T0 - MIN - 20 * SEC, MIN, 1),
                new RecordingTimeline.Source("joined.mp4", T0 - 20 * SEC, MIN, 1),
                new RecordingTimeline.Source("next.mp4", T0 + 40 * SEC, MIN, 1)));
        LaneTrack owned = rear.belongingTo(sessions, 0, MIN);
        assertEquals(3, owned.size());
        assertEquals("环视加入之前那一段不在画面上", Arrays.asList("joined.mp4", "next.mp4"),
                pathsOf(rear.shownIn(session)));
        assertEquals(Arrays.asList("early.mp4"), pathsOf(owned.attachedTo(session, 0)));
        assertTrue(owned.attachedTo(session, 1).isEmpty());
    }
}
