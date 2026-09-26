package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

    @Test
    public void aClipBelongsToTheRecordingItStartsIn() {
        RecordingTimeline.Session session = surround();
        LaneTrack lane = LaneTrack.of(Arrays.asList(
                // 比环视早 2 秒开录：仍然是这一次录的
                new RecordingTimeline.Source("early.mp4", T0 - 2 * SEC, MIN, 1),
                // 上一次录制留下的
                new RecordingTimeline.Source("old.mp4", T0 - 10 * MIN, MIN, 1),
                // 环视停了之后才开头的：下一次录制
                new RecordingTimeline.Source("next.mp4", T0 + 3 * MIN + 2 * SEC, MIN, 1)));
        LaneTrack mine = lane.within(session);
        assertEquals(1, mine.size());
        assertEquals("early.mp4", mine.clip(0).path);
        assertEquals(1L, mine.totalBytes());
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
