/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.res.Resources;

import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.TwoStatePreference;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.R;

/**
 * 设置行长什么样，按类型决定，写在这一处。
 *
 * <h3>为什么在代码里套，而不是在主题里换样式</h3>
 *
 * <p>在主题里换要写 {@code preferenceStyle}、{@code switchPreferenceCompatStyle} 那一串，
 * 每个都得继承 androidx 内部的样式名 —— 名字写错要到编译才知道，库一升级还可能改名。
 * 在这里对每个 Preference 调一次 {@code setLayoutResource}，只用公开的 API，
 * 而且「哪一类长什么样」一眼看得完。</p>
 *
 * <h3>对照车机系统设置</h3>
 *
 * <ul>
 *   <li>每一行是一块灰底卡片，行间留缝，不画分隔线；</li>
 *   <li>开关放在行首（{@link R.layout#pref_row}），橙色轨道 + 白色方块滑块；</li>
 *   <li>每一行的名字从同一条竖线开始：没有开关的行在开关的位置留出同样宽的空位
 *       （{@code pref_text_start}，ui-spec §三「文字对齐」）；</li>
 *   <li>有当前值的（下拉、输入），值放行尾再加 ›（{@link R.layout#pref_row_value}）；</li>
 *   <li>点进去还有下一层的，行尾 ›（{@link R.layout#pref_row_nav}）；</li>
 *   <li>组名是小号灰字（{@link R.layout#pref_category}）。</li>
 * </ul>
 *
 * <p>必须在 Preference 交给列表之前调（{@code onCreatePreferences} 里、或者往屏幕上
 * 加完行的同一帧里）：列表按布局资源分视图类型，交出去之后再换不会重新建视图。</p>
 */
final class PreferenceRows {

    /** 自己带布局的行（选择行、配置编辑里的格子图）：不替它换。 */
    interface OwnLayout {
    }

    private static final String EXTRA_ROW = "cam_row";
    private static final String ROW_VALUE = "value";
    private static final String ROW_PRIMARY = "primary";

    private PreferenceRows() {
    }

    /**
     * 这一行的 summary 是一个当前值（「30 fps」「中」「90°」）：值放行尾。
     * 普通 Preference 看不出自己是不是「有值」，所以由建行的地方说一声。
     */
    static void markValue(Preference preference) {
        preference.getExtras().putString(EXTRA_ROW, ROW_VALUE);
    }

    /** 这一屏的主操作（比如「保存」）：实心能量色的一行。一屏最多一处。 */
    static void markPrimary(Preference preference) {
        preference.getExtras().putString(EXTRA_ROW, ROW_PRIMARY);
    }

    /** 给一组设置套上行样式，子分组一起。 */
    static void apply(PreferenceGroup group) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            preference.setIconSpaceReserved(false);
            if (preference instanceof OwnLayout) {
                // 自己带布局的组（LinkCategory）：组不换，组里的行照样要套
                if (preference instanceof PreferenceGroup) {
                    apply((PreferenceGroup) preference);
                }
                continue;
            }
            String row = preference.peekExtras() != null
                    ? preference.peekExtras().getString(EXTRA_ROW) : null;
            if (ROW_PRIMARY.equals(row)) {
                preference.setLayoutResource(R.layout.pref_row_primary);
            } else if (ROW_VALUE.equals(row)) {
                preference.setLayoutResource(R.layout.pref_row_value);
            } else if (preference instanceof PreferenceCategory) {
                preference.setLayoutResource(R.layout.pref_category);
                apply((PreferenceGroup) preference);
            } else if (preference instanceof PreferenceGroup) {
                // 嵌套的分区在右栏里不会作为一行出现（按 key 取出来的就是它本身）
                apply((PreferenceGroup) preference);
            } else if (preference instanceof SeekBarPreference) {
                preference.setLayoutResource(R.layout.pref_row_seekbar);
            } else if (preference instanceof SwitchPreferenceCompat) {
                preference.setLayoutResource(R.layout.pref_row);
                preference.setWidgetLayoutResource(R.layout.pref_widget_switch);
            } else if (preference instanceof TwoStatePreference) {
                preference.setLayoutResource(R.layout.pref_row);
            } else if (preference instanceof DialogPreference) {
                preference.setLayoutResource(R.layout.pref_row_value);
            } else if (preference.isSelectable()) {
                preference.setLayoutResource(R.layout.pref_row_nav);
            } else {
                preference.setLayoutResource(R.layout.pref_row_info);
            }
        }
    }

    /** 列表本身：不画分隔线（行与行之间靠卡片的缝），四周留白。 */
    static void styleList(PreferenceFragmentCompat fragment, int horizontalDp, int verticalDp) {
        fragment.setDivider(null);
        RecyclerView list = fragment.getListView();
        if (list == null) {
            return;
        }
        float density = Resources.getSystem().getDisplayMetrics().density;
        int horizontal = Math.round(horizontalDp * density);
        int vertical = Math.round(verticalDp * density);
        list.setPadding(horizontal, vertical, horizontal, vertical);
        list.setClipToPadding(false);
    }

    /**
     * 行出现、消失时的动效（ui-spec §十四「展开、收起」）：要走的行先淡出，剩下的行挪到位，新来的行再淡入。
     * 时长照 {@link com.kooo.evcam.ui.Motion}。行自己变了内容（开关、值）不放交叉淡入：那是控件自己的事，整行淡一下反而像闪。
     * 装饰动效不放时（{@code MotionPolicy}）直接到位。
     */
    static void animateChanges(PreferenceFragmentCompat fragment) {
        RecyclerView list = fragment.getListView();
        if (list == null || fragment.getContext() == null) {
            return;
        }
        if (!com.kooo.evcam.ui.MotionPolicy.decorative(fragment.getContext())) {
            list.setItemAnimator(null);
            return;
        }
        androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setRemoveDuration(com.kooo.evcam.ui.Motion.LEAVE);
        animator.setMoveDuration(com.kooo.evcam.ui.Motion.FAST);
        animator.setAddDuration(com.kooo.evcam.ui.Motion.FAST);
        list.setItemAnimator(animator);
    }
}
