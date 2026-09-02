package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * {@link RecordingTimeline} 的单元测试。
 *
 * <p>时间轴换算错了，表现是「拖到某个位置播出来的画面不对」——
 * 这种错误在车上靠肉眼几乎发现不了，所以必须在这里锁死。</p>
 */
public class RecordingTimelineTest {

    private static long epoch(int y, int mo, int d, int h, int mi, int s) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(y, mo - 1, d, h, mi, s);
        return cal.getTimeInMillis();
    }

    private static final long MIN = 60_000L;

    // ---------- 文件名解析 ----------

    @Test
    public void parsesTimestampFromRecordingFileName() {
        long parsed = RecordingTimeline.parseStartEpochMs("20260828_221530_front.mp4");
        assertEquals(epoch(2026, 8, 28, 22, 15, 30), parsed);
    }

    @Test
    public void rejectsNamesThatAreNotRecordings() {
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs(null));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("short.mp4"));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("2026082822153_x.mp4"));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("20260828-221530_f.mp4"));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("2026ab28_221530_f.mp4"));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("20261328_221530_f.mp4"));
        assertEquals(-1L, RecordingTimeline.parseStartEpochMs("20260828_251530_f.mp4"));
    }

    // ---------- 摄像头槽位（连续回放只看环视那一路） ----------

    @Test
    public void parsesCameraSlotFromFileName() {
        assertEquals("front", RecordingTimeline.parseCameraSlot("20260829_100000_front.mp4"));
        assertEquals("back", RecordingTimeline.parseCameraSlot("20260829_100000_back.mp4"));
        assertEquals("left", RecordingTimeline.parseCameraSlot("20260829_100000_left.mp4"));
    }

    @Test
    public void returnsNullForNamesWithoutASlot() {
        assertNull(RecordingTimeline.parseCameraSlot(null));
        assertNull(RecordingTimeline.parseCameraSlot("noextension"));
        assertNull(RecordingTimeline.parseCameraSlot("20260829_100000_.mp4"));
    }

    @Test
    public void keepsOnlyTheRequestedSlot() {
        // 三路录制时同一分段写出三个文件，时间戳前缀完全相同 ——
        // 不过滤的话它们会被当成时间上前后相接的三段接到同一条时间轴上
        assertTrue(RecordingTimeline.isSlot("20260829_100000_front.mp4", "front"));
        assertFalse(RecordingTimeline.isSlot("20260829_100000_back.mp4", "front"));
        assertFalse(RecordingTimeline.isSlot("20260829_100000_left.mp4", "front"));
    }

    @Test
    public void slotMatchIsCaseInsensitive() {
        assertTrue(RecordingTimeline.isSlot("20260829_100000_FRONT.mp4", "front"));
    }

    // ---------- 连续分段合并 ----------

    @Test
    public void mergesBackToBackSegmentsIntoOneTimeline() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN),
                new RecordingTimeline.Source("c.mp4", t0 + 6 * MIN, 3 * MIN));

        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);

        assertEquals("首尾相接应合成一条时间轴", 1, sessions.size());
        RecordingTimeline.Session s = sessions.get(0);
        assertEquals(3, s.segmentCount());
        assertEquals(9 * MIN, s.totalDurationMs);
        assertEquals(0L, s.segments.get(0).timelineOffsetMs);
        assertEquals(3 * MIN, s.segments.get(1).timelineOffsetMs);
        assertEquals(6 * MIN, s.segments.get(2).timelineOffsetMs);
    }

    @Test
    public void splitsWhenRecordingWasStoppedAndRestarted() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN),
                // 中间停了 20 分钟
                new RecordingTimeline.Source("c.mp4", t0 + 26 * MIN, 3 * MIN));

        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);

        assertEquals("断档处应另起一条时间轴", 2, sessions.size());
        assertEquals(2, sessions.get(0).segmentCount());
        assertEquals(6 * MIN, sessions.get(0).totalDurationMs);
        assertEquals(1, sessions.get(1).segmentCount());
        assertEquals(3 * MIN, sessions.get(1).totalDurationMs);
        assertEquals("新会话的偏移应从 0 重新开始",
                0L, sessions.get(1).segments.get(0).timelineOffsetMs);
    }

    @Test
    public void toleratesTheSmallGapBetweenSegments() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        // 分段切换本身有约 1 秒的写文件间隙，不应被当成断档
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN + 1000L, 3 * MIN));

        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);
        assertEquals(1, sessions.size());
        assertEquals(2, sessions.get(0).segmentCount());
    }

    @Test
    public void sortsUnorderedInput() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("c.mp4", t0 + 6 * MIN, 3 * MIN),
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN));

        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);
        assertEquals("a.mp4", s.segments.get(0).path);
        assertEquals("b.mp4", s.segments.get(1).path);
        assertEquals("c.mp4", s.segments.get(2).path);
    }

    // ---------- 定位：这是拖进度条时真正用到的换算 ----------

    @Test
    public void locatesPositionsAcrossSegmentBoundaries() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN),
                new RecordingTimeline.Source("c.mp4", t0 + 6 * MIN, 3 * MIN));
        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);

        RecordingTimeline.Locator at0 = s.locate(0);
        assertEquals("a.mp4", at0.segment.path);
        assertEquals(0L, at0.offsetInSegmentMs);

        // 第 4 分钟落在第二段的第 1 分钟
        RecordingTimeline.Locator at4 = s.locate(4 * MIN);
        assertEquals("b.mp4", at4.segment.path);
        assertEquals(1 * MIN, at4.offsetInSegmentMs);

        // 第 8 分钟落在第三段的第 2 分钟
        RecordingTimeline.Locator at8 = s.locate(8 * MIN);
        assertEquals("c.mp4", at8.segment.path);
        assertEquals(2 * MIN, at8.offsetInSegmentMs);
    }

    @Test
    public void boundaryBelongsToTheFollowingSegment() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN));
        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);

        // 恰好 3 分钟是第一段的结束，应算作第二段的开头
        RecordingTimeline.Locator at = s.locate(3 * MIN);
        assertEquals("b.mp4", at.segment.path);
        assertEquals(0L, at.offsetInSegmentMs);

        // 差 1 毫秒仍属于第一段
        RecordingTimeline.Locator just = s.locate(3 * MIN - 1);
        assertEquals("a.mp4", just.segment.path);
    }

    @Test
    public void clampsPositionsOutsideTheTimeline() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN));
        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);

        assertEquals("a.mp4", s.locate(-5000).segment.path);
        assertEquals(0L, s.locate(-5000).offsetInSegmentMs);

        RecordingTimeline.Locator past = s.locate(99 * MIN);
        assertNotNull(past);
        assertEquals("a.mp4", past.segment.path);
        assertTrue(past.offsetInSegmentMs < 3 * MIN);
    }

    // ---------- 文件大小（连续回放左栏要显示） ----------

    @Test
    public void sumsFileSizesPerSession() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        long mb = 1024L * 1024L;
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN, 100 * mb),
                new RecordingTimeline.Source("b.mp4", t0 + 3 * MIN, 3 * MIN, 120 * mb),
                // 断档：另起一条时间轴，大小不能算到上一条里
                new RecordingTimeline.Source("c.mp4", t0 + 30 * MIN, 3 * MIN, 90 * mb));

        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);

        assertEquals(2, sessions.size());
        assertEquals(220 * mb, sessions.get(0).totalSizeBytes);
        assertEquals(90 * mb, sessions.get(1).totalSizeBytes);
    }

    @Test
    public void sizeDefaultsToZeroWhenNotSupplied() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        // 三参构造保留给不关心大小的调用方，不该逼它们编一个数字出来
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 3 * MIN));

        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);
        assertEquals(0L, s.totalSizeBytes);
        // 破折号而不是「大小未知」：这个类没有 Context 取不到资源，
        // 而符号在中英文界面下都读得通
        assertEquals("—", TimelineFormat.size(s.totalSizeBytes));
    }

    @Test
    public void formatsSizeAtEachScale() {
        assertEquals("—", TimelineFormat.size(0));
        assertEquals("512 KB", TimelineFormat.size(512L * 1024L));
        assertEquals("1.5 MB", TimelineFormat.size(3L * 512L * 1024L));
        assertEquals("2.00 GB", TimelineFormat.size(2L * 1024 * 1024 * 1024));
    }

    @Test
    public void formatsDurationWithHoursOnlyWhenNeeded() {
        assertEquals("00:45", TimelineFormat.duration(45_000L));
        assertEquals("27:04", TimelineFormat.duration(27 * MIN + 4_000L));
        assertEquals("1:05:00", TimelineFormat.duration(65 * MIN));
    }

    // ---------- 边界输入 ----------

    @Test
    public void handlesEmptyAndUnusableInput() {
        assertTrue(RecordingTimeline.build(null).isEmpty());
        assertTrue(RecordingTimeline.build(new ArrayList<>()).isEmpty());

        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        // 时长读不出来的文件应被跳过，而不是把时间轴算歪
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("bad.mp4", t0, 0),
                new RecordingTimeline.Source("good.mp4", t0 + MIN, 3 * MIN));
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(sources);
        assertEquals(1, sessions.size());
        assertEquals(1, sessions.get(0).segmentCount());
        assertEquals("good.mp4", sessions.get(0).segments.get(0).path);
    }

    @Test
    public void offsetsRemainContiguousRegardlessOfSegmentLength() {
        long t0 = epoch(2026, 8, 28, 10, 0, 0);
        // 最后一段常常不足整段（手动停止录制）
        List<RecordingTimeline.Source> sources = Arrays.asList(
                new RecordingTimeline.Source("a.mp4", t0, 5 * MIN),
                new RecordingTimeline.Source("b.mp4", t0 + 5 * MIN, 5 * MIN),
                new RecordingTimeline.Source("c.mp4", t0 + 10 * MIN, 47_000L));
        RecordingTimeline.Session s = RecordingTimeline.build(sources).get(0);

        assertEquals(10 * MIN + 47_000L, s.totalDurationMs);
        long expected = 0L;
        for (RecordingTimeline.Segment seg : s.segments) {
            assertEquals("每段的起点必须紧接上一段的终点", expected, seg.timelineOffsetMs);
            expected = seg.timelineEndMs();
        }
    }
}
