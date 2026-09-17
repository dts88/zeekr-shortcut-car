package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 录像空间怎么管：给定盘上的录像、上限和剩余空间，决定删哪些、还是停下来。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li><b>设了上限</b>：保持「已用 + 下一个分段」不超过上限，同时盘上始终留出余量。
 *       不够就从最旧的分段删起，一次删一整组（同一分钟里几路相机的文件一起删，不留半组）。
 *       正在写的那一组永远不碰。</li>
 *   <li><b>没设上限</b>：一个文件都不删。剩余空间低于余量就停下来 —— 这是用户选的
 *       「不限制」的含义：不替他决定哪些录像可以丢。</li>
 *   <li>删光本应用自己的旧录像也腾不出余量（盘被别的东西占满了）：<b>不删</b>，直接停。
 *       否则就是白白删掉录像，最后还是录不了。</li>
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

        Decision(Verdict verdict, List<String> toDelete, long deleteBytes, long marginBytes,
                 boolean capless) {
            this.verdict = verdict;
            this.toDelete = toDelete;
            this.deleteBytes = deleteBytes;
            this.marginBytes = marginBytes;
            this.capless = capless;
        }
    }

    /**
     * 用最近一个<b>写完的</b>分段组估算一个分段（所有相机合起来）有多大。
     *
     * <p>最新那一组正在写，不算；倒数第二组是最近一个完整的。量出来的比按码率算的准 ——
     * 实际码率随画面内容浮动。</p>
     */
    public static long estimateSegmentBytes(List<Clip> clips) {
        Map<String, Long> groups = groupSizes(clips);
        if (groups.size() < 2) {
            return DEFAULT_SEGMENT_BYTES;
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        Collections.sort(keys);
        long bytes = groups.get(keys.get(keys.size() - 2));
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

        // 最旧的组在前；最新一组正在写，不可删
        Map<String, List<Clip>> groups = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Clip clip : clips) {
            String key = groupOf(clip.name);
            if (!groups.containsKey(key)) {
                groups.put(key, new ArrayList<>());
                order.add(key);
            }
            groups.get(key).add(clip);
        }
        Collections.sort(order);
        if (!order.isEmpty()) {
            order.remove(order.size() - 1);
        }

        long deletable = 0;
        for (String key : order) {
            for (Clip clip : groups.get(key)) {
                deletable += Math.max(0, clip.bytes);
            }
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
                toDelete.add(clip.name);
                freed += Math.max(0, clip.bytes);
            }
        }
        if (toDelete.isEmpty()) {
            // 超了上限但只剩正在写的那一组：没有能删的，这一组写完下次再算
            return new Decision(Verdict.OK, Collections.emptyList(), 0, margin, false);
        }
        return new Decision(Verdict.DELETE, toDelete, freed, margin, false);
    }

    private static Map<String, Long> groupSizes(List<Clip> clips) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (Clip clip : clips) {
            String key = groupOf(clip.name);
            Long current = sizes.get(key);
            sizes.put(key, (current == null ? 0 : current) + Math.max(0, clip.bytes));
        }
        return sizes;
    }
}
