package com.kooo.evcam.camera;

import java.util.Locale;

/**
 * 从相机声明的帧率区间里挑一个，用来<b>钉住</b>帧率。
 *
 * <h3>为什么必须挑一个</h3>
 *
 * <p>本应用此前<b>从来没有向相机请求过帧率区间</b>（{@code CONTROL_AE_TARGET_FPS_RANGE}
 * 全项目一次都没设过）。不设的话，自动曝光会在 HAL 给的默认区间里自己决定跑多快 ——
 * 而默认区间通常是可变的，比如 15–30。曝光一紧张、负载一上来，它就会滑到下限。</p>
 *
 * <p>这正好解释了「预览测出 29，录制只有 15」：不是相机给不到 30，
 * 是没人要求它保持 30，于是它在允许的范围内选了自己舒服的那一端。</p>
 *
 * <h3>挑选顺序</h3>
 *
 * <ol>
 *   <li><b>固定区间 [n,n]</b> —— 上下限相同，AE 没有滑动的余地，最优先；</li>
 *   <li>包含 n 且<b>下限最高</b>的区间 —— 退而求其次，把可滑动的空间压到最小；</li>
 *   <li>上限最接近 n 的区间 —— 目标帧率压根不在任何区间里时的兜底；</li>
 *   <li>都没有就不设 —— 相机没声明区间时，硬塞一个只会让会话配置失败。</li>
 * </ol>
 *
 * <p>纯逻辑，不依赖 Android，可以单独测。区间用两个 int 表示，
 * 免得为了跑测试把 {@code android.util.Range} 拖进来。</p>
 */
public final class FpsRangePicker {

    private FpsRangePicker() {
    }

    /** 一个帧率区间。 */
    public static final class Choice {
        public final int lower;
        public final int upper;

        public Choice(int lower, int upper) {
            this.lower = lower;
            this.upper = upper;
        }

        /** 上下限相同，AE 只能按这个速度跑。 */
        public boolean isFixed() {
            return lower == upper;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Choice)) {
                return false;
            }
            Choice that = (Choice) other;
            return lower == that.lower && upper == that.upper;
        }

        @Override
        public int hashCode() {
            return lower * 31 + upper;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "[%d,%d]", lower, upper);
        }
    }

    /**
     * 挑一个区间。
     *
     * @param ranges    相机声明的全部区间，形如 {@code {{15,30},{30,30}}}
     * @param targetFps 想要的帧率
     * @return 挑中的区间；没有可用区间时返回 null，调用方就不要设这一项
     */
    public static Choice pick(int[][] ranges, int targetFps) {
        if (ranges == null || ranges.length == 0 || targetFps <= 0) {
            return null;
        }

        Choice fixed = null;
        Choice containing = null;
        Choice nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int lower = Math.min(range[0], range[1]);
            int upper = Math.max(range[0], range[1]);
            if (upper <= 0) {
                continue;
            }
            Choice candidate = new Choice(lower, upper);

            if (lower == targetFps && upper == targetFps) {
                fixed = candidate;
            }
            if (lower <= targetFps && targetFps <= upper) {
                // 下限越高，AE 能往下滑的空间越小
                if (containing == null || lower > containing.lower) {
                    containing = candidate;
                }
            }
            int distance = Math.abs(upper - targetFps);
            if (distance < nearestDistance
                    || (distance == nearestDistance && nearest != null && lower > nearest.lower)) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }

        if (fixed != null) {
            return fixed;
        }
        if (containing != null) {
            return containing;
        }
        return nearest;
    }
}
