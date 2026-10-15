package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 相机步骤统计（2.11.4 R2，项目所有者 2026-10-14：「识别前后座舱摄像头被卡住的原因或者统计」）：一行一步存得下、读得回，
 * 读不懂的行跳过；报告里分组数得对 —— 哨兵、主界面在不在前、离我们上一次关它多久、是不是车机刚放开它；
 * 备选方案「关座舱时先短暂关环视」要比的那一组（R3，只记录）。
 */
public class StepStatsTest {

    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    @Test
    public void anEntryRoundTripsThroughItsLine() {
        SessionCounts counts = new SessionCounts();
        counts.reset(1_000);
        counts.failed(9_000, 0, 3);
        StepStats.Entry e = new StepStats.Builder()
                .step(1_697_000_000_000L, REAR, "1", StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000)
                .configured(80)
                .counts(counts.snapshot())
                .vehicle(1, 200, 4_600, 150, 125)
                .main(false)
                .lastClose(4_500, 223)
                .released(1_126_900, 3_000)
                .word(4_400, true)
                .surround("STREAMING", -1, null)
                .build();
        String line = e.encode();
        StepStats.Entry back = StepStats.parse(line);
        assertNotNull(line, back);
        assertEquals(line, back.encode());
        assertEquals(REAR, back.key);
        assertEquals(StepStats.Move.OPEN, back.move);
        assertEquals(StepStats.Result.NO_FRAMES, back.result);
        assertEquals(15_000, back.ms);
        assertEquals("HAL_HOLDING", back.counts);
        assertEquals(1, back.failures);
        assertEquals(1, back.sentry);
        assertEquals(0, back.mainForeground);
        assertEquals(125, back.importance);
        assertEquals(4_500, back.sinceCloseMs);
        assertEquals(223, back.closeMs);
        assertEquals(1_126_900, back.heldMs);
        assertEquals(1, back.wordOurs);
        assertEquals("STREAMING", back.surround);
        assertEquals("没量的是 -1", -1, back.surroundGapMs);
        assertEquals(-1, back.oldCloseMs);
    }

    @Test
    public void linesItCannotReadAreSkipped() {
        assertNull(StepStats.parse(null));
        assertNull(StepStats.parse(""));
        assertNull("不是这一版", StepStats.parse("v9 t=1 k=left m=OPEN r=FRAMED"));
        assertNull("少了动作", StepStats.parse("v1 t=1 k=left r=FRAMED"));
        assertNull("认不出的动作", StepStats.parse("v1 t=1 k=left m=TELEPORT r=FRAMED"));
        assertNull("数坏了", StepStats.parse("v1 t=x k=left m=OPEN r=FRAMED"));
        StepStats.Entry e = StepStats.parse("v1 t=5 k=left m=CLOSE r=CLOSED ms=200 future=whatever");
        assertNotNull("以后加的键跳过", e);
        assertEquals(200, e.ms);
        assertEquals(-1, e.sentry);
        List<StepStats.Entry> all = StepStats.parseAll(Arrays.asList("garbage", e.encode(), "v1 k=left"));
        assertEquals(1, all.size());
    }

    @Test
    public void onlyTheLastTwoHundredAreKept() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < StepStats.MAX + 50; i++) {
            lines.add(entry(i, REAR, StepStats.Move.OPEN, StepStats.Result.FRAMED, 100).encode());
        }
        List<StepStats.Entry> kept = StepStats.parseAll(lines);
        assertEquals(StepStats.MAX, kept.size());
        assertEquals("最早的那几条扔掉", 50, kept.get(0).at);
    }

    @Test
    public void aTallyCountsOutcomesAndCloseTimes() {
        List<StepStats.Entry> entries = Arrays.asList(
                entry(1, REAR, StepStats.Move.OPEN, StepStats.Result.FRAMED, 200),
                entry(2, REAR, StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000),
                entry(3, REAR, StepStats.Move.OPEN, StepStats.Result.ERROR, 300),
                entry(4, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 200),
                entry(5, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 6_200),
                entry(6, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 300),
                entry(7, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSE_TIMED_OUT, 30_000));
        StepStats.Tally t = StepStats.tally(entries);
        assertEquals(7, t.count);
        assertEquals(1, t.framed);
        assertEquals(1, t.noFrames);
        assertEquals(1, t.errors);
        assertEquals(4, t.closes);
        assertEquals("关完了的那三次的中位数", 300, t.closeMedianMs);
        assertEquals(6_200, t.closeMaxMs);
        assertEquals("超过 1 秒的、加上到点没关完的", 2, t.slowCloses);
        assertEquals(1, t.closeTimedOut);
    }

    @Test
    public void bucketsSplitBySentryMainScreenTimeSinceOurCloseAndHeadUnitRelease() {
        StepStats.Entry sentryOn = new StepStats.Builder()
                .step(1, REAR, "1", StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000)
                .vehicle(1, -1, -1, -1, -1).main(false).lastClose(4_500, 220).build();
        assertEquals("sentry:1", StepStats.sentryBucket(sentryOn));
        assertEquals("main:no", StepStats.mainBucket(sentryOn));
        assertEquals("close:3-6", StepStats.sinceCloseBucket(sentryOn));
        assertEquals("release:no", StepStats.releaseBucket(sentryOn));

        StepStats.Entry unknown = entry(2, REAR, StepStats.Move.OPEN, StepStats.Result.FRAMED, 100);
        assertEquals("sentry:?", StepStats.sentryBucket(unknown));
        assertEquals("main:?", StepStats.mainBucket(unknown));
        assertEquals("close:none", StepStats.sinceCloseBucket(unknown));

        assertEquals("close:<3", StepStats.sinceCloseBucket(withClose(2_999)));
        assertEquals("close:3-6", StepStats.sinceCloseBucket(withClose(3_000)));
        assertEquals("close:6-30", StepStats.sinceCloseBucket(withClose(6_000)));
        assertEquals("close:6-30", StepStats.sinceCloseBucket(withClose(30_000)));
        assertEquals("close:>30", StepStats.sinceCloseBucket(withClose(30_001)));

        // 10-12 20:57：车机拿了 18 分 47 秒、放开 3 秒后我们开 —— 放开以后我们没关过它
        StepStats.Entry afterRelease = new StepStats.Builder()
                .step(3, REAR, "1", StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000)
                .lastClose(1_127_000, 7_822).released(1_126_900, 3_000).build();
        assertEquals("release:yes", StepStats.releaseBucket(afterRelease));
        StepStats.Entry closedSince = new StepStats.Builder()
                .step(4, REAR, "1", StepStats.Move.OPEN, StepStats.Result.FRAMED, 200)
                .lastClose(4_000, 200).released(600_000, 60_000).build();
        assertEquals("放开以后我们又关过它", "release:no", StepStats.releaseBucket(closedSince));

        Map<String, List<StepStats.Entry>> groups = StepStats.groupBy(
                Arrays.asList(sentryOn, unknown, afterRelease), StepStats::sentryBucket);
        assertEquals(Arrays.asList("sentry:1", "sentry:?"), new ArrayList<>(groups.keySet()));
        assertEquals(1, groups.get("sentry:1").size());
        assertEquals(2, groups.get("sentry:?").size());
    }

    /**
     * R3（项目所有者 2026-10-14，只记录）：座舱每一次关按关的时候环视怎样分组，带上之后那一路下一次开有没有画面；
     * 关了又关、中间没开的不算「下一次开」。环视自己的关不算。
     */
    @Test
    public void cabinClosesAreComparedBySurroundStateWithTheNextOpenOfThatLane() {
        List<StepStats.Entry> entries = Arrays.asList(
                closeWithSurround(1, REAR, "STREAMING", 220),
                entry(2, REAR, StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000),
                entry(3, REAR, StepStats.Move.REOPEN, StepStats.Result.FRAMED, 8_700),
                closeWithSurround(4, FRONT, "STREAMING", 150),
                entry(5, FRONT, StepStats.Move.OPEN, StepStats.Result.FRAMED, 100),
                closeWithSurround(6, REAR, "CLOSED", 180),
                closeWithSurround(7, REAR, "CLOSED", 160),
                closeWithSurround(8, SUR, null, 100));
        Map<String, StepStats.CloseComparison> groups = StepStats.cabinClosesBySurround(entries);
        assertEquals(Arrays.asList("STREAMING", "CLOSED"), new ArrayList<>(groups.keySet()));
        StepStats.CloseComparison streaming = groups.get("STREAMING");
        assertEquals(2, streaming.closes.size());
        assertEquals(2, streaming.nextOpens);
        assertEquals("关完之后那一次开没画面", 1, streaming.nextNoFrames);
        StepStats.CloseComparison closed = groups.get("CLOSED");
        assertEquals(2, closed.closes.size());
        assertEquals("关了又关、之后没开过", 0, closed.nextOpens);
        assertEquals(Collections.singletonList(6L), Collections.singletonList(closed.closes.get(0).at));
    }

    /**
     * 关座舱时环视那一条（2.11.5 第 D2、D3 项）存得下、读得回：关的几路、为什么关、环视最后一帧比关完早（负数）、关完 1 秒后没出过画面、
     * 我们救它那一刻、环视第几台、点火。
     */
    @Test
    public void aCouplingEntryRoundTripsThroughItsLine() {
        StepStats.Entry e = new StepStats.Builder()
                .step(1_697_000_000_000L, SUR, "2", StepStats.Move.COUPLE, StepStats.Result.STALLED, 8_121)
                .vehicle(0, 30_000, -1, 30_000, 125)
                .main(false)
                .surround(null, 8_121, Boolean.FALSE)
                .lineage(5, "CAME_BACK")
                .coupling(Arrays.asList(FRONT, REAR), "DARK_RELEASE", -35, 0, "RESCUE", 8_100)
                .ignition("ACC")
                .build();
        String line = e.encode();
        StepStats.Entry back = StepStats.parse(line);
        assertNotNull(line, back);
        assertEquals(line, back.encode());
        assertEquals(StepStats.Move.COUPLE, back.move);
        assertEquals(StepStats.Result.STALLED, back.result);
        assertEquals(8_121, back.ms);
        assertEquals("back,left", back.closedKeys);
        assertEquals("DARK_RELEASE", back.closeWhy);
        assertEquals("负数照存", -35, back.lastResultMs);
        assertEquals(0, back.deliveredAfter);
        assertEquals("RESCUE", back.ours);
        assertEquals(8_100, back.oursAtMs);
        assertEquals(5, back.lineage);
        assertEquals("CAME_BACK", back.lineageReset);
        assertEquals("ACC", back.ignition);
        assertNull(back.closeKind);
        assertEquals("关座舱时环视那一条不是一步", false, StepStats.isStep(back));

        StepStats.Entry plain = StepStats.parse("v1 t=5 k=left m=CLOSE r=CLOSED ms=200");
        assertEquals("没记的是 MIN_VALUE", Long.MIN_VALUE, plain.lastResultMs);
        assertEquals(-1, plain.lineage);
        assertNull("2.11.4 记的行没有这几项", StepStats.lineageBucket(plain));
        assertNull("认不出的关的种类：整行不要", StepStats.parse("v1 t=5 k=left m=CLOSE r=CLOSED ck=TELEPORT"));
    }

    /**
     * 丢了之后那一次关、救援重开时关旧设备（2.11.5 第 D3 项）单算一组；备选方案那一组只比计划里的关；
     * 环视那一台是不是第一台分三种（不知道的不算进哪一组）。
     */
    @Test
    public void rescueAndLossClosesAreTheirOwnGroupAndLineageSplitsFirstFromLater() {
        StepStats.Entry loss = new StepStats.Builder()
                .step(1, SUR, "2", StepStats.Move.CLOSE, StepStats.Result.CLOSED, 5_798)
                .closeKind(StepStats.CloseKind.LOSS).error("DEVICE_ERROR:4").build();
        StepStats.Entry back = StepStats.parse(loss.encode());
        assertEquals(StepStats.CloseKind.LOSS, back.closeKind);
        assertEquals("front CLOSE:LOSS", StepStats.laneMove(back));
        assertEquals("front CLOSE", StepStats.laneMove(entry(2, SUR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 100)));

        StepStats.Entry first = new StepStats.Builder()
                .step(3, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 200).lineage(1, "TAKEN").build();
        StepStats.Entry later = new StepStats.Builder()
                .step(4, REAR, "1", StepStats.Move.CLOSE, StepStats.Result.CLOSED, 200).lineage(5, "CAME_BACK").build();
        StepStats.Entry counted = new StepStats.Builder()
                .step(5, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 200).lineage(1, null).build();
        assertEquals("lineage:first", StepStats.lineageBucket(first));
        assertEquals("lineage:later", StepStats.lineageBucket(later));
        assertEquals("从记录开始数的不可信", "lineage:?", StepStats.lineageBucket(counted));
        assertNull(StepStats.lineageBucket(new StepStats.Builder()
                .step(6, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 200).lineage(0, "TAKEN").build()));

        StepStats.Entry cabinLoss = new StepStats.Builder()
                .step(7, REAR, "1", StepStats.Move.CLOSE, StepStats.Result.CLOSED, 7_822)
                .closeKind(StepStats.CloseKind.LOSS).surround("STREAMING", 0, Boolean.FALSE).build();
        Map<String, StepStats.CloseComparison> groups = StepStats.cabinClosesBySurround(Arrays.asList(
                closeWithSurround(8, REAR, "STREAMING", 220), cabinLoss));
        assertEquals("丢了之后那一次关不比", 1, groups.get("STREAMING").closes.size());
    }

    /**
     * 关座舱之后环视那一边（2.11.5 第 D3 项）：几次关座舱、环视断了几次、最长的那一次；别的条不算。10-15 的统计那样：
     * 不是第一台、主界面在后台的断了，第一台的没断。
     */
    @Test
    public void surroundAfterClosesCountsStallsAndTheLongest() {
        List<StepStats.Entry> couples = Arrays.asList(
                couple(1, StepStats.Result.STALLED, 48_800, 5, false),
                couple(2, StepStats.Result.STALLED, 42_400, 5, false),
                couple(3, StepStats.Result.STEADY, 100, 1, false),
                entry(4, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 200));
        StepStats.SurroundAfterClose all = StepStats.surroundAfterCloses(couples);
        assertEquals(3, all.closes);
        assertEquals(2, all.stalled);
        assertEquals(48_800, all.longestMs);
        Map<String, List<StepStats.Entry>> byLineage = StepStats.groupBy(couples, StepStats::lineageBucket);
        assertEquals(2, StepStats.surroundAfterCloses(byLineage.get("lineage:later")).stalled);
        StepStats.SurroundAfterClose firsts = StepStats.surroundAfterCloses(byLineage.get("lineage:first"));
        assertEquals(1, firsts.closes);
        assertEquals(0, firsts.stalled);
        assertEquals("没断过", -1, firsts.longestMs);
    }

    /**
     * 存下的「配好以来回了什么」照今天的规矩重算（2.11.5 第 D0 项）：2.11.4 记的 STARVING 里开始 0 的那几条其实是 HAL 一帧没开始；
     * 旧的 FAILING 也是这一种；数不全的照存下的名字。
     */
    @Test
    public void storedCountsAreReclassifiedWithTodaysRule() {
        assertEquals("HAL_FAILING", StepStats.parse(
                "v1 t=1 k=front m=OPEN r=NO_FRAMES cnt=STARVING st=0 res=0 fail=2 lost=1").kind());
        assertEquals("STARVING", StepStats.parse(
                "v1 t=1 k=front m=OPEN r=NO_FRAMES cnt=STARVING st=3 res=0 fail=2 lost=1").kind());
        assertEquals("HAL_FAILING", StepStats.parse(
                "v1 t=1 k=front m=OPEN r=NO_FRAMES cnt=FAILING st=0 res=0 fail=3 lost=0").kind());
        assertEquals("NOT_CONFIGURED", StepStats.parse("v1 t=1 k=front m=OPEN r=NO_FRAMES cnt=NOT_CONFIGURED").kind());
        assertEquals("数不全", "STARVING", StepStats.parse("v1 t=1 k=front m=OPEN r=NO_FRAMES cnt=STARVING").kind());
        assertNull(entry(1, REAR, StepStats.Move.OPEN, StepStats.Result.FRAMED, 100).kind());
    }

    /**
     * 关座舱前先关环视之后环视开回来的那一步（项目所有者 2026-10-15 定，2.11.6）存得下、读得回；数一组：出画面 / 没画面 / 出错、
     * 出画面用了多久、环视断了多久（开始关环视到开 + 出画面用的）、中间座舱关得慢的几次（其中开回来没画面的几次）。别的步不算进来。
     */
    @Test
    public void surroundFirstReopensAreCountedWithTheirSeamAndSlowCabinCloses() {
        StepStats.Entry framed = new StepStats.Builder()
                .step(1, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 230)
                .surroundFirst(290, 140).build();
        StepStats.Entry back = StepStats.parse(framed.encode());
        assertNotNull(back);
        assertEquals(framed.encode(), back.encode());
        assertEquals(290, back.surroundFirstMs);
        assertEquals(140, back.surroundFirstCabinMs);
        assertEquals(true, back.afterSurroundFirst());
        assertEquals("环视断了 = 开始关环视到开 + 出画面用的", 520, back.surroundFirstGapMs());
        StepStats.Entry plain = StepStats.parse("v1 t=5 k=front m=OPEN r=FRAMED ms=200");
        assertEquals(false, plain.afterSurroundFirst());
        assertEquals(-1, plain.surroundFirstGapMs());
        assertEquals("开始那一刻不知道的不记", -1, new StepStats.Builder()
                .step(1, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 230).surroundFirst(-1, 140).build()
                .surroundFirstCabinMs);

        List<StepStats.Entry> entries = Arrays.asList(
                framed,
                new StepStats.Builder().step(2, SUR, "2", StepStats.Move.OPEN, StepStats.Result.FRAMED, 400)
                        .surroundFirst(300, 120).build(),
                new StepStats.Builder().step(3, SUR, "2", StepStats.Move.OPEN, StepStats.Result.NO_FRAMES, 15_000)
                        .surroundFirst(6_500, 6_200).build(),
                new StepStats.Builder().step(4, SUR, "2", StepStats.Move.OPEN, StepStats.Result.ERROR, 900)
                        .surroundFirst(300, -1).build(),
                entry(5, SUR, StepStats.Move.OPEN, StepStats.Result.FRAMED, 100));
        StepStats.SurroundFirstReopens t = StepStats.surroundFirstReopens(entries);
        assertEquals(4, t.count);
        assertEquals(2, t.framed);
        assertEquals(1, t.noFrames);
        assertEquals(1, t.errors);
        assertEquals(230, t.frameMedianMs);
        assertEquals(400, t.frameMaxMs);
        assertEquals(520, t.gapMedianMs);
        assertEquals(700, t.gapMaxMs);
        assertEquals("中间座舱关得慢", 1, t.slowCabins);
        assertEquals("那一次开回来没画面", 1, t.slowCabinsDark);
        StepStats.SurroundFirstReopens none = StepStats.surroundFirstReopens(Collections.singletonList(
                entry(6, REAR, StepStats.Move.CLOSE, StepStats.Result.CLOSED, 100)));
        assertEquals(0, none.count);
        assertEquals(-1, none.gapMaxMs);
    }

    @Test
    public void tokensNeverCarrySpacesOrEquals() {
        assertEquals("a_b_c", StepStats.token("a b=c"));
    }

    private static StepStats.Entry entry(long at, String key, StepStats.Move move, StepStats.Result result, long ms) {
        return new StepStats.Builder().step(at, key, "1", move, result, ms).build();
    }

    private static StepStats.Entry withClose(long sinceCloseMs) {
        return new StepStats.Builder().step(1, REAR, "1", StepStats.Move.OPEN, StepStats.Result.FRAMED, 100)
                .lastClose(sinceCloseMs, 200).build();
    }

    private static StepStats.Entry couple(long at, StepStats.Result result, long ms, int lineage, boolean main) {
        return new StepStats.Builder().step(at, SUR, "2", StepStats.Move.COUPLE, result, ms).main(main)
                .lineage(lineage, "CAME_BACK").coupling(Arrays.asList(REAR, FRONT), "DARK_RELEASE", -35, 0, null, -1)
                .build();
    }

    private static StepStats.Entry closeWithSurround(long at, String key, String surround, long ms) {
        return new StepStats.Builder().step(at, key, "1", StepStats.Move.CLOSE, StepStats.Result.CLOSED, ms)
                .surround(surround, surround == null ? -1 : 0, surround == null ? null : Boolean.FALSE).build();
    }
}
