package com.kooo.evcam.zeekr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.kooo.evcam.AppLog;

/**
 * 给<b>静态图片</b>做鱼眼校正：把一张画面按格子切开，每一格单独校正，画回原来的位置。
 *
 * <h3>为什么是按格子切</h3>
 *
 * <p>环视照片落盘时已经拼成了 2×2（见 {@code SingleCamera.saveBitmapAsJPEG}）——
 * 一张图里装着四路，每一路各有自己的光心。整张一起校正等于把四个镜头当成一个，
 * 画面会朝图片中心塌进去。所以这里一格一格来。</p>
 *
 * <h3>为什么用 drawBitmapMesh 而不是后视镜那套</h3>
 *
 * <p>后视镜是实时画面，只能在绘制时逐格 {@code setPolyToPoly}（见
 * {@link RearViewMirrorView}）。照片是死的，可以一次算完：{@code drawBitmapMesh}
 * 天生就是「把位图按网格拉到指定顶点上」，一次调用画完一格，没有分片接缝，
 * 采样也由它自己做。整张图只算一次，之后交给 Glide 缓存。</p>
 */
public final class FisheyeCorrector {

    private static final String TAG = "FisheyeCorrector";

    private FisheyeCorrector() {
    }

    /**
     * 校正一张按 {@code columns × rows} 排列的合成图。
     *
     * @return 新的位图；参数不合法或中途出错时原样返回入参，宁可不校正也不能没有图
     */
    public static Bitmap correctGrid(Bitmap source, int columns, int rows) {
        if (source == null || source.isRecycled() || columns < 1 || rows < 1) {
            return source;
        }
        int cellWidth = source.getWidth() / columns;
        int cellHeight = source.getHeight() / rows;
        if (cellWidth < 2 || cellHeight < 2) {
            return source;
        }

        Bitmap corrected;
        try {
            corrected = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            AppLog.w(TAG, "校正用的位图申请不下来，原图照旧: " + e);
            return source;
        }

        Canvas canvas = new Canvas(corrected);
        // 只要双线性采样，不要抗锯齿：网格是紧挨着的，抗锯齿反而会在格线上留下缝
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        int divisions = FisheyeProjection.PHOTO_MESH_DIVISIONS;
        float[] vertices = new float[(divisions + 1) * (divisions + 1) * 2];
        float[] point = new float[2];

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                Bitmap cell = null;
                try {
                    cell = Bitmap.createBitmap(source, column * cellWidth, row * cellHeight,
                            cellWidth, cellHeight);
                    int i = 0;
                    for (int y = 0; y <= divisions; y++) {
                        for (int x = 0; x <= divisions; x++) {
                            FisheyeProjection.correctedPoint(
                                    (float) x / divisions, (float) y / divisions, point, 0);
                            vertices[i++] = column * cellWidth + point[0] * cellWidth;
                            vertices[i++] = row * cellHeight + point[1] * cellHeight;
                        }
                    }
                    // 四个角落在本格之外（见 FisheyeProjection#keepCircleScale），
                    // 不裁的话它们会画到隔壁那一路上去
                    int save = canvas.save();
                    canvas.clipRect(column * cellWidth, row * cellHeight,
                            (column + 1) * cellWidth, (row + 1) * cellHeight);
                    canvas.drawBitmapMesh(cell, divisions, divisions, vertices, 0, null, 0, paint);
                    canvas.restoreToCount(save);
                } catch (Exception e) {
                    AppLog.w(TAG, "第 " + row + "," + column + " 格校正失败: " + e);
                } finally {
                    if (cell != null && cell != source) {
                        cell.recycle();
                    }
                }
            }
        }
        return corrected;
    }
}
