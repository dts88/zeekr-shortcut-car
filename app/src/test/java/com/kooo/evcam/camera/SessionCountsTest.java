package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 一份会话配好以来相机回了什么（2.11.4 第 1a 项，项目所有者 2026-10-14 批准）：15 秒没画面的那一行自己分得出是哪一种 ——
 * HAL 一个都没回（10-12 20:57：配好后约 8 秒一次失败，原因 0，丢缓冲 0）、我们的输出接不住（丢缓冲、差不多每秒一次失败）、
 * 还是开始了没有结果。
 */
public class SessionCountsTest {

    private static final long CONFIGURED = 1_000_000L;

    @Test
    public void nothingConfiguredYetIsNotConfigured() {
        SessionCounts counts = new SessionCounts();
        assertEquals(SessionCounts.Kind.NOT_CONFIGURED, counts.snapshot().kind());
        assertEquals(SessionCounts.Kind.NOT_CONFIGURED, SessionCounts.NONE.kind());
    }

    /** 10-12 20:57 的样子：配好了、重复请求发了，一个开始、一个结果都没有，配好后约 8 秒一次失败。 */
    @Test
    public void noStartNoResultAndOneLateFailureIsTheHalHolding() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.repeating(CONFIGURED + 10);
        assertEquals(SessionCounts.Kind.HAL_HOLDING, counts.snapshot().kind());
        counts.failed(CONFIGURED + 8_000, 0, 3);
        SessionCounts.Snapshot s = counts.snapshot();
        assertEquals(SessionCounts.Kind.HAL_HOLDING, s.kind());
        assertEquals(CONFIGURED, s.configuredAt);
        assertEquals(CONFIGURED + 10, s.repeatingAt);
        assertEquals(1, s.failures);
        assertEquals(CONFIGURED + 8_000, s.firstFailAt);
        assertEquals(0, s.firstFailReason);
        assertEquals(3, s.firstFailFrame);
    }

    @Test
    public void lostBuffersMeanOurOutputsCannotKeepUpEvenWhenCapturesStarted() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.started();
        counts.failed(CONFIGURED + 1_000, 1, 1);
        counts.failed(CONFIGURED + 2_000, 1, 2);
        counts.lost("record");
        counts.lost("record");
        counts.lost("main-preview");
        SessionCounts.Snapshot s = counts.snapshot();
        assertEquals(SessionCounts.Kind.STARVING, s.kind());
        assertEquals(3, s.lostTotal());
        assertEquals(Long.valueOf(2), s.lost.get("record"));
        assertEquals("第一次失败的那一下留着", CONFIGURED + 1_000, s.firstFailAt);
        assertEquals(1, s.firstFailReason);
        assertEquals(1, s.firstFailFrame);
    }

    @Test
    public void startsWithoutResultsAreALateStartAndResultsMeanFramed() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.started();
        assertEquals(SessionCounts.Kind.LATE_START, counts.snapshot().kind());
        counts.completed();
        assertEquals(SessionCounts.Kind.FRAMED, counts.snapshot().kind());
    }

    @Test
    public void repeatedFailuresWithoutStartsOrLostBuffersAreTheHalFailing() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.failed(CONFIGURED + 500, 0, 1);
        counts.failed(CONFIGURED + 1_500, 0, 2);
        assertEquals(SessionCounts.Kind.HAL_FAILING, counts.snapshot().kind());
    }

    /**
     * 2.11.5 第 D0 项（rel_final §6）：HAL 开始出帧以后才怪我们的输出。10-14 装新版后第一次开环视「开始 0、结果 0、失败 2、
     * 丢缓冲 main-preview 1」、亮屏重开后座舱「失败 2、丢缓冲 record 1」—— 以前都写成「我们的输出接不住」。
     */
    @Test
    public void lostBuffersBeforeAnyStartAreTheHalFailingNotOurOutputs() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.failed(CONFIGURED + 1_000, 0, 0);
        counts.lost("main-preview");
        assertEquals("一次失败、丢了缓冲、没开始", SessionCounts.Kind.HAL_FAILING, counts.snapshot().kind());
        counts.failed(CONFIGURED + 2_000, 0, 1);
        assertEquals(SessionCounts.Kind.HAL_FAILING, counts.snapshot().kind());
        counts.started();
        assertEquals("开始过以后丢缓冲才算我们接不住", SessionCounts.Kind.STARVING, counts.snapshot().kind());
    }

    /** 一条规矩，先到先得：没配好、出过结果、开始过（丢没丢缓冲）、没开始（一次以内的失败、没丢缓冲 / 其余）。 */
    @Test
    public void classifyIsOneRuleInOrder() {
        assertEquals(SessionCounts.Kind.NOT_CONFIGURED, SessionCounts.classify(false, 3, 5, 2, 1));
        assertEquals(SessionCounts.Kind.FRAMED, SessionCounts.classify(true, 0, 1, 9, 9));
        assertEquals(SessionCounts.Kind.STARVING, SessionCounts.classify(true, 1, 0, 0, 1));
        assertEquals(SessionCounts.Kind.LATE_START, SessionCounts.classify(true, 1, 0, 3, 0));
        assertEquals(SessionCounts.Kind.HAL_HOLDING, SessionCounts.classify(true, 0, 0, 0, 0));
        assertEquals(SessionCounts.Kind.HAL_HOLDING, SessionCounts.classify(true, 0, 0, 1, 0));
        assertEquals(SessionCounts.Kind.HAL_FAILING, SessionCounts.classify(true, 0, 0, 2, 0));
        assertEquals(SessionCounts.Kind.HAL_FAILING, SessionCounts.classify(true, 0, 0, 0, 1));
    }

    /** 新的一份会话从头数；重复请求只记第一次（画面调节、录像换输出那几次重发不算）；新的一台设备没配好之前不算上一台的。 */
    @Test
    public void aNewSessionStartsOverAndANewDeviceForgetsTheOldOne() {
        SessionCounts counts = new SessionCounts();
        counts.reset(CONFIGURED);
        counts.repeating(CONFIGURED + 5);
        counts.repeating(CONFIGURED + 900);
        counts.completed();
        assertEquals(CONFIGURED + 5, counts.snapshot().repeatingAt);
        counts.reset(CONFIGURED + 10_000);
        SessionCounts.Snapshot s = counts.snapshot();
        assertEquals(0, s.results);
        assertEquals(0, s.repeatingAt);
        assertEquals(SessionCounts.Kind.HAL_HOLDING, s.kind());
        counts.clear();
        assertEquals(SessionCounts.Kind.NOT_CONFIGURED, counts.snapshot().kind());
    }
}
