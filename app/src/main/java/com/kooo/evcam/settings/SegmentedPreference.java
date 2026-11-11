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
import android.view.ViewGroup;
import android.widget.TextView;

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
 * <p>三段是舒服的上限。再多，每一段就窄到看不清字，那时候下拉框反而是对的。
 * 例外是开关合进分段的那一行（{@link #setOffValue}）：「关闭」加三档，四段。</p>
 *
 * <h3>开关合进分段</h3>
 *
 * <p>一个功能开着时只能从几档里选一档的，开关和那几档合成一行，第一段是「关闭」（ui-spec §三，项目所有者 2026-11-11：
 * 超级镜子、盲区画面）。选中「关闭」时，写着 dependency 指向这一行的那几行变灰，和开关关着一样。
 * 选中的那一档要交代一句的，写在说明下面一行小字（{@link #setNote}）。</p>
 *
 * <p>段怎么摆在 {@link Segments}。</p>
 */
public class SegmentedPreference extends Preference implements PreferenceRows.OwnLayout {

    private CharSequence[] entries = new CharSequence[0];
    private String[] values = new String[0];
    private String value;
    /** 选中它就是关着（{@link #setOffValue}）；null：这一行没有「关闭」。 */
    private String offValue;
    private CharSequence note;

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
        boolean wasOff = shouldDisableDependents();
        this.value = value;
        dependentsFollow(wasOff);
        notifyChanged();
    }

    /** 开关合进分段：选中这个值就是关着，依赖这一行的几行跟着变灰（{@link #shouldDisableDependents}）。 */
    void setOffValue(String offValue) {
        boolean wasOff = shouldDisableDependents();
        this.offValue = offValue;
        dependentsFollow(wasOff);
    }

    /** 说明下面一行小字（盲区画面：选中的这一档什么时候显示）；空就不写。 */
    void setNote(CharSequence note) {
        if (!TextUtils.equals(this.note, note)) {
            this.note = note;
            notifyChanged();
        }
    }

    /** 选中「关闭」时和开关关着一样：写着 dependency 指向这一行的那几行不可用。 */
    @Override
    public boolean shouldDisableDependents() {
        return (offValue != null && offValue.equals(value)) || super.shouldDisableDependents();
    }

    private void dependentsFollow(boolean wasOff) {
        boolean off = shouldDisableDependents();
        if (off != wasOff) {
            notifyDependencyChange(off);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (holder.findViewById(R.id.pref_segment_note) instanceof TextView) {
            TextView noteView = (TextView) holder.findViewById(R.id.pref_segment_note);
            noteView.setText(note);
            noteView.setVisibility(TextUtils.isEmpty(note) ? View.GONE : View.VISIBLE);
        }
        if (!(holder.findViewById(R.id.segments) instanceof ViewGroup)) {
            return;
        }
        // 依赖的那一行关着（android:dependency）时整行置灰：段也跟着灰、点了不动（超级镜子「关闭」时的切换方式）
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
