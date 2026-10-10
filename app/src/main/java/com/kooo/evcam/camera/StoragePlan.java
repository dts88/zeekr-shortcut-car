package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 录像空间怎么管：给定盘上的录像、上限和剩余空间，决定删哪些、还是停下来。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li><b>设了上限</b>：保持「已用 + 下一个分段」不超过上限，同时盘上始终留出余量。
 *       不够就从最旧的分段删起，一次删一整组（同一分钟里几路相机的文件一起删，不留半组）。
 *       <b>每一路最新的那个文件永远不碰</b>，它在的那一组整组留着（{@link #newestOfEachSlot}）：
 *       正在写的文件就是它那一路最新的。以前只留「最新的一组」—— 一路单独接回录像时（2026-10-10），
 *       它的第一个文件按接回那一刻命名、自己成了最新的一组，别的路正在写的那一组反倒成了可删的。</li>
 *   <li><b>没设上限</b>：一个文件都不删。剩余空间低于余量就停下来 —— 这是用户选的
 *       「不限制」的含义：不替他决定哪些录像可以丢。</li>
 *   <li>删光本应用自己的旧录像也腾不出余量（盘被别的东西占满了）：<b>不删</b>，直接停。
 *       否则就是白白删掉录像，最后还是录不了。</li>
 *   <li><b>锁定的</b>（{@code FootageLocks}）：永远不进删除名单，但照样算占用。没锁的都删了还不够
 *       （上限或余量），就停 —— 锁到腾不出空间就停录（项目所有者 2026-10-03）。</li>
 * </ul>
 *
 * <h3>只认自己的文件</h3>
 *
 * <p>U 盘上可能有用户自己的东西。以前的清理「不筛选格式」，目录里有什么删什么；
 * 这里只认本应用写出来的分段文件名（{@code yyyyMMdd_HHmmss_<那一路>.mp4}）。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class StoragePlan {

    /** 余量的下限：估算的分段大小再小，也至少留这么多。 */
    public static final long MIN_MARGIN_BYTES = 512L * 1024 * 1024;

    /** 还没写完过一个分段、量不出大小时，按这个估。 */
    public static final long DEFAULT_SEGMENT_BYTES = 1024L * 1024 * 1024;

    private static final Pattern OWN_CLIP = Pattern.compile("^\\d{8}_\\d{6}_[a-z0-9]+\\.mp4$");
    private static final Pattern OWN_PHOTO = Pattern.compile("^\\d{8}_\\d{6}_[a-z0-9]+\\.jpe?g$");

    private StoragePlan() {
    }

    /** 是不是本应用写出来的分段录像。 */
    public static boolean isOwnClip(String name) {
        return name != null && OWN_CLIP.matcher(name).matches();
    }

    /** 是不是本应用拍的照片。 */
    public static boolean isOwnPhoto(String name) {
        return name != null && OWN_PHOTO.matcher(name).matches();
    }

    /** 分组键：文件名开头的时间戳。同一分段里几路相机的文件共用它，字典序就是时间序。 */
    public static String groupOf(String name) {
        return name != null && name.length() >= 15 ? name.substring(0, 15) : "";
    }

    /** 文件是哪一路的（文件名最后一个下划线之后）：改名前后的两种叫法算同一路（{@link CameraSlots#canonical}）。 */
    static String slotOf(String name) {
        String slot = com.kooo.evcam.zeekr.RecordingTimeline.parseCameraSlot(name);
        return slot == null ? "" : CameraSlots.canonical(slot);
    }

    /** 每一路的文件，各自从新到旧（名字开头是时刻，字典序就是时间序）。 */
    private static Map<String, List<Clip>> newestFirstBySlot(List<Clip> clips) {
        Map<String, List<Clip>> bySlot = new LinkedHashMap<>();
        for (Clip clip : clips) {
            String slot = slotOf(clip.name);
            List<Clip> list = bySlot.get(slot);
            if (list == null) {
                list = new ArrayList<>();
                bySlot.put(slot, list);
            }
            list.add(clip);
        }
        for (List<Clip> list : bySlot.values()) {
            Collections.sort(list, (a, b) -> b.name.compareTo(a.name));
        }
        return bySlot;
    }

    /**
     * 每一路最新的那个文件：永远不删（2026-10-10）。正在写的文件一定是它那一路最新的 ——
     * 几路的分段不再一定同名：一路单独接回录像时，第一个文件按接回那一刻命名，和别的路错开。
     * 一路不录了（离开了录像、关掉了这一路）的最后一个文件也在里面：多留它那一组，不少留正在写的。
     */
    static Set<String> newestOfEachSlot(List<Clip> clips) {
        Set<String> newest = new HashSet<>();
        for (List<Clip> list : newestFirstBySlot(clips).values()) {
            newest.add(list.get(0).name);
        }
        return newest;
    }

    /** 余量：两个分段，但不少于 {@link #MIN_MARGIN_BYTES}。 */
    public static long margin(long segmentBytes) {
        return Math.max(MIN_MARGIN_BYTES, 2 * Math.max(0, segmentBytes));
    }

    /** 盘上的一个录像文件。 */
    public static final class Clip {
        public final String name;
        public final long bytes;

        public Clip(String name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    public enum Verdict {
        /** 空间够，什么都不用做。 */
        OK,
        /** 删掉 {@link Decision#toDelete} 里的文件。 */
        DELETE,
        /** 停止录制（或者不开始）。 */
        FULL
    }

    /** 决定的结果。 */
    public static final class Decision {
        public final Verdict verdict;
        public final List<String> toDelete;
        public final long deleteBytes;
        public final long marginBytes;
        /** 为什么是 FULL：没设上限，还是删光也不够。 */
        public final boolean capless;
        /** FULL 是因为剩下的都锁着：没锁的删光了还不够，解锁一些就能接着录。 */
        public final boolean lockedFull;

        Decision(Verdict verdict, List<String> toDelete, long deleteBytes, long marginBytes,
                 boolean capless) {
            this(verdict, toDelete, deleteBytes, marginBytes, capless, false);
        }

        Decision(Verdict verdict, List<String> toDelete, long deleteBytes, long marginBytes,
                 boolean capless, boolean lockedFull) {
            this.verdict = verdict;
            this.toDelete = toDelete;
            this.deleteBytes = deleteBytes;
            this.marginBytes = marginBytes;
            this.capless = capless;
            this.lockedFull = lockedFull;
        }
    }

    /**
     * 用每一路最近一个<b>写完的</b>文件估算一个分段（所有相机合起来）有多大：每一路第二新的那个加起来。
     *
     * <p>每一路最新的那个正在写，不算；第二新的是它最近一个完整的。量出来的比按码率算的准 ——
     * 实际码率随画面内容浮动。以前取倒数第二组：一路单独接回录像、第一个文件自成最新的一组时（2026-10-10），
     * 倒数第二组是别的路正在写的那一组，估小了。</p>
     */
    public static long estimateSegmentBytes(List<Clip> clips) {
        long bytes = 0;
        for (List<Clip> list : newestFirstBySlot(clips).values()) {
            if (list.size() >= 2) {
                bytes += Math.max(0, list.get(1).bytes);
            }
        }
        return bytes > 0 ? bytes : DEFAULT_SEGMENT_BYTES;
    }

    /**
     * 决定该做什么。
     *
     * @param clips        盘上本应用的录像（顺序不限）
     * @param capBytes     视频存储上限；0 或负数表示不限制
     * @param freeBytes    盘上剩余空间
     * @param segmentBytes 一个分段（所有相机合起来）的大小估计
     */
    public static Decision decide(List<Clip> clips, long capBytes, long freeBytes,
                                  long segmentBytes) {
        return decide(clips, Collections.<String>emptySet(), capBytes, freeBytes, segmentBytes);
    }

    /**
     * 同上，{@code locked} 里的文件不删（但算占用）。
     *
     * @param locked 锁定的文件名（{@code FootageLocks}）；开关关着时是空集合
     */
    public static Decision decide(List<Clip> clips, Set<String> locked, long capBytes, long freeBytes,
                                  long segmentBytes) {
        long margin = margin(segmentBytes);
        boolean capless = capBytes <= 0;

        if (capless) {
            return freeBytes >= margin
                    ? new Decision(Verdict.OK, Collections.emptyList(), 0, margin, true)
                    : new Decision(Verdict.FULL, Collections.emptyList(), 0, margin, true);
        }

        long used = 0;
        for (Clip clip : clips) {
            used += Math.max(0, clip.bytes);
        }
        // 下一个分段落下之后仍不超上限；盘上始终留出余量
        long needForCap = Math.max(0, used + Math.max(0, segmentBytes) - capBytes);
        long needForFree = Math.max(0, margin - freeBytes);
        long need = Math.max(needForCap, needForFree);
        if (need == 0) {
            return new Decision(Verdict.OK, Collections.emptyList(), 0, margin, false);
        }

        // 最旧的组在前；哪一路最新的文件（正在写的就是它）在里面，这一组整组不可删
        Set<String> newest = newestOfEachSlot(clips);
        Map<String, List<Clip>> groups = new LinkedHashMap<>();
        Set<String> kept = new HashSet<>();
        for (Clip clip : clips) {
            String key = groupOf(clip.name);
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
            }
            groups.get(key).add(clip);
            if (newest.contains(clip.name)) {
                kept.add(key);
            }
        }
        List<String> order = new ArrayList<>(groups.keySet());
        order.removeAll(kept);
        Collections.sort(order);

        long deletable = 0;
        long lockedOld = 0;
        for (String key : order) {
            for (Clip clip : groups.get(key)) {
                if (locked.contains(clip.name)) {
                    lockedOld += Math.max(0, clip.bytes);
                } else {
                    deletable += Math.max(0, clip.bytes);
                }
            }
        }
        if (deletable < need && lockedOld > 0 && deletable + lockedOld >= need) {
            // 锁定的占着：没锁的删光也不够，不锁的话又够 —— 停，让人去解锁
            return new Decision(Verdict.FULL, Collections.emptyList(), 0, margin, false, true);
        }
        if (deletable < needForFree) {
            // 删光也腾不出余量：盘被别的东西占了。删了也录不了，不删
            return new Decision(Verdict.FULL, Collections.emptyList(), 0, margin, false);
        }

        List<String> toDelete = new ArrayList<>();
        long freed = 0;
        for (String key : order) {
            if (freed >= need) {
                break;
            }
            for (Clip clip : groups.get(key)) {
                if (locked.contains(clip.name)) {
                    continue;
                }
                toDelete.add(clip.name);
                freed += Math.max(0, clip.bytes);
            }
        }
        if (toDelete.isEmpty()) {
            // 超了上限但只剩正在写的那几组：没有能删的，写完下次再算
            return new Decision(Verdict.OK, Collections.emptyList(), 0, margin, false);
        }
        return new Decision(Verdict.DELETE, toDelete, freed, margin, false);
    }
}
