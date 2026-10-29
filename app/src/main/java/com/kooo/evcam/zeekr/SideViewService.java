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
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.ChannelScheduler;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 盲区画面：打转向灯时在中控屏、HUD 上显示该侧摄像头的画面（issue #6、#24）。窗口、相机登记、车辆信号都在这里。
 *
 * <h3>两个出口，一套判定</h3>
 *
 * <p>中控屏上的窗口（{@link SideViewWindow}）和 HUD 上的窗口（{@link SideViewHudWindow}，2026-10-21）各是一个出口
 * （{@link Output}）：各自的窗口、各自在环视上声明的画布、各自「这一段带不带它」。显示在哪由设置定
 * （{@link AppConfig#getSideViewTarget}，默认两处）：选 HUD 而找不到 HUD、或者这一段 HUD 用不了时，退回中控屏。
 * 判定规则同一套（{@link SideViewTrigger}），只差一条：HUD 不看最低车速（FerixzTanz 的规则，项目所有者 2026-10-16 定照用）。
 * 中控屏窗口的关闭按钮关掉的是这一次打灯，两处一起收。</p>
 *
 * <h3>只声明、只登记（同超级镜子，{@link RearViewMirrorService}）</h3>
 *
 * <p>通道调度（{@link ChannelScheduler}）是唯一动相机的地方。这里只做两件事：把窗口的画布声明成环视的盲区画面输出
 * （{@link SingleCamera#declareSide} / {@link SingleCamera#declareHud}），和在登记表上登记 {@link CameraNeeds.Holder#SIDE_VIEW}
 * —— 它要环视，配置里没开环视也要（项目所有者 2026-10-16，{@code ChannelRules}）。两个出口共用这一个登记方，都放开了才放开。
 * 开不开环视、什么时候把画布加进会话、挂不下时谁让路，都由调度和相机层判；以后调规则也只在登记表这一处。</p>
 *
 * <h3>什么时候挂、什么时候显示</h3>
 *
 * <p>规则在 {@link SideViewTrigger}（纯逻辑）：挂 D 挡就备着（窗口挂着、隐藏，登记、声明着，预备方式 B）；
 * 打灯显示那一侧，灯灭后 1 秒收起；离开 D 挡 30 秒后、熄屏、原厂画面出现时移走窗口、注销、撤回。
 * 超级镜子一点不动：几个窗口各管各的。</p>
 *
 * <p><b>移走窗口时画布由管线留着</b>（同超级镜子 §7.7，{@link MultiCameraManager#keepSideCanvas}、{@link MultiCameraManager#keepHudCanvas}）：
 * 会话里的画布只由通道调度放，不自己放 —— PR #1、#19 就错在这里（窗口一拆就放掉画布，环视会话里多一个死输出，摘到之前整条流停着）。
 * 下一次挂窗口接回同一块（{@link Output#takeBackKeptCanvas}），通道零变动。</p>
 *
 * <h3>车辆信号</h3>
 *
 * <p>亮屏时完整登记（要车速），熄屏注销、窗口移走：熄屏后不该留着监听和相机过去（同超级镜子熄屏放开登记的理由）。
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
    private final Output center = new Output(false);
    private final Output hud = new Output(true);
    private AppConfig appConfig;
    private boolean listening;
    private boolean destroyed;
    /** 这一段里打灯时已经提示过一次「无法显示盲区画面」。 */
    private boolean refusalShown;
    private Runnable watchdog;
    /** HUD 那块屏（{@link HudScreen}）：上一次问系统的时刻和结果。 */
    private long hudCheckedAt;
    private Display hudDisplay;
    /** 车辆处于夜间模式（昼夜信号），给 HUD 的窗口。 */
    private boolean night;

    private final Runnable evaluateRunnable = this::evaluate;
    private final Telemetry.Listener readingsListener = readings -> evaluate();

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
    public static void applyLayout() {
        SideViewService svc = instance;
        if (svc != null && svc.center.window instanceof SideViewWindow)
            ((SideViewWindow) svc.center.window).applyLayoutFromConfig();
    }

    public static void applyAim() {
        SideViewService svc = instance;
        if (svc != null) {
            svc.center.applyAim();
            svc.hud.applyAim();
        }
    }

    /** 改了最低车速、显示位置、HUD 的设置：HUD 的窗口照新的画，再照新的设置判一次。 */
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

    /** 卡顿报告里的一行：两个出口的窗口、输出声明和登记的状态。从监测线程读，是近似值。 */
    public static String describeForStall() {
        SideViewService svc = instance;
        if (svc == null) {
            return "side view: service not running";
        }
        return "side view: listening=" + svc.listening
                + " claimed=" + CameraNeeds.current().isHeld(CameraNeeds.Holder.SIDE_VIEW)
                + " center{" + svc.center.describe() + "} hud{" + svc.hud.describe() + "}";
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
        instance = this;
        ScreenState.addListener(screenListener);
        // 管线被放掉重建了：新的一份建好当场重新声明（同超级镜子，见 pipelineReplaced）
        CameraManagerHolder.getInstance().addReplacedListener(pipelineReplaced);
    }

    /**
     * 管线被放掉重建了（换车型、上一次列相机出错、超级镜子要后座舱而管线里没有）：新的一份建好就当场重新声明，不等看门狗那两秒 ——
     * 新的一份开环视时会话里就带着盲区画面的画布，不用随后再改一次输出。和超级镜子、主界面同一个办法。
     */
    private final Runnable pipelineReplaced = () -> {
        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        holder.whenBuilt(holder.getCameraManager(), built -> handler.post(this::rebindIfAttached));
    };

    /** 窗口挂着、亮着屏、画布在：两个出口各自再声明一次（看门狗到点、管线换了都走这里）。 */
    private void rebindIfAttached() {
        if (!destroyed) {
            center.rebindIfAttached();
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
        // 被杀了要不要重启：跟着「开机自启动」开关（规格 §3），同超级镜子
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
        AppLog.i(TAG, "盲区画面：开始听转向灯和挡位");
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
        center.stop();
        hud.stop();
        refusalShown = false;
        AppLog.i(TAG, "盲区画面：停止监听");
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
        boolean useCenter = !AppConfig.SIDE_VIEW_TARGET_HUD.equals(target) || !useHud || hud.refused;

        SideViewDecision.Input in = input(s, r, appConfig.getSideViewMinSpeed());
        // HUD 不看最低车速：低速也显示（FerixzTanz 的规则）
        SideViewDecision.Input hudIn = input(s, r, 0);
        SideViewTrigger.Result centerResult = center.trigger.update(now, in, dark);
        SideViewTrigger.Result hudResult = hud.trigger.update(now, hudIn, dark);
        center.evaluate(useCenter ? centerResult : null, in);
        hud.evaluate(useHud ? hudResult : null, hudIn);
        if (hud.window instanceof SideViewHudWindow) {
            ((SideViewHudWindow) hud.window).setNight(night);
        }
        noteRefusal(useCenter && centerResult.shown, useHud && hudResult.shown);

        long wakeAt = centerResult.wakeAt;
        if (hudResult.wakeAt > 0 && (wakeAt == 0 || hudResult.wakeAt < wakeAt)) {
            wakeAt = hudResult.wakeAt;
        }
        if (wakeAt > 0) {
            handler.postAtTime(evaluateRunnable, wakeAt);
        }
    }

    private static SideViewDecision.Input input(VehicleState s, Readings r, int minSpeedKmh) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = s.turnSignal;
        in.hazard = s.hazard;
        in.gear = s.gear;
        in.stock360Shown = s.stockSurroundShown;
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        in.speedKmh = s.speedKmh;
        in.minSpeedKmh = minSpeedKmh;
        return in;
    }

    /**
     * 打了灯却一处都显示不了（该显示的出口这一段不带它，另一处也没在显示）：说一句，这一段只说一次。
     * 两个出口这一段都过去了（都不再「不带」）才重新算。
     */
    private void noteRefusal(boolean centerWants, boolean hudWants) {
        if (!center.refused && !hud.refused) {
            refusalShown = false;
            return;
        }
        boolean refusedWanted = (centerWants && center.refused) || (hudWants && hud.refused);
        if (refusedWanted && !center.showing() && !hud.showing() && !refusalShown) {
            refusalShown = true;
            showRefusal();
        }
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

    /** 点了中控屏窗口的关闭按钮：关掉这一次打灯，中控屏、HUD 两处一起收。 */
    private void onCloseTapped() {
        BlackBox.note("盲区画面：点了关闭，这一次打灯不再显示");
        center.noteIfNoFrame();
        hud.noteIfNoFrame();
        center.trigger.dismiss();
        hud.trigger.dismiss();
        evaluate();
    }

    private void showRefusal() {
        try {
            android.widget.Toast.makeText(com.kooo.evcam.settings.Languages.localized(this),
                    R.string.msg_side_view_unavailable, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.w(TAG, "提示弹不出来: " + e);
        }
    }

    /** 两个出口都声明了画布、登记了才算用着：都放开了才放开登记。登记表一变，监听就叫醒调度。 */
    private void releaseClaimIfUnused() {
        if (!center.claimed && !hud.claimed) {
            CameraNeeds.current().release(CameraNeeds.Holder.SIDE_VIEW);
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
        if (watchdog != null && center.window == null && hud.window == null) {
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
        CameraManagerHolder.getInstance().removeReplacedListener(pipelineReplaced);
        stopListening();
        center.detach();
        hud.detach();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ================================================================= 一个出口

    /**
     * 盲区画面的一个出口：中控屏、或者 HUD。窗口、画布在环视上的声明、这一段带不带它各管各的；
     * 判定（{@link #trigger}）各一份，规则同一套（HUD 那份不看最低车速）。登记表、看门狗、提示是服务一份。
     */
    private final class Output {

        final boolean isHud;
        final SideViewTrigger trigger = new SideViewTrigger();
        SideViewOutputWindow window;
        /** 输出声明在哪一路相机上（环视）、属于哪一份管线、声明出去的那块画布。 */
        SingleCamera boundCamera;
        MultiCameraManager boundManager;
        SurfaceTexture boundTexture;
        /** 刚接回留着的画布：下一次出帧时把缓冲区尺寸拨回去（同超级镜子的 restoreBufferOnFrame）。 */
        boolean restoreBufferOnFrame;
        /**
         * 这一段不带它：相机层这一回丢了它（共享预览流挂不下、会话配不上时它最先让路，JPEG 之后），或者窗口挂不上
         * （HUD 那块屏没了时系统也会收掉窗口）。到窗口本来就该移走为止（离开 D 挡 30 秒、熄屏、原厂画面出现）不再挂，
         * 下一次再挂时重试 —— 一段里最多一次，调度不会一直想把它加回去；配不上多半是一时的，不该从此不显示（ultracode 审查 core-1）。
         */
        boolean refused;
        /** 登记表上 SIDE_VIEW 算它一份：声明着画布。 */
        boolean claimed;
        int retryCount;
        Runnable retryRunnable;
        /** 这一次显示的时刻、第一帧来没来：黑匣子里看「打灯后多久出画面」。 */
        long shownAtMs;
        boolean firstFrameSeen;

        Output(boolean isHud) {
            this.isHud = isHud;
        }

        /** 黑匣子里跟在「盲区画面」后面的标记：中控屏不加，HUD 加「（HUD）」。 */
        String label() {
            return isHud ? "（HUD）" : "";
        }

        boolean showing() {
            SideViewOutputWindow w = window;
            return w != null && w.isShowing();
        }

        String describe() {
            SideViewOutputWindow w = window;
            SingleCamera camera = boundCamera;
            return "refused=" + refused + " claimed=" + claimed
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

        /** 熄屏、服务退出：判定从头来，这一段结束，窗口移走。 */
        void stop() {
            trigger.reset();
            if (refused) {
                // 熄屏也是这一段的结束（同离开 D 挡 30 秒、原厂画面）：亮屏后再挂窗口时重试。服务退出也走这里，那就只是结束
                refused = false;
                BlackBox.note(destroyed ? "盲区画面" + label() + "：服务停下，不带它的这一段结束"
                        : "盲区画面" + label() + "：熄屏，不带它的这一段结束，亮屏后再挂窗口时重试");
            }
            detach();
        }

        /**
         * 照这一刻的判定做事。
         *
         * @param result 这个出口的判定；null 是这一刻不用这个出口（显示位置不含它、找不到 HUD、只选 HUD 而 HUD 用得上）
         */
        void evaluate(SideViewTrigger.Result result, SideViewDecision.Input in) {
            if (result == null) {
                if (window != null) {
                    BlackBox.note("盲区画面" + label() + "：这一刻不用这一处，移走窗口");
                    detach();
                }
                refused = false;
                return;
            }
            if (refused && !result.attached) {
                // 这一段过去了（离开 D 挡 30 秒、熄屏、原厂画面）：下一次挂窗口时重试
                refused = false;
                BlackBox.note("盲区画面" + label() + "：不带它的这一段结束，下一次挂窗口时重试");
            }
            if (!refused) {
                apply(result, in);
            }
        }

        private void apply(SideViewTrigger.Result result, SideViewDecision.Input in) {
            SideViewOutputWindow w = window;
            if (w != null && !w.isAttached()) {
                // 窗口不是我们收的（HUD 那块屏没了，系统收掉了 Presentation）：这一段不再挂
                BlackBox.noteImportant("盲区画面" + label() + "：窗口被系统收掉了，这一段不再挂");
                refuse();
                return;
            }
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
            if (w == null) {
                BlackBox.note(result.shown
                        ? "盲区画面" + label() + "：显示「" + LaneCycle.labelOf(result.lane) + "」（窗口没备着，现在挂）"
                        : "盲区画面" + label() + "：D 挡，窗口备着");
                open(result.lane, result.shown);
                return;
            }
            if (result.shown) {
                if (!w.isShowing() || w.lane() != result.lane) {
                    BlackBox.note("盲区画面" + label() + "：显示「" + LaneCycle.labelOf(result.lane) + "」");
                    noteShown();
                    w.show(result.lane);
                }
            } else if (w.isShowing()) {
                noteIfNoFrame();
                BlackBox.note("盲区画面" + label() + "：收起，窗口备着");
                w.conceal();
            }
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
            MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
            if (pipeline == null || pipeline.isReleased()) {
                return;
            }
            SurfaceTexture kept = isHud ? pipeline.takeKeptHud() : pipeline.takeKeptSide();
            if (kept == null) {
                return;
            }
            try {
                w.getTextureView().setSurfaceTexture(kept);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "接回留着的盲区画面画布失败" + label() + ": " + e);
                if (!keepCanvas(pipeline, kept)) {
                    kept.release();
                }
                return;
            }
            restoreBufferOnFrame = true;
            BlackBox.note("盲区画面" + label() + "：接回留着的画布（同一块画布，通道不动）");
            bindCamera(kept);
            w.syncCanvas();
        }

        /** 这块画布交给管线留着（{@link MultiCameraManager#keepSideCanvas} / {@link MultiCameraManager#keepHudCanvas}）。 */
        private boolean keepCanvas(MultiCameraManager pipeline, SurfaceTexture st) {
            return isHud ? MultiCameraManager.keepHudCanvas(pipeline, st) : MultiCameraManager.keepSideCanvas(pipeline, st);
        }

        /**
         * 窗口的画布要被系统收走了（窗口移走）：交给管线留着，由调度等它离开会话再放（同超级镜子 {@code handCanvasToPipeline}）。
         *
         * @return true：画布交给管线了（回调返回 false）；false：照旧让 TextureView 放
         */
        private boolean handCanvasToPipeline(SurfaceTexture st) {
            cancelRetry();
            restoreBufferOnFrame = false;
            if (st == boundTexture) {
                unbindCamera();
                withdrawOutput();
            }
            MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
            boolean kept = keepCanvas(pipeline, st);
            if (!kept) {
                AppLog.i(TAG, "盲区画面" + label() + "画布没了、管线里没有环视：照旧放掉，还在会话里的由通道调度马上摘");
                ChannelScheduler.wakeAll();
            }
            return kept;
        }

        /** 移走窗口：放开登记、撤回输出，再当场移走（画布在移走那一下交给管线，见 {@link #handCanvasToPipeline}）。 */
        void detach() {
            cancelRetry();
            SideViewOutputWindow w = window;
            if (w == null) {
                unbindCamera();
                withdrawOutput();
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
            withdrawOutput();
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

        /** 显示过、到收起时一帧都没来：记下来，诊断报告里看得到（PR #19 的做法）。 */
        void noteIfNoFrame() {
            SideViewOutputWindow w = window;
            if (firstFrameSeen || w == null || !w.isShowing()) {
                return;
            }
            SingleCamera camera = boundCamera;
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
            SingleCamera camera = boundCamera;
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
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager == null) {
                manager = CameraManagerHolder.getInstance().getOrInit(SideViewService.this);
            }
            SingleCamera camera = manager != null ? manager.getCamera(CameraSlots.KEY_SURROUND) : null;
            if (camera == null) {
                scheduleRetry(surfaceTexture);
                return;
            }
            retryCount = 0;
            if (camera != boundCamera || surfaceTexture != boundTexture) {
                // 换了一路相机（换车型，管线重建了）或者换了一块画布：先从原来那里撤回，再在这里声明
                withdrawOutput();
                boundCamera = camera;
                boundManager = manager;
                boundTexture = surfaceTexture;
                restoreBufferSize(surfaceTexture);
            }
            Size previewSize = camera.getPreviewSize();
            if (previewSize != null && !w.hasGeometry()) {
                w.setSourceSize(previewSize);
            }
            final SingleCamera declaredOn = camera;
            SingleCamera.RecordDropped dropped = why -> onDropped(declaredOn, surfaceTexture, why);
            boolean declared = isHud ? camera.declareHud(surfaceTexture, dropped)
                    : camera.declareSide(surfaceTexture, dropped);
            CameraNeeds needs = CameraNeeds.current();
            boolean newlyClaimed = !needs.isHeld(CameraNeeds.Holder.SIDE_VIEW);
            claimed = true;
            needs.claim(CameraNeeds.Holder.SIDE_VIEW);
            if (declared) {
                manager.channelScheduler().wake();
            }
            if (declared || newlyClaimed) {
                AppLog.i(TAG, "盲区画面" + label() + (declared ? "声明了输出" : "的输出早已声明")
                        + (newlyClaimed ? "、登记了 SIDE_VIEW" : "，SIDE_VIEW 早已登记")
                        + "：开环视、把画布加进会话都由通道调度做（环视此刻 "
                        + manager.channelScheduler().condition(CameraSlots.KEY_SURROUND)
                        + "，预览尺寸 " + previewSize + "，缓冲区 " + camera.getPreviewBufferSize() + "）");
            }
        }

        /**
         * 相机层丢了这一处的画布（调度在主线程上派发）：共享预览流挂不下、或者会话配不上时盲区画面最先让路（JPEG 之后），
         * 预览、超级镜子、录像不受影响。这一段不再挂（{@link #refused}），不然调度会一直想把它加回去，一次次重建环视会话。
         */
        private void onDropped(SingleCamera camera, SurfaceTexture texture, SingleCamera.Drop why) {
            if (destroyed || camera != boundCamera || texture != boundTexture) {
                return;
            }
            if (why != SingleCamera.Drop.CONFIG_FAILED) {
                AppLog.w(TAG, "盲区画面" + label() + "的画布离开了会话（" + why + "）");
                return;
            }
            BlackBox.noteImportant("盲区画面" + label() + "：相机层这一回不带它（共享预览流挂不下或会话配不上），这一段不再挂"
                    + "（离开 D 挡 30 秒、熄屏或原厂画面之后再试）");
            refuse();
        }

        /** 这一段不带这一处（{@link #refused}）：放开登记、撤回、移走窗口；正打着灯、另一处也显示不了的话当场说一句。 */
        private void refuse() {
            refused = true;
            refusalShown = false;
            detach();
            // 不在这里直接判：可能正在 apply 里（窗口挂不上）
            handler.post(evaluateRunnable);
        }

        /** 把缓冲区尺寸拨回相机会话配置时用的那个（理由见超级镜子的 {@code restoreBufferSize}）。 */
        private void restoreBufferSize(SurfaceTexture surfaceTexture) {
            if (surfaceTexture == null || boundCamera == null) {
                return;
            }
            Size buffer = boundCamera.getPreviewBufferSize();
            if (buffer == null || buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
                return;
            }
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        }

        /** 这一处的输出此刻在环视的会话里（按会话配好时记下的快照）。从监测线程读时是近似值。 */
        boolean inSession() {
            SingleCamera camera = boundCamera;
            SurfaceTexture texture = boundTexture;
            return camera != null && texture != null && camera.sessionOutputs().carries(
                    new ChannelRules.Out(isHud ? ChannelRules.Kind.HUD : ChannelRules.Kind.SIDE, texture));
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

        /**
         * 撤回这一处的输出。撤回不碰会话：画布还活着就是「留着的输出」，等下一次本来要动、或者关相机时一起摘
         * （移走窗口时画布交给管线留着，见 {@link #handCanvasToPipeline}）。
         */
        private void withdrawOutput() {
            SingleCamera camera = boundCamera;
            MultiCameraManager manager = boundManager;
            SurfaceTexture texture = boundTexture;
            boundCamera = null;
            boundManager = null;
            boundTexture = null;
            if (camera != null && texture != null
                    && (isHud ? camera.withdrawHud(texture) : camera.withdrawSide(texture))) {
                AppLog.i(TAG, "盲区画面" + label() + "撤回了输出（相机 " + camera.getCameraId() + "）：还在会话里的由通道调度摘");
                if (manager != null) {
                    manager.channelScheduler().wake();
                }
            }
        }
    }
}
