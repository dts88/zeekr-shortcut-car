/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

import android.graphics.Bitmap;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.zeekr.FisheyeCorrector;

import java.io.ByteArrayOutputStream;

/**
 * 提醒附带的那张图：要不要掰直、盖上时间、压多大。纯图形逻辑，不碰网络。
 *
 * <h3>一张 2×2</h3>
 *
 * <p>环视拍下来的是 2×2 四宫格（照片跟着这一路录制的排列走，{@code SingleCamera.saveBitmapAsJPEG}）：
 * 整张发出去，手机上一眼看全四个方向。</p>
 *
 * <h3>掰直</h3>
 *
 * <p>用的就是屏幕上那个鱼眼校正（{@link FisheyeCorrector#correctGrid}，同一套几何、同一组参数），不是另做一套 ——
 * 车主在手机上看到的和在车上看到的是一回事。不是四宫格的图（录制排列选了整幅显示）它自己认出来、原样还回来。</p>
 *
 * <h3>压多大</h3>
 *
 * <p>车上 4G 上传越小越不容易被掐断，所以压到 {@link PictureBudget#ALERT_BYTES} 以内：先降画质，再缩小
 * （{@link PictureBudget}），哪一档都压不进去就不附图，只发文字。</p>
 *
 * The picture attached to an alert: whether to straighten it, the moment stamped on it, and how much to
 * compress it. Pure graphics logic; no network access. The surround view photo is one 2x2 grid, sent
 * whole. Straightening uses the same fisheye correction as the screen; a picture that is not a grid is
 * handed back unchanged by the corrector itself. On the car's 4G a small upload is less likely to be cut
 * off, so it is squeezed under {@link PictureBudget#ALERT_BYTES} — quality first, then size — and if
 * nothing fits, the alert goes out as text only.
 */
public final class AlertPicture {

    /** 环视合成图的格子排布：2 列 2 行。The grid layout of the surround composite: 2 columns, 2 rows. */
    public static final int COLUMNS = 2;
    public static final int ROWS = 2;

    private static final String TAG = "AlertPicture";

    private AlertPicture() {
    }

    /**
     * 掰直整张四宫格。开关关着、或者没图时原样还回来。
     *
     * @param fovDegrees 视野角度（和屏幕上那一项同一个含义）
     * @param projection 投影方式
     *
     * Straighten the whole grid picture. Return it unchanged when the switch is off or there is no
     * picture.
     */
    public static Bitmap straighten(Bitmap grid, boolean straighten, float fovDegrees,
                                    String projection) {
        if (!straighten || grid == null || grid.isRecycled()) {
            return grid;
        }
        try {
            return FisheyeCorrector.correctGrid(grid, COLUMNS, ROWS, fovDegrees, projection);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "straighten failed, sending the picture as it is: " + e);
            return grid;
        }
    }

    /**
     * 压进预算：先降画质，再缩小，到 {@code maxBytes} 以内就停（{@link PictureBudget}）。
     *
     * @return 压好的 JPEG；哪一档都压不进去时返回 {@code null}（不发超预算的图）
     *
     * Compress into the budget: quality first, then size, stopping once it fits ({@link PictureBudget}).
     *
     * @return the JPEG, or {@code null} when no step fits (an over-budget picture is never sent)
     */
    public static byte[] jpegUnder(Bitmap bitmap, int maxBytes) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        int budget = maxBytes > 0 ? maxBytes : PictureBudget.ALERT_BYTES;
        for (int step = 0; step < PictureBudget.steps(); step++) {
            float[] plan = PictureBudget.step(step);
            if (plan == null) {
                break;
            }
            float scale = plan[0];
            int quality = (int) plan[1];
            Bitmap source = bitmap;
            Bitmap scaled = null;
            if (PictureBudget.shrinks(scale)) {
                int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
                int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
                scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
                source = scaled;
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                source.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
                byte[] encoded = bytes.toByteArray();
                if (PictureBudget.fits(encoded.length, budget)) {
                    return encoded;
                }
            } finally {
                if (scaled != null && scaled != bitmap && !scaled.isRecycled()) {
                    scaled.recycle();
                }
            }
        }
        return null;
    }

    /**
     * 把一行字盖在图的左下角：那一刻的时间跟着图走，图转手几道也还在。
     *
     * <p>画在<b>新</b>的一张上，源图不动也不回收。白字配一层深色影子，影子是偏移着重画一遍而不是模糊的，
     * 压成 JPEG 之后边缘还在。边距取宽度的 2%，字号取高度的 3.5%，缩到手机上看仍然读得出来。</p>
     *
     * @param source 源图，可以是不可变的
     * @param text   要盖的字；{@code null} 或空串就只是复制一张
     * @return 一张新图；源图为空或已回收时返回 {@code null}
     *
     * Draw one line into the picture's bottom-left corner, onto a <b>new</b> bitmap; the source is
     * neither changed nor recycled. White text with a dark shadow drawn as an offset second pass rather
     * than a blur, so the edge survives JPEG.
     */
    public static Bitmap stamped(Bitmap source, String text) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        try {
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            canvas.drawBitmap(source, 0f, 0f, null);
            if (text == null || text.isEmpty()) {
                return out;
            }
            float margin = width * 0.02f;
            float size = height * 0.035f;
            android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(size);
            float baseline = height - margin;
            // 影子单独画一遍：模糊的 setShadowLayer 压过 JPEG 就糊成一片，偏移的硬边不会。
            // The shadow is its own pass: a blurred setShadowLayer smears away under JPEG, an
            // offset hard edge does not.
            float offset = Math.max(1f, size * 0.08f);
            android.graphics.Paint shadow = new android.graphics.Paint(paint);
            shadow.setColor(android.graphics.Color.argb(220, 0, 0, 0));
            canvas.drawText(text, margin + offset, baseline + offset, shadow);
            paint.setColor(android.graphics.Color.WHITE);
            canvas.drawText(text, margin, baseline, paint);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "could not stamp the moment onto the picture: " + e);
            return null;
        }
    }
}
