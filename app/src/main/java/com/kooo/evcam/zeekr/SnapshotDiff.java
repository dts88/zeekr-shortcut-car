package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两份系统状态快照的差异。
 *
 * <h3>为什么要这么找信号</h3>
 *
 * <p>之前找车辆信号的做法是「猜名字」—— 猜属性叫 {@code vehicle.gear} 还是
 * {@code car.gear.status}，猜错了就以为读不到。但这台车机上有上千个系统属性，
 * 猜中的概率很低，猜不中也<b>证明不了信号不存在</b>。</p>
 *
 * <p>换个方向：<b>不问它叫什么，只问它变没变。</b>挂倒挡之前拍一张快照，
 * 挂上之后再拍一张，对比两张 —— 凡是变了的，就是跟这个动作有关的候选项。
 * 名字是结果，不是前提。</p>
 *
 * <p>这也是这个项目一直在用的原则：<b>枚举，不要猜。</b></p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class SnapshotDiff {

    /** 一处变化。 */
    public static final class Change {
        public final String key;
        /** 变化前的值；新增项为 null。 */
        public final String before;
        /** 变化后的值；消失项为 null。 */
        public final String after;

        public Change(String key, String before, String after) {
            this.key = key;
            this.before = before;
            this.after = after;
        }

        public boolean isAdded() {
            return before == null;
        }

        public boolean isRemoved() {
            return after == null;
        }

        @Override
        public String toString() {
            if (isAdded()) {
                return key + "：（原本没有）→ " + after;
            }
            if (isRemoved()) {
                return key + "：" + before + " →（消失了）";
            }
            return key + "：" + before + " → " + after;
        }
    }

    private SnapshotDiff() {
    }

    /**
     * 对比两份快照。
     *
     * <p>结果按键名排序 —— 同一个动作重复做两次，两次报告应当能直接对照着看，
     * 顺序飘来飘去会让人以为结果不稳定。</p>
     *
     * @param before 先拍的那份
     * @param after  后拍的那份
     * @return 所有变化；没有变化时是空列表，不是 null
     */
    public static List<Change> between(Map<String, String> before, Map<String, String> after) {
        List<Change> changes = new ArrayList<>();
        if (before == null || after == null) {
            return changes;
        }

        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());

        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);

        for (String key : sorted) {
            String oldValue = before.get(key);
            String newValue = after.get(key);
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                changes.add(new Change(key, oldValue, newValue));
            }
        }
        return changes;
    }

    /**
     * 过滤掉一望而知与车辆无关的项。
     *
     * <p>两次快照之间必然会变的东西不少：开机时长、内存占用、各种计数器。
     * 它们淹没真正有用的那几行，所以按前缀滤掉。</p>
     *
     * <p>只滤<b>确定无关</b>的，宁可多留几行噪音，也不要把真信号滤没了 ——
     * 滤错了的代价是「明明读得到却以为读不到」，比多看几行大得多。</p>
     */
    public static boolean isLikelyNoise(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith("sys.uidcpupower")
                || key.startsWith("debug.")
                || key.startsWith("cache_key.")
                || key.contains("uptime")
                || key.contains("boottime")
                || key.contains(".heap")
                || key.contains("random")
                || key.contains("timestamp");
    }

    /** 去掉噪音之后的变化。 */
    public static List<Change> signalOnly(List<Change> changes) {
        List<Change> kept = new ArrayList<>();
        if (changes == null) {
            return kept;
        }
        for (Change change : changes) {
            if (!isLikelyNoise(change.key)) {
                kept.add(change);
            }
        }
        return kept;
    }
}
