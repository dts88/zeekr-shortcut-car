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
 * 开关加下一层（ui-spec §三，项目所有者 2026-10-23）：一个功能既要开关、又要到下一层去填写或选择时，合成一行 ——
 * 车牌号水印（下一层是输入车牌号）、行驶信息条（下一层是「车辆信号」里选显示哪些信号）、熄屏持续录制（下一层是熄屏后的帧率，
 * 行尾的值下面另有一行哨兵模式此刻的状态，{@link #showSentryStatus}）。
 *
 * <p>开关开着时说明下面也可以多一排附加项（{@link #addOption}，和「开关加附加项」那一排同一种，{@link RowOptions}），
 * 也可以是三选一（{@link #setSingleChoice}，画成分段选择，和「显示位置」同一种）：盲区画面的情境「丰富」「标准」「简洁」
 * （项目所有者 2026-11-09；2026-11-10 定画成分段选择）。那一排自己接住点击，没点中按钮或分段时不进下一层。</p>
 *
 * <p>行首的开关只管开关；点这一行其余的地方不切换开关，交给 {@code OnPreferenceClickListener} 进下一层；
 * 行尾写当前值（{@link #setValue}）再加 ›。和安卓系统设置里 Wi-Fi、蓝牙那一行是同一种，开关照车机放在行首。</p>
 *
 * <p>开关那一块从行的左边缘到开关右边都算点开关（{@code pref_row_switch_nav}）。切换照 SwitchPreferenceCompat 的规矩：
 * 先问 {@code OnPreferenceChangeListener}，它说不行就不变 —— 车牌号还空着时打开，由它先弹输入框。</p>
 */
public class SwitchNavPreference extends SwitchPreferenceCompat implements PreferenceRows.OwnLayout {

    /** 行尾的当前值；空 = 不写，只有 ›。 */
    private CharSequence value;
    /** 开关开着时说明下面那一排附加项；没加就没有。 */
    private final RowOptions options = new RowOptions();

    public SwitchNavPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_switch_nav);
        setWidgetLayoutResource(R.layout.pref_widget_switch);
    }

    /**
     * 行尾的当前值下面多一行哨兵模式此刻的状态（{@code pref_row_switch_nav_sentry}，{@code ui.SentryStatusView} 自己读、自己换字）：
     * 开关的效果要看哨兵模式的那一行用（熄屏持续录制）。要在这一行交给列表之前叫（列表按布局分视图类型）。
     */
    public void showSentryStatus() {
        setLayoutResource(R.layout.pref_row_switch_nav_sentry);
    }

    /** 加一个附加项按钮（开关开着时才显示），和 {@link SwitchChipsPreference#addOption} 一样。 */
    public void addOption(CharSequence label, boolean checked, SwitchChipsPreference.OptionListener listener) {
        options.add(label, checked, listener);
        notifyChanged();
    }

    /** 那一排是三选一（{@link RowOptions#setSingleChoice}），画成分段选择：只能选一个，监听只在选中的那一下叫。在加选项之前叫。 */
    public void setSingleChoice(boolean single) {
        options.setSingleChoice(single);
        notifyChanged();
    }

    /** 那一排按钮下面的一行小字，和 {@link SwitchChipsPreference#setOptionsNote} 一样；空就不写。 */
    public void setOptionsNote(CharSequence note) {
        if (options.setNote(note)) {
            notifyChanged();
        }
    }

    /** 行尾的当前值（车牌号、选了几项）；空就只有 ›。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
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
        View toggle = holder.findViewById(android.R.id.widget_frame);
        if (toggle != null) {
            toggle.setEnabled(isEnabled());
            toggle.setContentDescription(getTitle());
            toggle.setOnClickListener(v -> {
                if (!isEnabled()) {
                    return;
                }
                boolean next = !isChecked();
                if (callChangeListener(next)) {
                    setChecked(next);
                }
            });
        }
    }

    /** 点这一行（开关以外的地方）不切换开关：交给 OnPreferenceClickListener 进下一层。 */
    @Override
    protected void onClick() {
    }
}
