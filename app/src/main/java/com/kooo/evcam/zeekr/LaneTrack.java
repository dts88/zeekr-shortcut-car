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
     * 用 {@link #belongingTo}：每个文件至多归一条。</p>
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
     * 归第 index 条录制的文件：删除、分享、大小、「含已锁定文件」都按它算（执行大纲阶段 P，2026-10-11）。
     *
     * <p>每个文件<b>至多归一条</b>，归时间上离它最近的那一条（怎么比远近见 {@link #recordingOf}）：</p>
     * <ul>
     *   <li>和它重叠的那一条。座舱比环视早一两秒开头、环视中途单独停过又接回，都还是这一条；</li>
     *   <li>不重叠的，归隔得最近的那一条，一样近归前一条；但最多隔 {@code maxGapMs}（一个分段时长）
     *       —— 环视停着、座舱照录时，紧挨着环视的那一段还算这一次录的；</li>
     *   <li>再远的不归任何一条：回放里删不掉、锁不到，只由循环清理收（大纲 §6 风险 12，
     *       要不要给它们单独一条以后再定）。</li>
     * </ul>
     *
     * <p>以前（v2）归「开头之前最近开录的那一条」，比第一条还早的归第一条，没有上限。
     * 2026-10-10 起录像每一路各自进出，环视可能比座舱晚加入（开录时原厂 360 正拿着它）：
     * 环视加入之前座舱录下的那几段于是挂到了上一条录制上 —— 可能是前一天的，
     * 删前一天那条时跟着被删掉，而它们明明是这一次录的。</p>
     *
     * <p>它在环视之外的那一截不在任何一刻的画面上（进度条跟着环视），这是有意的；
     * 锁定、解锁时它跟着离它最近的那一段环视走（{@link #attachedTo}）。</p>
     *
     * @param sessions 全部录制，按先后排（{@link RecordingTimeline#build} 的结果）
     * @param maxGapMs 不重叠时最多隔多远还算这一条：这一路的一个分段时长
     *                 （各路分段时长可以不一样，调用方取这一路自己的）
     */
    public LaneTrack belongingTo(List<RecordingTimeline.Session> sessions, int index,
                                 long maxGapMs) {
        List<Clip> list = new ArrayList<>();
        for (Clip clip : clips) {
            if (recordingOf(clip, sessions, maxGapMs) == index) {
                list.add(clip);
            }
        }
        return new LaneTrack(list);
    }

    /**
     * 一次分好：第 i 个就是 {@code belongingTo(sessions, i, maxGapMs)}，规则同一条。
     *
     * <p>回放列表要每一条录制的大小和锁定标记。挨条调 {@link #belongingTo} 是录制条数 × 文件数，
     * U 盘上存着好几天的录像时，扫完那一下会在主线程上卡住；这里每个文件只判一次。</p>
     */
    public List<LaneTrack> byRecording(List<RecordingTimeline.Session> sessions, long maxGapMs) {
        List<List<Clip>> lists = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            lists.add(new ArrayList<>());
        }
        for (Clip clip : clips) {
            int index = recordingOf(clip, sessions, maxGapMs);
            if (index >= 0) {
                lists.get(index).add(clip);
            }
        }
        List<LaneTrack> tracks = new ArrayList<>();
        for (List<Clip> list : lists) {
            tracks.add(new LaneTrack(list));
        }
        return tracks;
    }

    /**
     * 这个文件归第几条录制（{@link #belongingTo} 的规则）；不归任何一条返回 -1。
     *
     * <p>远近只用一个数比（{@link #closeness}）：重叠的按重叠了多长，越长越近；不重叠的按隔了多远，
     * 越短越近，隔得超过 maxGapMs 的不算。一样近不换，归前一条。</p>
     *
     * <p>和两条都重叠的也有：环视被原厂 360 拿走半分钟（倒车），环视的录像断成两条，座舱照录，
     * 它那一段跨着两条。按同一个数，它归重叠得多的那一条 —— 删掉另一条时它留下，
     * 留下的那一条画面上它占的那一大截不缺。</p>
     */
    static int recordingOf(Clip clip, List<RecordingTimeline.Session> sessions, long maxGapMs) {
        long limit = Math.max(0L, maxGapMs);
        // 录制按先后排、首尾隔开，结束的时刻也是递增的：先二分跳过结束得太早、够不着它的那些
        int lo = 0;
        int hi = sessions.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sessions.get(mid).endEpochMs() + limit < clip.startEpochMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int best = -1;
        long bestCloseness = Long.MIN_VALUE;
        for (int i = lo; i < sessions.size(); i++) {
            RecordingTimeline.Session session = sessions.get(i);
            if (session.startEpochMs - clip.endEpochMs() > limit) {
                break;   // 开头得太晚，够不着；后面的更晚
            }
            long closeness = closeness(clip, session.startEpochMs, session.endEpochMs());
            if (closeness > bestCloseness) {   // 一样近不换：归前一条
                best = i;
                bestCloseness = closeness;
            }
        }
        return best;
    }

    /**
     * 这一路归这一条录制、却在环视之外的文件里，挂在环视第 segment 段上的那些（执行大纲阶段 P，2026-10-11）。
     *
     * <p>「环视之外」：和环视的哪一段都不重叠 —— 回放从头放到尾，哪一刻的画面上都没有它
     * （进度条跟着环视，项目所有者 2026-10-10）。「锁定此刻 / 解锁」碰的是此刻画面上的文件，
     * 再加上挂在此刻环视那一段上的这些；不然它们锁不上、锁着的也解不开，这是 v2 记下的缺口。
     * 进度条下的细条也按这个挂法画（见 {@link #attachedSegment}）。</p>
     *
     * @param session 这些文件归的那一条录制（先用 {@link #belongingTo} 挑出来）
     * @param segment 环视的第几段；负数时什么都不挂
     */
    public LaneTrack attachedTo(RecordingTimeline.Session session, int segment) {
        List<Clip> list = new ArrayList<>();
        if (segment >= 0) {
            for (Clip clip : clips) {
                if (attachedSegment(clip, session) == segment) {
                    list.add(clip);
                }
            }
        }
        return new LaneTrack(list);
    }

    /**
     * 环视之外的一个文件挂在这一条录制的第几段环视上：同一条录制里，时间上最近的那一段，一样近挂前一段。
     *
     * <p>远近和 {@link #recordingOf} 用同一个数。挂上去的那一段的一端，正是
     * {@link RecordingTimeline.Session#positionAt} 把这个文件换算到进度条上的那一点 ——
     * 时间轴把段与段之间的空挤掉了，空两边的那两段在进度条上是同一点 ——
     * 所以细条上它画成那一端的一个点，和按「锁定 / 解锁」时跟着哪一段走对得上。</p>
     *
     * @return 和环视的某一段重叠（它在画面上，不用挂）、或这一条没有分段时返回 -1
     */
    static int attachedSegment(Clip clip, RecordingTimeline.Session session) {
        int best = -1;
        long bestCloseness = Long.MIN_VALUE;
        for (int i = 0; i < session.segments.size(); i++) {
            RecordingTimeline.Segment segment = session.segments.get(i);
            long closeness = closeness(clip, segment.startEpochMs,
                    segment.startEpochMs + segment.durationMs);
            if (closeness > 0) {
                return -1;   // 和这一段重叠：它在画面上
            }
            if (closeness > bestCloseness) {   // 一样近不换：挂前一段
                best = i;
                bestCloseness = closeness;
            }
        }
        return best;
    }

    /**
     * 这个文件离 [from, to) 有多近：重叠时是重叠的长度（正数），不重叠时是隔开的距离取负，正好挨着是 0。
     *
     * <p>两种情况是同一个式子：两段里先结束的那一端减去后开头的那一端。</p>
     */
    private static long closeness(Clip clip, long from, long to) {
        return Math.min(clip.endEpochMs(), to) - Math.max(clip.startEpochMs, from);
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
