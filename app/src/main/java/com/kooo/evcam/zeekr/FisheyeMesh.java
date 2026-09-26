package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.Matrix;

/**
 * 在不碰相机链路的前提下，把一个 TextureView 按鱼眼校正画出来：分格反投影。
 *
 * <p>超级后视镜、主界面预览、视频回看都走这一套。三处的共同点是：画面在一个普通的
 * TextureView 里，相机（或解码器）那一头不动，能动的只有「父容器怎么把它画出来」。
 * 为什么预览不上 GL，见 docs/zeekr-platform-notes.md §2.1。</p>
 *
 * <h3>做法</h3>
 *
 * <p>把要画的那块目标区域切成 N×N 个小格。每一格的四个角各算一次
 * {@link FisheyeProjection} 的反投影，得到子视图上的四个源点，再用 {@code setPolyToPoly}
 * 定出一个透视矩阵，把子视图画进这一格。格内是透视变换，格与格共用角点，所以拼起来没有缝。</p>
 *
 * <h3>格子切多密：误差和开销的取舍</h3>
 *
 * <p>格内用一个透视矩阵代替那条曲线，格子中间会偏一点；偏多少随格子在屏幕上的大小平方变。
 * 按每路在屏幕上显示 800px 量过（格内最大偏差，屏幕像素）：</p>
 *
 * <pre>
 *                     每边 10 格   16 格    20 格
 *   直线投影 110°        4.8       1.9      1.2
 *   直线投影 140°       12.1       4.8      3.1
 *   柱面投影 180°        7.3       3.1      2.0
 *   立体投影 180°        2.7       1.1      0.7
 * </pre>
 *
 * <p>代价是每一格都要把子视图整个重画一次（裁到那一格），四宫格就是 4×N² 次。
 * 所以格数按目标在屏幕上多大来定，每格约 {@link #TILE_PX} 像素，再夹在
 * [{@link #MIN_DIVISIONS}, {@link #MAX_DIVISIONS}] 里：四宫格每路约 800px 是 16 格、
 * 四路共 1024 次；单独放大一路是 24 格、576 次。把格子往四周加密试过，只好 10–15%，不值得。</p>
 *
 * <p>算网格的那部分是纯 Java，可以直接跑单元测试；只有 {@link #draw} 碰画布。</p>
 */
public final class FisheyeMesh {

    /** 一格在屏幕上大约多宽（px）。 */
    public static final float TILE_PX = 50f;
    public static final int MIN_DIVISIONS = 8;
    public static final int MAX_DIVISIONS = 24;

    /** 把子视图画一次。容器实现它，里面就是一句 {@code drawChild}。 */
    public interface Painter {
        void paint(Canvas canvas);
    }

    private float fovDegrees = FisheyeProjection.PHOTO_FOV_DEGREES;
    private String projection = FisheyeProjection.PROJECTION_RECTILINEAR;
    private float strength = 1f;

    private int divisions;
    /** (N+1)² 个源点，子视图坐标，按行排：[x0, y0, x1, y1, ...]。 */
    private float[] grid = new float[0];
    private final float[] point = new float[2];
    private final float[] source = new float[8];
    private final float[] dest = new float[8];
    /** 第一次画的时候才建：算网格的那部分要能在没有 Android 的 JVM 上跑。 */
    private Matrix matrix;

    /** 校正用哪种投影、多大视野、多大强度。含义和 {@link FisheyeProjection#sourcePoint} 一样。 */
    public void setCorrection(float fovDegrees, String projection, float strength) {
        this.projection = projection == null ? FisheyeProjection.PROJECTION_RECTILINEAR : projection;
        this.fovDegrees = FisheyeProjection.clampFov(fovDegrees, this.projection);
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    public float fovDegrees() {
        return fovDegrees;
    }

    public String projection() {
        return projection;
    }

    public float strength() {
        return strength;
    }

    /** 目标在屏幕上有多大（取长边，px），就切多少格。 */
    public static int divisionsFor(float displayedPx) {
        int wanted = Math.round(displayedPx / TILE_PX);
        return Math.max(MIN_DIVISIONS, Math.min(MAX_DIVISIONS, wanted));
    }

    /**
     * 算网格。
     *
     * @param divisions 每边几格
     * @param laneLeft  这一路原始画面在子视图里的位置和大小（子视图坐标）
     * @param windowLeft 校正后的画面里要看哪一块，这一路里的归一化坐标；整幅是 0,0,1,1
     */
    public void prepare(int divisions,
                        float laneLeft, float laneTop, float laneWidth, float laneHeight,
                        float windowLeft, float windowTop, float windowWidth, float windowHeight) {
        this.divisions = Math.max(1, divisions);
        int side = this.divisions + 1;
        if (grid.length != side * side * 2) {
            grid = new float[side * side * 2];
        }
        int index = 0;
        for (int row = 0; row < side; row++) {
            float v = windowTop + windowHeight * row / this.divisions;
            for (int column = 0; column < side; column++) {
                float u = windowLeft + windowWidth * column / this.divisions;
                // 校正后画面里的一点 → 原始鱼眼画面里的采样点（这一路内，夹在 0..1）
                FisheyeProjection.sourcePoint(u, v, fovDegrees, projection, strength, point, 0);
                grid[index++] = laneLeft + point[0] * laneWidth;
                grid[index++] = laneTop + point[1] * laneHeight;
            }
        }
    }

    public int divisions() {
        return divisions;
    }

    /** 网格上第 row 行、第 column 列那个角点对应的源点（子视图坐标）。 */
    public float sourceX(int row, int column) {
        return grid[(row * (divisions + 1) + column) * 2];
    }

    public float sourceY(int row, int column) {
        return grid[(row * (divisions + 1) + column) * 2 + 1];
    }

    /**
     * 逐格画进目标矩形。先 {@link #prepare}。
     *
     * <p>每一格先裁到它在目标里的位置，再把子视图按四个角定出的透视矩阵画过去。
     * 裁剪在变换之前做，所以是屏幕上的一个矩形 —— 这一格之外的像素不会画出来，
     * 也就不会有别的格子、别的那一路漏进来。</p>
     *
     * @return 实际画了几格；四个角挤成一条线的格子画不出来，跳过
     */
    public int draw(Canvas canvas, float targetLeft, float targetTop,
                    float targetWidth, float targetHeight, Painter painter) {
        if (matrix == null) {
            matrix = new Matrix();
        }
        int drawn = 0;
        for (int row = 0; row < divisions; row++) {
            float top = targetTop + targetHeight * row / divisions;
            float bottom = targetTop + targetHeight * (row + 1) / divisions;
            for (int column = 0; column < divisions; column++) {
                float left = targetLeft + targetWidth * column / divisions;
                float right = targetLeft + targetWidth * (column + 1) / divisions;

                // 顺序：左上、右上、右下、左下
                dest[0] = left;  dest[1] = top;
                dest[2] = right; dest[3] = top;
                dest[4] = right; dest[5] = bottom;
                dest[6] = left;  dest[7] = bottom;
                source[0] = sourceX(row, column);         source[1] = sourceY(row, column);
                source[2] = sourceX(row, column + 1);     source[3] = sourceY(row, column + 1);
                source[4] = sourceX(row + 1, column + 1); source[5] = sourceY(row + 1, column + 1);
                source[6] = sourceX(row + 1, column);     source[7] = sourceY(row + 1, column);

                matrix.reset();
                if (!matrix.setPolyToPoly(source, 0, dest, 0, 4)) {
                    continue;
                }
                int save = canvas.save();
                canvas.clipRect(left, top, right, bottom);
                canvas.concat(matrix);
                painter.paint(canvas);
                canvas.restoreToCount(save);
                drawn++;
            }
        }
        return drawn;
    }
}
