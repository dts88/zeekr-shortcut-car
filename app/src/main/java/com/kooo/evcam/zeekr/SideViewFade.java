/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

/**
 * 盲区画面收起前的淡出（项目所有者 2026-11-04）：画面在给定的时间里从正常匀速淡到看不见。
 *
 * <p>淡到哪了交给 {@link Target} 画：中控屏的窗口一个窗口一个画面，淡整个视图（{@code View#setAlpha}）；
 * HUD 的窗口一个窗口里左右两个画面，各淡各的（画的时候乘上去）。窗口那一层的透明度不动 —— 备着时的 0、
 * HUD 的透明度和夜间亮度（{@link HudPicture#opacity}）照旧，两层相乘。什么时候淡、淡多久是判定定的
 * （{@link SideViewTrigger#FADE_MS}），这里只管画。主线程。</p>
 */
final class SideViewFade {

    /** 淡到哪了：1 是正常，0 是看不见。 */
    interface Target {
        void setFade(float alpha);
    }

    private final Target target;
    private ValueAnimator animator;

    SideViewFade(Target target) {
        this.target = target;
    }

    /** 开始淡出，{@code durationMs} 后淡完；已经在淡就不重来。 */
    void start(long durationMs) {
        if (animator != null) {
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(Math.max(0L, durationMs));
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> target.setFade((Float) v.getAnimatedValue()));
        animator = a;
        a.start();
    }

    /** 又显示了（淡出中又亮、换一侧、新的一次显示）：停下，回到正常。 */
    void restore() {
        stop();
        target.setFade(1f);
    }

    /** 收起、移走：停下。淡到哪留在哪（这时已经看不见），下一次显示时回到正常（{@link #restore}）。 */
    void stop() {
        ValueAnimator a = animator;
        animator = null;
        if (a != null) {
            a.cancel();
        }
    }

    boolean isFading() {
        return animator != null;
    }
}
