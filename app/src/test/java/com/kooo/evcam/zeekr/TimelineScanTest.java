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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 录像回放的分阶段扫描（{@link TimelineScan}，项目所有者 2026-11-14）：最新的那一条录制先交出来，更早的一批一批补，
 * 最后一次交的和一口气扫完的一模一样。时刻、时长都是编的。
 */
public class TimelineScanTest {

    private static final long GAP = RecordingTimeline.DEFAULT_MAX_GAP_MS;
    private static final long MINUTE = 60_000L;
    private static final long REACH = 11 * MINUTE;

    /** 假的一盘录像：文件、每个文件的时长；记着读过哪些、按什么次序。 */
    private static final class Disk {
        final List<TimelineScan.Named> composite = new ArrayList<>();
        final Map<String, List<TimelineScan.Named>> others = new HashMap<>();
        final Map<String, Long> durations = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        long clock;
        int stopAfterReads = Integer.MAX_VALUE;
        int serial;

        /** 从 start 起连着录 count 段，每段 length；返回这一条结束的时刻。 */
        long record(String lane, long start, int count, long length) {
            for (int i = 0; i < count; i++) {
                // 名字带个流水号：随机编的几条录制里，同一路可能有两个文件正好同一刻开录
                String path = lane + "@" + (start + i * length) + "#" + (serial++);
                TimelineScan.Named named = new TimelineScan.Named(path, start + i * length, 1000);
                if ("surround".equals(lane)) {
                    composite.add(named);
                } else {
                    List<TimelineScan.Named> list = others.get(lane);
                    if (list == null) {
                        list = new ArrayList<>();
                        others.put(lane, list);
                    }
                    list.add(named);
                }
                durations.put(path, length - 200);  // 换段有零点几秒的空
            }
            return start + count * length;
        }

        List<TimelineScan.Batch> scan(long minInterval) {
            List<TimelineScan.Batch> batches = new ArrayList<>();
            TimelineScan.run(composite, others, GAP, REACH, minInterval, path -> {
                reads.add(path);
                clock += 30;  // 读一个文件 30 ms
                Long known = durations.get(path);
                return known == null ? -1L : known;
            }, () -> clock, () -> reads.size() >= stopAfterReads, batches::add);
            return batches;
        }
    }

    private static List<Long> starts(List<RecordingTimeline.Session> sessions) {
        List<Long> out = new ArrayList<>();
        for (RecordingTimeline.Session session : sessions) {
            out.add(session.startEpochMs);
        }
        return out;
    }

    private static List<String> paths(List<RecordingTimeline.Source> sources) {
        List<String> out = new ArrayList<>();
        for (RecordingTimeline.Source source : sources) {
            out.add(source.path);
        }
        Collections.sort(out);
        return out;
    }

    /** 三条录制：最新的那一条先交，而且只多读了一个文件（隔开它的那个）就交了。 */
    @Test
    public void theNewestRecordingComesFirst() {
        Disk disk = new Disk();
        long day = 1_000_000_000L;
        disk.record("surround", day, 50, MINUTE);
        disk.record("surround", day + 5 * 60 * MINUTE, 30, MINUTE);
        long newest = day + 10 * 60 * MINUTE;
        disk.record("surround", newest, 4, MINUTE);
        List<TimelineScan.Batch> batches = disk.scan(0);

        TimelineScan.Batch first = batches.get(0);
        assertFalse(first.done);
        List<RecordingTimeline.Session> built = RecordingTimeline.build(first.composite);
        assertEquals("头一次只有最新的那一条", Collections.singletonList(newest), starts(built));
        assertEquals("四段都在", 4, built.get(0).segmentCount());
        // 读到第 5 个文件（上一条的最后一段）才知道隔开了：头一次交之前只读了 5 个
        int readsBeforeFirst = -1;
        for (int i = 0; i < disk.reads.size(); i++) {
            if (disk.reads.get(i).startsWith("surround@" + (day + 5 * 60 * MINUTE + 28 * MINUTE) + "#")) {
                readsBeforeFirst = i;
            }
        }
        assertEquals("头一次交之前读的文件：最新一条的 4 个，加隔开它的那 1 个", 5, readsBeforeFirst);
    }

    /** 后面的每一次只在前面补更早的录制：已经交过的那几条原样在后面。最后一次是全部。 */
    @Test
    public void laterBatchesOnlyAddOlderRecordings() {
        Disk disk = new Disk();
        long at = 2_000_000_000L;
        for (int i = 0; i < 8; i++) {
            at = disk.record("surround", at, 3 + i, MINUTE) + 30 * MINUTE;
        }
        List<TimelineScan.Batch> batches = disk.scan(0);
        assertEquals("八条录制：七次中间的、一次最后的", 8, batches.size());
        List<Long> previous = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<Long> now = starts(RecordingTimeline.build(batches.get(i).composite));
            assertEquals("第 " + (i + 1) + " 次有几条", i + 1, now.size());
            assertEquals("已经交过的原样在后面", previous, now.subList(now.size() - previous.size(), now.size()));
            assertEquals("只有最后一次说扫完了", i == batches.size() - 1, batches.get(i).done);
            previous = now;
        }
    }

    /** 第一次之后隔够了才交：读得快的时候不是每一条都刷一次界面。最后一次总会交。 */
    @Test
    public void batchesAreSpacedOut() {
        Disk disk = new Disk();
        long at = 3_000_000_000L;
        for (int i = 0; i < 20; i++) {
            at = disk.record("surround", at, 2, MINUTE) + 30 * MINUTE;  // 每条两段，读一条 60 ms
        }
        List<TimelineScan.Batch> batches = disk.scan(500);
        assertTrue("二十条录制，交的次数少得多：" + batches.size(), batches.size() >= 3 && batches.size() <= 6);
        assertEquals("头一次只有最新的一条", 1, RecordingTimeline.build(batches.get(0).composite).size());
        TimelineScan.Batch last = batches.get(batches.size() - 1);
        assertTrue(last.done);
        assertEquals(20, RecordingTimeline.build(last.composite).size());
    }

    /** 别的几路：挨着这一条录制的先读、跟着它一起交；离得远的留到最后。最后一次是全部读得出时长的。 */
    @Test
    public void otherLanesNearARecordingComeWithIt() {
        Disk disk = new Disk();
        long old = 4_000_000_000L;
        disk.record("surround", old, 5, MINUTE);
        disk.record("cabin", old, 5, MINUTE);
        long newest = old + 24 * 60 * MINUTE;
        disk.record("surround", newest, 3, MINUTE);
        disk.record("cabin", newest - 2 * MINUTE, 5, MINUTE);  // 环视加入之前座舱已经在录
        List<TimelineScan.Batch> batches = disk.scan(0);

        TimelineScan.Batch first = batches.get(0);
        assertEquals("头一次带着最新那一条的座舱文件，早开录的两个也在", 5, first.others.get("cabin").size());
        for (RecordingTimeline.Source source : first.others.get("cabin")) {
            assertTrue("头一次没有昨天的座舱文件", source.startEpochMs >= newest - 2 * MINUTE);
        }
        TimelineScan.Batch last = batches.get(batches.size() - 1);
        assertTrue(last.done);
        assertEquals("最后一次是全部", 10, last.others.get("cabin").size());
    }

    /** 最后一次交的，和不分阶段、把每个文件都读一遍得到的一模一样 —— 随便怎么录、怎么断、有没有读不出时长的。 */
    @Test
    public void theFinalBatchEqualsAPlainFullScan() {
        Random random = new Random(20261114);
        for (int round = 0; round < 300; round++) {
            Disk disk = new Disk();
            long at = 5_000_000_000L;
            int recordings = 1 + random.nextInt(12);
            for (int i = 0; i < recordings; i++) {
                long length = (1 + random.nextInt(3)) * MINUTE;
                int count = 1 + random.nextInt(15);
                long end = disk.record("surround", at, count, length);
                if (random.nextBoolean()) {
                    disk.record("cabin", at - random.nextInt(3) * MINUTE, count + random.nextInt(3), length);
                }
                if (random.nextInt(3) == 0) {
                    disk.record("front", at + random.nextInt(2) * length, Math.max(1, count - 1), length);
                }
                // 下一条隔多远：有刚过界线的、有贴着界线的、有隔半天的
                at = end + new long[] {GAP + 1 - 200, GAP - 200, 20_000, 3 * MINUTE, 12 * 60 * MINUTE}[random.nextInt(5)];
            }
            // 几个读不出时长的（断电留下的半截文件）
            for (TimelineScan.Named named : new ArrayList<>(disk.composite)) {
                if (random.nextInt(25) == 0) {
                    disk.durations.remove(named.path);
                }
            }

            // 不分阶段的：每个文件读一遍
            List<RecordingTimeline.Source> allComposite = new ArrayList<>();
            for (TimelineScan.Named named : disk.composite) {
                Long d = disk.durations.get(named.path);
                if (d != null) {
                    allComposite.add(new RecordingTimeline.Source(named.path, named.startEpochMs, d, named.sizeBytes));
                }
            }
            List<RecordingTimeline.Session> plain = RecordingTimeline.build(allComposite);

            List<TimelineScan.Batch> batches = disk.scan(random.nextBoolean() ? 0 : 200);
            TimelineScan.Batch last = batches.get(batches.size() - 1);
            assertTrue(last.done);
            List<RecordingTimeline.Session> staged = RecordingTimeline.build(last.composite);
            assertEquals("第 " + round + " 轮：分出来的条", starts(plain), starts(staged));
            for (int i = 0; i < plain.size(); i++) {
                assertEquals(plain.get(i).totalDurationMs, staged.get(i).totalDurationMs);
                assertEquals(plain.get(i).segmentCount(), staged.get(i).segmentCount());
            }
            assertEquals(paths(allComposite), paths(last.composite));
            for (Map.Entry<String, List<TimelineScan.Named>> lane : disk.others.entrySet()) {
                assertEquals("别的几路也是全部", lane.getValue().size(), last.others.get(lane.getKey()).size());
            }
            // 中间每一次：交出来的都是完整的条，而且就是最后结果里最新的那几条
            for (TimelineScan.Batch batch : batches) {
                List<Long> now = starts(RecordingTimeline.build(batch.composite));
                List<Long> all = starts(staged);
                assertEquals("第 " + round + " 轮：中间交的是最后结果的尾巴", all.subList(all.size() - now.size(), all.size()), now);
                List<RecordingTimeline.Session> partial = RecordingTimeline.build(batch.composite);
                for (int i = 0; i < partial.size(); i++) {
                    RecordingTimeline.Session whole = staged.get(staged.size() - partial.size() + i);
                    assertEquals("中间交的每一条都是完整的", whole.segmentCount(), partial.get(i).segmentCount());
                }
            }
            assertEquals("每个文件只读一次", disk.reads.size(), new java.util.HashSet<>(disk.reads).size());
        }
    }

    /** 叫停了就停：不再读、不再交（界面关了、又重新扫了）。 */
    @Test
    public void stopsWhenCancelled() {
        Disk disk = new Disk();
        long at = 6_000_000_000L;
        for (int i = 0; i < 10; i++) {
            at = disk.record("surround", at, 10, MINUTE) + 30 * MINUTE;
        }
        disk.stopAfterReads = 25;
        List<TimelineScan.Batch> batches = disk.scan(0);
        assertEquals("读到第 25 个就停了", 25, disk.reads.size());
        for (TimelineScan.Batch batch : batches) {
            assertFalse("没扫完，不会说扫完了", batch.done);
        }
        assertEquals("停之前完整的两条交过了", 2, batches.size());
    }

    /** 一个文件都没有、只有读不出时长的：交一次空的、说扫完了。 */
    @Test
    public void nothingToShowStillFinishes() {
        Disk empty = new Disk();
        List<TimelineScan.Batch> batches = empty.scan(0);
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).done);
        assertTrue(batches.get(0).composite.isEmpty());

        Disk broken = new Disk();
        broken.record("surround", 7_000_000_000L, 3, MINUTE);
        broken.durations.clear();
        batches = broken.scan(0);
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).done);
        assertTrue(batches.get(0).composite.isEmpty());
    }
}
