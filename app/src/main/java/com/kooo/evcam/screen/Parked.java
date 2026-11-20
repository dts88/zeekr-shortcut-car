/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.button.SmartButtons;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 停车：人不在车上，相机以外的也放开（项目所有者 2026-11-20、11-21，规格 §0、§3.3「停车」）。一处定什么时候放、按什么次序放、
 * 人回来按什么次序拿回 —— 别处只照做。
 *
 * <h3>什么时候</h3>
 *
 * <p>熄屏（{@link ScreenState#dark}：人不在车上）满 {@link ChannelRules#HOLD_MS}，相机管家先放相机（封文件、环视、座舱）；
 * 再过 {@link #AFTER_CAMERAS_MS} 是这里（{@link #due}）。黑着时安卓也熄屏了（车机要睡）当场放，不等。进程起来时没有人的证据：
 * 从停车起，什么都还没拿。停了车就一直停着，车机自己醒来、屏幕亮了都不变；有了人的证据（亮屏，E3）才结束。</p>
 *
 * <h3>放什么，按这个次序</h3>
 *
 * <ol>
 *   <li>悬浮窗：悬浮按钮、超级镜子、盲区画面的服务停掉，窗口移走（{@link OverlayCoordinator#stopForParking}）；</li>
 *   <li>蓝牙：灵动贴的扫描和连接（{@code SmartButtons.park}）；</li>
 *   <li>车辆信号：对账只读判人回没回来、相机管家熄屏时看的那几项，浮点传感器、定位、后视镜上的灯停掉（{@code Telemetry.setParked}）。</li>
 * </ol>
 *
 * <p>「熄屏持续录制」在录时（哨兵模式开着）一样放这几样：录像要的是相机、编码、录音、存储和前台服务，都不在这里。
 * 一步出错不挡后面的。</p>
 *
 * <h3>人回来</h3>
 *
 * <p>亮屏那条边上相机管家先接相机（{@code ScreenState.wentLit}），然后这里，同一下：车辆信号恢复（离散的当场全读一遍）、
 * 蓝牙接着听、欠着的悬浮窗照设置起（{@link OverlayCoordinator#restoreAfterParking}）—— 我们自己停掉的，不看开机自启动；
 * 用户退出了的不起。屏幕黑着时有人要起悬浮窗（主界面打开）也记成欠着，一样在这里起。</p>
 */
public final class Parked {

    private static final String TAG = "Parked";
    /** 相机在黑满 {@link ChannelRules#HOLD_MS} 那一刻开始放；相机以外的排在它后面这么久。 */
    static final long AFTER_CAMERAS_MS = 10_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    /** 停车着。装上之前照没停车算（单元测试里别的类问到时）。 */
    private static volatile boolean parked;
    /** 这一次停车从什么时候起（elapsedRealtime）；0 = 没停车，或者进程起来时就停着。 */
    private static volatile long parkedAtMs;
    /** 停车时停掉的、黑着时没起的悬浮窗：人回来照设置起。 */
    private static volatile boolean overlaysOwed;

    private Parked() {
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，紧跟 {@link ScreenState#install}，在灵动贴之前）：这时没有人的证据，
     * 从停车起 —— 车辆信号从一开始就只读那几项，蓝牙不扫，悬浮窗不起。
     */
    public static synchronized void install(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        if (!ScreenState.dark()) {
            return;
        }
        parked = true;
        step("telemetry", () -> Telemetry.get().setParked(true));
        BlackBox.noteImportant("进程起来时没有人在车上的证据：从停车起，相机以外的也不拿（悬浮窗、蓝牙；车辆信号只读判人回来的那几项）");
    }

    /** 停车着（相机以外的也放开了）。哪个线程都能问。 */
    public static boolean is() {
        return parked;
    }

    /** 停车多久了（毫秒，含深睡）；没停车、进程起来时就停着的是 -1。诊断报告用。 */
    public static long forMs() {
        long at = parkedAtMs;
        return parked && at > 0 ? SystemClock.elapsedRealtime() - at : -1;
    }

    /** 悬浮窗欠着：人回来要照设置起（诊断报告、恢复看）。 */
    public static boolean overlaysOwed() {
        return overlaysOwed;
    }

    /** 屏幕黑着时有人要起悬浮窗（主界面打开）：先不起，记着，人回来再起。 */
    public static void oweOverlays() {
        overlaysOwed = true;
    }

    /** 悬浮窗照设置起过了（{@link OverlayCoordinator#restoreOnLaunch}、{@link OverlayCoordinator#restoreAfterParking}）。 */
    public static void overlaysRestored() {
        overlaysOwed = false;
    }

    // ================================================================= 规矩（纯函数）

    /**
     * 该停车了：熄屏满 {@link ChannelRules#HOLD_MS} + {@link #AFTER_CAMERAS_MS}（相机先放），或者黑着时安卓也熄屏了（车机要睡，
     * 当场放），或者不知道什么时候黑的（进程起来时就黑着）。
     *
     * @param dark       熄屏着（人不在车上）
     * @param darkForMs  熄屏多久了；起点不明是负数
     * @param androidOff 黑着时安卓也熄屏了
     */
    static boolean due(boolean dark, long darkForMs, boolean androidOff) {
        return dark && (androidOff || darkForMs < 0 || darkForMs >= ChannelRules.HOLD_MS + AFTER_CAMERAS_MS);
    }

    /** 再过多久该停车（毫秒）；该了是 0，亮着是 -1。 */
    static long untilDue(boolean dark, long darkForMs, boolean androidOff) {
        if (!dark) {
            return -1;
        }
        return due(true, darkForMs, androidOff) ? 0 : ChannelRules.HOLD_MS + AFTER_CAMERAS_MS - darkForMs;
    }

    // ================================================================= 输入（主线程）

    private static final Runnable CHECK = Parked::check;

    /**
     * 熄屏判断每换一次状态叫一次（{@code ScreenState.move}，那条边自己的事做完之后）：亮屏就结束停车；黑着就看该不该停车、
     * 还差多久（到点自己再看）。灵动贴要知道的两下也从这里转：安卓亮了（车机醒了，蓝牙多半重启过）、亮屏。
     *
     * @param edge 这一次跨过的边，没跨是 null
     */
    static void screenMoved(ScreenTransitions.State before, ScreenTransitions.State after, ScreenTransitions.Edge edge) {
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null && before.androidOff && !after.androidOff) {
            buttons.headUnitWoke();
        }
        if (edge == ScreenTransitions.Edge.LIT) {
            leave();
            if (buttons != null) {
                buttons.screenLit();
            }
            if (overlaysOwed && app != null) {
                step("overlays", () -> {
                    if (UserExit.isExited(app)) {
                        overlaysOwed = false;
                    } else {
                        OverlayCoordinator.restoreAfterParking(app);
                    }
                });
            }
            return;
        }
        if (after.dark()) {
            check();
        }
    }

    private static void check() {
        MAIN.removeCallbacks(CHECK);
        if (parked || app == null || !ScreenState.dark()) {
            return;
        }
        long wait = untilDue(true, ScreenState.darkForMs(), ScreenState.androidOff());
        if (wait > 0) {
            MAIN.postDelayed(CHECK, wait);
            return;
        }
        enter();
    }

    /** 停车：按次序放开。 */
    private static void enter() {
        parked = true;
        parkedAtMs = SystemClock.elapsedRealtime();
        BlackBox.noteImportant("停车：人不在车上" + (ScreenState.androidOff() ? "、车机要睡" : "")
                + "，相机以外的也放开（悬浮窗 → 蓝牙 → 车辆信号只读判人回来的那几项）" + BlackBox.afterScreenOff());
        step("overlays", () -> {
            if (OverlayCoordinator.stopForParking(app)) {
                overlaysOwed = true;
            }
        });
        step("bluetooth", () -> {
            SmartButtons buttons = SmartButtons.get();
            if (buttons != null) {
                buttons.park();
            }
        });
        step("telemetry", () -> Telemetry.get().setParked(true));
    }

    /** 人回来了（亮屏）：还没到停车就回来的，什么都没放开过。欠着的悬浮窗由 {@link #screenMoved} 接着起。 */
    private static void leave() {
        MAIN.removeCallbacks(CHECK);
        if (!parked) {
            return;
        }
        long ms = forMs();
        parked = false;
        parkedAtMs = 0;
        BlackBox.noteImportant("人回来了：停车结束" + (ms < 0 ? "" : "（停了 " + ms / 1000 + " 秒）")
                + "，车辆信号恢复、蓝牙接着听" + (overlaysOwed ? "、悬浮窗照设置起" : ""));
        step("telemetry", () -> Telemetry.get().setParked(false));
    }

    /** 一步出错不挡后面的。 */
    private static void step(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            AppLog.e(TAG, "step " + name + " failed", e);
            BlackBox.noteImportant("停车：" + name + " 这一步出错（" + e + "），后面的照做");
        }
    }
}
