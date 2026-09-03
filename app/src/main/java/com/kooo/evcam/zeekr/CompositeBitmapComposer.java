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
     * @param source 原始合成图（如 1280x5140）
     * @param order  长度为 4 的排列，order[格子位置] = 画面序号；null 表示默认顺序
     * @return 2x2 四宫格图；若来源不是合成图或重排失败，返回 {@code source} 本身
     */
    public static Bitmap toGrid(Bitmap source, int[] order) {
        if (source == null || source.isRecycled()) {
            return source;
        }

        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        if (!CompositeStreamGeometry.looksLikeComposite(CompositeSplitProfile.compositeCameraId(), srcWidth, srcHeight)) {
            return source;
        }

        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(CompositeSplitProfile.compositeCameraId(), srcWidth, srcHeight, 0);
        if (!plan.isComposite() || plan.laneCount() < CompositeStreamGeometry.LANE_COUNT) {
            return source;
        }

        int[] cellOrder = {0, 1, 2, 3};
        if (order != null && order.length == CompositeStreamGeometry.LANE_COUNT) {
            cellOrder = order;
        }

        // 每个画面都是正方形，2x2 输出边长 = 2 倍画面边长
        int lane = plan.laneSizePx;
        if (lane <= 0) {
            return source;
        }
        int outSize = lane * 2;

        Bitmap grid;
        try {
            grid = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            AppLog.e(TAG, "内存不足，无法生成 " + outSize + "x" + outSize + " 四宫格图，保存原图");
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
            int left = (cell % 2) * lane;
            int top = (cell / 2) * lane;
            dstRect.set(left, top, left + lane, top + lane);

            canvas.drawBitmap(source, srcRect, dstRect, paint);
        }

        AppLog.d(TAG, "照片已重排为四宫格: " + srcWidth + "x" + srcHeight
                + " -> " + outSize + "x" + outSize);
        return grid;
    }
}
