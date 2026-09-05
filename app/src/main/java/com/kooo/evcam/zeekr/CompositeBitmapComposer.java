package com.kooo.evcam.zeekr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.kooo.evcam.AppLog;

/**
 * 把一张「四联合成流」静态图重排成 2x2 四宫格。
 *
 * <p>视频走的是 GL 路径（见 {@code EglSurfaceEncoder} 的四宫格渲染），拍照拿到的是
 * 一张普通 Bitmap，用 Canvas 重排更直接，也不用去碰相机会话。</p>
 *
 * <p>拆分位置来自 {@link CompositeStreamGeometry}，与预览、录制用的是同一套几何，
 * 三处结果一致。</p>
 */
public final class CompositeBitmapComposer {

    private static final String TAG = "CompositeBitmapComposer";

    private CompositeBitmapComposer() {
    }

    /**
     * 把合成图重排成 2x2。
     *
     * <p>不修改也不回收 {@code source}，调用方仍然拥有它。</p>
     *
     * @param cameraId 这张图是<b>哪一路</b>拍的。必须传 —— 拆不拆只取决于这个，
     *                 而座舱那两路的照片是一整幅画面，切成四格就毁了。
     * @param source 原始合成图（如 1280x5140）
     * @param order  长度为 4 的排列，order[格子位置] = 画面序号；null 表示默认顺序
     * @return 2x2 四宫格图；若来源不是合成图或重排失败，返回 {@code source} 本身
     */
    public static Bitmap toGrid(String cameraId, Bitmap source, int[] order) {
        if (source == null || source.isRecycled()) {
            return source;
        }

        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        // 用这张图<b>实际来自</b>的相机来判断，不能拿「哪一路是合成流」冒充。
        // 以前这里写死了合成流那一路的 id，于是座舱拍的照片也被当成合成图切开。
        if (!CompositeStreamGeometry.looksLikeComposite(cameraId, srcWidth, srcHeight)) {
            return source;
        }

        CompositeStreamGeometry.Plan plan =
                CompositeStreamGeometry.analyse(cameraId, srcWidth, srcHeight);
        if (!plan.isComposite() || plan.laneCount() < CompositeStreamGeometry.LANE_COUNT) {
            return source;
        }

        int[] cellOrder = {0, 1, 2, 3};
        if (order != null && order.length == CompositeStreamGeometry.LANE_COUNT) {
            cellOrder = order;
        }

        // 2x2 输出：宽高各取单格的两倍。
        //
        // 不能拿 laneSizePx 当边长 —— 那个字段是「排布方向上的格长」，
        // 只在单格是正方形时才等于宽和高。3840x2160 的单格是 3840x540，
        // 硬当正方形算会得出 1080x1080 —— 四个画面全被压扁，
        // 而且无论怎么改分辨率都卡在这个尺寸上。
        CompositeStreamGeometry.Lane first = plan.lane(0);
        int outWidth = first.width * 2;
        int outHeight = first.height * 2;
        if (outWidth <= 0 || outHeight <= 0) {
            return source;
        }

        Bitmap grid;
        try {
            grid = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            AppLog.e(TAG, "内存不足，无法生成 " + outWidth + "x" + outHeight
                    + " 四宫格图，保存原图");
            return source;
        }

        Canvas canvas = new Canvas(grid);
        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        Rect srcRect = new Rect();
        Rect dstRect = new Rect();
        for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
            int laneIndex = cellOrder[cell];
            if (laneIndex < 0 || laneIndex >= plan.laneCount()) {
                continue;
            }
            CompositeStreamGeometry.Lane l = plan.lane(laneIndex);

            srcRect.set(l.x, l.y, l.x + l.width, l.y + l.height);
            int cellWidth = outWidth / 2;
            int cellHeight = outHeight / 2;
            int left = (cell % 2) * cellWidth;
            int top = (cell / 2) * cellHeight;
            dstRect.set(left, top, left + cellWidth, top + cellHeight);

            canvas.drawBitmap(source, srcRect, dstRect, paint);
        }

        AppLog.d(TAG, "照片已重排为四宫格: " + srcWidth + "x" + srcHeight
                + " -> " + outWidth + "x" + outHeight);
        return grid;
    }
}
