/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 限速的提示（docs/ui-spec.md §十五，项目所有者 2026-11-28）：装上这个版本后第一次在主界面时弹一次 ——
 * 新加坡、马来西亚、香港的用户可以下载限速数据，在哪里下载和开启；其他国家和地区的用户到 GitHub 的 Issues 页面提交需求。
 *
 * <p>弹没弹过存在单独一个文件里：不是设置，不进设置备份、诊断报告。点了哪个按钮、按返回键都算弹过；
 * 没点就被关掉的（界面被关）下次再弹。什么时候看一眼归主界面（{@code MainActivity}），该不该弹归这里。</p>
 */
public final class SpeedLimitNotice {

    private static final String TAG = "SpeedLimitNotice";
    static final String FILE = "notices";
    static final String KEY = "speed_limit";

    private SpeedLimitNotice() {
    }

    /** 该不该弹：没弹过、不用走初始设置、还没开「悬浮按钮显示限速」（开着的人已经知道了）。 */
    public static boolean due(Context context, AppConfig config) {
        return !shown(context) && !config.needsSetup() && !config.isSpeedLimitOnButton();
    }

    static boolean shown(Context context) {
        return prefs(context).getBoolean(KEY, false);
    }

    private static void markShown(Context context) {
        prefs(context).edit().putBoolean(KEY, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 弹出来。「打开设置」先记下弹过，再 {@code openSettings}；「知道了」、返回键只记下弹过。
     */
    public static AlertDialog show(Activity activity, Runnable openSettings) {
        Context app = activity.getApplicationContext();
        AppLog.d(TAG, "弹出限速的提示");
        return CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_speed_limit_title)
                .setMessage(R.string.notice_speed_limit_msg)
                .setPositiveButton(R.string.action_open_settings, (dialog, which) -> {
                    markShown(app);
                    AppLog.d(TAG, "限速的提示：打开设置");
                    openSettings.run();
                })
                .setNegativeButton(R.string.action_got_it, (dialog, which) -> {
                    markShown(app);
                    AppLog.d(TAG, "限速的提示：知道了");
                })
                .setOnCancelListener(dialog -> {
                    markShown(app);
                    AppLog.d(TAG, "限速的提示：返回键");
                }));
    }
}
