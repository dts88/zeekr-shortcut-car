package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一路相机的录像文件，按真实时刻排好。
 *
 * <p>连续回放的时间轴按环视那一路排（{@link RecordingTimeline}）。座舱各路的文件
 * 自己分段 —— 分段时长可以和环视不一样，开始录的时刻也差个一两秒 —— 所以不能按
 * 「第几段」去对，只能按<b>真实时刻</b>去对：环视播到哪一刻，就找这一路里装着
 * 那一刻的文件、文件里的偏移。</p>
 *
 * <p>纯逻辑，不碰 Android API，可以直接跑 JVM 单元测试。</p>
 */
public final class LaneTrack {

    /** 一个文件。 */
    public static final class Clip {
        public final String path;
        public final long startEpochMs;
        public final long durationMs;
        public final long sizeBytes;

        Clip(String path, long startEpochMs, long durationMs, long sizeBytes) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
        }

        public long endEpochMs() {
            return startEpochMs + durationMs;
        }
    }

    /** {@link #at} 的结果：第几个文件，文件里的偏移。 */
    public static final class Hit {
        public final int index;
        public final Clip clip;
        public final long offsetMs;

        Hit(int index, Clip clip, long offsetMs) {
            this.index = index;
            this.clip = clip;
            this.offsetMs = offsetMs;
        }
    }

    public static final LaneTrack EMPTY = new LaneTrack(new ArrayList<>());

    private final List<Clip> clips;

    private LaneTrack(List<Clip> sorted) {
        this.clips = Collections.unmodifiableList(sorted);
    }

    /** 由一批文件建起来；顺序不限，时长读不出来的跳过。 */
    public static LaneTrack of(List<RecordingTimeline.Source> sources) {
        List<Clip> list = new ArrayList<>();
        if (sources != null) {
            for (RecordingTimeline.Source source : sources) {
                if (source != null && source.durationMs > 0) {
                    list.add(new Clip(source.path, source.startEpochMs, source.durationMs,
                            source.sizeBytes));
                }
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(a.startEpochMs, b.startEpochMs));
        return new LaneTrack(list);
    }

    /** 环视那一路：就是这条时间轴自己的分段。 */
    public static LaneTrack of(RecordingTimeline.Session session) {
        List<Clip> list = new ArrayList<>();
        for (RecordingTimeline.Segment segment : session.segments) {
            list.add(new Clip(segment.path, segment.startEpochMs, segment.durationMs,
                    segment.sizeBytes));
        }
        return new LaneTrack(list);
    }

    /**
     * 这一条录制的画面上要放的文件：和它的时间段有重叠的那些（2026-10-10）。
     *
     * <p>按重叠算，不按开头算。一路相机被别的程序占用时只停那一路、其余照录，放开后单独接回
     * —— 停的也可能是环视。这时座舱的文件可能在环视重新开录之前就开头了；按开头算它不归这一条，
     * 环视开录后的那几十秒里座舱那一格就写着「该路此时无录像」，其实录像在。</p>
     *
     * <p>按重叠算，一个文件可能在相邻两条录制里都露面，所以删除、分享、算大小不用它，
     * 用 {@link #belongingTo}：每个文件恰好归一条。</p>
     */
    public LaneTrack shownIn(RecordingTimeline.Session session) {
        long from = session.startEpochMs;
        long to = session.endEpochMs();
        List<Clip> list = new ArrayList<>();
        for (Clip clip : clips) {
            if (clip.endEpochMs() > from && clip.startEpochMs < to) {
                list.add(clip);
            }
        }
        return new LaneTrack(list);
    }

    /**
     * 归第 index 条录制的文件：删除、分享、大小、「含已锁定文件」都按它算（2026-10-10）。
     *
     * <p>每个文件<b>恰好归一条</b>：开头之前最近开录的那一条；比第一条还早的归第一条。
     * 开头往前放宽 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS} —— 几路相机不是同一刻开录的，
     * 座舱那一路比环视早一两秒开头很正常，它仍然是这一次录的。</p>
     *
     * <p>以前只收开头落在这一条时间段里的。环视停着、座舱照录的那段时间里录下的文件于是
     * 不归任何一条：回放里删不掉、不算大小，锁上了列表里也不标 —— 只能等自动清理。
     * 现在它归前一条，和前一条一起删。它在环视之外的那一截不在任何一条的画面上
     * （进度条跟着环视），这是有意的。</p>
     *
     * @param sessions 全部录制，按先后排（{@link RecordingTimeline#build} 的结果）
     */
    public LaneTrack belongingTo(List<RecordingTimeline.Session> sessions, int index) {
        long gap = RecordingTimeline.DEFAULT_MAX_GAP_MS;
        long from = index == 0 ? Long.MIN_VALUE : sessions.get(index).startEpochMs - gap;
        long to = index == sessions.size() - 1
                ? Long.MAX_VALUE : sessions.get(index + 1).startEpochMs - gap;
        List<Clip> list = new ArrayList<>();
        for (Clip clip : clips) {
            if (clip.startEpochMs >= from && clip.startEpochMs < to) {
                list.add(clip);
            }
        }
        return new LaneTrack(list);
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    public int size() {
        return clips.size();
    }

    public Clip clip(int index) {
        return clips.get(index);
    }

    public List<Clip> clips() {
        return clips;
    }

    public long totalBytes() {
        long bytes = 0L;
        for (Clip clip : clips) {
            bytes += clip.sizeBytes;
        }
        return bytes;
    }

    /**
     * 装着这一刻的那个文件。
     *
     * @return 这一刻这一路没有录像（还没开始录、中间断过、已经停了）时返回 null
     */
    public Hit at(long epochMs) {
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            if (epochMs >= clip.startEpochMs && epochMs < clip.endEpochMs()) {
                return new Hit(i, clip, epochMs - clip.startEpochMs);
            }
        }
        return null;
    }

    /**
     * 装着这一刻的文件；这一刻落在空隙里，就是空隙之后的第一个文件的开头。
     *
     * <p>领着时间走的那一路用它：它要是停在空隙里，整个画面就都停了。</p>
     *
     * @return 这一刻之后再也没有文件时返回 null
     */
    public Hit atOrAfter(long epochMs) {
        Hit hit = at(epochMs);
        if (hit != null) {
            return hit;
        }
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            if (clip.startEpochMs >= epochMs) {
                return new Hit(i, clip, 0L);
            }
        }
        return null;
    }
}
