/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 分段选择的段（ui-spec §三「分段选择」）：底槽（{@code pref_segment_track}）里摆两三段，选中的那一段实心能量色、白字，
 * 其余透明、次要文字色；点一下就切，不能拖。
 *
 * <p>自己占一行的（显示位置、切换范围、切换方式、操作按钮位置，{@link SegmentedPreference}）和设置行下面那一排的三选一
 * （盲区画面的情境，{@link RowOptions}，项目所有者 2026-11-10：和「显示位置」同一种）都由这里摆，两处一个样子。</p>
 */
final class Segments {

    /** 点了另一段（第几段，照摆的顺序）；点已经选中的那一段不叫。 */
    interface OnPick {
        void pick(int index);
    }

    private static final int SEGMENT_HEIGHT_DP = 46;
    private static final int SEGMENT_MIN_WIDTH_DP = 96;
    private static final int SEGMENT_PADDING_DP = 18;
    /** 不可用时段的不透明度（Material 的禁用值）。 */
    private static final float DISABLED_ALPHA = 0.38f;

    private Segments() {
    }

    /**
     * 把底槽里的段整排换成这几段（行的视图会被别的行拿去用：每次都整排重摆）。
     *
     * @param chosen  选中的是第几段；不在范围里就一段都不选中
     * @param enabled 可用；不可用时段置灰、点了不动
     */
    static void fill(ViewGroup track, CharSequence[] labels, int chosen, boolean enabled, OnPick onPick) {
        track.removeAllViews();
        Context context = track.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            TextView view = segment(context, density, labels[i], i == chosen, enabled);
            view.setOnClickListener(v -> {
                if (index != chosen) {
                    onPick.pick(index);
                }
            });
            track.addView(view);
        }
    }

    private static TextView segment(Context context, float density, CharSequence label, boolean chosen,
                                    boolean enabled) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        // 设置页整体比别处低一档，段上的字跟着行标题往下走一级
        view.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        view.setTextColor(ContextCompat.getColor(context,
                chosen ? R.color.on_energy : R.color.text_secondary));
        view.setBackgroundResource(R.drawable.bg_segment);
        view.setActivated(chosen);
        int padding = Math.round(SEGMENT_PADDING_DP * density);
        view.setPadding(padding, 0, padding, 0);
        view.setMinWidth(Math.round(SEGMENT_MIN_WIDTH_DP * density));
        view.setEnabled(enabled);
        if (!enabled) {
            view.setAlpha(DISABLED_ALPHA);
        }
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(SEGMENT_HEIGHT_DP * density)));
        return view;
    }
}
