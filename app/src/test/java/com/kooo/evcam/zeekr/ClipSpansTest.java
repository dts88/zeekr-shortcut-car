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
 * 不打开文件就知道时长的那些分段（{@link ClipSpans}，项目所有者 2026-11-14）：同一路里，修改时间和下一段的开录时刻
 * 对得上的，时长算到下一段开头；对不上的不给，照旧打开读。时刻都是编的。
 */
public class ClipSpansTest {

    private static final long MINUTE = 60_000L;

    private static ClipSpans.Timed clip(String path, long start, long modified) {
        return new ClipSpans.Timed(path, start, modified);
    }

    /** 连着录的三段：前两段的修改时间挨着下一段的开头，时长就是两段开头的差；最后一段后面没有了，不给。 */
    @Test
    public void aClipThatRunsIntoTheNextOneIsKnown() {
        long t = 1_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("a", t, t + MINUTE + 100));               // 封好比下一段开录晚 0.1 秒
        lane.add(clip("b", t + MINUTE, t + 2 * MINUTE - 300));   // 早 0.3 秒
        lane.add(clip("c", t + 2 * MINUTE, t + 2 * MINUTE + 41_000));
        Map<String, Long> known = ClipSpans.known(lane);
        assertEquals(Long.valueOf(MINUTE), known.get("a"));
        assertEquals(Long.valueOf(MINUTE), known.get("b"));
        assertFalse("最后一段要打开读", known.containsKey("c"));
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
     * 对不上的不给：中间停过（修改时间比下一段的开头早得多）、文件后来被动过（晚得多）、没有修改时间、
     * 两段隔得比一个分段还长、两个文件同一刻开录。
     */
    @Test
    public void anythingThatDoesNotLineUpIsLeftToBeRead() {
        long t = 3_000_000_000L;
        List<ClipSpans.Timed> lane = new ArrayList<>();
        lane.add(clip("stopped", t, t + 20_000));                              // 录了 20 秒就停了，40 秒后才又开录
        lane.add(clip("touched", t + MINUTE, t + 2 * MINUTE + 25_000));        // 修改时间晚了 25 秒
        lane.add(clip("no-time", t + 2 * MINUTE, 0));
        lane.add(clip("long-gap", t + 3 * MINUTE, t + 4 * MINUTE));            // 下一段在半小时以后
        lane.add(clip("twin-1", t + 33 * MINUTE, t + 34 * MINUTE));
        lane.add(clip("twin-2", t + 33 * MINUTE, t + 34 * MINUTE));
        lane.add(clip("edge-in", t + 34 * MINUTE, t + 35 * MINUTE + ClipSpans.TOLERANCE_MS));
        lane.add(clip("edge-out", t + 35 * MINUTE, t + 36 * MINUTE + ClipSpans.TOLERANCE_MS + 1));
        lane.add(clip("last", t + 36 * MINUTE, t + 37 * MINUTE));
        Map<String, Long> known = ClipSpans.known(lane);
        assertFalse(known.containsKey("stopped"));
        assertFalse(known.containsKey("touched"));
        assertFalse(known.containsKey("no-time"));
        assertFalse(known.containsKey("long-gap"));
        assertFalse("同一刻开录的两个：前一个到后一个的差是 0", known.containsKey("twin-1"));
        assertEquals("差正好在线上：算", Long.valueOf(MINUTE), known.get("edge-in"));
        assertFalse("过线一毫秒：不算", known.containsKey("edge-out"));
        assertFalse(known.containsKey("last"));
        assertTrue(ClipSpans.known(new ArrayList<>()).isEmpty());
        assertTrue(ClipSpans.known(Collections.singletonList(clip("only", t, t + MINUTE))).isEmpty());
    }

    /**
     * 照这个办法定时长（对得上的算到下一段开头，对不上的用读出来的），分出来的条和全部打开读一遍分出来的一样：
     * 一样的几条、每条一样多段。照实车的样子编：换段空零点几秒，封文件晚零点几秒，录制之间隔几秒到几小时，
     * 偶尔有个文件的修改时间不对。
     */
    @Test
    public void groupingMatchesReadingEveryFile() {
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
                    if (random.nextInt(200) == 0) {
                        modified += 25_000;  // 偶尔一个不对的
                    }
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
                assertTrue("每条只比读出来的长出换段的那点空：" + longer,
                        longer >= 0 && longer <= 500L * byReading.get(i).segmentCount());
            }
            assertTrue("要打开读的只有每条的最后一段和个别对不上的：" + read + " / " + exact.size(),
                    read <= recordings + exact.size() / 50 + 2);
        }
    }
}
