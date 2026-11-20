/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recovery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 连着崩溃就不再自动回来（项目所有者 2026-11-21，规格 §6）：应用装在容器里，一遍遍崩、一遍遍被周期任务拉回来，可能把容器也带崩。
 *
 * <ul>
 *   <li>崩一次记一次（{@link #noteCrash}，同步落盘 —— 后面进程就没了）；</li>
 *   <li>进程好好跑满 {@link #HEALTHY_MS} 清零（不含车机深睡的时间）；</li>
 *   <li>连着 {@link #MAX} 次：下一次进程起来时照用户退出处理（{@link UserExit#markHeldAfterCrashes}）—— 周期任务取消、
 *       什么都不恢复、相机不开、蓝牙不扫，直到人自己点开主界面或者车机真正开机（{@link UserExit#clear}，那里把次数清零）。</li>
 * </ul>
 */
public final class CrashHold {

    private static final String TAG = "CrashHold";
    /** 连着崩这么多次就按住。 */
    static final int MAX = 2;
    /** 进程跑满这么久没崩，之前的不算连着了（Handler 的钟：车机深睡时不走）。 */
    static final long HEALTHY_MS = 5 * 60_000L;

    private static final String PREFS = "crash_hold";
    private static final String KEY_COUNT = "crashes_in_a_row";

    private CrashHold() {
    }

    /** 连着崩了这么多次，该不该按住。 */
    static boolean holds(int crashesInARow) {
        return crashesInARow >= MAX;
    }

    /** 崩溃处理器里叫（哪个线程都可能）：加一，同步落盘。不抛。 */
    public static void noteCrash(Context context) {
        if (context == null) {
            return;
        }
        try {
            SharedPreferences p = prefs(context);
            p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).commit();
        } catch (RuntimeException ignored) {
            // 崩溃路上：记不下就算了
        }
    }

    /**
     * 进程一起来就叫（{@code ZeekrShortcutApp.onCreate}，黑匣子接上之后、别的装上之前，主线程）：连着崩够了就照用户退出处理；
     * 崩过、还没够的，排一个「跑满了清零」。
     *
     * @return 这一次起来被按住了
     */
    public static boolean atProcessStart(Context context) {
        final Context app = context.getApplicationContext();
        int n = count(app);
        if (holds(n)) {
            if (!UserExit.isExited(app)) {
                UserExit.markHeldAfterCrashes(app, n);
            }
            return true;
        }
        if (n > 0) {
            BlackBox.noteImportant("上一次运行是崩溃结束的（连着 " + n + " 次）：再崩一次就不再自动回来；这一次跑满 "
                    + HEALTHY_MS / 60_000L + " 分钟清零");
            new Handler(Looper.getMainLooper()).postDelayed(() -> reset(app), HEALTHY_MS);
        }
        return false;
    }

    /** 清零：进程好好跑满了一阵，或者人自己点开了主界面、车机真正开机了（{@link UserExit#clear}）。 */
    public static void reset(Context context) {
        try {
            SharedPreferences p = prefs(context);
            if (p.getInt(KEY_COUNT, 0) != 0) {
                p.edit().remove(KEY_COUNT).apply();
                AppLog.i(TAG, "crash count cleared");
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "reset failed: " + e);
        }
    }

    /** 连着崩了几次（诊断报告）。 */
    public static int count(Context context) {
        try {
            return prefs(context).getInt(KEY_COUNT, 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
