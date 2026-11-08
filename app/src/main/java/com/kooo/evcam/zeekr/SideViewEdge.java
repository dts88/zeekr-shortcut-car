/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * 一个盲区画面窗口里的边框（规则在 {@link SideViewAlert}）：左右两侧各记一级，画在那一侧画面的四周。
 *
 * <p>闪不另起计时：窗口本来就每 {@link SideViewAlert#BLINK_HALF_MS} ms 走一次（看画面新不新），那时叫 {@link #tick}，
 * 有一侧在闪就换亮暗、让窗口重画。没有在闪的就不重画 —— 常亮的边框只在变的时候画一次。只在主线程用。</p>
 */
final class SideViewEdge {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int left = SideViewAlert.NONE;
    private int right = SideViewAlert.NONE;
    private boolean motion = true;
    /** 闪的亮那一半。 */
    private boolean bright = true;

    SideViewEdge() {
        paint.setStyle(Paint.Style.STROKE);
    }

    /**
     * 服务每次判定都给一次（{@link SideViewOutputWindow#setAlert}）。
     *
     * @return 变了（要重画）
     */
    boolean set(int side, int level, boolean motion) {
        int before = level(side);
        if (before == level && this.motion == motion) {
            return false;
        }
        if (side == LaneCycle.LEFT) {
            left = level;
        } else {
            right = level;
        }
        this.motion = motion;
        if (SideViewAlert.blinks(level, motion) && !SideViewAlert.blinks(before, motion)) {
            // 刚开始闪：先亮
            bright = true;
        }
        return true;
    }

    int level(int side) {
        return side == LaneCycle.LEFT ? left : right;
    }

    /**
     * 窗口的定时那一下：显示着的那几侧有一侧在闪就换亮暗。
     *
     * @return 要重画
     */
    boolean tick(boolean leftShown, boolean rightShown) {
        boolean blinking = leftShown && SideViewAlert.blinks(left, motion)
                || rightShown && SideViewAlert.blinks(right, motion);
        if (!blinking) {
            bright = true;
            return false;
        }
        bright = !bright;
        return true;
    }

    /** 画在 (0, 0)–(width, height) 这一块的四周，线整个在里面；这一侧不描边就什么都不画。 */
    void draw(Canvas canvas, int side, float width, float height, float strokePx) {
        int color = SideViewAlert.color(level(side), motion, bright);
        if (color == 0 || width <= strokePx || height <= strokePx) {
            return;
        }
        paint.setColor(color);
        paint.setStrokeWidth(strokePx);
        float half = strokePx / 2f;
        canvas.drawRect(half, half, width - half, height - half, paint);
    }

    /** {@code describe()} 里的记号：none / yellow / red / red+blink。 */
    String describe(int side) {
        int level = level(side);
        return level == SideViewAlert.NONE ? "none" : level == SideViewAlert.CAUTION ? "yellow"
                : SideViewAlert.blinks(level, motion) ? "red+blink" : "red";
    }
}
