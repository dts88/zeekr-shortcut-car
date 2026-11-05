/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) suyunkai and the EVCam contributors - https://github.com/suyunkai/EVCam
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.kooo.evcam.AppLog;

/**
 * 录制悬浮按钮绑着的服务（{@link RecordingFloatingService}），应用在后台时悬浮按钮按下录制也先把它起来。
 * 录像不经过这里：开、停都向相机管家（{@code CameraKeeper}）请求。它以前的开始 / 停止两个入口从没被叫过，2026-11-03 删了。
 *
 * <p>它以前是 EVCam「新录制架构」的中枢：自带一套全景合成引擎、录制状态机和补盲叠加层。
 * 那套东西在这台车上从没跑起来过（引擎只在四路以上时启用，极氪最多三路），1.44.0 删了，
 * 只留下开始 / 停止这一件事。</p>
 */
public class CameraRecordingService extends Service {
    private static final String TAG = "CameraRecordingService";

    private final IBinder binder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "录制服务创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "CameraRecordingService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "录制服务销毁");
        // 录像不属于这个服务：它被系统收走时录像照常，由相机管家管
    }
}
