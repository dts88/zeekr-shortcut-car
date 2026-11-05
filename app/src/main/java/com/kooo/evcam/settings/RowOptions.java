/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置行下面那一排附加项（ui-spec §三「开关加附加项」，项目所有者 2026-10-23）：行首的开关开着时说明下面多一排 ——
 * 一个小标题加几个按钮（Material 的筛选按钮，按下是开、再按是关），下面可以有一行小字；开关关着时那一排不显示。
 * 还不能用的附加项照样摆出来，置灰、按不动。
 *
 * <p>「开关加附加项」（{@link SwitchChipsPreference}）和「开关加下一层」（{@link SwitchNavPreference}：盲区画面的「盲区提醒」
 * 「开门预警」，项目所有者 2026-11-04）两种行共用这一份；那一排也是同一块布局（{@code pref_options}，include 进各自的行布局）。</p>
 */
final class RowOptions {

    private static final class Option {
        final CharSequence label;
        boolean checked;
        /** null：还不能用（置灰、按不动）。 */
        final SwitchChipsPreference.OptionListener listener;

        Option(CharSequence label, boolean checked, SwitchChipsPreference.OptionListener listener) {
            this.label = label;
            this.checked = checked;
            this.listener = listener;
        }
    }

    private final List<Option> options = new ArrayList<>();
    private CharSequence label;
    private CharSequence note;

    /** 加一个附加项按钮；{@code listener} 是 null 的是还不能用的那种（摆着、置灰、按不动）。 */
    void add(CharSequence text, boolean checked, SwitchChipsPreference.OptionListener listener) {
        options.add(new Option(text, checked, listener));
    }

    /** 那一排按钮前面的小标题。返回变了没有（变了行要重画）。 */
    boolean setLabel(CharSequence value) {
        if (TextUtils.equals(label, value)) {
            return false;
        }
        label = value;
        return true;
    }

    /** 那一排按钮下面的一行小字（附加项的说明是次要的，写在下面）。返回变了没有。 */
    boolean setNote(CharSequence value) {
        if (TextUtils.equals(note, value)) {
            return false;
        }
        note = value;
        return true;
    }

    /** 从外面改了某一项（第几个，照加的顺序）：按钮照新的显示，不叫它的监听。返回变了没有。 */
    boolean setChecked(int index, boolean checked) {
        if (index < 0 || index >= options.size() || options.get(index).checked == checked) {
            return false;
        }
        options.get(index).checked = checked;
        return true;
    }

    /**
     * 画那一排（行的 onBindViewHolder 里叫）：开关关着、没有附加项时不显示；行布局里没有那一排的什么都不做。
     *
     * @param switchOn 行首的开关此刻开着
     * @param enabled  这一行可用（不可用时按钮也按不动）
     */
    void bind(PreferenceViewHolder holder, boolean switchOn, boolean enabled) {
        View row = holder.findViewById(R.id.pref_options);
        ChipGroup chips = (ChipGroup) holder.findViewById(R.id.pref_option_chips);
        if (row == null || chips == null) {
            return;
        }
        boolean show = switchOn && !options.isEmpty();
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        TextView labelView = (TextView) holder.findViewById(R.id.pref_options_label);
        if (labelView != null) {
            labelView.setText(label);
            labelView.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
        }
        TextView noteView = (TextView) holder.findViewById(R.id.pref_options_note);
        if (noteView != null) {
            noteView.setText(note);
            noteView.setVisibility(TextUtils.isEmpty(note) ? View.GONE : View.VISIBLE);
        }
        chips.removeAllViews();
        if (!show) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(chips.getContext());
        for (Option option : options) {
            Chip chip = (Chip) inflater.inflate(R.layout.pref_option_chip, chips, false);
            chip.setText(option.label);
            chip.setChecked(option.checked);
            chip.setEnabled(enabled && option.listener != null);
            if (option.listener != null) {
                chip.setOnCheckedChangeListener((button, checked) -> {
                    option.checked = checked;
                    option.listener.onChanged(checked);
                });
            }
            chips.addView(chip);
        }
    }
}
