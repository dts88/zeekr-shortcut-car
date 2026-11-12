/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 指给你看（ui-spec §十四）：从主界面、状态条点到设置里的某一行时，那一行描一圈能量色的边、垫一层能量色的淡底，闪两下。
 *
 * <p>画在这一行的覆盖层上（{@code ViewOverlay}），不动这一行自己的背景和布局，闪完拿掉。装饰动效不放时
 * （{@link MotionPolicy}：系统关了动画，或者在录制而开着「录制时减少动效」）不闪，亮着停一会儿再拿掉 —— 人还是得知道是哪一行。</p>
 */
public final class RowFlash {

    private RowFlash() {
    }

    public static void play(View row) {
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable mark = new GradientDrawable();
        mark.setCornerRadius(context.getResources().getDimension(R.dimen.corner_radius));
        mark.setColor(ContextCompat.getColor(context, R.color.energy_quiet));
        mark.setStroke(Math.round(3 * density), ContextCompat.getColor(context, R.color.energy));
        mark.setBounds(0, 0, row.getWidth(), row.getHeight());
        row.getOverlay().add(mark);

        if (!MotionPolicy.decorative(context)) {
            mark.setAlpha(110);
            row.postDelayed(() -> row.getOverlay().remove(mark), Motion.FLASH * 3);
            return;
        }
        // 两下：亮、暗、亮、暗。淡底盖在字上，所以最亮也只到一半
        ValueAnimator blink = ValueAnimator.ofInt(0, 130, 0, 130, 0);
        blink.setDuration(Motion.FLASH * 4);
        blink.setInterpolator(Motion.standard());
        blink.addUpdateListener(animation -> {
            mark.setAlpha((Integer) animation.getAnimatedValue());
            row.invalidate();
        });
        blink.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                row.getOverlay().remove(mark);
            }
        });
        blink.start();
    }
}
