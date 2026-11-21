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
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.camera.ChannelFacts;
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
 * <p>在开车时不停车（{@code ChannelFacts.drivingNow}，和相机管家同一个定义：挡位不在 P）：亮着的时候车辆信号读不到太久、
 * 安卓在行驶中熄屏，都会判成熄屏，这时把悬浮按钮、灵动贴也放开的话，人就没有办法「碰一下应用」把它叫回来了。
 * 不开了（挂回 P）还黑着，再照上面的时刻算。进程起来时照旧从停车起 —— 那时什么都没拿，开着车也不为它去拿。</p>
 *
 * <h3>放什么，按这个次序</h3>
 *
 * <ol>
 *   <li>悬浮窗：悬浮按钮、超级镜子、盲区画面的服务停掉，窗口移走（{@link OverlayCoordinator#stopForParking}）；</li>
 *   <li>蓝牙：灵动贴的扫描和连接（{@code SmartButtons.park}）；</li>
 *   <li>车辆信号：对账只读判人回没回来、相机管家熄屏时看的那几项，浮点传感器、定位、后视镜上的灯停掉（{@code Telemetry.setParked}）；</li>
 *   <li>前台服务：没在录就停掉（{@code CameraKeeper.parkingChanged}，项目所有者 2026-11-21）—— 应用停车时车机随时可以收走。
 *       「熄屏持续录制」开着、「这一趟要录」立着的，留到车机睡过（{@link #headUnitSlept}）：这之前哨兵模式变成开还接得上录像。</li>
 * </ol>
 *
 * <p>「熄屏持续录制」在录时（哨兵模式开着）前三样一样放：录像要的是相机、编码、录音、存储和前台服务。一步出错不挡后面的。</p>
 *
 * <h3>人回来</h3>
 *
 * <p>亮屏那条边上，同一下：车辆信号最先恢复（{@link #leave}，在相机管家和各模块之前 —— 它们那一下要看的读数、要登记的
 * 浮点和定位都是恢复了的；离散的当场全读一遍）；那条边自己的事做完之后，蓝牙接着听、欠着的悬浮窗照设置起
 * （{@link OverlayCoordinator#restoreAfterParking}）—— 我们自己停掉的，不看开机自启动；用户退出了的不起。
 * 屏幕黑着时有人要起悬浮窗（主界面打开）也记成欠着，一样在这里起。</p>
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
    /** 这一段熄屏里车机睡过（安卓熄过屏）；亮屏清掉。前台服务留不留看它（{@link #headUnitSlept}）。 */
    private static volatile boolean slept;

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
        // 在开车时不停车：不开了（挡位变了）还黑着的，那一下再看
        Telemetry.get().addListener(readings -> {
            if (!parked && ScreenState.dark()) {
                check();
            }
        });
        if (!ScreenState.dark()) {
            return;
        }
        parked = true;
        slept = ScreenState.androidOff();
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

    /**
     * 这一段熄屏里车机睡过（安卓熄过屏）。相机管家问（{@code ChannelRules.sentryCanStartWhileParked}）：睡过以后前台服务不再为
     * 「熄屏中哨兵模式变成开就接着录」留着 —— 车自己醒来时不为它拿着。
     */
    public static boolean headUnitSlept() {
        return slept;
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
     * 当场放），或者不知道什么时候黑的。在开车时不算。
     *
     * @param dark       熄屏着（人不在车上）
     * @param darkForMs  熄屏多久了；起点不明是负数
     * @param androidOff 黑着时安卓也熄屏了
     * @param driving    在开车（{@code ChannelRules.driving}）
     */
    static boolean due(boolean dark, long darkForMs, boolean androidOff, boolean driving) {
        return dark && !driving && (androidOff || darkForMs < 0 || darkForMs >= ChannelRules.HOLD_MS + AFTER_CAMERAS_MS);
    }

    /** 再过多久该停车（毫秒）；该了是 0；亮着、在开车是 -1（不用等：不开了那一下再看）。 */
    static long untilDue(boolean dark, long darkForMs, boolean androidOff, boolean driving) {
        if (!dark || driving) {
            return -1;
        }
        return due(true, darkForMs, androidOff, false) ? 0 : ChannelRules.HOLD_MS + AFTER_CAMERAS_MS - darkForMs;
    }

    // ================================================================= 输入（主线程）

    private static final Runnable CHECK = Parked::check;

    /**
     * 熄屏判断每换一次状态叫一次（{@code ScreenState.move}，那条边自己的事做完之后）：亮屏就把蓝牙、欠着的悬浮窗拿回来
     * （车辆信号在那条边的开头已经恢复，{@link #leave}）；黑着就看该不该停车、还差多久（到点自己再看）。
     * 灵动贴要知道的两下也从这里转：安卓亮了（车机醒了，蓝牙多半重启过）、亮屏。
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
            if (after.androidOff && !slept) {
                slept = true;
                if (parked) {
                    // 停车着车机要睡了：前台服务不再留
                    step("foreground service", Parked::tellKeeper);
                }
            }
            check();
        }
    }

    private static void tellKeeper() {
        if (app != null) {
            CameraKeeper.get(app).parkingChanged();
        }
    }

    private static void check() {
        MAIN.removeCallbacks(CHECK);
        if (parked || app == null || !ScreenState.dark()) {
            return;
        }
        long wait = untilDue(true, ScreenState.darkForMs(), ScreenState.androidOff(), ChannelFacts.drivingNow());
        if (wait > 0) {
            MAIN.postDelayed(CHECK, wait);
        } else if (wait == 0) {
            enter();
        }
        // 在开车：不停车，不开了（车辆读数变了）那一下再看
    }

    /** 停车：按次序放开。 */
    private static void enter() {
        parked = true;
        parkedAtMs = SystemClock.elapsedRealtime();
        BlackBox.noteImportant("停车：人不在车上" + (ScreenState.androidOff() ? "、车机要睡" : "")
                + "，相机以外的也放开（悬浮窗 → 蓝牙 → 车辆信号只读判人回来的那几项 → 前台服务）" + BlackBox.afterScreenOff());
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
        step("foreground service", Parked::tellKeeper);
    }

    /**
     * 人回来了（亮屏那条边的开头，{@code ScreenState.wentLit}，在相机管家和各模块之前）：停车结束，车辆信号恢复。
     * 还没到停车就回来的，什么都没放开过。蓝牙、欠着的悬浮窗由 {@link #screenMoved} 在那条边的最后拿回。重复叫无害。
     */
    static void leave() {
        MAIN.removeCallbacks(CHECK);
        slept = false;
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
