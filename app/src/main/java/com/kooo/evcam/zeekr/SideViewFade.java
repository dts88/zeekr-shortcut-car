package com.kooo.evcam.zeekr;

import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 盲区画面收起前的淡出（项目所有者 2026-11-04）：画面在给定的时间里从正常匀速淡到看不见。
 *
 * <p>动的是画面这一层的透明度（窗口里的整个视图），窗口那一层的不动 —— 备着时的 0、HUD 的透明度和夜间亮度
 * （{@link HudPicture#opacity}）照旧，两层相乘。什么时候淡、淡多久是判定定的（{@link SideViewTrigger#FADE_MS}），
 * 这里只管画；中控屏、HUD 两个窗口各用一份。主线程。</p>
 */
final class SideViewFade {

    private final View view;
    private boolean fading;

    SideViewFade(View view) {
        this.view = view;
    }

    /** 开始淡出，{@code durationMs} 后淡完；已经在淡就不重来。 */
    void start(long durationMs) {
        if (fading) {
            return;
        }
        fading = true;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(Math.max(0L, durationMs))
                .setInterpolator(new LinearInterpolator()).start();
    }

    /** 又显示了（淡出中又亮、换一侧、新的一次显示）：停下，回到正常。 */
    void restore() {
        stop();
        view.setAlpha(1f);
    }

    /** 收起、移走：停下。淡到哪留在哪（这时窗口已经看不见），下一次显示时回到正常（{@link #restore}）。 */
    void stop() {
        if (fading) {
            view.animate().cancel();
            fading = false;
        }
    }

    boolean isFading() {
        return fading;
    }
}
