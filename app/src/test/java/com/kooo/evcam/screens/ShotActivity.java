/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.kooo.evcam.R;

/** 截图用的空壳：应用的主题，一个铺满的容器，要画哪个界面就把它放进来。 */
public class ShotActivity extends FragmentActivity {

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
