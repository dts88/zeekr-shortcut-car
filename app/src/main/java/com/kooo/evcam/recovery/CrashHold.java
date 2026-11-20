/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recovery;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 连着崩溃就不再自动回来（项目所有者 2026-11-21，规格 §6）：应用装在容器里，一遍遍崩、一遍遍被周期任务拉回来，可能把容器也带崩。
 *
 * <ul>
 *   <li>「连着」= 一次接一次的运行都是崩溃结束的。崩溃处理器记一笔（{@link #noteCrash}，同步落盘 —— 后面进程就没了；
 *       一个进程只记一次）；</li>
 *   <li>进程起来时看（{@link #atProcessStart}）：上一次运行不是崩溃结束的（被系统收走、车机下电、人退出），之前的不算，从头数
 *       （{@link #inARow}）；</li>
 *   <li>连着 {@link #MAX} 次：这一次起来照用户退出处理（{@link UserExit#markHeldAfterCrashes}）—— 周期任务取消、什么都不恢复、
 *       相机不开、蓝牙不扫，直到人自己点开主界面（{@link UserExit#clear}，那里把次数清零）。</li>
 * </ul>
 *
 * <p>有了「人在车上才拿资源」（{@code ScreenState}），被周期任务带回来的进程人不在时什么都不做，本来就不大会崩；这里防的是
 * 一起来就崩的那一种。</p>
 */
public final class CrashHold {

    private static final String TAG = "CrashHold";
    /** 连着崩这么多次就按住。 */
    static final int MAX = 2;

    private static final String PREFS = "crash_hold";
    private static final String KEY_COUNT = "crashes_in_a_row";
    /** 上一次运行是崩溃结束的：崩溃处理器写，进程起来时读完就清。 */
    private static final String KEY_LAST_RUN_CRASHED = "last_run_crashed";

    /** 这个进程里记过了（崩溃处理器不止被叫一次时只算一次）。 */
    private static volatile boolean noted;

    private CrashHold() {
    }

    /** 连着崩了这么多次，该不该按住。 */
    static boolean holds(int crashesInARow) {
        return crashesInARow >= MAX;
    }

    /**
     * 到这一次起来为止连着崩了几次：上一次运行不是崩溃结束的，之前记着的不算。
     *
     * @param lastRunCrashed 上一次运行是崩溃结束的
     * @param stored         记着的次数
     */
    static int inARow(boolean lastRunCrashed, int stored) {
        return lastRunCrashed ? Math.max(stored, 0) : 0;
    }

    /** 崩溃处理器里叫（哪个线程都可能）：加一、记下这一次运行是崩溃结束的，同步落盘。不抛。 */
    public static void noteCrash(Context context) {
        if (context == null || noted) {
            return;
        }
        noted = true;
        try {
            SharedPreferences p = prefs(context);
            p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).putBoolean(KEY_LAST_RUN_CRASHED, true).commit();
        } catch (RuntimeException ignored) {
            // 崩溃路上：记不下就算了
        }
    }

    /**
     * 进程一起来就叫（{@code ZeekrShortcutApp.onCreate}，黑匣子接上之后、别的装上之前，主线程）：连着崩够了就照用户退出处理。
     *
     * @return 这一次起来被按住了
     */
    public static boolean atProcessStart(Context context) {
        final Context app = context.getApplicationContext();
        int n;
        try {
            SharedPreferences p = prefs(app);
            n = inARow(p.getBoolean(KEY_LAST_RUN_CRASHED, false), p.getInt(KEY_COUNT, 0));
            // 这一次运行从「没崩」开始；次数照连着的记
            p.edit().putInt(KEY_COUNT, n).putBoolean(KEY_LAST_RUN_CRASHED, false).apply();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "crash count unreadable: " + e);
            return false;
        }
        if (holds(n)) {
            if (!UserExit.isExited(app)) {
                UserExit.markHeldAfterCrashes(app, n);
            }
            return true;
        }
        if (n > 0) {
            BlackBox.noteImportant("上一次运行是崩溃结束的（连着 " + n + " 次）：这一次再崩，就不再自动回来");
        }
        return false;
    }

    /** 清零：人自己点开了主界面（{@link UserExit#clear}）。 */
    public static void reset(Context context) {
        try {
            SharedPreferences p = prefs(context);
            if (p.getInt(KEY_COUNT, 0) != 0 || p.getBoolean(KEY_LAST_RUN_CRASHED, false)) {
                p.edit().remove(KEY_COUNT).remove(KEY_LAST_RUN_CRASHED).apply();
                AppLog.i(TAG, "crash count cleared");
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "reset failed: " + e);
        }
    }

    /** 到这一次起来为止连着崩了几次（诊断报告）。 */
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
