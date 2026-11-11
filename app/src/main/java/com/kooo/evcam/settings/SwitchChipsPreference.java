/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.kooo.evcam.R;

/**
 * 开关加几个附加项（ui-spec §三，项目所有者 2026-10-23）：一个功能开着时还有几样可以单独开、关的附加项，合成一行 ——
 * 锁定影像（附加项：「自动锁定」的「闪远光」「双闪」「鸣笛」）、悬浮按钮（「录制时长」「锁定位置」）。
 *
 * <p>行首的开关管整个功能，点这一行其余的地方也是这个开关（没有下一层；那一排按钮所在的地方除外）；
 * 行尾写当前值（{@link #setValue}）。
 * 开着时说明下面多一排：一个小标题（{@link #setOptionsLabel}）加几个按钮（{@link #addOption}，Material 的筛选按钮，
 * 按下是开、再按是关）；关着时那一排不显示。还不能用的附加项照样摆出来，置灰、按不动（{@link #addUnavailableOption}）。
 * 那一排的画法在 {@link RowOptions}。只能选一样的不是附加项，是分段选择（{@link SegmentedPreference}）。</p>
 */
public class SwitchChipsPreference extends SwitchPreferenceCompat implements PreferenceRows.OwnLayout {

    /** 一个附加项按下、松开时叫它。 */
    public interface OptionListener {
        void onChanged(boolean checked);
    }

    private final RowOptions options = new RowOptions();
    private CharSequence value;

    public SwitchChipsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_switch_chips);
        setWidgetLayoutResource(R.layout.pref_widget_switch);
    }

    /** 行尾的当前值；空就不写。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    /** 那一排按钮前面的小标题。 */
    public void setOptionsLabel(CharSequence label) {
        if (options.setLabel(label)) {
            notifyChanged();
        }
    }

    /** 那一排按钮下面的一行小字（附加项的说明是次要的，写在下面，ui-spec §三）；空就不写。 */
    public void setOptionsNote(CharSequence note) {
        if (options.setNote(note)) {
            notifyChanged();
        }
    }

    /** 加一个附加项按钮（开关开着时才显示）。 */
    public void addOption(CharSequence label, boolean checked, OptionListener listener) {
        options.add(label, checked, listener);
        notifyChanged();
    }

    /** 加一个还不能用的附加项：摆着、置灰、按不动（鸣笛：车机还读不到喇叭信号）。 */
    public void addUnavailableOption(CharSequence label) {
        options.add(label, false, null);
        notifyChanged();
    }

    /** 从外面改了某一项（第几个，照加的顺序；比如重置之后）：按钮照新的显示，不叫它的监听。 */
    public void setOptionChecked(int index, boolean checked) {
        if (options.setChecked(index, checked)) {
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView valueView = (TextView) holder.findViewById(R.id.pref_value);
        if (valueView != null) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
        options.bind(holder, isChecked(), isEnabled());
    }
}
