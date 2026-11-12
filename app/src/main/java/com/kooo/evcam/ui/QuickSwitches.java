/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.settings.SettingsRegistry;

/**
 * 主界面动作栏上的六块开关（ui-spec §十三，项目所有者 2026-11-12）：超级镜子、盲区画面、悬浮按钮、灵动贴、模拟声浪、鱼眼校正。
 * 每块上面是名字，下面一行是现在的状态，右上角一个点（开着是能量色）；点一下就开、关。
 *
 * <p>这六样原来分在三处：抽屉里两行开关、动作栏上一个临时的「鱼眼校正」键、其余只在设置里。放到动作栏是因为它在驾驶座一侧，
 * 伸手就到。它们只是设置里那几个开关的另一个入口：值是同一份（{@link AppConfig}），开、关走的也是同一条路
 * （{@link OverlayCoordinator}：没有悬浮窗权限时不拨上去，去要权限）。</p>
 *
 * <p>点击和显示都在这个控件自己身上，所在的界面不用接线：放进布局就能用。看得见时听那几项设置，别处（设置页、悬浮按钮、灵动贴）
 * 改了这里跟着变。</p>
 */
public class QuickSwitches extends LinearLayout {

    private static final int MIRROR = 0;
    private static final int SIDE_VIEW = 1;
    private static final int FLOATING = 2;
    private static final int SMART_BUTTONS = 3;
    private static final int DRIVE_SOUND = 4;
    private static final int FISHEYE = 5;

    private static final int[] NAMES = {R.string.set_section_rearview, R.string.set_side_view_title,
            R.string.set_section_floating, R.string.set_smart_buttons_title, R.string.dev_drive_sound_title,
            R.string.set_rearview_fisheye_title};

    private final AppConfig config;
    private final TextView[] states = new TextView[NAMES.length];
    private final View[] leds = new View[NAMES.length];
    private final View[] tiles = new View[NAMES.length];
    /** 拿住它：SharedPreferences 只弱引用监听器。 */
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    public QuickSwitches(Context context) {
        this(context, null);
    }

    public QuickSwitches(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        config = new AppConfig(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout row = null;
        for (int i = 0; i < NAMES.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                row.setBaselineAligned(false);
                LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = i == 0 ? 0 : gap;
                addView(row, rowParams);
            }
            View tile = inflater.inflate(R.layout.item_quick_switch, row, false);
            LayoutParams params = new LayoutParams(0, tile.getLayoutParams().height, 1f);
            if (i % 2 == 1) {
                params.setMarginStart(gap);
            }
            ((TextView) tile.findViewById(R.id.quick_name)).setText(NAMES[i]);
            states[i] = tile.findViewById(R.id.quick_state);
            leds[i] = tile.findViewById(R.id.quick_led);
            tiles[i] = tile;
            final int which = i;
            tile.setOnClickListener(v -> toggle(which));
            row.addView(tile, params);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
        listener = config.onQuickSwitchesChanged(this::refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        config.removeChangeListener(listener);
        listener = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && states[0] != null) {
            refresh();
        }
    }

    private void toggle(int which) {
        Context context = getContext();
        boolean on;
        switch (which) {
            case MIRROR:
                on = !config.isRearViewEnabled();
                if (!overlay(OverlayCoordinator.setRearViewEnabled(context, on))) {
                    return;
                }
                if (on) {
                    Toast.makeText(context, R.string.msg_rearview_on, Toast.LENGTH_SHORT).show();
                    RearViewGuide.showOnce(activity());
                }
                break;
            case SIDE_VIEW:
                on = !config.isSideViewEnabled();
                if (!overlay(OverlayCoordinator.setSideViewEnabled(context, on))) {
                    return;
                }
                break;
            case FLOATING:
                on = !config.isRecordingFloatingEnabled();
                if (!overlay(OverlayCoordinator.setRecordButtonEnabled(context, on))) {
                    return;
                }
                break;
            case SMART_BUTTONS:
                on = !config.isSmartButtonsEnabled();
                config.setSmartButtonsEnabled(on);
                com.kooo.evcam.button.SmartButtons buttons = com.kooo.evcam.button.SmartButtons.get();
                if (buttons != null) {
                    buttons.setEnabled(on);
                }
                break;
            case DRIVE_SOUND:
                on = !config.isDriveSoundEnabled();
                config.setDriveSoundEnabled(on);
                com.kooo.evcam.sound.DriveSound.refresh(context);
                break;
            default:
                on = !config.isFisheyeCorrection();
                config.setFisheyeCorrection(on);
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant(
                "开关变更: 主界面 → " + context.getString(NAMES[which]) + (on ? " 开" : " 关"));
        refresh();
    }

    /** 悬浮窗类的开关没开成（没有悬浮窗权限）：提示一句、去要权限，这一块停在原位。 */
    private boolean overlay(boolean applied) {
        if (!applied) {
            Toast.makeText(getContext(), R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(getContext());
        }
        return applied;
    }

    /** 每块照实际状态写：下面那一行字、右上角的点。 */
    private void refresh() {
        boolean mirror = config.isRearViewEnabled();
        show(MIRROR, mirror, mirror ? SettingsRegistry.REARVIEW_VIEWS.label(getContext(), config.getRearViewViews().key)
                : getContext().getString(R.string.opt_off));
        boolean side = config.isSideViewEnabled();
        show(SIDE_VIEW, side, side ? SettingsRegistry.SIDE_VIEW_MODE.label(getContext(), config.getSideViewMode())
                : getContext().getString(R.string.opt_off));
        show(FLOATING, config.isRecordingFloatingEnabled(), null);
        boolean smart = config.isSmartButtonsEnabled();
        int bound = config.getSmartButtons().size();
        show(SMART_BUTTONS, smart, !smart ? null : bound > 0
                ? getContext().getString(R.string.quick_smart_bound, bound) : getContext().getString(R.string.quick_smart_none));
        show(DRIVE_SOUND, config.isDriveSoundEnabled(), null);
        show(FISHEYE, config.isFisheyeCorrection(), null);
    }

    /** @param text 状态那一行写什么；null 就写「开」「关」 */
    private void show(int which, boolean on, CharSequence text) {
        states[which].setText(text != null ? text : getContext().getString(on ? R.string.quick_on : R.string.quick_off));
        leds[which].setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(getContext(), on ? R.color.energy : R.color.text_tertiary)));
        tiles[which].setSelected(on);
    }

    private Activity activity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
