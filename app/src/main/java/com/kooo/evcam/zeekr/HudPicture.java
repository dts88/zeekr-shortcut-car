package com.kooo.evcam.zeekr;

/**
 * HUD 上盲区画面的位置、大小和样子（对比度、透明度、边缘渐隐）。
 *
 * <h3>位置和大小</h3>
 *
 * <p>高度是 HUD 高度的百分比，宽度 = 高度 × 形状（宽 ÷ 高 ×100），最多一整个 HUD 宽。左右位置是「离打灯那一侧边缘的距离」：
 * 左灯的画面离左边这么远、右灯的离右边这么远，都是剩余宽度里的百分比，0 贴边、50 居中；上下位置是剩余高度里的百分比，0 贴顶、100 贴底。</p>
 *
 * <h3>样子：HUD 是投影</h3>
 *
 * <p>黑色是「不亮」，其余每一级灰都是亮光叠在路面上。摄像头画面大半是中灰（路面、天空、车身反光），原样投上去一片发白、
 * 分不出层次。所以投上去之前过一条直线：{@code 输出 = k × 输入 + (1 − k) × 枢轴}，k 是对比度 ÷ 100，枢轴比中灰高（{@link #TONE_PIVOT}）——
 * 比它暗的往黑压，只有亮部提亮。夜里画面本来就暗，夜间那一档默认原样。四边从不透明渐隐到黑（黑就是不亮），投到路面上是一块边缘柔和的画面。
 * 夜间模式时在透明度上再乘一个比例。</p>
 *
 * <p>来源：FerixzTanz 的 CAMCORDER（HudPanelPlacement、HudPictureTone、HudEdgeFade、HudNightDimming，GPL-3.0）：
 * 公式和他在自己 7X 上调出来的默认值照用（AppConfig.HUD_*_DEFAULT），代码自己写。纯 Java，见 {@code HudPictureTest}。</p>
 */
public final class HudPicture {

    /** 再小就看不清了（像素）。 */
    public static final int MIN_SIZE_PX = 48;
    /** 对比度曲线的枢轴，0..255：这一级灰不动，比它暗的压暗、比它亮的提亮。60% 灰。 */
    public static final float TONE_PIVOT = 153f;

    private HudPicture() {
    }

    /** 画面在 HUD 里的位置（像素）。 */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        Rect(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * @param heightPercent   高度，HUD 高度的百分比
     * @param shapePercent    宽 ÷ 高 ×100
     * @param edgePercent     离打灯那一侧边缘多远：剩余宽度的百分比
     * @param verticalPercent 上下位置：剩余高度的百分比
     * @param rightSide       右灯：从右边量；否则从左边量
     */
    public static Rect place(int hudWidth, int hudHeight, int heightPercent, int shapePercent,
                             int edgePercent, int verticalPercent, boolean rightSide) {
        int height = Math.round(hudHeight * heightPercent / 100f);
        height = Math.max(Math.min(MIN_SIZE_PX, hudHeight), Math.min(height, hudHeight));
        int width = Math.round(height * shapePercent / 100f);
        width = Math.max(Math.min(MIN_SIZE_PX, hudWidth), Math.min(width, hudWidth));
        int fromEdge = Math.round(Math.max(0, hudWidth - width) * clampPercent(edgePercent) / 100f);
        int left = rightSide ? hudWidth - width - fromEdge : fromEdge;
        int top = Math.round(Math.max(0, hudHeight - height) * clampPercent(verticalPercent) / 100f);
        return new Rect(left, top, width, height);
    }

    /** 对比度 100 就是原画面：不必套曲线。 */
    public static boolean toneIsIdentity(int contrastPercent) {
        return contrastPercent == 100;
    }

    /** 对比度曲线的 4×5 颜色矩阵（{@code android.graphics.ColorMatrix} 的数组，行优先，R G B A 四行）。 */
    public static float[] toneMatrix(int contrastPercent) {
        float k = contrastPercent / 100f;
        float offset = (1f - k) * TONE_PIVOT;
        return new float[]{
                k, 0, 0, 0, offset,
                0, k, 0, 0, offset,
                0, 0, k, 0, offset,
                0, 0, 0, 1, 0,
        };
    }

    /** 一级灰（0..255）过曲线之后是多少，钳在 0..255。 */
    public static float tone(int contrastPercent, float grey) {
        float k = contrastPercent / 100f;
        return Math.max(0f, Math.min(255f, k * grey + (1f - k) * TONE_PIVOT));
    }

    /**
     * 窗口显示时的不透明度，0..1。
     *
     * @param opacityPercent 设置里的透明度（100 是完全不透明）
     * @param nightPercent   夜间再乘的比例，100 是不调暗
     * @param night          车辆处于夜间模式（读不到按白天）
     */
    public static float opacity(int opacityPercent, int nightPercent, boolean night) {
        float base = opacityPercent / 100f;
        return night ? base * nightPercent / 100f : base;
    }

    /**
     * 边缘渐隐带的宽度（像素）：短边的百分比，画面再扁，四边看上去一样软。
     *
     * @return 0 表示硬边；最多是短边的一半（两条带正好在中线相接）
     */
    public static float fadePx(int percent, int width, int height) {
        if (percent <= 0 || width <= 0 || height <= 0) {
            return 0f;
        }
        float shorter = Math.min(width, height);
        return Math.min(shorter / 2f, shorter * percent / 100f);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
