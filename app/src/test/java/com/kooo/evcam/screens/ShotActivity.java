/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.kooo.evcam.R;
import com.kooo.evcam.settings.Languages;

/** 截图用的空壳：应用的主题，一个铺满的容器，要画哪个界面就把它放进来。 */
public class ShotActivity extends FragmentActivity {

    /**
     * 应用里，界面的语言由 appcompat 照 {@link Languages} 给的标签套上（阿拉伯语带 nu-latn，数字写 0–9）；
     * 这个空壳不经过 appcompat，照同一个函数自己套一遍。中文、英文、马来文、希伯来文本来就写 0–9，不动。
     */
    @Override
    protected void attachBaseContext(Context base) {
        Configuration config = base.getResources().getConfiguration();
        String tags = Languages.tagsFor(Languages.AUTO, config.getLocales().get(0));
        if (!tags.isEmpty()) {
            Configuration override = new Configuration(config);
            override.setLocales(LocaleList.forLanguageTags(tags));
            base = base.createConfigurationContext(override);
        }
        super.attachBaseContext(base);
    }

    /** 容器的 id。 */
    public int container;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.Theme_Cam);
        super.onCreate(savedInstanceState);
        FrameLayout frame = new FrameLayout(this);
        container = View.generateViewId();
        frame.setId(container);
        setContentView(frame);
    }
}
