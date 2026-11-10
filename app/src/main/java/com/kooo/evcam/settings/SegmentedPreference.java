/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 选项只有两三个的设置：直接摆在行里，点一下就切。
 *
 * <h3>为什么不用下拉框</h3>
 *
 * <p>下拉框要点两次（开框、选中），而且收起来之后看不见「另一个是什么」。
 * 「操作按钮放左边还是右边」这种问题，两个答案一起摆出来，一眼就知道自己在选什么，
 * 手也只用抬一次 —— 在车上，少一次点击是实在的。</p>
 *
 * <h3>选项多了就别用它</h3>
 *
 * <p>三段是舒服的上限。再多，每一段就窄到看不清字，那时候下拉框反而是对的。</p>
 *
 * <p>段怎么摆在 {@link Segments}：设置行下面那一排的三选一（盲区画面的情境）也用它，两处一个样子。</p>
 */
public class SegmentedPreference extends Preference implements PreferenceRows.OwnLayout {

    private CharSequence[] entries = new CharSequence[0];
    private String[] values = new String[0];
    private String value;

    public SegmentedPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_segmented);
        setPersistent(false);
        setSelectable(false);
    }

    /** 名字和取值一一对应，由绑定的地方从 {@link SettingSpec} 取。 */
    void setOptions(CharSequence[] entries, String[] values) {
        if (entries == null || values == null || entries.length != values.length) {
            throw new IllegalArgumentException(getKey() + ": 名字和取值对不上");
        }
        this.entries = entries;
        this.values = values;
        notifyChanged();
    }

    void setValue(String value) {
        this.value = value;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (!(holder.findViewById(R.id.segments) instanceof ViewGroup)) {
            return;
        }
        // 依赖的开关关着（android:dependency）时整行置灰：段也跟着灰、点了不动（超级镜子的切换范围）
        Segments.fill((ViewGroup) holder.findViewById(R.id.segments), entries, indexOf(value), isEnabled(),
                index -> pick(values[index]));
    }

    private int indexOf(String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    /** Segments 只在点了另一段时叫。 */
    private void pick(String target) {
        if (callChangeListener(target)) {
            setValue(target);
        }
    }
}
