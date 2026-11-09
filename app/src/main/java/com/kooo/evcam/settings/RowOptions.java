/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
 * 还不能用的附加项照样摆出来，置灰、按不动。那一排也可以是一条几档的拖动条（{@link #setSteps}）。
 *
 * <p>「开关加附加项」（{@link SwitchChipsPreference}）和「开关加下一层」（{@link SwitchNavPreference}：盲区画面的情境
 * 「简洁」「标准」「丰富」，三档的拖动条，项目所有者 2026-11-10）两种行共用这一份；那一排也是同一块布局
 * （{@code pref_options}，include 进各自的行布局）。</p>
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
    /**
     * 几档的拖动条（{@link #setSteps}，项目所有者 2026-11-10：盲区画面的情境用滑块，不用三个按钮）：档位名，从左到右；
     * null = 没有。
     */
    private CharSequence[] steps;
    /** 拖动条此刻在第几档（从 0 起）。 */
    private int step;
    private StepListener stepListener;

    /** 拖动条停在了另一档（松手，或者无障碍调的）。 */
    interface StepListener {
        /** @param index 第几档，从 0 起 */
        void onStep(int index);
    }

    /** 加一个附加项按钮；{@code listener} 是 null 的是还不能用的那种（摆着、置灰、按不动）。 */
    void add(CharSequence text, boolean checked, SwitchChipsPreference.OptionListener listener) {
        options.add(new Option(text, checked, listener));
    }

    /**
     * 那一排是一条几档的拖动条：档位名写在下面、对着各档，选中的那一档字亮一些；拖的时候不叫，松手停在另一档才叫监听。
     *
     * @param labels   档位名，从左到右，至少两档
     * @param selected 此刻在第几档
     */
    void setSteps(CharSequence[] labels, int selected, StepListener listener) {
        steps = labels;
        step = Math.max(0, Math.min(labels.length - 1, selected));
        stepListener = listener;
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
        boolean hasSteps = steps != null && steps.length > 1;
        boolean show = switchOn && (!options.isEmpty() || hasSteps);
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
        // 行的视图会被别的行拿去用：按钮那一排、拖动条每次都照这一行设，用不着的藏起来
        chips.setVisibility(options.isEmpty() ? View.GONE : View.VISIBLE);
        bindSteps(holder, show && hasSteps, enabled);
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

    /** 画那一条拖动条；{@code visible} 是 false 就藏起来（这一行没有拖动条，或者开关关着）。 */
    private void bindSteps(PreferenceViewHolder holder, boolean visible, boolean enabled) {
        View box = holder.findViewById(R.id.pref_option_steps);
        SeekBar bar = (SeekBar) holder.findViewById(R.id.pref_option_step_bar);
        LinearLayout labels = (LinearLayout) holder.findViewById(R.id.pref_option_step_labels);
        if (box == null || bar == null || labels == null) {
            return;
        }
        bar.setOnSeekBarChangeListener(null);
        labels.removeAllViews();
        box.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        bar.setMax(steps.length - 1);
        bar.setProgress(step);
        bar.setEnabled(enabled);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bar.setStateDescription(steps[step]);
        }
        for (int i = 0; i < steps.length; i++) {
            TextView text = new TextView(labels.getContext());
            text.setText(steps[i]);
            text.setTextAppearance(R.style.TextAppearance_Cam_Caption);
            text.setTextColor(androidx.core.content.ContextCompat.getColor(labels.getContext(),
                    i == step ? R.color.pref_title : R.color.pref_summary));
            text.setGravity(i == 0 ? Gravity.START : i == steps.length - 1 ? Gravity.END : Gravity.CENTER_HORIZONTAL);
            labels.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !tracking) {
                    // 无障碍、键盘调的：没有松手这一下，当场算
                    settle(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                tracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                tracking = false;
                settle(seekBar.getProgress());
            }
        });
    }

    /** 拖动条停在 {@code index}：换了档才叫监听（它负责存、换下面那行小字，行跟着重画）。 */
    private void settle(int index) {
        if (steps == null || index == step || index < 0 || index >= steps.length) {
            return;
        }
        step = index;
        if (stepListener != null) {
            stepListener.onStep(index);
        }
    }
}
