package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link PlaybackLead}：录像回放里谁领着时间走（2026-10-10：进度条跟着环视）。
 *
 * <p>判断错了，车上看到的是「放大座舱之后进度条跳了几分钟」「放到一半整个停了」
 * 「拖过去画面卡住不动」—— 看得出不对，看不出是哪一条判断错了，所以在这里钉死。</p>
 */
public class PlaybackLeadTest {

    private static final long T0 = 1_790_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long SEC = 1_000L;

    /** 环视：一条 10 分钟的录制，两段各 5 分钟。 */
    private static RecordingTimeline.Session session() {
        List<RecordingTimeline.Session> sessions = RecordingTimeline.build(Arrays.asList(
                new RecordingTimeline.Source("s1.mp4", T0, 5 * MIN, 10),
                new RecordingTimeline.Source("s2.mp4", T0 + 5 * MIN, 5 * MIN, 10)));
        assertEquals(1, sessions.size());
        assertEquals(T0 + 10 * MIN, sessions.get(0).endEpochMs());
        return sessions.get(0);
    }

    /**
     * 后座舱：比环视晚 1 秒开录；第 3 分钟被别的程序占用、停下，第 6 分钟单独接回（缺 3 分钟）；
     * 接回后的第一段到第 7 分钟，下一段算出来空了 3 秒（文件名只精确到秒，比画面早一点），
     * 一直录到第 9 分 30 秒，比环视早结束半分钟。
     */
    private static LaneTrack rear() {
        return LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", T0 + SEC, 3 * MIN - SEC, 1),
                new RecordingTimeline.Source("b.mp4", T0 + 6 * MIN, MIN, 1),
                new RecordingTimeline.Source("c.mp4", T0 + 7 * MIN + 3 * SEC,
                        2 * MIN + 27 * SEC, 1)));
    }

    // ---------- 放大的座舱此时能不能领 ----------

    @Test
    public void aCabinLeadsInsideItsFootage() {
        assertTrue(PlaybackLead.cabinLeads(rear(), session(), T0 + MIN));
        assertTrue(PlaybackLead.cabinLeads(rear(), session(), T0 + 8 * MIN));
    }

    /** 开录差一两秒、分段之间算出来空几秒：仍然算有录像，和环视判断「同一次录制」同一个数。 */
    @Test
    public void shortGapsCountAsFootage() {
        assertTrue("比环视晚 1 秒开录", PlaybackLead.cabinLeads(rear(), session(), T0));
        assertTrue("分段之间空 3 秒", PlaybackLead.cabinLeads(rear(), session(), T0 + 7 * MIN + SEC));
        assertTrue("接回前 4 秒", PlaybackLead.cabinLeads(rear(), session(), T0 + 6 * MIN - 4 * SEC));
    }

    @Test
    public void aCabinDoesNotLeadWhereItHasNoFootage() {
        assertFalse("被占用、停着的那 3 分钟", PlaybackLead.cabinLeads(rear(), session(), T0 + 4 * MIN));
        assertFalse("刚停下那一刻", PlaybackLead.cabinLeads(rear(), session(), T0 + 3 * MIN));
        assertFalse("比环视早结束", PlaybackLead.cabinLeads(rear(), session(), T0 + 9 * MIN + 40 * SEC));
        assertFalse("这一条里没有这一路", PlaybackLead.cabinLeads(LaneTrack.EMPTY, session(), T0));
    }

    /** 环视之外的座舱录像存着、不放：环视结束之后开头的不算，环视结束那一刻及以后一律不领。 */
    @Test
    public void footageAfterTheSurroundEndsDoesNotLead() {
        LaneTrack startsAfter = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("late.mp4", T0 + 10 * MIN + 2 * SEC, MIN, 1)));
        assertFalse(PlaybackLead.cabinLeads(startsAfter, session(), T0 + 10 * MIN - SEC));

        LaneTrack runsPast = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("long.mp4", T0 + 9 * MIN, 2 * MIN, 1)));
        assertTrue(PlaybackLead.cabinLeads(runsPast, session(), T0 + 10 * MIN - 1));
        assertFalse(PlaybackLead.cabinLeads(runsPast, session(), T0 + 10 * MIN));
    }

    // ---------- 夹进这一条录制 ----------

    @Test
    public void clampKeepsTheMomentInsideTheRecording() {
        RecordingTimeline.Session session = session();
        assertEquals(T0, PlaybackLead.clamp(session, T0 - 3 * SEC));
        assertEquals(T0 + 4 * MIN, PlaybackLead.clamp(session, T0 + 4 * MIN));
        // 结束那一刻不在任何一段里：夹到它前 1 毫秒，环视还找得到要放的文件
        assertEquals(T0 + 10 * MIN - 1, PlaybackLead.clamp(session, T0 + 10 * MIN));
        assertEquals(T0 + 10 * MIN - 1, PlaybackLead.clamp(session, T0 + 12 * MIN));
    }

    // ---------- 领头的一段放完之后 ----------

    @Test
    public void theSurroundGoesOnUntilItsLastClip() {
        RecordingTimeline.Session session = session();
        LaneTrack surround = LaneTrack.of(session);
        assertEquals(PlaybackLead.AfterClip.NEXT_CLIP,
                PlaybackLead.afterClip(true, surround, 0, session));
        assertEquals(PlaybackLead.AfterClip.END, PlaybackLead.afterClip(true, surround, 1, session));
    }

    /** 以前：放大的座舱跳过缺的 3 分钟直接接下一段，进度条跟着跳。现在交回环视。 */
    @Test
    public void aCabinWithMinutesMissingHandsBackToTheSurround() {
        assertEquals(PlaybackLead.AfterClip.SURROUND,
                PlaybackLead.afterClip(false, rear(), 0, session()));
    }

    @Test
    public void aCabinGoesOnAcrossAShortGap() {
        assertEquals(PlaybackLead.AfterClip.NEXT_CLIP,
                PlaybackLead.afterClip(false, rear(), 1, session()));
    }

    /** 以前：座舱最后一段放完整个停下，环视那半分钟看不到。现在交回环视。 */
    @Test
    public void aCabinEndingBeforeTheSurroundHandsBackToTheSurround() {
        assertEquals(PlaybackLead.AfterClip.SURROUND,
                PlaybackLead.afterClip(false, rear(), 2, session()));
    }

    /** 各路的最后一段本来就差一两秒结束：不为这一两秒收回网格，就是放完了。 */
    @Test
    public void aCabinEndingWithTheSurroundEndsTheRecording() {
        LaneTrack almost = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("a.mp4", T0 + SEC, 10 * MIN - 4 * SEC, 1)));
        assertEquals(PlaybackLead.AfterClip.END,
                PlaybackLead.afterClip(false, almost, 0, session()));

        LaneTrack past = LaneTrack.of(Arrays.asList(
                new RecordingTimeline.Source("long.mp4", T0 + 9 * MIN, 2 * MIN, 1),
                new RecordingTimeline.Source("later.mp4", T0 + 11 * MIN, MIN, 1)));
        assertEquals("放过了环视的末尾，后面的不放", PlaybackLead.AfterClip.END,
                PlaybackLead.afterClip(false, past, 0, session()));
    }
}
