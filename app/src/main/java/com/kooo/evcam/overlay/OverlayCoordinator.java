/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.overlay;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.screen.Parked;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.service.CameraRecordingService;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.zeekr.RearViewMirrorService;
import com.kooo.evcam.zeekr.SideViewService;

/**
 * 三个悬浮窗（超级镜子、盲区画面、录制悬浮按钮）「该不该开、能不能开、什么时候开」。
 *
 * <h3>为什么要有这么个地方</h3>
 *
 * <p>同一个问题原先在三处各答一遍：主界面启动时、设置页开关时、前台服务在开机自启时。
 * 三份答案已经不一致了 —— 最明显的一处是<b>超级镜子的开关没查悬浮窗权限</b>：
 * 没授权时开关拨上去、提示语还告诉你手势怎么用，屏幕上什么都没有，
 * 服务在 {@code onStartCommand} 里 {@code stopSelf()} 走了，只往 logcat 写了一行。
 * 画面悬浮窗和录制悬浮按钮的开关都查了，唯独它漏了。</p>
 *
 * <p>这类「设置里显示开着、实际没开」的毛病，根子是同一个判断被抄了好几份。
 * 抄的时候都对，改的时候只改一处。所以这里只留一份。</p>
 */
public final class OverlayCoordinator {

    private static final String TAG = "OverlayCoordinator";

    /** 后视镜要绑相机，相机这会儿还在开，等一下再拉。 */
    private static final long REAR_VIEW_DELAY_MS = 2000;

    private OverlayCoordinator() {
    }

    // ------------------------------------------------------------------ 判断

    /** 有没有悬浮窗权限。没有的话，下面这些一个都开不起来。 */
    public static boolean canShowOverlay(Context context) {
        return WakeUpHelper.hasOverlayPermission(context);
    }


    // ------------------------------------------------------------------ 启动时恢复

    /**
     * 按设置把该开的悬浮窗都开起来。
     *
     * <p>悬浮按钮的录制状态不从这里推：它显示出来时自己问 {@code CameraKeeper}，之后听相机管家的广播。
     * 以前这里隔 500 ms 推一次主界面记的状态 —— 主界面刚重建、还没对上录制状态时推的是「没在录」，
     * 录着像按钮却是灰的（2.10.0 实车，2026-10-08）。</p>
     *
     * <p>屏幕黑着（人不在车上，或者还不知道）不起：记着，人回来（亮屏）那一下照设置起（{@link Parked}、
     * {@link #restoreAfterParking}；项目所有者 2026-11-21：人不在车上不取资源）。</p>
     */
    public static void restoreOnLaunch(Context context) {
        if (ScreenState.dark()) {
            Parked.oweOverlays();
            return;
        }
        start(context, REAR_VIEW_DELAY_MS);
    }

    /**
     * 人回来了（{@link Parked}，亮屏那条边上、主线程）：停车时停掉的、屏幕黑着时没起的，照设置当场起。超级镜子不等那两秒 ——
     * 它的画布要赶在环视建会话之前交上，那一路第一轮就带着它，不用随后再改一次输出（规格 §5.3）。
     *
     * <p>应用这时可能在后台、又没有前台服务撑着（开机自启动关着、没在录）：安卓不让后台起服务。起不来的还欠着，
     * 主界面下一次到前台时再起（{@link #onMainScreenInFront}）。</p>
     */
    public static void restoreAfterParking(Context context) {
        start(context, 0);
    }

    private static void start(Context context, long rearViewDelayMs) {
        Parked.overlaysRestored();
        AppConfig config = new AppConfig(context);
        boolean allowed = canShowOverlay(context);

        if (config.isRearViewEnabled() && allowed) {
            // 这一段以前没有：开关存着「开」，但没人在启动时把服务拉起来，
            // 于是每次重开应用都要去设置里关一次再开一次它才出现。
            if (rearViewDelayMs <= 0) {
                startOrOwe(() -> RearViewMirrorService.start(context), "mirror");
            } else {
                main().postDelayed(() -> {
                    if (ScreenState.dark()) {
                        // 这两秒里人走了：不起，回来再说
                        Parked.oweOverlays();
                        return;
                    }
                    startOrOwe(() -> RearViewMirrorService.start(context), "mirror");
                }, rearViewDelayMs);
            }
        }

        if (config.isSideViewEnabled() && allowed) {
            // 不用等相机：挂 D 挡才挂窗口、登记环视，开相机由通道调度做
            startOrOwe(() -> SideViewService.start(context), "side view");
        }

        if (config.isRecordingFloatingEnabled() && allowed) {
            startOrOwe(() -> sendToRecordingFloating(context, RecordingFloatingService.ACTION_SHOW), "floating button");
        }
    }

    /** 起一个悬浮窗的服务；系统不让起（应用在后台、没有前台服务）就还欠着，一个起不来不挡别的。 */
    private static void startOrOwe(Runnable start, String what) {
        try {
            start.run();
            AppLog.d(TAG, "overlay started by settings: " + what);
        } catch (RuntimeException e) {
            Parked.oweOverlays();
            AppLog.w(TAG, "overlay not started (" + what + "), still owed: " + e);
        }
    }

    // ------------------------------------------------------------------ 停车

    /**
     * 停车（人不在车上，{@link Parked}）：三个悬浮窗的服务都停掉、窗口移走，悬浮按钮绑着的那个服务也停。设置不动：
     * 人回来照设置起（{@link #restoreOnLaunch}）。一个停不掉不挡别的。
     *
     * @return 停之前有在跑的（人回来要起）
     */
    public static boolean stopForParking(Context context) {
        boolean any = RearViewMirrorService.isRunning() || SideViewService.isRunning()
                || RecordingFloatingService.isRunning();
        stop(context, RearViewMirrorService.class);
        stop(context, SideViewService.class);
        stop(context, RecordingFloatingService.class);
        stop(context, CameraRecordingService.class);
        AppLog.d(TAG, "stopped for parking, any running=" + any);
        return any;
    }

    private static void stop(Context context, Class<?> service) {
        try {
            context.stopService(new Intent(context, service));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "stopService(" + service.getSimpleName() + ") failed: " + e);
        }
    }

    // ------------------------------------------------------------------ 开关

    /**
     * 开 / 关悬浮按钮。
     *
     * <p>0.45 起只有这一个按钮：原来那个只管「打开应用」的已经并进来，
     * 成了它默认的单击动作。</p>
     *
     * @return 是否真的按要求生效；没有悬浮窗权限时返回 {@code false}，
     *         调用方应当把开关保持在原位而不是拨上去
     */
    public static boolean setRecordButtonEnabled(Context context, boolean enabled) {
        if (enabled && !canShowOverlay(context)) {
            return false;
        }
        new AppConfig(context).setRecordingFloatingEnabled(enabled);
        sendToRecordingFloating(context, enabled
                ? RecordingFloatingService.ACTION_SHOW
                : RecordingFloatingService.ACTION_HIDE);
        return true;
    }

    /**
     * 开 / 关超级镜子。返回值含义同 {@link #setRecordButtonEnabled}。
     *
     * <p>这里的权限检查是补上的 —— 原来没有，没授权时开关会拨上去而窗口不出现。</p>
     */
    public static boolean setRearViewEnabled(Context context, boolean enabled) {
        if (enabled && !canShowOverlay(context)) {
            return false;
        }
        new AppConfig(context).setRearViewEnabled(enabled);
        if (enabled) {
            RearViewMirrorService.start(context);
        } else {
            RearViewMirrorService.stop(context);
        }
        return true;
    }

    /** 开 / 关盲区画面。返回值含义同 {@link #setRecordButtonEnabled}。 */
    public static boolean setSideViewEnabled(Context context, boolean enabled) {
        if (enabled && !canShowOverlay(context)) {
            return false;
        }
        new AppConfig(context).setSideViewEnabled(enabled);
        if (enabled) {
            SideViewService.start(context);
        } else {
            SideViewService.stop(context);
        }
        return true;
    }


    /**
     * 主界面（预览那一页）到了前台、离开了前台（{@code MainActivity} 报）。在前台时悬浮按钮收起来：录制键就在动作栏上，
     * 按钮只会压住画面和标题（项目所有者 2026-11-13）；离开了照设置显示回来。显示还是收起由按钮的服务自己照这个状态摆
     * （别处叫它显示时也一样），这里只是告诉它变了。
     */
    public static void onMainScreenInFront(Context context, boolean inFront) {
        boolean changed = RecordingFloatingService.setMainScreenInFront(inFront);
        if (inFront && Parked.overlaysOwed() && !ScreenState.dark()) {
            // 人回来那一下应用在后台、系统没让起的（restoreAfterParking）：主界面到了前台，现在起得来
            restoreAfterParking(context);
            return;
        }
        if (!changed) {
            return;
        }
        if (!new AppConfig(context).isRecordingFloatingEnabled() || !canShowOverlay(context)) {
            return;
        }
        if (ScreenState.dark()) {
            // 屏幕黑着（熄屏后主界面离开前台的那一下、车机自己醒来时界面被摆来摆去）：不为它起悬浮按钮；
            // 人回来时照设置、照那时主界面在不在前摆（Parked）
            Parked.oweOverlays();
            return;
        }
        sendToRecordingFloating(context, RecordingFloatingService.ACTION_SHOW);
    }

    /**
     * 主界面销毁时的清理。
     *
     * <p>什么都不用停：后视镜和悬浮按钮是<b>脱离主界面用的</b>，
     * 主界面没了它们还该在 —— 那本来就是它们存在的理由。</p>
     */
    public static void onActivityDestroyed(Context context) {
        // 有意留空
    }

    // ------------------------------------------------------------------

    private static void sendToRecordingFloating(Context context, String action) {
        Intent intent = new Intent(context, RecordingFloatingService.class);
        intent.setAction(action);
        context.startService(intent);
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }
}
