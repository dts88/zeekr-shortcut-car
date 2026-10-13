package com.kooo.evcam.storage;

import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.CodecVideoRecorder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 自动锁定的时间窗：某一刻前后各 10 秒；盘上哪些录像文件和它有重叠（每一路都算）。纯函数（{@code LockWindowTest}）。
 * 谁来锁、什么时候锁，看 {@link AutoLock}；规则见 docs/storage-spec.md。
 *
 * <h3>一个文件占哪一段时间</h3>
 *
 * <p>文件名里的时刻（{@code yyyyMMdd_HHmmss}，本地时间，只到秒）只会比这一路真正开始写早，不会晚：
 * 秒被截掉了；第一段用的是开录那一刻的时间戳，真正写进第一帧还要等会话重建、画面稳定，
 * 分段计时又是从第一帧算起；几路分段切换时共用一个时间戳
 * （{@code MultiCameraManager.TIMESTAMP_CACHE_DURATION_MS}），晚切的那一路，名字比它真正切换早最多这么久。
 * 所以结束不能只按「名字 + 标称时长」算 —— 那样会把还有画面的那几秒漏掉。</p>
 *
 * <ul>
 *   <li><b>开始</b>：文件名里的时刻（最早的可能）。</li>
 *   <li><b>结束</b>：同一路下一个文件的开始 —— 只要它不晚于「开始 + 这一路的标称分段时长（各路可以不一样）
 *       + {@link #NEXT_FILE_TOLERANCE_MS}」，它就是这一段真正切到哪的记录（晚于标称，是开录慢了；
 *       早于标称，是停过录又接上）。没有下一个文件（还在写、停录了），或者下一个隔得更远（停过录、很久以后才又录），
 *       按开始 + 标称时长。两种都再多算 {@link #SLACK_MS}：同一个名字底下晚切的那一路最多晚这么久。
 *       宁可多锁一段，也别漏一段。</li>
 *   <li><b>重叠</b>：开始 ≤ 窗口结束，而且结束 ≥ 窗口开始，两头都算。</li>
 * </ul>
 *
 * <p>同一路按对外的槽位名归一（{@link CameraSlots#canonical}）：改名之前的 {@code _front} 和现在的
 * {@code _surround} 是同一路。不是本应用录像文件名的不算（{@link StoragePlan#isOwnClip}）。</p>
 */
public final class LockWindow {

    /** 往前锁多久。设置项和提示里写的「前后 10 秒」说的就是它和 {@link #AFTER_MS}。 */
    public static final long BEFORE_MS = 10_000L;
    /** 往后锁多久。 */
    public static final long AFTER_MS = 10_000L;
    /**
     * 文件名里的时刻最多比这一路真正切换早多久：几路分段切换时共用一个时间戳的有效期，
     * 再加秒被截掉的那 1 秒。每个文件的结束多算这么多（见类说明）；第二遍锁定也要多等这么久
     * （{@link #SECOND_PASS_DELAY_MS}）。
     */
    public static final long SLACK_MS = MultiCameraManager.TIMESTAMP_CACHE_DURATION_MS + 1_000L;
    /**
     * 第二遍锁定在窗口结束之后再等多久（{@link AutoLock}）：t 之后才开始的那一段要先在盘上。
     * 名字落在窗口里的那一段，晚切的那一路最多晚 {@link #SLACK_MS} 才换文件；文件由写入线程建，
     * 它排着队、再卡一次，最多还要晚 {@link CodecVideoRecorder#MAX_FILE_LAG_MS}；再留 1 秒。一共 18 秒。
     */
    public static final long SECOND_PASS_DELAY_MS = SLACK_MS + CodecVideoRecorder.MAX_FILE_LAG_MS + 1_000L;
    /**
     * 同一路下一个文件比「开始 + 标称时长」晚多少以内，还算接着录的下一段（它的开始就是这一段的结束）：
     * 第一段从开录到写进第一帧的那几秒（会话重建最多 3 秒、等画面稳定最多 2 秒、截掉的 1 秒），
     * 加上共用时间戳的 {@link #SLACK_MS}，取整。再晚就当是停过录、过了一阵才又录，按标称时长算。
     */
    public static final long NEXT_FILE_TOLERANCE_MS = 20_000L;

    /** 窗口开始（毫秒时间戳，含）。 */
    public final long startMs;
    /** 窗口结束（毫秒时间戳，含）。 */
    public final long endMs;

    LockWindow(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }

    /** 这一刻前 {@link #BEFORE_MS} 到后 {@link #AFTER_MS}。 */
    public static LockWindow around(long momentMs) {
        return new LockWindow(momentMs - BEFORE_MS, momentMs + AFTER_MS);
    }

    /**
     * 和窗口有重叠的录像文件名。
     *
     * @param names             盘上的文件名，几个目录合在一起；重复的、别的文件、null 都可以有
     * @param segmentMsBySlot   每一路的标称分段时长（毫秒），键是槽位名（文件名里那段，新旧写法、内部 key 都认）
     * @param fallbackSegmentMs 表里没有的那一路按多长算
     * @return 按名字排序（就是按时间）
     */
    public Set<String> overlapping(Collection<String> names, Map<String, Long> segmentMsBySlot,
                                   long fallbackSegmentMs) {
        Map<String, Long> lengths = new HashMap<>();
        if (segmentMsBySlot != null) {
            for (Map.Entry<String, Long> e : segmentMsBySlot.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    lengths.put(canonicalSlot(e.getKey()), e.getValue());
                }
            }
        }

        // 每一路自己的文件，按开始时刻排
        Map<String, List<Clip>> bySlot = new HashMap<>();
        Set<String> unique = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (StoragePlan.isOwnClip(name)) {
                    unique.add(name);
                }
            }
        }
        for (String name : unique) {
            long start = RecordingTimeline.parseStartEpochMs(name);
            String slot = RecordingTimeline.parseCameraSlot(name);
            if (start < 0 || slot == null) {
                continue;
            }
            String key = canonicalSlot(slot);
            List<Clip> clips = bySlot.get(key);
            if (clips == null) {
                clips = new ArrayList<>();
                bySlot.put(key, clips);
            }
            clips.add(new Clip(name, start));
        }

        Set<String> hit = new TreeSet<>();
        for (Map.Entry<String, List<Clip>> e : bySlot.entrySet()) {
            List<Clip> clips = e.getValue();
            Collections.sort(clips);
            Long nominal = lengths.get(e.getKey());
            long length = nominal != null && nominal > 0 ? nominal : fallbackSegmentMs;
            for (int i = 0; i < clips.size(); i++) {
                Clip clip = clips.get(i);
                long end = clip.startMs + length;
                // 同一路下一个开始得更晚的文件（同一秒开始的不算「下一个」）：离得不远，它就是这一段切到哪
                for (int j = i + 1; j < clips.size(); j++) {
                    long next = clips.get(j).startMs;
                    if (next > clip.startMs) {
                        if (next <= clip.startMs + length + NEXT_FILE_TOLERANCE_MS) {
                            end = next;
                        }
                        break;
                    }
                }
                end += SLACK_MS;
                if (clip.startMs <= endMs && end >= startMs) {
                    hit.add(clip.name);
                }
            }
        }
        return hit;
    }

    private static String canonicalSlot(String slot) {
        return CameraSlots.canonical(slot.toLowerCase(Locale.US));
    }

    /** 一个录像文件：名字和文件名里的开始时刻。 */
    private static final class Clip implements Comparable<Clip> {
        final String name;
        final long startMs;

        Clip(String name, long startMs) {
            this.name = name;
            this.startMs = startMs;
        }

        @Override
        public int compareTo(Clip other) {
            int byStart = Long.compare(startMs, other.startMs);
            return byStart != 0 ? byStart : name.compareTo(other.name);
        }
    }
}
