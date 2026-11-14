/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 录像回放的扫描，分阶段交结果：最新的那一条录制先出来，更早的一批一批补（项目所有者 2026-11-14）。
 *
 * <h3>为什么要分阶段</h3>
 *
 * <p>列目录、从文件名里读出是哪一路、几点开录的，都快。慢的只有一步：每个文件的时长要单独打开读一次
 * （{@link ClipDurations}）。原来是全部读完、全部分好条才显示 —— U 盘上存着几千个文件时，打开回放要等很久，
 * 而人多半只想看最近的那一条。</p>
 *
 * <h3>怎么分</h3>
 *
 * <ol>
 *   <li>环视的文件按开录时刻从新到旧一个一个读时长。两个文件之间隔得超过 {@code gapMs}，前面攒的那几个就是完整的一条录制
 *       （和 {@link RecordingTimeline#build} 是同一条界线，只是倒着走）；</li>
 *   <li>一条录制一完整，就把别的几路里和它挨得着的文件（开头在它前后 {@code reachMs} 之内的）也读了，交出去一次 ——
 *       第一条（最新的）马上交，后面的隔 {@code minIntervalMs} 以上才交一次，免得界面一直在刷；</li>
 *   <li>环视都读完了，把别的几路剩下的文件读完，交最后一次（{@link Batch#done}）。</li>
 * </ol>
 *
 * <h3>交出去的是什么</h3>
 *
 * <p>到这一刻为止读到的全部：完整的那几条录制的环视文件、别的几路已经读过的文件。接的人照原来的办法分条、分归属
 * （{@code RecordingTimeline.build}、{@code LaneTrack.byRecording}）—— 最后一次交的和一口气扫完的一模一样
 * （测试钉着）。中间那几次，最旧的那一条和它更早的邻居之间的文件归谁还可能变（邻居还没读到），所以没交完之前
 * 接的人不能照它删文件；看、播、锁定不受影响。</p>
 *
 * <p>不碰安卓的东西：读时长、看钟、要不要停都是传进来的，测试里换成假的。</p>
 */
final class TimelineScan {

    /** 目录里的一个录像文件：名字里读得出来的那些，时长还没读。 */
    static final class Named {
        final String path;
        final long startEpochMs;
        final long sizeBytes;

        Named(String path, long startEpochMs, long sizeBytes) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.sizeBytes = sizeBytes;
        }
    }

    /** 读一个文件的时长（毫秒）；读不出来给 ≤ 0，这个文件就跳过。 */
    interface Durations {
        long of(String path);
    }

    /** 交一次结果（在扫描的线程上叫）。 */
    interface Sink {
        void accept(Batch batch);
    }

    /** 要不要停（界面关了、又重新扫了）：每读一个文件之前问一次。 */
    interface Cancelled {
        boolean get();
    }

    /** 现在几点（毫秒，只用来算隔了多久）。 */
    interface Clock {
        long now();
    }

    /** 交出去的一次：到这一刻为止读到的全部（每次都是新的一份，接的人可以留着）。 */
    static final class Batch {
        /** 已经完整的那几条录制的环视文件（没排序）。 */
        final List<RecordingTimeline.Source> composite;
        /** 别的几路读过时长的文件，按槽位分。 */
        final Map<String, List<RecordingTimeline.Source>> others;
        /** 都读完了：这是最后一次。 */
        final boolean done;

        Batch(List<RecordingTimeline.Source> composite, Map<String, List<RecordingTimeline.Source>> others,
              boolean done) {
            this.composite = composite;
            this.others = others;
            this.done = done;
        }
    }

    private TimelineScan() {
    }

    /**
     * 扫一遍（规矩见类说明）。被叫停了就直接返回，不再交。
     *
     * @param composite     环视的文件
     * @param others        别的几路的文件，按槽位分
     * @param gapMs         两个环视文件隔多远算两条录制（{@link RecordingTimeline#DEFAULT_MAX_GAP_MS}）
     * @param reachMs       别的几路的文件，开头离一条录制多远之内先读：要盖住一个分段的时长，再宽一点
     * @param minIntervalMs 第一次之后，两次交结果至少隔多久
     */
    static void run(List<Named> composite, Map<String, List<Named>> others, long gapMs, long reachMs,
                    long minIntervalMs, Durations durations, Clock clock, Cancelled cancelled, Sink sink) {
        List<Named> newestFirst = new ArrayList<>(composite);
        Collections.sort(newestFirst, (a, b) -> Long.compare(b.startEpochMs, a.startEpochMs));
        Map<String, Lane> lanes = new HashMap<>();
        for (Map.Entry<String, List<Named>> entry : others.entrySet()) {
            lanes.put(entry.getKey(), new Lane(entry.getValue()));
        }

        List<RecordingTimeline.Source> closed = new ArrayList<>();
        List<RecordingTimeline.Source> open = new ArrayList<>();  // 正在攒的那一条（从新到旧）
        long openStart = 0L;  // 攒着的那一条里最早的开录时刻
        long openEnd = 0L;    // 攒着的那一条里最晚的结束时刻
        boolean sent = false;
        long lastSent = 0L;

        for (Named named : newestFirst) {
            if (cancelled.get()) {
                return;
            }
            long duration = durations.of(named.path);
            if (duration <= 0) {
                continue;  // 时长读不出来的跳过（同 RecordingTimeline.build）
            }
            long end = named.startEpochMs + duration;
            if (!open.isEmpty() && openStart - end > gapMs) {
                // 和后面那个隔开了：攒着的这一条完整了
                closed.addAll(open);
                open.clear();
                if (!readNear(lanes, openStart - reachMs, openEnd + reachMs, durations, cancelled)) {
                    return;
                }
                if (!sent || clock.now() - lastSent >= minIntervalMs) {
                    sink.accept(new Batch(new ArrayList<>(closed), snapshot(lanes), false));
                    sent = true;
                    lastSent = clock.now();
                }
            }
            if (open.isEmpty()) {
                openEnd = end;
            } else {
                openEnd = Math.max(openEnd, end);
            }
            openStart = named.startEpochMs;
            open.add(new RecordingTimeline.Source(named.path, named.startEpochMs, duration, named.sizeBytes));
        }
        closed.addAll(open);
        // 别的几路剩下的都读完：归属、大小要照全部文件算
        if (!readNear(lanes, Long.MIN_VALUE, Long.MAX_VALUE, durations, cancelled)) {
            return;
        }
        sink.accept(new Batch(new ArrayList<>(closed), snapshot(lanes), true));
    }

    /** 别的几路里开头落在这一段时间里、还没读过的文件，读一遍。被叫停了返回 false。 */
    private static boolean readNear(Map<String, Lane> lanes, long from, long to, Durations durations,
                                    Cancelled cancelled) {
        for (Lane lane : lanes.values()) {
            for (int i = 0; i < lane.files.size(); i++) {
                if (lane.read[i]) {
                    continue;
                }
                Named named = lane.files.get(i);
                if (named.startEpochMs < from || named.startEpochMs > to) {
                    continue;
                }
                if (cancelled.get()) {
                    return false;
                }
                lane.read[i] = true;
                long duration = durations.of(named.path);
                if (duration > 0) {
                    lane.measured.add(new RecordingTimeline.Source(named.path, named.startEpochMs, duration,
                            named.sizeBytes));
                }
            }
        }
        return true;
    }

    private static Map<String, List<RecordingTimeline.Source>> snapshot(Map<String, Lane> lanes) {
        Map<String, List<RecordingTimeline.Source>> out = new HashMap<>();
        for (Map.Entry<String, Lane> entry : lanes.entrySet()) {
            if (!entry.getValue().measured.isEmpty()) {
                out.put(entry.getKey(), new ArrayList<>(entry.getValue().measured));
            }
        }
        return out;
    }

    /** 别的一路：它的文件、哪些读过了、读出时长的那些。 */
    private static final class Lane {
        final List<Named> files;
        final boolean[] read;
        final List<RecordingTimeline.Source> measured = new ArrayList<>();

        Lane(List<Named> files) {
            this.files = files;
            this.read = new boolean[files.size()];
        }
    }
}
