/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.HudPicture;

/**
 * HUD 的示意（ui-spec §十六「位置与大小」）：一块 3 : 1 的深色（HUD 那块屏是 1440 × 480），里面画着左、右两个盲区画面现在的位置和大小。
 * 框里的拖动条一动它就跟着变，人不用猜「离边距离 11」是多远。
 *
 * <p>位置和大小用盲区画面自己那份算法（{@link HudPicture#place}），画出来的比例就是 HUD 上的比例。它只是画：不开相机，
 * 也不让 HUD 显示画面。左侧的实线、垫能量色淡底；右侧的虚线（两个画面位置对称，看一个就够）。边缘渐隐画成框里再缩一圈的细线。</p>
 */
final class HudSketchView extends View {

    /** HUD 那块屏的尺寸，只用来定比例。 */
    private static final int HUD_WIDTH = 1440;
    private static final int HUD_HEIGHT = 480;

    private int heightPercent;
    private int shapePercent;
    private int edgePercent;
    private int verticalPercent;
    private int fadePercent;

    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;
    private final String left;
    private final String right;
    private final String label;

    HudSketchView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        // HUD 是投影，黑色是不亮：示意的底固定深色，不跟日夜配色走
        ground.setColor(0xFF101114);
        int energy = ContextCompat.getColor(context, R.color.energy);
        fill.setColor(energy);
        fill.setAlpha(46);
        edge.setColor(energy);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(3 * density);
        dashed.setColor(0xB3FFFFFF);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(2 * density);
        dashed.setPathEffect(new DashPathEffect(new float[] {8 * density, 6 * density}, 0));
        faint.setColor(0x66FFFFFF);
        faint.setStyle(Paint.Style.STROKE);
        faint.setStrokeWidth(density);
        TypedArray array = context.obtainStyledAttributes(R.style.TextAppearance_Cam_Micro,
                new int[] {android.R.attr.textSize});
        text.setTextSize(array.getDimension(0, 14 * density));
        array.recycle();
        text.setColor(0xCCFFFFFF);
        left = context.getString(R.string.side_view_editor_left);
        right = context.getString(R.string.side_view_editor_right);
        label = context.getString(R.string.hud_sketch_label);
    }

    void set(int height, int shape, int edgeDistance, int vertical, int fade) {
        heightPercent = height;
        shapePercent = shape;
        edgePercent = edgeDistance;
        verticalPercent = vertical;
        fadePercent = fade;
        invalidate();
    }

    void setHeightPercent(int value) {
        heightPercent = value;
        invalidate();
    }

    void setShapePercent(int value) {
        shapePercent = value;
        invalidate();
    }

    void setEdgePercent(int value) {
        edgePercent = value;
        invalidate();
    }

    void setVerticalPercent(int value) {
        verticalPercent = value;
        invalidate();
    }

    void setFadePercent(int value) {
        fadePercent = value;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(width * (float) HUD_HEIGHT / HUD_WIDTH));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float radius = getResources().getDimension(R.dimen.corner_radius);
        box.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(box, radius, radius, ground);
        float scale = getWidth() / (float) HUD_WIDTH;
        drawPicture(canvas, scale, false);
        drawPicture(canvas, scale, true);
        canvas.drawText(label, 14 * density, getHeight() - 12 * density, text);
    }

    private void drawPicture(Canvas canvas, float scale, boolean rightSide) {
        HudPicture.Rect rect = HudPicture.place(HUD_WIDTH, HUD_HEIGHT, heightPercent, shapePercent, edgePercent,
                verticalPercent, rightSide);
        box.set(rect.left * scale, rect.top * scale, (rect.left + rect.width) * scale, (rect.top + rect.height) * scale);
        float corner = 6 * density;
        if (!rightSide) {
            canvas.drawRoundRect(box, corner, corner, fill);
        }
        canvas.drawRoundRect(box, corner, corner, rightSide ? dashed : edge);
        String name = rightSide ? right : left;
        canvas.drawText(name, box.centerX() - text.measureText(name) / 2f,
                box.centerY() - (text.ascent() + text.descent()) / 2f, text);
        // 边缘渐隐：画面四边往里这么宽是淡出去的
        float fade = HudPicture.fadePx(fadePercent, rect.width, rect.height) * scale;
        if (fade >= 2 * density && fade * 2 < Math.min(box.width(), box.height())) {
            box.inset(fade, fade);
            canvas.drawRoundRect(box, corner, corner, faint);
        }
    }
}
