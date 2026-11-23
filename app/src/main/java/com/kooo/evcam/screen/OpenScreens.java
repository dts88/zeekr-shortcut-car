/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 我们开着的界面（主界面、回放、诊断、关于、截屏的同意框）：建了、还没销毁的都记着，停车时一处关掉
 * （{@link Parked}，规格 §3.3「停车」第 1 步，项目所有者 2026-11-21）。
 *
 * <p>是关掉（{@code finish}），不是退到后台：项目所有者说的「最小化」是运行资源最小 —— 留在后台的界面，视图、画布、
 * 播放器都还占着。录像、相机不靠界面（管线属于应用），在录的照录。人回来以后界面怎么回来不归这里管：熄屏那一刻主界面在前的，
 * 照那个记号重新起一个（{@code Recovery.bringBackUiAfterScreenOn}）；别的等人自己点开。</p>
 *
 * <p>只在主线程上动（界面的生命周期回调、{@link Parked} 都在主线程）。记的是活着的界面本身，销毁那一下就摘掉，留不住它。</p>
 */
public final class OpenScreens implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "OpenScreens";

    /** 活着的界面，按建的先后。 */
    private static final List<Activity> ALIVE = new ArrayList<>();
    /** 其中此刻在眼前的（onStart 之后、onStop 之前）。 */
    private static final Set<Activity> SHOWN = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 装在哪一个应用上（进程里只有一个；测试里每一条各建一个，换了就重新装、从头记）。 */
    private static Application installedOn;

    private OpenScreens() {
    }

    /** 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，在任何界面建出来之前）。重复装无害。 */
    public static synchronized void install(Application app) {
        if (app == null || installedOn == app) {
            return;
        }
        installedOn = app;
        ALIVE.clear();
        SHOWN.clear();
        app.registerActivityLifecycleCallbacks(new OpenScreens());
    }

    /** 活着的界面有几个（诊断报告、测试）。 */
    public static int count() {
        return ALIVE.size();
    }

    /**
     * 把开着的界面关掉（主线程）。已经在关的不算。
     *
     * @param hiddenOnly 只关不在眼前的（系统催内存时：在眼前的那一个，人可能正在用）
     * @param why        为什么（ASCII，进黑匣子：parked、head-unit-sleeps、trim-memory N）
     * @return 这一次关了几个
     */
    static int close(boolean hiddenOnly, String why) {
        int closed = 0;
        StringBuilder names = new StringBuilder();
        // 后建的先关（盖在上面的那一个先走）；都标成在关以后系统才动手，不会先把下面那一个摆回前台
        List<Activity> alive = new ArrayList<>(ALIVE);
        for (int i = alive.size() - 1; i >= 0; i--) {
            Activity activity = alive.get(i);
            if (activity.isFinishing() || (hiddenOnly && SHOWN.contains(activity))) {
                continue;
            }
            try {
                activity.finish();
                closed++;
                names.append(names.length() == 0 ? "" : ", ").append(activity.getClass().getSimpleName());
            } catch (RuntimeException e) {
                // 一个关不掉不挡别的
                AppLog.w(TAG, "finish " + activity.getClass().getSimpleName() + " failed: " + e);
            }
        }
        if (closed > 0) {
            BlackBox.noteImportant("停车：关掉开着的界面 " + closed + " 个（" + names + "；" + why + "）"
                    + BlackBox.afterScreenOff());
        }
        return closed;
    }

    // ================================================================= 界面的生命周期（主线程）

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (!ALIVE.contains(activity)) {
            ALIVE.add(activity);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        SHOWN.add(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        SHOWN.remove(activity);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        SHOWN.remove(activity);
        ALIVE.remove(activity);
    }
}
