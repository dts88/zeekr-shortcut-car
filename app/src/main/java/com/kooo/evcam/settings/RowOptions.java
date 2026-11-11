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
import android.view.ViewGroup;
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
 * <p>那一排也可以是三选一（{@link #setSingleChoice}）：不摆按钮，摆一条分段选择，和「显示位置」同一种、段由同一份代码摆
 * （{@link Segments}，项目所有者 2026-11-10；之前是三个筛选按钮）。</p>
 *
 * <p>用它的是「开关加附加项」（{@link SwitchChipsPreference}：锁定影像、超级镜子、悬浮按钮的附加项；盲区画面的情境
 * 「丰富」「标准」「简洁」，三选一，项目所有者 2026-11-09）；那一排是 {@code pref_options}，include 进 {@code pref_row_switch_chips}。</p>
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
    /** 三选一（单选）：画成分段选择，只能选一个，点选中的那一段不动，监听只在选中的那一下叫。 */
    private boolean singleChoice;

    /**
     * 加一个附加项按钮；{@code listener} 是 null 的是还不能用的那种（摆着、置灰、按不动）。
     * 三选一的每一项都要能选（分段选择里没有单独置灰的一段）。
     */
    void add(CharSequence text, boolean checked, SwitchChipsPreference.OptionListener listener) {
        if (singleChoice && listener == null) {
            throw new IllegalArgumentException("single choice: every option needs a listener");
        }
        options.add(new Option(text, checked, listener));
    }

    /** 那一排是三选一（单选），画成分段选择：只能选一个，点选中的那一段不动；监听只在选中的那一下叫。在加选项之前叫。 */
    void setSingleChoice(boolean value) {
        singleChoice = value;
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
        ViewGroup segments = (ViewGroup) holder.findViewById(R.id.pref_option_segments);
        if (row == null || chips == null || segments == null) {
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
        // 行的视图会被别的行拿去用：每次都照这一行摆（按钮还是分段、摆哪几个）
        chips.removeAllViews();
        segments.removeAllViews();
        chips.setVisibility(singleChoice ? View.GONE : View.VISIBLE);
        segments.setVisibility(singleChoice ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        if (singleChoice) {
            bindSegments(segments, enabled);
        } else {
            bindChips(chips, enabled);
        }
    }

    /** 三选一：分段选择（{@link Segments}）。点了另一段，先当场换成它选中的样子，再叫它的监听。 */
    private void bindSegments(ViewGroup track, boolean enabled) {
        CharSequence[] labels = new CharSequence[options.size()];
        int chosen = -1;
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (chosen < 0 && options.get(i).checked) {
                chosen = i;
            }
        }
        Segments.fill(track, labels, chosen, enabled, index -> {
            for (int i = 0; i < options.size(); i++) {
                options.get(i).checked = i == index;
            }
            bindSegments(track, enabled);
            options.get(index).listener.onChanged(true);
        });
    }

    /** 附加项：筛选按钮，按下是开、再按是关。 */
    private void bindChips(ChipGroup chips, boolean enabled) {
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
