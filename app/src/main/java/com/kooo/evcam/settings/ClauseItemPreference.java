/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 展开以后的一行「标签 + 内容」（ui-spec §十六「地点与自动化」）：名称、当、而且、就 —— 标签在前（次要文字色，同一个宽度），
 * 内容在后（正文色），从上往下读是一句话。同一个标签下的第二行起标签空着。
 *
 * <p>「添加条件」「添加动作」这一种（{@link #setAdd}）：标签空着，内容是加号带文字，用 {@code energy_text}。
 * 卡片的形状、›、行间的线照 {@link CardItemPreference}。</p>
 */
public class ClauseItemPreference extends CardItemPreference {

    private CharSequence label;
    private boolean add;

    public ClauseItemPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.pref_row_clause);
    }

    void setLabel(CharSequence label) {
        if (!android.text.TextUtils.equals(this.label, label)) {
            this.label = label;
            notifyChanged();
        }
    }

    CharSequence getLabel() {
        return label;
    }

    /** 加号带文字的那一种（「添加条件」「添加动作」）。 */
    void setAdd(boolean add) {
        if (this.add != add) {
            this.add = add;
            notifyChanged();
        }
    }

    boolean isAdd() {
        return add;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context context = holder.itemView.getContext();
        TextView labelView = (TextView) holder.findViewById(R.id.pref_label);
        if (labelView != null) {
            labelView.setText(label);
        }
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null) {
            Drawable plus = add ? ContextCompat.getDrawable(context, R.drawable.ic_add) : null;
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(plus, null, null, null);
            title.setTextColor(ContextCompat.getColorStateList(context, add ? R.color.energy_text : R.color.pref_title));
        }
    }
}
