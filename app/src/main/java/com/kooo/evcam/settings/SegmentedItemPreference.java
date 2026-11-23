/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

import java.util.function.IntConsumer;

/**
 * 展开以后的一行分段、行尾一个按钮（ui-spec §十六「地点」的半径：分段 200 / 400 / 600 / 1000 m，行尾「自定义」）。
 * 一段都不亮（选中的是 -1）表示现在的值不在分段里，说明写是多少。段怎么摆照 {@link Segments}；卡片照 {@link CardItemPreference}。
 */
public class SegmentedItemPreference extends CardItemPreference {

    private CharSequence[] labels = new CharSequence[0];
    private int chosen = -1;
    private IntConsumer onPick;
    private CharSequence actionLabel;
    private Runnable action;

    public SegmentedItemPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.pref_row_card_segmented);
        setSelectable(false);
    }

    /** 几段的字、现在选中第几段（不在分段里 -1）、点了另一段做什么。 */
    void setSegments(CharSequence[] labels, int chosen, IntConsumer onPick) {
        this.labels = labels;
        this.chosen = chosen;
        this.onPick = onPick;
        notifyChanged();
    }

    int getChosen() {
        return chosen;
    }

    /** 行尾的按钮。 */
    void setAction(CharSequence label, Runnable action) {
        this.actionLabel = label;
        this.action = action;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        // 点不动的行 androidx 会把名字染成说明的颜色：这一行点不动只是因为要点的是那几段
        if (holder.findViewById(android.R.id.title) instanceof TextView) {
            ((TextView) holder.findViewById(android.R.id.title)).setTextColor(
                    androidx.core.content.ContextCompat.getColorStateList(getContext(), R.color.pref_title));
        }
        if (holder.findViewById(R.id.segments) instanceof ViewGroup) {
            Segments.fill((ViewGroup) holder.findViewById(R.id.segments), labels, chosen, isEnabled(), index -> {
                if (onPick != null) {
                    onPick.accept(index);
                }
            });
        }
        boolean shown = action != null && !TextUtils.isEmpty(actionLabel);
        View line = holder.findViewById(R.id.pref_action_line);
        if (line != null) {
            line.setVisibility(shown ? View.VISIBLE : View.GONE);
        }
        TextView button = (TextView) holder.findViewById(R.id.pref_action);
        if (button != null) {
            button.setVisibility(shown ? View.VISIBLE : View.GONE);
            button.setText(actionLabel);
            button.setEnabled(isEnabled());
            button.setOnClickListener(shown ? v -> {
                if (isEnabled()) {
                    action.run();
                }
            } : null);
        }
    }
}
