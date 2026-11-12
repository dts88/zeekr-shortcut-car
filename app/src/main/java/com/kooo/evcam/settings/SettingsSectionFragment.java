/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;

/**
 * 设置右边的内容区：一个分区，左右两列（ui-spec §三「双列」，项目所有者 2026-11-12）。
 *
 * <h3>为什么分两列</h3>
 *
 * <p>车机的应用区域宽 2133 dp，去掉左栏，右边还有约 1850 dp。一列排下来，名字在最左、分段和值在最右，中间隔着一整块屏；
 * 行一多还要滚 —— 而这块屏是伸手够着用的，滚动比点击贵得多。分成两列以后行短，全部打开的页也在一屏之内。</p>
 *
 * <h3>怎么分</h3>
 *
 * <p>{@code preferences.xml} 里每个分区下面有 {@code …_left}、{@code …_right} 两屏，这里各放一份
 * {@link SettingsPreferenceFragment} 把它们取出来。哪一组在哪一列只写在那个 XML 里。</p>
 *
 * <p>有的规则跨列（「盲区画面」那一行在左列，它管的「HUD 画面」一组在右列）：一列里改了，两列都照现在的设置再对一遍
 * （{@link #syncColumns}）。</p>
 */
public class SettingsSectionFragment extends Fragment {

    private static final String ARG_SECTION = "section";

    static SettingsSectionFragment forSection(String screenKey) {
        SettingsSectionFragment fragment = new SettingsSectionFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, screenKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_section, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            return;  // 两列由 fragment 管理器自己恢复
        }
        String section = requireArguments().getString(ARG_SECTION);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.settings_column_left, SettingsPreferenceFragment.forSection(section + "_left"))
                .replace(R.id.settings_column_right, SettingsPreferenceFragment.forSection(section + "_right"))
                .commit();
    }

    /** 两列都照现在的设置再对一遍：哪些行出现、哪些变灰。 */
    void syncColumns() {
        if (!isAdded()) {
            return;
        }
        for (Fragment column : getChildFragmentManager().getFragments()) {
            if (column instanceof SettingsPreferenceFragment) {
                ((SettingsPreferenceFragment) column).syncRows();
            }
        }
    }

    /** 滚到某一行、闪两下；两列里都没有这一行（或者它此刻收着）返回 false。 */
    boolean flash(String key) {
        if (!isAdded()) {
            return false;
        }
        for (Fragment column : getChildFragmentManager().getFragments()) {
            if (column instanceof SettingsPreferenceFragment && ((SettingsPreferenceFragment) column).flash(key)) {
                return true;
            }
        }
        return false;
    }
}
