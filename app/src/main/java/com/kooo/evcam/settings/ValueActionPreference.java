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

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 值加一个按钮（ui-spec §三，项目所有者 2026-10-23）：一行有当前值、点进去改，行尾再带一个直接做的动作 ——
 * 超级镜子的「窗口」（点这一行在整个屏幕上调宽高，行尾「重置」）。
 *
 * <p>点这一行交给 {@code OnPreferenceClickListener}（进下一层）；行尾的按钮单独接（{@link #setAction}），
 * 点它不算点行。当前值写在 › 前面（{@link #setValue}）。行不可用时按钮也不可用。</p>
 */
public class ValueActionPreference extends Preference implements PreferenceRows.OwnLayout {

    private CharSequence value;
    private CharSequence actionLabel;
    private Runnable action;
    private Boolean expanded;
    private int lead;

    public ValueActionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_value_action);
    }

    /** › 前面的当前值；空就不写。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    void setExpanded(Boolean expanded) {
        if (!java.util.Objects.equals(this.expanded, expanded)) {
            this.expanded = expanded;
            notifyChanged();
        }
    }

    void setLead(int drawable) {
        if (lead != drawable) {
            lead = drawable;
            notifyChanged();
        }
    }

    /** 行尾的按钮：字和点了做什么。 */
    public void setAction(CharSequence label, Runnable action) {
        this.actionLabel = label;
        this.action = action;
        notifyChanged();
    }

    /**
     * 行尾 › 转多少度：进下一层的不转；展开的朝上、收着的朝下。从右往左时 › 本身已经左右翻过来（‹，autoMirrored），
     * 转的方向也要反过来，不然展开、收着上下颠倒。
     */
    static float chevronRotation(Boolean expanded, boolean rightToLeft) {
        if (expanded == null) {
            return 0f;
        }
        float turn = expanded ? -90f : 90f;
        return rightToLeft ? -turn : turn;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View frame = holder.findViewById(android.R.id.widget_frame);
        if (frame != null) {
            frame.setVisibility(View.VISIBLE);
        }
        android.widget.ImageView leadView = (android.widget.ImageView) holder.findViewById(R.id.pref_lead);
        if (leadView != null) {
            if (lead != 0) {
                leadView.setImageResource(lead);
            } else {
                leadView.setImageDrawable(null);
            }
            leadView.setVisibility(lead != 0 ? View.VISIBLE : View.INVISIBLE);
        }
        TextView valueView = (TextView) holder.findViewById(R.id.pref_value);
        if (valueView != null) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
        View chevron = holder.findViewById(R.id.pref_chevron);
        if (chevron != null) {
            // 照界面语言的方向判（绑定时这一行可能还没挂上去，它自己的方向还没解析出来）
            chevron.setRotation(chevronRotation(expanded, chevron.getResources().getConfiguration().getLayoutDirection()
                    == View.LAYOUT_DIRECTION_RTL));
        }
        CardItemPreference.shape(holder.itemView, Boolean.TRUE.equals(expanded) ? CardItemPreference.TOP
                : CardItemPreference.ALONE, true);
        TextView button = (TextView) holder.findViewById(R.id.pref_action);
        if (button != null) {
            boolean shown = action != null && !TextUtils.isEmpty(actionLabel);
            button.setVisibility(shown ? View.VISIBLE : View.GONE);
            button.setText(actionLabel);
            button.setEnabled(isEnabled());
            button.setAlpha(isEnabled() ? 1f : 0.38f);
            button.setOnClickListener(shown ? v -> {
                if (isEnabled()) {
                    action.run();
                }
            } : null);
        }
    }
}
