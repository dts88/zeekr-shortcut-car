package com.kooo.evcam.camera;

import com.kooo.evcam.zeekr.CompositeStreamGeometry;

/**
 * 视频流的尺寸 → 实际编码的尺寸。
 *
 * <h3>为什么不是同一个数</h3>
 *
 * <p>两件事会改它：</p>
 *
 * <ul>
 *   <li><b>四宫格重排</b>：1280×5140 的竖条被拼成 2×2，边长是单格的两倍
 *       （2560×2560）。顺带解决一个实际问题 —— 5140 超过编码器 4096 的上限，
 *       按原样编码会被整体缩小，而 2×2 反而落在限制之内。</li>
 *   <li><b>编码器上限</b>：任何一边超过 4096 都要等比缩小，并取偶数。</li>
 * </ul>
 *
 * <h3>为什么抽出来</h3>
 *
 * <p>设置界面要按这个尺寸算目标码率。界面自己再推一遍规则，两边迟早走散 ——
 * 那就又是「界面写一个数、实际用另一个数」。纯函数，可以单独测。</p>
 */
public final class EncodeSize {

    /** 编码器单边上限。 */
    public static final int MAX_SIDE = 4096;

    public final int width;
    public final int height;

    /** 是否走了四宫格重排。 */
    public final boolean grid;

    private EncodeSize(int width, int height, boolean grid) {
        this.width = width;
        this.height = height;
        this.grid = grid;
    }

    /**
     * @param gridRequested 设置里选的是不是「四宫格」排列
     */
    public static EncodeSize forSource(int sourceWidth, int sourceHeight, boolean gridRequested) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new EncodeSize(0, 0, false);
        }
        if (gridRequested
                && CompositeStreamGeometry.looksLikeComposite(sourceWidth, sourceHeight)) {
            CompositeStreamGeometry.Plan plan =
                    CompositeStreamGeometry.analyse(sourceWidth, sourceHeight);
            if (plan.isComposite()) {
                int side = even(Math.min(plan.laneSizePx * 2, MAX_SIDE));
                return new EncodeSize(side, side, true);
            }
        }
        if (sourceWidth <= MAX_SIDE && sourceHeight <= MAX_SIDE) {
            return new EncodeSize(sourceWidth, sourceHeight, false);
        }
        float scale = Math.min((float) MAX_SIDE / sourceWidth, (float) MAX_SIDE / sourceHeight);
        return new EncodeSize(even((int) (sourceWidth * scale)),
                even((int) (sourceHeight * scale)), false);
    }

    /** 编码器要求偶数边长。 */
    private static int even(int value) {
        int result = (value / 2) * 2;
        return Math.max(2, result);
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
