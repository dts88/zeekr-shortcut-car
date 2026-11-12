/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.setup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 初始设置「驾驶座位置」那一步的小图（{@link SetupFragment}）：一块屏幕，操作按钮那一列在左边或者右边。
 * 那一列用能量色，画面那一块用中性灰 —— 看的是「按钮在哪一侧」，不是屏幕上真有什么。
 */
public class RailSideSketch extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private boolean railOnLeft;

    public RailSideSketch(Context context) {
        this(context, null);
    }

    public RailSideSketch(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
    }

    public void setRailOnLeft(boolean left) {
        if (railOnLeft != left) {
            railOnLeft = left;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 中控屏应用区域的比例（1600 : 848），宽 240dp
        int width = Math.round(240 * density);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                resolveSize(Math.round(width * 848f / 1600f), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float corner = 8 * density;
        float inset = 6 * density;
        float stroke = 1.5f * density;

        // 屏幕
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.surface));
        canvas.drawRoundRect(rect, corner, corner, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.line));
        canvas.drawRoundRect(rect, corner, corner, paint);

        // 操作按钮那一列、画面那一块
        float rail = (w - inset * 3) * 0.2f;
        float railLeft = railOnLeft ? inset : w - inset - rail;
        float viewLeft = railOnLeft ? inset * 2 + rail : inset;
        float viewRight = railOnLeft ? w - inset : w - inset * 2 - rail;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.preview_frame_background));
        rect.set(viewLeft, inset, viewRight, h - inset);
        canvas.drawRoundRect(rect, corner / 2, corner / 2, paint);
        paint.setColor(ContextCompat.getColor(getContext(), R.color.energy));
        float gap = 4 * density;
        float first = (h - inset * 2) * 0.3f;
        rect.set(railLeft, inset, railLeft + rail, inset + first);
        canvas.drawRoundRect(rect, corner / 2, corner / 2, paint);
        paint.setAlpha(110);
        float rest = (h - inset * 2 - first - gap * 3) / 3f;
        float top = inset + first + gap;
        for (int i = 0; i < 3; i++) {
            rect.set(railLeft, top, railLeft + rail, top + rest);
            canvas.drawRoundRect(rect, corner / 2, corner / 2, paint);
            top += rest + gap;
        }
        paint.setAlpha(255);
    }
}
