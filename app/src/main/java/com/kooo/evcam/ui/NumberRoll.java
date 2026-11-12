/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.widget.TextView;

/**
 * 一行字变了，让它滚上去换成新的，而不是凭空跳一下。
 *
 * <h3>为什么值得做</h3>
 *
 * <p>剩余空间、帧率这类数字是<b>偶尔</b>变的。直接 setText 的话，人眼看到的是
 * 「刚才那个数好像不是这个」—— 不确定自己有没有看错。滚一下，变化本身就被看见了，
 * 不需要回头确认。</p>
 *
 * <h3>什么时候不滚</h3>
 *
 * <p>录制中、或者系统关了动画时不滚（{@link MotionPolicy}）。值一样时也不滚 ——
 * 每秒刷新一次却每次都动，那是噪音不是信息。</p>
 */
public final class NumberRoll {

    /** 值变了：一出一进合起来是最短的一档（{@link Motion#FAST}），出去的占小头。 */
    private static final long OUT_MS = Motion.FAST * 2 / 5;
    private static final long IN_MS = Motion.FAST - OUT_MS;
    /** 滚动距离按字号取，字大就滚得远一点，看起来才是同一个动作。 */
    private static final float DISTANCE_RATIO = 0.6f;

    private NumberRoll() {
    }

    public static void set(TextView view, CharSequence next) {
        if (view == null) {
            return;
        }
        CharSequence target = next == null ? "" : next;
        CharSequence current = view.getText();
        if (current != null && current.toString().contentEquals(target)) {
            return;
        }

        // 上一次可能还没滚完：先收拾干净，否则透明度和位移会留在半路
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);

        if (!MotionPolicy.decorative(view.getContext())) {
            view.setText(target);
            return;
        }

        float distance = view.getTextSize() * DISTANCE_RATIO;
        view.animate()
                .alpha(0f)
                .translationY(-distance)
                .setDuration(OUT_MS)
                .setInterpolator(Motion.exit())
                .withEndAction(() -> {
                    view.setText(target);
                    view.setTranslationY(distance);
                    view.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(IN_MS)
                            .setInterpolator(Motion.enter())
                            .start();
                })
                .start();
    }
}
