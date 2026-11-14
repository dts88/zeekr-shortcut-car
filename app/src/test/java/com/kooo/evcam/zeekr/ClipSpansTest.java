/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 不打开文件就知道时长的那些分段（{@link ClipSpans}，项目所有者 2026-11-14）：照文件名里的开录时刻和文件的修改时间算；
 * 算不了的不给，调用方打开读。时刻都是编的。
 */
public class ClipSpansTest {

    private static final long MINUTE = 60_000L;

    private static ClipSpans.Timed clip(String path, long start, long modified) {
        return new ClipSpans.Timed(path, start, modified);
    }

    /** 连着录的三段：前两段封好的那一下挨着下一段的开头，时长就是两段开头的差；最新的那一段可能还在录，不给。 */
    @Test
    public void aClipThatRunsIntoTheNextOneLastsUntilItStarts() {
        long t = 1_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("a", t, t + MINUTE + 100));               // 封好比下一段开录晚 0.1 秒
        lane.add(clip("b", t + MINUTE, t + 2 * MINUTE - 300));   // 早 0.3 秒
        lane.add(clip("c", t + 2 * MINUTE, t + 2 * MINUTE + 41_000));
        Map<String, Long> known = ClipSpans.known(lane);
        assertEquals(Long.valueOf(MINUTE), known.get("a"));
        assertEquals(Long.valueOf(MINUTE), known.get("b"));
        assertFalse("最新的那一段要打开看", known.containsKey("c"));
    }

    /** 自己停的那一段（后面隔了一阵才又开录）：时长是修改时间 − 开录时刻。 */
    @Test
    public void aClipThatStoppedOnItsOwnLastsUntilItWasSealed() {
        long t = 1_500_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("full", t, t + MINUTE + 200));
        lane.add(clip("stopped", t + MINUTE, t + MINUTE + 20_400));   // 录了 20 秒停了
        lane.add(clip("later", t + 30 * MINUTE, t + 31 * MINUTE));    // 半小时以后的另一条
        lane.add(clip("newest", t + 31 * MINUTE, t + 31 * MINUTE + 5_000));
        Map<String, Long> known = ClipSpans.known(lane);
        assertEquals(Long.valueOf(MINUTE), known.get("full"));
        assertEquals(Long.valueOf(20_400L), known.get("stopped"));
        assertEquals(Long.valueOf(MINUTE), known.get("later"));
        assertFalse(known.containsKey("newest"));
    }

    /** 次序随便给：自己按开录时刻排。 */
    @Test
    public void orderDoesNotMatter() {
        long t = 2_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("c", t + 2 * MINUTE, t + 3 * MINUTE));
        lane.add(clip("a", t, t + MINUTE));
        lane.add(clip("b", t + MINUTE, t + 2 * MINUTE));
        Map<String, Long> known = ClipSpans.known(lane);
        assertEquals(2, known.size());
        assertEquals(Long.valueOf(MINUTE), known.get("a"));
        assertEquals(Long.valueOf(MINUTE), known.get("b"));
    }

    /**
     * 文件时间用不上的不给：没有修改时间、比开录时刻还早、离开录时刻比一个分段还长、下一段都开录了它才封好
     * （后来被动过）、两个文件同一刻开录。挨着下一段开头的那条线：差正好 2 秒算录到了下一段，晚过 2 秒的不给。
     */
    @Test
    public void aFileWhoseTimeCannotBeRightIsLeftToBeRead() {
        long t = 3_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("no-time", t, 0));
        lane.add(clip("before-start", t + MINUTE, t + MINUTE - 5_000));
        lane.add(clip("too-long", t + 2 * MINUTE, t + 2 * MINUTE + ClipSpans.LIMIT_MS + 1));
        lane.add(clip("touched", t + 60 * MINUTE, t + 61 * MINUTE + 25_000));   // 下一段开录 25 秒后才「封好」
        lane.add(clip("twin-1", t + 61 * MINUTE, t + 62 * MINUTE));
        lane.add(clip("twin-2", t + 61 * MINUTE, t + 62 * MINUTE));
        lane.add(clip("edge-early", t + 62 * MINUTE, t + 63 * MINUTE - ClipSpans.TOLERANCE_MS));
        lane.add(clip("edge-late", t + 63 * MINUTE, t + 64 * MINUTE + ClipSpans.TOLERANCE_MS));
        lane.add(clip("edge-out", t + 64 * MINUTE, t + 65 * MINUTE + ClipSpans.TOLERANCE_MS + 1));
        lane.add(clip("own-end", t + 65 * MINUTE, t + 66 * MINUTE - ClipSpans.TOLERANCE_MS - 1));
        lane.add(clip("newest", t + 66 * MINUTE, t + 67 * MINUTE));
        Map<String, Long> known = ClipSpans.known(lane);
        assertFalse(known.containsKey("no-time"));
        assertFalse(known.containsKey("before-start"));
        assertFalse(known.containsKey("too-long"));
        assertFalse(known.containsKey("touched"));
        assertFalse("同一刻开录的两个：名字说不清谁先谁后", known.containsKey("twin-1"));
        assertEquals("早 2 秒封好：算录到了下一段", Long.valueOf(MINUTE), known.get("edge-early"));
        assertEquals("晚 2 秒封好：算录到了下一段", Long.valueOf(MINUTE), known.get("edge-late"));
        assertFalse("晚过 2 秒：不给", known.containsKey("edge-out"));
        assertEquals("早过 2 秒：它自己停的", Long.valueOf(MINUTE - ClipSpans.TOLERANCE_MS - 1), known.get("own-end"));
        assertFalse(known.containsKey("newest"));
        assertTrue(ClipSpans.known(new ArrayList<>()).isEmpty());
        assertTrue("只有一个文件：它就是最新的", ClipSpans.known(
                Collections.singletonList(clip("only", t, t + MINUTE))).isEmpty());
    }

    /**
     * 文件时间对的时候，照这个办法分出来的条和全部打开读一遍分出来的一样：一样的几条、每条一样多段，每段只长出
     * 换段的空或者封文件的那点工夫；要打开的只有最新的那一个。照实车的样子编：换段空零点几秒，封文件晚零点几秒，
     * 录制之间隔几秒到几小时。
     */
    @Test
    public void groupingMatchesReadingEveryFileWhenTheTimesAreRight() {
        Random random = new Random(20261114);
        for (int round = 0; round < 300; round++) {
            List<ClipSpans.Timed> lane = new ArrayList<>();
            List<RecordingTimeline.Source> exact = new ArrayList<>();
            long at = 4_000_000_000L;
            int recordings = 1 + random.nextInt(10);
            for (int r = 0; r < recordings; r++) {
                long segment = (1 + random.nextInt(3)) * MINUTE;
                int count = 1 + random.nextInt(20);
                for (int i = 0; i < count; i++) {
                    boolean lastOne = i == count - 1;
                    // 最后一段录到一半停；别的录满一段，差一个换段的空
                    long real = lastOne ? 5_000 + random.nextInt((int) segment - 6_000)
                            : segment - (100 + random.nextInt(400));
                    long modified = at + real + 100 + random.nextInt(700);
                    String path = "clip" + lane.size();
                    lane.add(clip(path, at, modified));
                    exact.add(new RecordingTimeline.Source(path, at, real));
                    at += lastOne ? real : segment;
                }
                // 下一条录制隔多远：6 秒（刚过界线）到几小时
                at += new long[] {6_000, 8_000, 30_000, 5 * MINUTE, 3 * 60 * MINUTE}[random.nextInt(5)];
            }
            Map<String, Long> known = ClipSpans.known(lane);
            List<RecordingTimeline.Source> mixed = new ArrayList<>();
            int read = 0;
            for (RecordingTimeline.Source source : exact) {
                Long span = known.get(source.path);
                if (span == null) {
                    read++;
                }
                mixed.add(new RecordingTimeline.Source(source.path, source.startEpochMs,
                        span != null ? span : source.durationMs));
            }
            List<RecordingTimeline.Session> byReading = RecordingTimeline.build(exact);
            List<RecordingTimeline.Session> byNames = RecordingTimeline.build(mixed);
            assertEquals("第 " + round + " 轮：几条", byReading.size(), byNames.size());
            for (int i = 0; i < byReading.size(); i++) {
                assertEquals(byReading.get(i).startEpochMs, byNames.get(i).startEpochMs);
                assertEquals(byReading.get(i).segmentCount(), byNames.get(i).segmentCount());
                long longer = byNames.get(i).totalDurationMs - byReading.get(i).totalDurationMs;
                assertTrue("每段只比读出来的长零点几秒：" + longer,
                        longer >= 0 && longer <= 800L * byReading.get(i).segmentCount());
            }
            assertEquals("要打开的只有最新的那一个", 1, read);
        }
    }

    /**
     * 文件时间不对时会算错，记在这里（所以开发者选项里留着「逐个读取片段时长」）：一条录制的最后一段录了 20 秒，
     * 修改时间却晚了 27 秒，离 30 秒后开录的下一条只有 3 秒 —— 照文件时间算，两条并成了一条；打开读是两条。
     */
    @Test
    public void aWrongFileTimeCanJoinTwoRecordings() {
        long t = 5_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("first", t, t + 20_000 + 27_000));
        lane.add(clip("second", t + 50_000, t + 50_000 + MINUTE));
        lane.add(clip("newest", t + 50_000 + MINUTE, t + 50_000 + 2 * MINUTE));
        Map<String, Long> known = ClipSpans.known(lane);
        assertEquals(Long.valueOf(47_000L), known.get("first"));

        List<RecordingTimeline.Source> byTime = new ArrayList<>();
        byTime.add(new RecordingTimeline.Source("first", t, known.get("first")));
        byTime.add(new RecordingTimeline.Source("second", t + 50_000, known.get("second")));
        assertEquals(1, RecordingTimeline.build(byTime).size());

        List<RecordingTimeline.Source> byReading = new ArrayList<>();
        byReading.add(new RecordingTimeline.Source("first", t, 20_000));
        byReading.add(new RecordingTimeline.Source("second", t + 50_000, MINUTE));
        assertEquals(2, RecordingTimeline.build(byReading).size());
    }
}
