/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 「鱼眼校正」按钮：图片回看、视频回看上都是它，拨的是同一个开关
 * （{@link AppConfig#isFisheyeCorrection}）。主界面上是动作栏四块开关里的一块（{@link QuickSwitches}，4.0 起），拨的也是它。
 *
 * <p>点击和显示都在按钮自己身上，所在的界面不用接线：放进布局就能用。
 * 画面那边（预览容器、视频外框、图片回看）
 * 各自听 {@link AppConfig#onFisheyeChanged}，开关一变就重画。</p>
 *
 * <p>开着的时候按主色点亮 —— 一眼能看出现在看到的画面动过手脚。</p>
 */
public class FisheyeToggleButton extends MaterialButton {

    private static final String TAG = "FisheyeToggle";

    private final AppConfig config;
    /** 拿住它：SharedPreferences 只弱引用监听器。 */
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    public FisheyeToggleButton(Context context) {
        this(context, null);
    }

    public FisheyeToggleButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        config = new AppConfig(context);
        setOnClickListener(v -> {
            boolean on = !config.isFisheyeCorrection();
            config.setFisheyeCorrection(on);
            AppLog.i(TAG, "鱼眼校正 " + (on ? "开" : "关"));
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
        listener = config.onFisheyeChanged(this::refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        config.removeFisheyeListener(listener);
        listener = null;
        super.onDetachedFromWindow();
    }

    private void refresh() {
        boolean on = config.isFisheyeCorrection();
        setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(getContext(), on ? R.color.energy : R.color.sunken)));
        setTextColor(ContextCompat.getColor(getContext(),
                on ? R.color.on_energy : R.color.text_primary));
        setSelected(on);
    }
}
