package com.kooo.evcam.zeekr;

import android.util.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 极氪合成流车型档案：从 HAL 声明的分辨率里挑出四联合成流，并给出编码建议。
 *
 * <p><b>硬性原则：绝不臆造分辨率。</b>只会从 {@code StreamConfigurationMap} 实际声明的
 * 尺寸中挑选。一个都挑不出来时返回 {@code null}，由上层把「本机未提供合成流」
 * 明确告诉用户，而不是拿一个猜出来的尺寸去开相机——那样只会得到黑屏或直接崩溃。</p>
 *
 * <p>已知尺寸的来源与致谢见 {@link CompositeStreamGeometry} 的类注释。</p>
 */
public final class ZeekrCompositeProfile {

    /** 车型标识，与 AppConfig 中的常量对应。 */
    public static final String CAR_MODEL = "zeekr_7x";

    /**
     * 优先级顺序的已知合成流尺寸。竖排优先，因为实测车机给的是竖排。
     */
    private static final int[][] KNOWN_SIZES = {
            {1280, 5140},
            {1280, 5120},
            {5120, 1280},
    };

    /** 整条合成流（约 655 万像素）的推荐码率。 */
    public static final int RECOMMENDED_BITRATE_BPS = 28_000_000;

    /** 省空间档位。 */
    public static final int ECONOMY_BITRATE_BPS = 14_000_000;

    /** 合成流分辨率极高，帧率给低一些更容易稳定编码。 */
    public static final int RECOMMENDED_FPS = 20;

    private ZeekrCompositeProfile() {
    }

    /**
     * 从 HAL 声明的尺寸里挑出最合适的合成流分辨率。
     *
     * @param declaredSizes Camera2 声明支持的输出尺寸，可为 null
     * @return 选中的尺寸；没有任何合成流候选时返回 null
     */
    public static Size selectCompositeSize(Size[] declaredSizes) {
        if (declaredSizes == null || declaredSizes.length == 0) {
            return null;
        }
        int[][] pairs = new int[declaredSizes.length][2];
        for (int i = 0; i < declaredSizes.length; i++) {
            Size declared = declaredSizes[i];
            pairs[i][0] = declared == null ? 0 : declared.getWidth();
            pairs[i][1] = declared == null ? 0 : declared.getHeight();
        }
        int index = selectCompositeIndex(pairs);
        return index < 0 ? null : declaredSizes[index];
    }

    /**
     * 选择逻辑的纯整数实现，便于 JVM 单元测试（{@code android.util.Size} 在
     * 单元测试里是桩实现，用不了）。
     *
     * @param declared 形如 {@code {{w,h},...}} 的声明尺寸
     * @return 选中项的下标；没有任何合成流候选时返回 -1
     */
    static int selectCompositeIndex(int[][] declared) {
        if (declared == null) {
            return -1;
        }

        // 第一优先：命中已知尺寸表，按表内顺序
        for (int[] known : KNOWN_SIZES) {
            for (int i = 0; i < declared.length; i++) {
                if (declared[i] != null
                        && declared[i][0] == known[0]
                        && declared[i][1] == known[1]) {
                    return i;
                }
            }
        }

        // 第二优先：任何长宽比像四联长条的声明尺寸，取像素最多的那个
        int best = -1;
        long bestArea = -1L;
        for (int i = 0; i < declared.length; i++) {
            int[] size = declared[i];
            if (size == null || !CompositeStreamGeometry.looksLikeCompositeByRatio(size[0], size[1])) {
                continue;
            }
            long area = (long) size[0] * size[1];
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        return best;
    }

    /** 列出所有像合成流的声明尺寸，供设置页展示，方便用户手动选择。 */
    public static List<Size> listCompositeCandidates(Size[] declaredSizes) {
        List<Size> candidates = new ArrayList<>();
        if (declaredSizes == null) {
            return candidates;
        }
        for (Size declared : declaredSizes) {
            if (declared != null
                    && CompositeStreamGeometry.looksLikeCompositeByRatio(
                            declared.getWidth(), declared.getHeight())) {
                candidates.add(declared);
            }
        }
        Collections.sort(candidates, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                long areaA = (long) a.getWidth() * a.getHeight();
                long areaB = (long) b.getWidth() * b.getHeight();
                return Long.compare(areaB, areaA);
            }
        });
        return candidates;
    }

    /**
     * 给定合成流尺寸推荐码率。
     *
     * <p>以实测的 1280x5140 @ 28Mbps 为基准按像素数线性缩放，
     * 并夹在 6~40 Mbps 之间。这是编码器设置，不需要 HAL 声明。</p>
     */
    public static int recommendedBitrate(Size size) {
        if (size == null) {
            return RECOMMENDED_BITRATE_BPS;
        }
        long pixels = (long) size.getWidth() * size.getHeight();
        long reference = 1280L * 5140L;
        long scaled = RECOMMENDED_BITRATE_BPS * pixels / reference;
        return (int) Math.max(6_000_000L, Math.min(40_000_000L, scaled));
    }

    /** 该尺寸是否为已知实测尺寸（而不是靠长宽比猜出来的）。 */
    public static boolean isKnownSize(Size size) {
        if (size == null) {
            return false;
        }
        for (int[] known : KNOWN_SIZES) {
            if (size.getWidth() == known[0] && size.getHeight() == known[1]) {
                return true;
            }
        }
        return false;
    }

    /** 供设置页显示的一行摘要。 */
    public static String describe(Size size) {
        if (size == null) {
            return "未检测到合成流";
        }
        CompositeStreamGeometry.Plan plan =
                CompositeStreamGeometry.analyse(size.getWidth(), size.getHeight());
        String origin = isKnownSize(size) ? "已知实测尺寸" : "按长宽比推断";
        return size.getWidth() + "x" + size.getHeight() + "（" + origin + "）\n" + plan.note;
    }
}
