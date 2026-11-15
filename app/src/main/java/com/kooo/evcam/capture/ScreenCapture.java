/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.capture;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.zeekr.RawFrameDump;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 截屏（项目所有者 2026-11-15，悬浮按钮、灵动贴的动作，给 wiki 统一截实车图）：截下整个中控屏的画面。
 *
 * <ul>
 *   <li>普通应用截整个屏幕只能走系统的屏幕录制授权（MediaProjection）：第一次按弹系统的同意框
 *       （{@link ScreenCaptureConsentActivity}），同意后到熄屏或退出应用为止不再问（项目所有者选的），
 *       这期间 {@link ScreenCaptureService} 拿着授权，状态栏有屏幕录制的图标。</li>
 *   <li>存在 U 盘照片目录下的 {@code raw}（开发者选项整帧另存的原图也在这里，{@link RawFrameDump}，不在图片回放里列出；
 *       项目所有者：存在当初存 raw 图片的位置），PNG。存不了（没有 U 盘）提示一句、不弹框。</li>
 * </ul>
 *
 * <p>车机是安卓 12L（Lab）：系统界面里有同意框；App Lab 容器放不放这条路、行车时同意框会不会被挡，要上车看。</p>
 */
public final class ScreenCapture {

    private static final String TAG = "ScreenCapture";

    private ScreenCapture() {
    }

    /** 截一张：授权还在就直接截；不在就先弹系统的同意框。主线程。 */
    public static void take(Context context) {
        Context app = context.getApplicationContext();
        if (folder(app) == null) {
            Toast.makeText(app, R.string.msg_screenshot_no_drive, Toast.LENGTH_SHORT).show();
            return;
        }
        ScreenCaptureService session = ScreenCaptureService.current();
        if (session != null) {
            session.capture(0);
            return;
        }
        BlackBox.note("截屏：没有授权，弹系统的同意框");
        Intent intent = new Intent(app, ScreenCaptureConsentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "consent activity failed: " + e);
            failed(app);
        }
    }

    /** 截图放哪：U 盘照片目录下的 {@code raw}；存不了（没有 U 盘）是 null。 */
    static File folder(Context app) {
        File photos = StorageHelper.getPhotoDir(app);
        return photos == null ? null : new File(photos, RawFrameDump.DIR_NAME);
    }

    /** 文件名：时间在前，同照片、整帧原图（{@code 20261115_143005_screenshot.png}）。 */
    static String fileName(Date when) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when) + "_screenshot.png";
    }

    /** 截不到：提示「无法执行「截屏」」（和车控动作做不了是同一句）。 */
    static void failed(Context app) {
        Toast.makeText(app, app.getString(R.string.msg_car_action_unavailable,
                app.getString(R.string.floating_action_take_screenshot)), Toast.LENGTH_SHORT).show();
    }
}
