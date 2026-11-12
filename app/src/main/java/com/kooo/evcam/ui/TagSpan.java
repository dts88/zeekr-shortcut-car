/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 名称旁的小标签（ui-spec §三「试验性标签」，项目所有者 2026-11-12）：试验性的功能不在名称后面写「（试验性）」，
 * 改成名称后面跟一个描边、两头圆的小标签。行名、组名都是一段文字，所以做成文字里的一段（{@link #append}），不用另加控件。
 *
 * <p>字是说明那一档（{@code TextAppearance.Cam.Micro}），次要文字色；描边是最弱一级文字色。</p>
 */
public final class TagSpan extends ReplacementSpan {

    private final float textSize;
    private final int textColor;
    private final int strokeColor;
    private final float gap;
    private final float padding;
    private final float stroke;
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private TagSpan(Context context) {
        TypedArray array = context.obtainStyledAttributes(R.style.TextAppearance_Cam_Micro,
                new int[] {android.R.attr.textSize});
        textSize = array.getDimension(0, 0f);
        array.recycle();
        textColor = ContextCompat.getColor(context, R.color.text_secondary);
        strokeColor = ContextCompat.getColor(context, R.color.text_tertiary);
        float density = context.getResources().getDisplayMetrics().density;
        gap = 10 * density;
        padding = 10 * density;
        stroke = Math.max(1f, density);
    }

    /** {@code name} 后面跟一个标签。 */
    public static CharSequence append(Context context, CharSequence name, int tagRes) {
        SpannableStringBuilder out = new SpannableStringBuilder(name == null ? "" : name);
        int start = out.length();
        out.append(context.getString(tagRes));
        out.setSpan(new TagSpan(context), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
        tagPaint.set(paint);
        tagPaint.setTextSize(textSize);
        tagPaint.setFakeBoldText(false);
        return Math.round(gap + padding * 2 + tagPaint.measureText(text, start, end));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     @NonNull Paint paint) {
        tagPaint.set(paint);
        tagPaint.setTextSize(textSize);
        tagPaint.setFakeBoldText(false);
        float width = tagPaint.measureText(text, start, end);
        Paint.FontMetrics metrics = tagPaint.getFontMetrics();
        float height = (metrics.descent - metrics.ascent) + 4 * stroke;
        float middle = (top + bottom) / 2f;
        box.set(x + gap, middle - height / 2f, x + gap + padding * 2 + width, middle + height / 2f);
        tagPaint.setStyle(Paint.Style.STROKE);
        tagPaint.setStrokeWidth(stroke);
        tagPaint.setColor(strokeColor);
        canvas.drawRoundRect(box, height / 2f, height / 2f, tagPaint);
        tagPaint.setStyle(Paint.Style.FILL);
        tagPaint.setColor(textColor);
        canvas.drawText(text, start, end, box.left + padding, middle - (metrics.ascent + metrics.descent) / 2f, tagPaint);
    }
}
