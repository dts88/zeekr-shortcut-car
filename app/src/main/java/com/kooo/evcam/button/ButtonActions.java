/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.overlay.QuickActions;
import com.kooo.evcam.sound.DriveSound;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 按下去做选的那一件事（{@link ButtonAction}）：悬浮按钮的点击 / 长按、灵动贴的单击 / 双击都走这一份
 * （项目所有者 2026-11-15：两边的动作合成一份列表，原有的全部保留）。都在主线程上叫。
 *
 * <ul>
 *   <li>应用功能（原来悬浮按钮的那几样）走 {@link QuickActions}。</li>
 *   <li>写车的（驾驶模式和其他车控动作）交给 {@link CarMoves}，没做成提示一句（{@link #carResult}）；媒体、语音走 {@link MediaMove}；
 *       升挡、降挡交给模拟声浪（{@link DriveSound#shift}）。</li>
 * </ul>
 *
 * <p>驾驶模式向上、向下连按时接着上一次的目标算（{@link #DRIVE_TARGET_MS}）：悬浮按钮、灵动贴按的算同一串。</p>
 */
public final class ButtonActions {

    private static final String TAG = "ButtonActions";

    /** 连按驾驶模式向上、向下时，这么久（毫秒）之内接着上一次的目标算。 */
    static final long DRIVE_TARGET_MS = 2000L;
    static final int MAX_STEPS = 10;

    /** 刚要切到的驾驶模式、什么时候要的（{@link #stepDriveMode}）；0 = 没有。只在主线程上碰。 */
    private static int driveTarget;
    private static long driveTargetAt;

    private ButtonActions() {
    }

    /** 做一件。{@code why} 说是谁按的（{@link CameraKeeper.Why#FLOATING} 悬浮按钮、{@link CameraKeeper.Why#BUTTON} 灵动贴）。 */
    public static void perform(Context app, ButtonAction action, CameraKeeper.Why why) {
        perform(app, action, why, 1);
    }

    public static void perform(Context app, ButtonAction action, CameraKeeper.Why why, int steps) {
        // 人按了悬浮按钮、灵动贴：人在车上的证据（ScreenState.userActed）
        com.kooo.evcam.screen.ScreenState.userActed(why == CameraKeeper.Why.FLOATING ? "floating" : "button");
        int n = action.stepwise() ? Math.max(1, Math.min(MAX_STEPS, steps)) : 1;
        if (action.floating != null) {
            try {
                QuickActions.perform(app, action.floating, why);
            } catch (RuntimeException e) {
                // 应用在后台、又没有前台服务撑着时，安卓不让起服务（超级镜子、录制）：这一下不做，进程不能因为它崩
                AppLog.w(TAG, "action " + action.key + " failed: " + e);
            }
        } else if (action.driveMode != 0) {
            writeDriveMode(app, action.driveMode, why);
        } else if (action.driveModeStep != 0) {
            stepDriveMode(app, action.driveModeStep * n, why);
        } else if (action.gearStep != 0) {
            for (int i = 0; i < n; i++) {
                DriveSound.shift(action.gearStep);
            }
        } else if (action.stepCar != null) {
            String label = action.label(app);
            CarMoves.run(app, why, action.key, action.stepCar.apply(n), result -> carResult(app, label, result));
        } else if (action.car != null) {
            String label = action.label(app);
            CarMoves.run(app, why, action.key, action.car, result -> carResult(app, label, result));
        } else if (action.media != null) {
            for (int i = 0; i < n; i++) {
                if (!action.media.perform(app)) {
                    Toast.makeText(app, app.getString(R.string.msg_car_action_unavailable, action.label(app)),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }

    /** 写车的没做成（主线程）：提示一句为什么。做成了不提示：车上看得到。 */
    private static void carResult(Context app, String label, CarMoves.Result result) {
        String text = CarMoves.failureText(app, label, result);
        if (text != null) {
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 驾驶模式走一级（{@link ButtonAction#nextDriveMode}）：到头不动、不提示；此刻不在舒适、标准、运动里（雪地、读不到）不切换。
     * 连按时接着上一次的目标算，车机回报晚了也不会少走一级（{@link #DRIVE_TARGET_MS}）。
     */
    private static void stepDriveMode(Context app, int step, CameraKeeper.Why why) {
        boolean recent = driveTarget != 0 && SystemClock.elapsedRealtime() - driveTargetAt < DRIVE_TARGET_MS;
        Integer from = recent ? Integer.valueOf(driveTarget) : Telemetry.get().readings().code(Signal.DRIVE_MODE);
        int to = ButtonAction.nextDriveMode(from, step);
        if (to == 0) {
            Toast.makeText(app, R.string.msg_drive_mode_failed, Toast.LENGTH_SHORT).show();
        } else if (to != from) {
            writeDriveMode(app, to, why);
        }
    }

    /** 切驾驶模式（{@link CarMoves#driveMode}）：没切成提示「驾驶模式没有切换」，连按的目标作废。 */
    private static void writeDriveMode(Context app, int mode, CameraKeeper.Why why) {
        driveTarget = mode;
        driveTargetAt = SystemClock.elapsedRealtime();
        CarMoves.run(app, why, "drive_mode", CarMoves.driveMode(mode), result -> {
            if (result != CarMoves.Result.DONE) {
                driveTarget = 0;
                Toast.makeText(app, R.string.msg_drive_mode_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
