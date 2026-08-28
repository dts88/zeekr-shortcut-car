package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把一串分段录像拼成一条连续时间轴。
 *
 * <p>现在的回放是一堆文件挨个找、挨个放。行车记录仪的实际使用方式却是
 * 「我想看 20 分钟前那一段」—— 用户不关心它落在第几个文件里。
 * 这个类把连续录制的分段合并成一条虚拟时间轴，拖动进度条时可以跨文件定位。</p>
 *
 * <p>纯逻辑，不碰 Android API，可以直接跑 JVM 单元测试 —— 时间轴换算错了
 * 表现就是"拖到哪播到哪不对"，很难靠肉眼在车上发现，所以这部分必须能测。</p>
 */
public final class RecordingTimeline {

    /**
     * 相邻两段之间允许的最大空隙。
     *
     * <p>分段切换本身有零点几秒的写文件间隙，所以不能要求严丝合缝；
     * 但停止录制再重新开始会留下明显的断档，那属于两次不同的录制，
     * 不该接在同一条时间轴上。</p>
     */
    public static final long DEFAULT_MAX_GAP_MS = 5_000L;

    private RecordingTimeline() {
    }

    /** 时间轴上的一段，对应一个文件。 */
    public static final class Segment {
        /** 文件路径，播放时用。 */
        public final String path;
        /** 录制起始时刻（毫秒），来自文件名。 */
        public final long startEpochMs;
        /** 该段时长（毫秒）。 */
        public final long durationMs;
        /** 该段在所属时间轴上的起始偏移（毫秒）。 */
        public final long timelineOffsetMs;

        Segment(String path, long startEpochMs, long durationMs, long timelineOffsetMs) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.durationMs = durationMs;
            this.timelineOffsetMs = timelineOffsetMs;
        }

        /** 该段在时间轴上的结束偏移（不含）。 */
        public long timelineEndMs() {
            return timelineOffsetMs + durationMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Segment[%s +%dms len=%dms]",
                    path, timelineOffsetMs, durationMs);
        }
    }

    /** 一次连续录制：若干首尾相接的分段。 */
    public static final class Session {
        public final List<Segment> segments;
        /** 整条时间轴的总长度（毫秒）。 */
        public final long totalDurationMs;
        /** 第一段的起始时刻，用于给会话命名。 */
        public final long startEpochMs;

        Session(List<Segment> segments, long totalDurationMs, long startEpochMs) {
            this.segments = Collections.unmodifiableList(segments);
            this.totalDurationMs = totalDurationMs;
            this.startEpochMs = startEpochMs;
        }

        public int segmentCount() {
            return segments.size();
        }

        /**
         * 把时间轴上的位置换算成「哪个文件 + 文件内偏移」。
         *
         * @param positionMs 时间轴位置，会被夹到 [0, totalDurationMs)
         * @return 定位结果；会话为空时返回 null
         */
        public Locator locate(long positionMs) {
            if (segments.isEmpty()) {
                return null;
            }
            long clamped = Math.max(0L, Math.min(positionMs, Math.max(0L, totalDurationMs - 1)));
            // 段数不多（一次录制通常几十段），线性扫描足够，且不易写错
            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                if (clamped < seg.timelineEndMs()) {
                    return new Locator(i, seg, clamped - seg.timelineOffsetMs);
                }
            }
            Segment last = segments.get(segments.size() - 1);
            return new Locator(segments.size() - 1, last, Math.max(0L, last.durationMs - 1));
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Session[%d segments, %dms]",
                    segments.size(), totalDurationMs);
        }
    }

    /** {@link Session#locate} 的结果。 */
    public static final class Locator {
        public final int segmentIndex;
        public final Segment segment;
        /** 在该文件内部的偏移（毫秒）。 */
        public final long offsetInSegmentMs;

        Locator(int segmentIndex, Segment segment, long offsetInSegmentMs) {
            this.segmentIndex = segmentIndex;
            this.segment = segment;
            this.offsetInSegmentMs = offsetInSegmentMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Locator[#%d %s +%dms]",
                    segmentIndex, segment.path, offsetInSegmentMs);
        }
    }

    /** 构建时间轴的输入：一个文件的路径、起始时刻与时长。 */
    public static final class Source {
        public final String path;
        public final long startEpochMs;
        public final long durationMs;

        public Source(String path, long startEpochMs, long durationMs) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.durationMs = durationMs;
        }
    }

    /**
     * 把一批分段按时间排序，并切分成若干条连续时间轴。
     *
     * <p>相邻两段的空隙小于 {@code maxGapMs} 就算连续，接在同一条时间轴上；
     * 超过就另起一条 —— 那通常意味着中间停过录制。</p>
     *
     * @param sources  分段列表，顺序不限
     * @param maxGapMs 允许的最大空隙；传 &lt;= 0 用 {@link #DEFAULT_MAX_GAP_MS}
     * @return 按时间先后排列的会话；输入为空时返回空列表
     */
    public static List<Session> build(List<Source> sources, long maxGapMs) {
        List<Session> sessions = new ArrayList<>();
        if (sources == null || sources.isEmpty()) {
            return sessions;
        }
        final long gapLimit = maxGapMs > 0 ? maxGapMs : DEFAULT_MAX_GAP_MS;

        List<Source> sorted = new ArrayList<>(sources);
        Collections.sort(sorted, new Comparator<Source>() {
            @Override
            public int compare(Source a, Source b) {
                return Long.compare(a.startEpochMs, b.startEpochMs);
            }
        });

        List<Segment> current = new ArrayList<>();
        long offset = 0L;
        long sessionStart = 0L;
        long previousEndEpoch = 0L;

        for (Source src : sorted) {
            if (src == null || src.durationMs <= 0) {
                continue;  // 时长读不出来的文件跳过，宁可少一段也不要把时间轴算错
            }
            boolean startsNewSession = current.isEmpty()
                    || (src.startEpochMs - previousEndEpoch) > gapLimit;

            if (startsNewSession && !current.isEmpty()) {
                sessions.add(new Session(current, offset, sessionStart));
                current = new ArrayList<>();
                offset = 0L;
            }
            if (current.isEmpty()) {
                sessionStart = src.startEpochMs;
            }

            current.add(new Segment(src.path, src.startEpochMs, src.durationMs, offset));
            offset += src.durationMs;
            previousEndEpoch = src.startEpochMs + src.durationMs;
        }

        if (!current.isEmpty()) {
            sessions.add(new Session(current, offset, sessionStart));
        }
        return sessions;
    }

    /** @see #build(List, long) */
    public static List<Session> build(List<Source> sources) {
        return build(sources, DEFAULT_MAX_GAP_MS);
    }

    /**
     * 从 {@code yyyyMMdd_HHmmss} 开头的文件名里解析起始时刻。
     *
     * <p>录制文件名形如 {@code 20260828_221530_front.mp4}。</p>
     *
     * @return 毫秒时间戳；解析不出来返回 -1
     */
    public static long parseStartEpochMs(String fileName) {
        if (fileName == null || fileName.length() < 15) {
            return -1L;
        }
        String stamp = fileName.substring(0, 15);
        if (stamp.charAt(8) != '_') {
            return -1L;
        }
        try {
            int year = Integer.parseInt(stamp.substring(0, 4));
            int month = Integer.parseInt(stamp.substring(4, 6));
            int day = Integer.parseInt(stamp.substring(6, 8));
            int hour = Integer.parseInt(stamp.substring(9, 11));
            int minute = Integer.parseInt(stamp.substring(11, 13));
            int second = Integer.parseInt(stamp.substring(13, 15));
            if (month < 1 || month > 12 || day < 1 || day > 31
                    || hour > 23 || minute > 59 || second > 59) {
                return -1L;
            }
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.clear();
            cal.set(year, month - 1, day, hour, minute, second);
            return cal.getTimeInMillis();
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
