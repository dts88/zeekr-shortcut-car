/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 wysie - https://github.com/wysie
 * Portions Copyright (C) 2026 FerixzTanz - https://github.com/FerixzTanz
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.CanvasSlot;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.ChannelScheduler;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.MirrorLamps;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 盲区画面：按设置里选的情境（{@link SideViewDecision.Mode}，项目所有者 2026-11-09），打转向灯、后视镜上的盲区指示灯、
 * 停车后的开门预警时在中控屏、HUD 上显示该侧摄像头的画面（issue #6、#24）。窗口、相机登记、车辆信号都在这里。
 *
 * <h3>三个出口，两份判定</h3>
 *
 * <p>中控屏上的窗口（{@link SideViewWindow}）和 HUD 上的窗口（{@link SideViewHudWindow}，2026-10-21）各是出口
 * （{@link Output}）：各自的窗口、各自在环视上声明的画布、各自「这一段带不带它」。显示在哪由设置定
 * （{@link AppConfig#getSideViewTarget}，默认两处）：选 HUD 而找不到 HUD、或者这一段 HUD 用不了时，退回中控屏。
 * 判定（{@link SideViewTrigger}）中控屏、HUD 各一份，规则同一套，只差一条：HUD 不看最低车速（FerixzTanz 的规则，
 * 项目所有者 2026-10-16 定照用）。</p>
 *
 * <p><b>左右两侧同时显示</b>（项目所有者 2026-11-04：后视镜上的灯左右可能同时亮）：中控屏两块画布、两个窗口，
 * 第一块管左侧、第二块（{@link ChannelRules.Kind#SIDE_SECOND}）管右侧；第二块这一段让出来了（共享预览流挂不下时它最先让），
 * 第一块显示优先的那一侧（{@link SideViewTrigger.Result#lane}）。HUD 一块画布、一个窗口同时画两侧。
 * 中控屏窗口的关闭按钮关掉的是那一侧这一次，中控屏、HUD 两处一起收。</p>
 *
 * <h3>只声明、只登记（同超级镜子，{@link RearViewMirrorService}）</h3>
 *
 * <p>通道调度（{@link ChannelScheduler}）是唯一动相机的地方。这里只做两件事：把窗口的画布交给相机管家（{@link CanvasSlot}，
 * 3c 第二步：它声明成环视的盲区画面输出，中控屏两块、HUD），和向相机管家要相机（{@link CameraKeeper#need}，{@link CameraKeeper.Use#SIDE_VIEW}）
 * —— 它要环视，配置里没开环视也要（项目所有者 2026-10-16，{@code ChannelRules}）。两个出口共用这一个登记方，都放开了才放开。
 * 开不开环视、什么时候把画布加进会话、挂不下时谁让路，都由调度和相机层判；以后调规则也只在登记表这一处。</p>
 *
 * <h3>什么时候挂、什么时候显示</h3>
 *
 * <p>规则在 {@link SideViewTrigger}（纯逻辑）：挂 D 挡就备着（窗口挂着、隐藏，登记、声明着，预备方式 B）；
 * 打灯显示那一侧，灯灭后 1 秒收起；离开 D 挡 30 秒后、熄屏、原厂画面出现时移走窗口、注销、撤回。
 * 超级镜子一点不动：几个窗口各管各的。</p>
 *
 * <p><b>移走窗口时画布由管线留着</b>（同超级镜子 §7.7，{@link CanvasSlot#keep}）：
 * 会话里的画布只由通道调度放，不自己放 —— PR #1、#19 就错在这里（窗口一拆就放掉画布，环视会话里多一个死输出，摘到之前整条流停着）。
 * 下一次挂窗口接回同一块（{@link Output#takeBackKeptCanvas}），通道零变动。</p>
 *
 * <h3>车辆信号</h3>
 *
 * <p>亮屏时完整登记（要车速），熄屏注销、窗口移走：熄屏后不该留着监听和相机过去（同超级镜子熄屏放开登记的理由）。
 * 后视镜上的灯另登记（{@link Telemetry#acquireLamps}）：亮屏时就读（三档都用得到）。
 * HUD 的夜间对比度、夜间亮度看昼夜信号（{@link Signal#DAY_NIGHT}），读不到按白天。</p>
 *
 * <p>来源：打灯判定、拉直和取景借自 FerixzTanz 的 PR #4、wysie 的 PR #19（GPL-3.0）；读车辆信号、备着 / 显示的流程
 * 也照它们的侧视弹窗服务写；HUD 照 FerixzTanz 的 CAMCORDER（GPL-3.0）。碰相机的部分照通道调度重写。</p>
 */
public class SideViewService extends Service {

    private static final String TAG = "SideViewSvc";
    private static final String TELEMETRY_USER = "side-view";

    /** 拿不到环视那一路（冷启动时管线可能还没建好）时的重试间隔与上限。 */
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;
    /** 看门狗间隔：管线换了（换车型）、冷启动时还没建好，都能在两秒内重新声明、重新登记上（同超级镜子）。 */
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    /** HUD 那块屏多久重新问一次系统：读数每 200 ms 来一次，不必每次都问。 */
    private static final long HUD_CHECK_MS = 2000L;

    private static volatile SideViewService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 判定：中控屏一份（两块画布共用）、HUD 一份（不看最低车速）。 */
    private final SideViewTrigger centerTrigger = new SideViewTrigger();
    private final SideViewTrigger hudTrigger = new SideViewTrigger();
    /** 中控屏第一块（左侧，只剩它时显示优先的那一侧）、第二块（右侧）、HUD。 */
    private final Output center = new Output(ChannelRules.Kind.SIDE);
    private final Output centerSecond = new Output(ChannelRules.Kind.SIDE_SECOND);
    private final Output hud = new Output(ChannelRules.Kind.HUD);
    private AppConfig appConfig;
    private boolean listening;
    private boolean destroyed;
    /** 这一段里打灯时已经提示过一次「无法显示盲区画面」。 */
    private boolean refusalShown;
    /** 环视出不了画面的这一阵里，打灯时已经提示过一次。环视能显示了才重新算（{@link #noteSurroundUnavailable}）。 */
    private boolean unavailableShown;
    private Runnable watchdog;
    /** HUD 那块屏（{@link HudScreen}）：上一次问系统的时刻和结果。 */
    private long hudCheckedAt;
    private Display hudDisplay;
    /** 车辆处于夜间模式（昼夜信号），给 HUD 的窗口。 */
    private boolean night;

    private final Runnable evaluateRunnable = this::evaluate;
    private final Telemetry.Listener readingsListener = readings -> evaluate();
    private final Telemetry.LampListener lampListener = lamps -> evaluate();
    /** 后视镜上的灯登记着（{@link #syncLamps}）。 */
    private boolean lampsHeld;

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            BlackBox.note("熄屏：盲区画面停止监听、移走窗口" + BlackBox.afterScreenOff());
            stopListening();
        }

        @Override
        public void onScreenOn() {
            startListening();
        }
    };

    public static void start(Context context) {
        context.startService(new Intent(context, SideViewService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SideViewService.class));
    }

    /** 开发者选项里改了标定角度：正在挂着的窗口照新的画。 */
    public static void applyAim() {
        SideViewService svc = instance;
        if (svc != null) {
            svc.center.applyAim();
            svc.centerSecond.applyAim();
            svc.hud.applyAim();
        }
    }

    /** 改了情境、最低车速、显示位置、HUD 的设置：HUD 的窗口照新的画，再照新的设置判一次。 */
    public static void applyConfig() {
        SideViewService svc = instance;
        if (svc != null) {
            svc.handler.post(() -> {
                if (svc.hud.window instanceof SideViewHudWindow) {
                    ((SideViewHudWindow) svc.hud.window).applyConfig();
                }
                svc.evaluate();
            });
        }
    }

    /** 设置里改了中控屏上的位置和大小（保存、重置）：挂着的窗口当场挪过去。 */
    public static void applyPlacement() {
        SideViewService svc = instance;
        if (svc != null) {
            svc.handler.post(() -> {
                for (Output output : new Output[]{svc.center, svc.centerSecond}) {
                    if (output.window instanceof SideViewWindow) {
                        ((SideViewWindow) output.window).applyPlacement();
                    }
                }
            });
        }
    }

    /** 卡顿报告里的一行：两个出口的窗口、输出声明和登记的状态。从监测线程读，是近似值。 */
    public static String describeForStall() {
        SideViewService svc = instance;
        if (svc == null) {
            return "side view: service not running";
        }
        return "side view: listening=" + svc.listening
                + " mode=" + svc.appConfig.getSideViewMode()
                + " lamps=" + (svc.lampsHeld ? Telemetry.get().lamps() : "off")
                + " claimed=" + CameraKeeper.get(svc).needs(CameraKeeper.Use.SIDE_VIEW)
                + " center{" + svc.center.describe() + "} second{" + svc.centerSecond.describe()
                + "} hud{" + svc.hud.describe() + "}";
    }

    /** 诊断报告里的一行：HUD 窗口此刻的样子；服务没在跑是 null。 */
    public static String describeHud() {
        SideViewService svc = instance;
        return svc == null ? null : svc.hud.describe();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BlackBox.attach(this, "Service:SideViewService");
        BlackBox.noteImportant("后台服务 SideViewService onCreate");
        appConfig = new AppConfig(this);
        // 三块画布交给相机管家的位置（3c 第二步）：声明在哪一路上、管线换了重新交、留下和接回都经过它。
        // 在 instance 之前建好：监测线程经 instance 读诊断时，位置已经在了
        center.open();
        centerSecond.open();
        hud.open();
        instance = this;
        ScreenState.addListener(screenListener);
    }

    /**
     * 窗口挂着、亮着屏、画布在：几个出口各自再声明一次（看门狗到点走这里）。管线换了一份时，相机管家把交着的画布
     * 声明到新的一份上，再叫各出口自己对一次（{@link Output#onRebuilt}），不等看门狗那两秒。
     */
    private void rebindIfAttached() {
        if (!destroyed) {
            center.rebindIfAttached();
            centerSecond.rebindIfAttached();
            hud.rebindIfAttached();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "SideViewService")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!appConfig.isSideViewEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，盲区画面无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        ScreenState.whenKnown(() -> {
            if (!destroyed && !ScreenState.dark()) {
                startListening();
            }
        });
        // 被杀了要不要重启：跟着「开机自启动」开关（规格 §6），同超级镜子
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    // ================================================================= 车辆信号

    private void startListening() {
        if (listening || destroyed) {
            return;
        }
        listening = true;
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(readingsListener);
        syncLamps();
        AppLog.i(TAG, "盲区画面：开始听转向灯、挡位和后视镜上的灯（情境 " + appConfig.getSideViewMode() + "）");
        evaluate();
    }

    private void stopListening() {
        if (!listening) {
            return;
        }
        listening = false;
        handler.removeCallbacks(evaluateRunnable);
        Telemetry.get().removeListener(readingsListener);
        Telemetry.get().release(TELEMETRY_USER);
        syncLamps();
        centerTrigger.reset();
        hudTrigger.reset();
        center.stop();
        centerSecond.stop();
        hud.stop();
        refusalShown = false;
        unavailableShown = false;
        AppLog.i(TAG, "盲区画面：停止监听");
    }

    /** 后视镜上的灯读不读：听着（亮屏）就读（三档都用得到：往有车的一侧打转向灯、开门预警，项目所有者 2026-11-09），不然放开。 */
    private void syncLamps() {
        boolean want = listening && !destroyed;
        if (want == lampsHeld) {
            return;
        }
        lampsHeld = want;
        if (want) {
            Telemetry.get().addLampListener(lampListener);
            Telemetry.get().acquireLamps(this, TELEMETRY_USER);
        } else {
            Telemetry.get().removeLampListener(lampListener);
            Telemetry.get().releaseLamps(TELEMETRY_USER);
        }
    }

    /**
     * 主线程上：照最新的读数判一次（{@link SideViewTrigger}），两个出口各自挂、显示、收起、移走窗口。
     *
     * <p>用哪几个出口：显示位置含中控屏的用中控屏；含 HUD、而且找得到 HUD 的用 HUD；只选 HUD 时，找不到 HUD、
     * 或者这一段 HUD 用不了（{@link Output#refused}），退回中控屏。</p>
     */
    private void evaluate() {
        handler.removeCallbacks(evaluateRunnable);
        if (!listening || destroyed) {
            return;
        }
        VehicleState s = Telemetry.get().latest();
        Readings r = Telemetry.get().readings();
        long now = SystemClock.uptimeMillis();
        boolean dark = ScreenState.dark();
        night = Integer.valueOf(Signal.NIGHT).equals(r.code(Signal.DAY_NIGHT));

        String target = appConfig.getSideViewTarget();
        boolean useHud = !AppConfig.SIDE_VIEW_TARGET_CENTER.equals(target) && hudDisplay(now) != null;
        boolean useCenter = !AppConfig.SIDE_VIEW_TARGET_HUD.equals(target) || !useHud || hud.refused();

        MirrorLamps lamps = Telemetry.get().lamps();
        SideViewDecision.Input in = input(s, r, lamps, appConfig.getSideViewMinSpeed());
        // HUD 不看最低车速：低速也显示（FerixzTanz 的规则）
        SideViewDecision.Input hudIn = input(s, r, lamps, 0);
        SideViewTrigger.Result centerResult = centerTrigger.update(now, in, dark);
        SideViewTrigger.Result hudResult = hudTrigger.update(now, hudIn, dark);
        ChannelRules.Condition surround = surroundCondition();
        // 中控屏：两块画布左右各管一侧；第二块这一段让出来了，第一块显示优先的那一侧
        boolean pair = !centerSecond.refused();
        center.evaluate(useCenter ? centerResult : null, in,
                pair ? new int[]{LaneCycle.LEFT} : new int[]{centerResult.lane}, surround);
        centerSecond.evaluate(useCenter ? centerResult : null, in, new int[]{LaneCycle.RIGHT}, surround);
        hud.evaluate(useHud ? hudResult : null, hudIn, new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}, surround);
        if (hud.window instanceof SideViewHudWindow) {
            ((SideViewHudWindow) hud.window).setNight(night);
        }
        noteRefusal(useCenter && centerResult.shown, useHud && hudResult.shown);
        noteSurroundUnavailable((useCenter && centerResult.shown && !center.refused())
                || (useHud && hudResult.shown && !hud.refused()), surround);

        long wakeAt = centerResult.wakeAt;
        if (hudResult.wakeAt > 0 && (wakeAt == 0 || hudResult.wakeAt < wakeAt)) {
            wakeAt = hudResult.wakeAt;
        }
        if (wakeAt > 0) {
            handler.postAtTime(evaluateRunnable, wakeAt);
        }
    }

    private SideViewDecision.Input input(VehicleState s, Readings r, MirrorLamps lamps, int minSpeedKmh) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = s.turnSignal;
        in.hazard = s.hazard;
        in.gear = s.gear;
        in.stock360Shown = s.stockSurroundShown;
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        in.speedKmh = s.speedKmh;
        in.minSpeedKmh = minSpeedKmh;
        in.mode = SideViewDecision.Mode.of(appConfig.getSideViewMode());
        // 灯没登记着时当读不到
        MirrorLamps held = lampsHeld ? lamps : MirrorLamps.UNKNOWN;
        in.blindSpotLeft = held.blindSpotLeft;
        in.blindSpotRight = held.blindSpotRight;
        in.doorWarningLeft = held.doorWarningLeft;
        in.doorWarningRight = held.doorWarningRight;
        // 收起前淡出是装饰动效，照同一条规矩放不放
        in.fade = com.kooo.evcam.ui.MotionPolicy.decorative(this);
        return in;
    }

    /**
     * 打了灯却一处都显示不了（该显示的出口这一段不带它，另一处也没在显示）：说一句，这一段只说一次。
     * 两个出口这一段都过去了（都不再「不带」）才重新算。
     */
    private void noteRefusal(boolean centerWants, boolean hudWants) {
        if (!center.refused() && !hud.refused()) {
            refusalShown = false;
            return;
        }
        boolean refusedWanted = (centerWants && center.refused()) || (hudWants && hud.refused());
        if (refusedWanted && !center.showing() && !centerSecond.showing() && !hud.showing() && !refusalShown) {
            refusalShown = true;
            showToast(R.string.msg_side_view_unavailable);
        }
    }

    /**
     * 该显示（打了灯、后视镜上的灯亮了）、环视此刻出不了画面（{@link SideViewDecision#surroundCanShow}）：窗口不显示，说一句为什么 —— 这一阵只说一次，
     * 环视能显示了才重新算。被别的程序拿着时说是车机在用摄像头（最常见的是车机自己的后排画面），别的情形只说显示不了。
     */
    private void noteSurroundUnavailable(boolean wanted, ChannelRules.Condition surround) {
        if (SideViewDecision.surroundCanShow(surround)) {
            unavailableShown = false;
            return;
        }
        if (wanted && !unavailableShown) {
            unavailableShown = true;
            showToast(surround == ChannelRules.Condition.TAKEN
                    ? R.string.msg_side_view_camera_in_use : R.string.msg_side_view_unavailable);
        }
    }

    /** 环视那一路此刻怎样（通道调度说的）；管线还没建好、已经放了，当作在起来。 */
    private static ChannelRules.Condition surroundCondition() {
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null || manager.isReleased()) {
            return ChannelRules.Condition.BRINGING_UP;
        }
        return manager.channelScheduler().condition(CameraSlots.KEY_SURROUND);
    }

    /** HUD 那块屏，最多每 {@link #HUD_CHECK_MS} 问一次系统；找得到、找不到变了才记一行。 */
    private Display hudDisplay(long now) {
        if (hudCheckedAt == 0 || now - hudCheckedAt >= HUD_CHECK_MS) {
            hudCheckedAt = now;
            Display found = HudScreen.find(this);
            if ((found == null) != (hudDisplay == null)) {
                BlackBox.note(found == null ? "盲区画面：找不到 HUD 那块屏"
                        : "盲区画面：找到 HUD 那块屏（id " + found.getDisplayId() + "）");
            }
            hudDisplay = found;
        }
        return hudDisplay;
    }

    /** 点了中控屏一个窗口的关闭按钮：关掉那一侧这一次（打灯的、后视镜上的灯亮的），中控屏、HUD 两处一起收；另一侧不动。 */
    private void onCloseTapped(int lane) {
        BlackBox.note("盲区画面：点了「" + LaneCycle.labelOf(lane) + "」的关闭，这一次不再显示");
        center.noteIfNoFrame();
        centerSecond.noteIfNoFrame();
        hud.noteIfNoFrame();
        centerTrigger.dismiss(lane);
        hudTrigger.dismiss(lane);
        evaluate();
    }

    private void showToast(int message) {
        try {
            android.widget.Toast.makeText(com.kooo.evcam.settings.Languages.localized(this),
                    message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.w(TAG, "提示弹不出来: " + e);
        }
    }

    /** 三个出口都放开了才放开登记（都声明了画布、登记了才算用着）。登记表一变，监听就叫醒调度。 */
    private void releaseClaimIfUnused() {
        if (!center.claimed && !centerSecond.claimed && !hud.claimed) {
            CameraKeeper.get(this).release(CameraKeeper.Use.SIDE_VIEW);
        }
    }

    /** 每隔几秒重新声明、重新登记一次：管线换了、冷启动时还没建好都能接上（同超级镜子的看门狗）。挂着窗口时一直跑。 */
    private void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = new Runnable() {
            @Override
            public void run() {
                rebindIfAttached();
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /** 两个出口都没有窗口了：看门狗停下。 */
    private void stopWatchdogIfIdle() {
        if (watchdog != null && center.window == null && centerSecond.window == null && hud.window == null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    @Override
    public void onDestroy() {
        BlackBox.noteImportant("后台服务 SideViewService onDestroy");
        destroyed = true;
        instance = null;
        ScreenState.removeListener(screenListener);
        stopListening();
        center.detach();
        centerSecond.detach();
        hud.detach();
        // 三块画布的位置关掉：管线换了不再替这个服务交，相机层再报的不再转过来
        center.close();
        centerSecond.close();
        hud.close();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================================================================= 一个出口

    /**
     * 盲区画面的一个出口：中控屏第一块、中控屏第二块、或者 HUD（{@link #kind}）。窗口、画布在环视上的声明、这一段带不带它
     * 各管各的；判定是服务的（中控屏两块共用一份，{@link #centerTrigger}），这里照它显示自己管的那几侧。
     * 登记表、看门狗、提示是服务一份。
     */
    private final class Output implements CanvasSlot.Listener {

        /** 在环视上声明成哪一样输出：{@link ChannelRules.Kind#SIDE}、{@link ChannelRules.Kind#SIDE_SECOND}、{@link ChannelRules.Kind#HUD}。 */
        final ChannelRules.Kind kind;
        final boolean isHud;
        SideViewOutputWindow window;
        /** 交给相机管家的位置（onCreate 建、onDestroy 关）：输出声明在哪一路相机上（环视）、这一段带不带它都在它那里。 */
        CanvasSlot slot;
        /** 刚接回留着的画布：下一次出帧时把缓冲区尺寸拨回去（同超级镜子的 restoreBufferOnFrame）。 */
        boolean restoreBufferOnFrame;
        /** 登记表上 SIDE_VIEW 算它一份：声明着画布。 */
        boolean claimed;
        int retryCount;
        Runnable retryRunnable;
        /** 这一次显示的时刻、第一帧来没来：黑匣子里看「打灯后多久出画面」。 */
        long shownAtMs;
        boolean firstFrameSeen;
        /** 该显示却没显示：环视此刻出不了画面（{@link SideViewDecision#surroundCanShow}）。黑匣子只在它变的时候记一行。 */
        boolean heldBack;
        /** 黑匣子记过的边框（{@link SideViewAlert}），左、右：显示着的那一侧变了才记一行。 */
        int edgeLeft = SideViewAlert.NONE;
        int edgeRight = SideViewAlert.NONE;

        Output(ChannelRules.Kind kind) {
            this.kind = kind;
            this.isHud = kind == ChannelRules.Kind.HUD;
        }

        void open() {
            slot = CameraKeeper.get(SideViewService.this).canvas(kind, CameraSlots.KEY_SURROUND, this);
        }

        void close() {
            slot.close();
        }

        /**
         * 这一段不带它（相机管家记着，{@link CanvasSlot#refused}）：相机层这一回丢了它（共享预览流挂不下、会话配不上时它最先让路，
         * JPEG 之后），或者窗口挂不上（HUD 那块屏没了时系统也会收掉窗口）。到窗口本来就该移走为止（离开 D 挡 30 秒、熄屏、
         * 原厂画面出现）不再挂，下一次再挂时重试（{@link CanvasSlot#retry}）—— 一段里最多一次，调度不会一直想把它加回去；
         * 配不上多半是一时的，不该从此不显示（ultracode 审查 core-1）。
         */
        boolean refused() {
            return slot.refused();
        }

        /** 黑匣子里跟在「盲区画面」后面的标记：中控屏第一块不加，别的加种类名（「（HUD）」「（SIDE_SECOND）」）。 */
        String label() {
            return kind == ChannelRules.Kind.SIDE ? "" : "（" + kind + "）";
        }

        boolean showing() {
            SideViewOutputWindow w = window;
            return w != null && w.isShowing();
        }

        String describe() {
            SideViewOutputWindow w = window;
            SingleCamera camera = slot.camera();
            return "refused=" + refused() + " claimed=" + claimed
                    + " window=" + (w == null ? "none" : w.describe())
                    + " boundCamera=" + (camera != null ? camera.getCameraId() : "none")
                    + " inSession=" + inSession();
        }

        void applyAim() {
            SideViewOutputWindow w = window;
            if (w != null) {
                w.applyAim();
            }
        }

        /** 熄屏、服务退出：这一段结束，窗口移走（判定由服务从头来）。 */
        void stop() {
            heldBack = false;
            if (refused()) {
                // 熄屏也是这一段的结束（同离开 D 挡 30 秒、原厂画面）：亮屏后再挂窗口时重试。服务退出也走这里，那就只是结束
                slot.retry();
                BlackBox.note(destroyed ? "盲区画面" + label() + "：服务停下，不带它的这一段结束"
                        : "盲区画面" + label() + "：熄屏，不带它的这一段结束，亮屏后再挂窗口时重试");
            }
            detach();
        }

        /**
         * 照这一刻的判定做事。
         *
         * @param result   这个出口的判定；null 是这一刻不用这个出口（显示位置不含它、找不到 HUD、只选 HUD 而 HUD 用得上）
         * @param lanes    这个出口这一刻管哪几侧：中控屏两块各一侧（只剩第一块时是优先的那一侧），HUD 两侧
         * @param surround 环视那一路此刻怎样：出不了画面时该显示也不显示（{@link SideViewDecision#surroundCanShow}）
         */
        void evaluate(SideViewTrigger.Result result, SideViewDecision.Input in, int[] lanes,
                      ChannelRules.Condition surround) {
            if (result == null) {
                if (window != null) {
                    BlackBox.note("盲区画面" + label() + "：这一刻不用这一处，移走窗口");
                    detach();
                }
                slot.retry();
                heldBack = false;
                return;
            }
            if (refused() && !result.attached) {
                // 这一段过去了（离开 D 挡 30 秒、熄屏、原厂画面）：下一次挂窗口时重试
                slot.retry();
                BlackBox.note("盲区画面" + label() + "：不带它的这一段结束，下一次挂窗口时重试");
            }
            if (!refused()) {
                apply(result, in, lanes, surround);
            }
        }

        private void apply(SideViewTrigger.Result result, SideViewDecision.Input in, int[] lanes,
                           ChannelRules.Condition surround) {
            SideViewOutputWindow w = window;
            if (w != null && !w.isAttached()) {
                // 窗口不是我们收的（HUD 那块屏没了，系统收掉了 Presentation）：这一段不再挂
                BlackBox.noteImportant("盲区画面" + label() + "：窗口被系统收掉了，这一段不再挂");
                refuse();
                return;
            }
            // 环视出不了画面（被别的程序拿着、在救、救不回来）：该显示也不显示，只会是一块黑框；窗口照旧备着，
            // 环视出画面了下一次读数就显示
            boolean canShow = SideViewDecision.surroundCanShow(surround);
            noteHeldBack(!canShow && wantsAny(result, lanes), surround);
            if (!result.attached) {
                if (w != null) {
                    BlackBox.note("盲区画面" + label() + "：移走窗口（" + (ScreenState.dark() ? "熄屏"
                            : SideViewDecision.factoryViewActive(in) ? "原厂画面在：挡位 " + in.gear
                                    + "、原厂 360 " + in.stock360Shown + "、原厂小窗 " + in.stockPopupShown
                                    + "、泊车辅助 " + in.parkAssistOn
                            : result.graceEnded ? "离开 D 挡 " + SideViewTrigger.READY_GRACE_MS / 1000 + " 秒"
                            : "显示结束，不在 D 挡（挡位 " + in.gear + "）") + "）");
                    detach();
                }
                return;
            }
            int first = SideViewDecision.NONE;
            for (int lane : lanes) {
                if (canShow && result.side(lane).shown) {
                    first = lane;
                    break;
                }
            }
            if (w == null) {
                SideViewTrigger.Cause firstCause = first != SideViewDecision.NONE ? result.side(first).cause : null;
                BlackBox.note(first != SideViewDecision.NONE
                        ? "盲区画面" + label() + "：显示「" + LaneCycle.labelOf(first) + "」"
                        + (firstCause == SideViewTrigger.Cause.TURN_TOWARD_CAR ? "（往盲区指示灯亮的一侧打转向灯）"
                        : firstCause == SideViewTrigger.Cause.BLIND_SPOT ? "（盲区指示灯亮）"
                        : firstCause == SideViewTrigger.Cause.DOOR_WARNING ? "（开门预警亮）" : "")
                        + "（窗口没备着，现在挂）"
                        : heldBack ? "盲区画面" + label() + "：该显示，环视出不了画面，窗口先备着"
                        : "盲区画面" + label() + "：D 挡，窗口备着");
                open(first != SideViewDecision.NONE ? first : lanes.length > 0 ? lanes[0] : result.lane,
                        first != SideViewDecision.NONE);
                w = window;
                if (w == null) {
                    return;
                }
            }
            for (int lane : lanes) {
                SideViewTrigger.Side side = result.side(lane);
                if (!canShow || !side.shown) {
                    continue;
                }
                if (!w.isShowing(lane)) {
                    BlackBox.note("盲区画面" + label() + "：显示「" + LaneCycle.labelOf(lane) + "」"
                            + (side.cause == SideViewTrigger.Cause.TURN_TOWARD_CAR ? "（往盲区指示灯亮的一侧打转向灯）"
                            : side.cause == SideViewTrigger.Cause.BLIND_SPOT ? "（盲区指示灯亮）"
                            : side.cause == SideViewTrigger.Cause.DOOR_WARNING ? "（开门预警亮）" : ""));
                    noteShown();
                    w.show(lane);
                } else if (side.fadeMs == 0 && w.isFading(lane)) {
                    // 淡出中那一侧又亮了：回到正常，接着显示
                    BlackBox.note("盲区画面" + label() + "：淡出中又显示「" + LaneCycle.labelOf(lane) + "」");
                    w.show(lane);
                }
                if (side.fadeMs > 0) {
                    w.fadeOut(lane, side.fadeMs);
                }
            }
            // 不归它管了的、不再显示的那几侧收起（只剩第一块时它换到另一侧，show 已经挪过去了）；环视出不了画面时都收起
            for (int lane : new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}) {
                if (w.isShowing(lane) && !(canShow && manages(lanes, lane) && result.side(lane).shown)) {
                    noteIfNoFrame();
                    w.conceal(lane);
                    BlackBox.note("盲区画面" + label() + "：收起「" + LaneCycle.labelOf(lane) + "」"
                            + (w.isShowing() ? "" : "，窗口备着"));
                }
            }
            // 边框（SideViewAlert）：照这一刻后视镜上的灯描；不归它管的那一侧不描。窗口只在那一侧显示着时画
            for (int lane : new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}) {
                boolean mine = manages(lanes, lane);
                w.setAlert(lane, mine ? SideViewAlert.level(in, lane) : SideViewAlert.NONE);
                noteEdge(w, lane, mine ? in : null);
            }
        }

        /**
         * 黑匣子：显示着的那一侧边框变了记一行（收起了不记，下一次显示再从头算）。
         *
         * @param in null：这一侧不归它管
         */
        private void noteEdge(SideViewOutputWindow w, int lane, SideViewDecision.Input in) {
            boolean visible = w.isShowing(lane);
            int level = visible && in != null ? SideViewAlert.level(in, lane) : SideViewAlert.NONE;
            int before = lane == LaneCycle.LEFT ? edgeLeft : edgeRight;
            if (level == before) {
                return;
            }
            if (lane == LaneCycle.LEFT) {
                edgeLeft = level;
            } else {
                edgeRight = level;
            }
            if (visible && in != null) {
                BlackBox.note("盲区画面" + label() + "「" + LaneCycle.labelOf(lane) + "」边框："
                        + (level == SideViewAlert.NONE ? "去掉"
                        : level == SideViewAlert.WARNING ? "红（开门预警亮）"
                        : level == SideViewAlert.CAUTION_BLINK ? "黄（盲区指示灯亮、往这一侧打着转向灯）"
                        : "黄（盲区指示灯亮）")
                        + (SideViewAlert.blinks(level) ? "，闪" : ""));
            }
        }

        private boolean manages(int[] lanes, int lane) {
            for (int one : lanes) {
                if (one == lane) {
                    return true;
                }
            }
            return false;
        }

        /** 它管的那几侧有没有一侧该显示（不看环视）。 */
        private boolean wantsAny(SideViewTrigger.Result result, int[] lanes) {
            for (int lane : lanes) {
                if (result.side(lane).shown) {
                    return true;
                }
            }
            return false;
        }

        // ------------------------------------------------------------- 窗口

        private void open(int lane, boolean show) {
            SideViewOutputWindow w;
            if (isHud) {
                Display display = hudDisplay;
                if (display == null) {
                    return;   // evaluate 刚看过还在；真没了下一次判定就不用这一处
                }
                SideViewHudWindow hudWindow = new SideViewHudWindow(SideViewService.this, appConfig, display);
                hudWindow.setNight(night);
                w = hudWindow;
            } else {
                w = new SideViewWindow(SideViewService.this, appConfig, SideViewService.this::onCloseTapped);
            }
            w.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                    bindCamera(st);
                    // 画布建好了：备着的话设为不可见、不再逐帧画。这一下在绘制途中，排到下一轮再改
                    handler.post(() -> {
                        if (window == w) {
                            w.syncCanvas();
                        }
                    });
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                    restoreBufferSize(st);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    // 画布要被系统收走了（窗口移走）：交给管线留着就返回 false，TextureView 不放
                    return !handCanvasToPipeline(st);
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    if (restoreBufferOnFrame) {
                        // 接回的画布第一次画出来：建图层那一下刚把缓冲区尺寸改成了窗口的布局尺寸（同超级镜子）
                        restoreBufferOnFrame = false;
                        restoreBufferSize(st);
                    }
                    onFrame();
                }
            });
            window = w;
            restoreBufferOnFrame = false;
            if (show) {
                noteShown();
                w.show(lane);
            } else {
                w.attachHidden(lane);
            }
            if (!w.isAttached()) {
                // 窗口挂不上：这一段不再试（不然每来一次读数就再挂一次、记一行，ultracode 审查 service-2）；
                // 中控屏的悬浮窗权限没了就停下服务，和开着时没有权限一样
                window = null;
                if (isHud) {
                    BlackBox.noteImportant("盲区画面（HUD）：窗口挂不上（屏 " + ((SideViewHudWindow) w).displayId()
                            + "），这一段不再挂");
                    refuse();
                    return;
                }
                boolean permitted = WakeUpHelper.hasOverlayPermission(SideViewService.this);
                BlackBox.noteImportant("盲区画面：窗口挂不上" + (permitted ? "，这一段不再挂" : "，没有悬浮窗权限，服务停下"));
                refuse();
                if (!permitted) {
                    stopSelf();
                }
                return;
            }
            takeBackKeptCanvas();
            startWatchdog();
        }

        /**
         * 上一个窗口留下的画布接回来（同超级镜子 {@code takeBackKeptCanvas}）：窗口加上之后、第一次画之前做。
         * {@code setSurfaceTexture} 之后视图不再自己建画布，也不会来 onSurfaceTextureAvailable，所以当场走一遍 {@link #bindCamera}：
         * 同一块画布就是同一个输出，会话里还带着它，通道零变动。
         */
        void takeBackKeptCanvas() {
            SideViewOutputWindow w = window;
            if (w == null || !w.isAttached()) {
                return;
            }
            SurfaceTexture kept = slot.takeKept();
            if (kept == null) {
                return;
            }
            try {
                w.getTextureView().setSurfaceTexture(kept);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "接回留着的盲区画面画布失败" + label() + ": " + e);
                if (!slot.keep(kept)) {
                    kept.release();
                }
                return;
            }
            restoreBufferOnFrame = true;
            BlackBox.note("盲区画面" + label() + "：接回留着的画布（同一块画布，通道不动）");
            bindCamera(kept);
            w.syncCanvas();
        }

        /**
         * 窗口的画布要被系统收走了（窗口移走）：交给管线留着，由调度等它离开会话再放（同超级镜子 {@code handCanvasToPipeline}）。
         *
         * @return true：画布交给管线了（回调返回 false）；false：照旧让 TextureView 放
         */
        private boolean handCanvasToPipeline(SurfaceTexture st) {
            cancelRetry();
            restoreBufferOnFrame = false;
            if (st == slot.texture()) {
                unbindCamera();
            }
            // 撤回（这块正声明着的话）、交给会话里还带着它的那一份管线留着
            boolean kept = slot.keep(st);
            if (!kept) {
                AppLog.i(TAG, "盲区画面" + label() + "画布没了、管线里没有环视：照旧放掉，还在会话里的由通道调度马上摘");
                CameraKeeper.factsChanged();
            }
            return kept;
        }

        /** 移走窗口：放开登记、撤回输出，再当场移走（画布在移走那一下交给管线，见 {@link #handCanvasToPipeline}）。 */
        void detach() {
            cancelRetry();
            edgeLeft = SideViewAlert.NONE;
            edgeRight = SideViewAlert.NONE;
            SideViewOutputWindow w = window;
            if (w == null) {
                unbindCamera();
                slot.withdraw();
                stopWatchdogIfIdle();
                return;
            }
            if (w.isShowing()) {
                noteIfNoFrame();
            }
            // 窗口还没走过第一次布局就要移走：TextureView 没挂上过，不会来 onSurfaceTextureDestroyed ——
            // 接回来的画布要自己交给管线，不然它没人管（ultracode 审查 service-1）
            SurfaceTexture orphan = w.getTextureView().isAttachedToWindow() ? null : w.getTextureView().getSurfaceTexture();
            unbindCamera();
            slot.withdraw();
            window = null;
            w.detach();
            if (orphan != null && !orphan.isReleased() && !handCanvasToPipeline(orphan)) {
                orphan.release();
            }
            stopWatchdogIfIdle();
        }

        private void noteShown() {
            shownAtMs = SystemClock.uptimeMillis();
            firstFrameSeen = false;
        }

        /** 打着灯却因为环视出不了画面没显示：开始、结束各记一行（读数每 200 ms 来一次，不逐次记）。 */
        private void noteHeldBack(boolean now, ChannelRules.Condition surround) {
            if (now == heldBack) {
                return;
            }
            heldBack = now;
            BlackBox.note(now
                    ? "盲区画面" + label() + "：该显示，环视此刻 " + surround + "，不显示（只会是一块黑框），窗口备着"
                    : "盲区画面" + label() + "：不再因为环视拦着（灯灭了或环视能显示了，环视此刻 " + surround + "）");
        }

        /** 显示过、到收起时一帧都没来：记下来，诊断报告里看得到（PR #19 的做法）。 */
        void noteIfNoFrame() {
            SideViewOutputWindow w = window;
            if (firstFrameSeen || w == null || !w.isShowing()) {
                return;
            }
            SingleCamera camera = slot.camera();
            BlackBox.note("盲区画面" + label() + "收起：显示了 " + (SystemClock.uptimeMillis() - shownAtMs)
                    + " ms 一帧都没来；" + (camera == null ? "没接上相机" : "环视" + (camera.isCameraOpened() ? "开着" : "没开")
                    + "、会话里" + (inSession() ? "带着" : "没有") + "盲区画面、最近错误 " + camera.lastErrorName()));
        }

        /** 每来一帧响一次。挂窗口时环视要是还没开过，预览尺寸是 null（开相机时才选），帧来了再补上。 */
        private void onFrame() {
            SideViewOutputWindow w = window;
            if (w == null) {
                return;
            }
            SingleCamera camera = slot.camera();
            if (!w.hasGeometry() && camera != null) {
                w.setSourceSize(camera.getPreviewSize());
            }
            w.noteFrame();
            if (w.isShowing() && !firstFrameSeen) {
                firstFrameSeen = true;
                BlackBox.note("盲区画面" + label() + "出画面：显示后 " + (SystemClock.uptimeMillis() - shownAtMs)
                        + " ms，几何" + (w.hasGeometry() ? "已知" : "未知"));
            }
        }

        // ------------------------------------------------------------- 相机

        /** 窗口挂着、亮着屏、画布在：再声明一次。 */
        void rebindIfAttached() {
            SideViewOutputWindow w = window;
            if (w != null && w.isAttached() && !ScreenState.dark() && w.getTextureView().isAvailable()) {
                bindCamera(w.getTextureView().getSurfaceTexture());
            }
        }

        /**
         * 声明这一处的画布、登记 SIDE_VIEW —— 只做这两件事（同超级镜子的 {@code bindCamera}）。
         *
         * <p>同一块画布再声明一次、同一项再登记一次都不算变化：看门狗每 2 秒走到这里一次，什么都没变就什么都不做。
         * 拿不到环视那一路（冷启动、管线还没建好）要重试。</p>
         */
        private void bindCamera(SurfaceTexture surfaceTexture) {
            SideViewOutputWindow w = window;
            if (ScreenState.dark() || w == null || !w.isAttached()
                    || surfaceTexture == null || surfaceTexture.isReleased()) {
                return;
            }
            // 交给相机管家：声明在当下那一份管线的环视上（还没有管线就建；换了一路相机或者一块画布，它先从原来那里撤回）。
            // 拿不到环视就过一会儿再交
            CanvasSlot.Offer offer = slot.offer(surfaceTexture, null);
            if (offer == CanvasSlot.Offer.NO_CAMERA) {
                scheduleRetry(surfaceTexture);
                return;
            }
            retryCount = 0;
            if (offer == CanvasSlot.Offer.REFUSED) {
                return;   // 这一段不带它：窗口那时已经移走了，走不到这里
            }
            SingleCamera camera = slot.camera();
            Size previewSize = camera.getPreviewSize();
            if (previewSize != null && !w.hasGeometry()) {
                w.setSourceSize(previewSize);
            }
            boolean declared = offer == CanvasSlot.Offer.DECLARED;
            CameraKeeper keeper = CameraKeeper.get(SideViewService.this);
            boolean newlyClaimed = !keeper.needs(CameraKeeper.Use.SIDE_VIEW);
            claimed = true;
            keeper.need(CameraKeeper.Use.SIDE_VIEW);
            if (declared || newlyClaimed) {
                AppLog.i(TAG, "盲区画面" + label() + (declared ? "声明了输出" : "的输出早已声明")
                        + (newlyClaimed ? "、登记了 SIDE_VIEW" : "，SIDE_VIEW 早已登记")
                        + "：开环视、把画布加进会话都由通道调度做（环视此刻 "
                        + slot.manager().channelScheduler().condition(CameraSlots.KEY_SURROUND)
                        + "，预览尺寸 " + previewSize + "，缓冲区 " + camera.getPreviewBufferSize() + "）");
            }
        }

        /** 声明到了这一路相机上（第一次、换了一块画布、管线换了）：缓冲区尺寸拨回去；窗口还不知道画面尺寸就交给它。 */
        @Override
        public void onBound(SingleCamera camera) {
            restoreBufferSize(slot.texture());
            SideViewOutputWindow w = window;
            Size previewSize = camera.getPreviewSize();
            if (w != null && previewSize != null && !w.hasGeometry()) {
                w.setSourceSize(previewSize);
            }
        }

        /**
         * 相机层这一回不带这一处（共享预览流挂不下、或者会话配不上时盲区画面最先让路，JPEG 之后；预览、超级镜子、录像不受影响）：
         * 相机管家记下了，这一段不再挂（{@link #refused}）—— 不然调度会一直想把它加回去，一次次重建环视会话。
         */
        @Override
        public void onRefused() {
            if (destroyed) {
                return;
            }
            BlackBox.noteImportant("盲区画面" + label() + "：相机层这一回不带它（共享预览流挂不下或会话配不上），这一段不再挂"
                    + "（离开 D 挡 30 秒、熄屏或原厂画面之后再试）");
            refuse();
        }

        /**
         * 管线换了一份、建好了（相机管家已经把交着的画布声明上去）：窗口挂着、亮着屏就再对一次、登记 SIDE_VIEW
         * （{@link #rebindIfAttached}；画布交的时候这一路还没有相机，那时没登记上）。这一段不带它的照旧不交。
         */
        @Override
        public void onRebuilt() {
            if (!destroyed) {
                rebindIfAttached();
            }
        }

        /** 这一段不带这一处（{@link #refused}）：放开登记、撤回、移走窗口；正打着灯、另一处也显示不了的话当场说一句。 */
        private void refuse() {
            slot.refuse();
            refusalShown = false;
            detach();
            // 不在这里直接判：可能正在 apply 里（窗口挂不上）
            handler.post(evaluateRunnable);
        }

        /** 把缓冲区尺寸拨回相机会话配置时用的那个（理由见超级镜子的 {@code restoreBufferSize}）。 */
        private void restoreBufferSize(SurfaceTexture surfaceTexture) {
            SingleCamera camera = slot.camera();
            if (surfaceTexture == null || camera == null) {
                return;
            }
            Size buffer = camera.getPreviewBufferSize();
            if (buffer == null || buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
                return;
            }
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        }

        /** 这一处的输出此刻在环视的会话里（按会话配好时记下的快照）。从监测线程读时是近似值。 */
        boolean inSession() {
            return slot.inSession();
        }

        private void scheduleRetry(SurfaceTexture surfaceTexture) {
            if (retryCount >= MAX_RETRY) {
                AppLog.w(TAG, "环视始终拿不到，盲区画面" + label() + "放弃绑定（看门狗接着试）");
                return;
            }
            retryCount++;
            cancelRetry();
            retryRunnable = () -> bindCamera(surfaceTexture);
            handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
        }

        private void cancelRetry() {
            if (retryRunnable != null) {
                handler.removeCallbacks(retryRunnable);
                retryRunnable = null;
            }
        }

        /** 放开这一处的登记份额；两处都放开了才真放开（{@link #releaseClaimIfUnused}）。 */
        private void unbindCamera() {
            cancelRetry();
            claimed = false;
            releaseClaimIfUnused();
        }
    }
}
