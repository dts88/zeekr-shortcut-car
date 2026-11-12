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

import androidx.annotation.NonNull;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 组名那一行的右边带一个入口的组（ui-spec §三「组名右边的入口」）：不改设置、只是去看说明的（超级镜子的「使用指南」）
 * 放在这里，不占一行。组名可以不写（这一组就是这一页的头几行），入口照样在右边。
 */
public class LinkCategory extends PreferenceCategory implements PreferenceRows.OwnLayout {

    private CharSequence link;
    private Runnable onLink;

    public LinkCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_category_link);
    }

    /** 右边那个入口写什么、点了做什么。 */
    void setLink(CharSequence text, Runnable action) {
        link = text;
        onLink = action;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View view = holder.findViewById(R.id.pref_category_link);
        if (!(view instanceof TextView)) {
            return;
        }
        TextView button = (TextView) view;
        button.setText(link);
        button.setVisibility(TextUtils.isEmpty(link) ? View.GONE : View.VISIBLE);
        button.setOnClickListener(v -> {
            if (onLink != null) {
                onLink.run();
            }
        });
    }
}
