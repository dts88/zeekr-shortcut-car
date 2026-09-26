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
     * 属于这一条录制的文件：开头落在这条时间轴里的那些。
     *
     * <p>按开头算，不按「有重叠」算：删除和分享也用它，一个文件只能归一条录制。
     * 开头往前放宽 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS} —— 几路相机不是同一刻
     * 开录的，座舱那一路比环视早一两秒开头很正常，它仍然是这一次录的。</p>
     */
    public LaneTrack within(RecordingTimeline.Session session) {
        long from = session.startEpochMs - RecordingTimeline.DEFAULT_MAX_GAP_MS;
        long to = session.endEpochMs();
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
