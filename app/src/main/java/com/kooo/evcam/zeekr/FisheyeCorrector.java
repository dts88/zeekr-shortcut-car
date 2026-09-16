package com.kooo.evcam.zeekr;

import android.graphics.Bitmap;

import com.kooo.evcam.AppLog;

/**
 * 给<b>静态图片</b>做鱼眼校正：按格子切开，每一格逐像素重新映射。
 *
 * <h3>为什么是按格子切</h3>
 *
 * <p>环视照片落盘时已经拼成了 2×2（见 {@code SingleCamera.saveBitmapAsJPEG}）——
 * 一张图里装着四路，每一路各有自己的光心。整张一起校正等于把四个镜头当成一个，
 * 画面会朝图片中心塌进去。所以这里一格一格来。</p>
 *
 * <h3>为什么逐像素算，而不是像后视镜那样分片逼近</h3>
 *
 * <p>后视镜是每秒三十帧的实时画面，只能把输出切成小格、每格用一个矩阵近似
 * （见 {@link RearViewMirrorView}）。<b>照片没有这个约束</b>：一张图只算一次，
 * 那就把每个像素都老老实实算准。</p>
 *
 * <p>这件事值得做，是因为分片近似在照片上会露馅：格内是线性的，格与格之间斜率会跳一下 ——
 * 一条直线于是变成一段段折线，看着像波浪。实时画面上这点误差被运动和小窗口盖住了，
 * 静止的大图上一眼就能看见。逐像素重映射没有格子，也就没有波浪，
 * 直线该多直就多直，剩下的只有采样本身的软化。</p>
 *
 * <h3>方向</h3>
 *
 * <p>走的是<b>反向</b>映射：对输出的每个像素问「该去原图哪里取色」
 * （{@link FisheyeProjection#sourcePoint}），取回来的位置一般不在整像素上，
 * 所以做双线性采样。正向（原图这点画到哪里去）会在放大的地方留下空洞，
 * 这也是所有重映射都反着做的原因。</p>
 */
public final class FisheyeCorrector {

    private static final String TAG = "FisheyeCorrector";

    private static final float EPSILON = 1e-5f;

    private FisheyeCorrector() {
    }

    /**
     * 校正一张按 {@code columns × rows} 排列的合成图。
     *
     * @param fovDegrees 校正后画面的视野角度，和后视镜那一项是同一个含义
     * @return 新的位图；参数不合法或中途出错时原样返回入参，宁可不校正也不能没有图
     */
    public static Bitmap correctGrid(Bitmap source, int columns, int rows, float fovDegrees) {
        if (source == null || source.isRecycled() || columns < 1 || rows < 1) {
            return source;
        }
        int cellWidth = source.getWidth() / columns;
        int cellHeight = source.getHeight() / rows;
        if (cellWidth < 2 || cellHeight < 2) {
            return source;
        }

        Bitmap corrected;
        int[] cell;
        int[] remapped;
        try {
            corrected = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            // 一格的缓冲，四格轮流用：整张图两份的话峰值要翻一倍
            cell = new int[cellWidth * cellHeight];
            remapped = new int[cellWidth * cellHeight];
        } catch (OutOfMemoryError e) {
            AppLog.w(TAG, "校正用的缓冲申请不下来，原图照旧: " + e);
            return source;
        }

        float halfFovTangent = FisheyeProjection.halfFovTangent(fovDegrees);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                try {
                    source.getPixels(cell, 0, cellWidth,
                            column * cellWidth, row * cellHeight, cellWidth, cellHeight);
                    remapCell(cell, remapped, cellWidth, cellHeight, halfFovTangent);
                    corrected.setPixels(remapped, 0, cellWidth,
                            column * cellWidth, row * cellHeight, cellWidth, cellHeight);
                } catch (Exception e) {
                    AppLog.w(TAG, "第 " + row + "," + column + " 格校正失败: " + e);
                }
            }
        }
        return corrected;
    }

    /**
     * 一格之内的重映射。
     *
     * <p>取样点算出来超出本格时，{@link #sample} 会夹在边上 —— 这一步不是保险而是必须的：
     * 合成图里四路紧挨着，越界取到的就是隔壁那个摄像头的画面。</p>
     */
    private static void remapCell(int[] cell, int[] out, int width, int height,
                                  float halfFovTangent) {
        int index = 0;
        for (int y = 0; y < height; y++) {
            // 用像素中心，否则整幅画面会偏半个像素
            float planeY = ((y + 0.5f) / height * 2f - 1f) * halfFovTangent;
            for (int x = 0; x < width; x++, index++) {
                float planeX = ((x + 0.5f) / width * 2f - 1f) * halfFovTangent;
                float planeRadius = (float) Math.sqrt(planeX * planeX + planeY * planeY);
                if (planeRadius < EPSILON) {
                    out[index] = cell[(height / 2) * width + width / 2];
                    continue;
                }
                // 等距鱼眼：原图半径正比于射线角度。除以像平面半径，就把方向留了下来
                float scale = FisheyeProjection.sourceRadius(planeRadius) / planeRadius * 0.5f;
                float sourceX = (0.5f + planeX * scale) * width - 0.5f;
                float sourceY = (0.5f + planeY * scale) * height - 0.5f;
                out[index] = sample(cell, width, height, sourceX, sourceY);
            }
        }
    }

    /** 双线性采样，坐标夹在这一格里。 */
    private static int sample(int[] cell, int width, int height, float x, float y) {
        if (x < 0f) {
            x = 0f;
        } else if (x > width - 1) {
            x = width - 1;
        }
        if (y < 0f) {
            y = 0f;
        } else if (y > height - 1) {
            y = height - 1;
        }
        int left = (int) x;
        int top = (int) y;
        int right = Math.min(left + 1, width - 1);
        int bottom = Math.min(top + 1, height - 1);
        float fx = x - left;
        float fy = y - top;

        int topLeft = cell[top * width + left];
        int topRight = cell[top * width + right];
        int bottomLeft = cell[bottom * width + left];
        int bottomRight = cell[bottom * width + right];

        float wTopLeft = (1f - fx) * (1f - fy);
        float wTopRight = fx * (1f - fy);
        float wBottomLeft = (1f - fx) * fy;
        float wBottomRight = fx * fy;

        int alpha = blend(topLeft, topRight, bottomLeft, bottomRight,
                wTopLeft, wTopRight, wBottomLeft, wBottomRight, 24);
        int red = blend(topLeft, topRight, bottomLeft, bottomRight,
                wTopLeft, wTopRight, wBottomLeft, wBottomRight, 16);
        int green = blend(topLeft, topRight, bottomLeft, bottomRight,
                wTopLeft, wTopRight, wBottomLeft, wBottomRight, 8);
        int blue = blend(topLeft, topRight, bottomLeft, bottomRight,
                wTopLeft, wTopRight, wBottomLeft, wBottomRight, 0);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** 四个邻居在某一个通道上的加权和。 */
    private static int blend(int topLeft, int topRight, int bottomLeft, int bottomRight,
                             float wTopLeft, float wTopRight, float wBottomLeft,
                             float wBottomRight, int shift) {
        float value = ((topLeft >>> shift) & 0xFF) * wTopLeft
                + ((topRight >>> shift) & 0xFF) * wTopRight
                + ((bottomLeft >>> shift) & 0xFF) * wBottomLeft
                + ((bottomRight >>> shift) & 0xFF) * wBottomRight;
        int rounded = (int) (value + 0.5f);
        return rounded < 0 ? 0 : Math.min(rounded, 255);
    }
}
