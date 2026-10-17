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
 * 盲区画面：打转向灯时在屏幕那一侧显示该侧摄像头的画面（issue #6）。窗口、相机登记、车辆信号都在这里。
 *
 * <h3>只声明、只登记（同超级镜子，{@link RearViewMirrorService}）</h3>
 *
 * <p>通道调度（{@link ChannelScheduler}）是唯一动相机的地方。这里只做两件事：把窗口的画布声明成环视的盲区画面输出
 * （{@link SingleCamera#declareSide}），和在登记表上登记 {@link CameraNeeds.Holder#SIDE_VIEW} —— 它要环视，
 * 配置里没开环视也要（项目所有者 2026-10-16，{@code ChannelRules}）。开不开环视、什么时候把画布加进会话、
 * 挂不下时谁让路，都由调度和相机层判；以后调规则也只在登记表这一处。</p>
 *
 * <h3>什么时候挂、什么时候显示</h3>
 *
 * <p>规则在 {@link SideViewTrigger}（纯逻辑）：挂 D 挡就备着（窗口挂着、隐藏，登记、声明着，预备方式 B）；
 * 打灯显示那一侧，灯灭后 1 秒收起；离开 D 挡 30 秒后、熄屏、原厂画面出现时移走窗口、注销、撤回。
 * 超级镜子一点不动：两个窗口各管各的。</p>
 *
 * <p><b>移走窗口时画布由管线留着</b>（同超级镜子 §7.7，{@link MultiCameraManager#keepSideCanvas}）：会话里的画布只由
 * 通道调度放，不自己放 —— PR #1、#19 就错在这里（窗口一拆就放掉画布，环视会话里多一个死输出，摘到之前整条流停着）。
 * 下一次挂窗口接回同一块（{@link #takeBackKeptCanvas}），通道零变动。</p>
 *
 * <h3>车辆信号</h3>
 *
 * <p>亮屏时完整登记（要车速），熄屏注销、窗口移走：熄屏后不该留着监听和相机过去（同超级镜子熄屏放开登记的理由）。</p>
 *
 * <p>来源：打灯判定、拉直和取景借自 FerixzTanz 的 PR #4、wysie 的 PR #19（GPL-3.0）；读车辆信号、备着 / 显示的流程
 * 也照它们的侧视弹窗服务写。碰相机的部分照通道调度重写。</p>
 */
public class SideViewService extends Service {

    private static final String TAG = "SideViewSvc";
    private static final String TELEMETRY_USER = "side-view";

    /** 拿不到环视那一路（冷启动时管线可能还没建好）时的重试间隔与上限。 */
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;
    /** 看门狗间隔：管线换了（换车型）、冷启动时还没建好，都能在两秒内重新声明、重新登记上（同超级镜子）。 */
    private static final long WATCHDOG_INTERVAL_MS = 2000L;

    private static volatile SideViewService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SideViewTrigger trigger = new SideViewTrigger();
    private AppConfig appConfig;
    private SideViewWindow window;
    /** 盲区画面输出声明在哪一路相机上（环视）、属于哪一份管线、声明出去的那块画布。 */
    private SingleCamera boundCamera;
    private MultiCameraManager boundManager;
    private SurfaceTexture boundTexture;
    /** 刚接回留着的画布：下一次出帧时把缓冲区尺寸拨回去（同超级镜子的 restoreBufferOnFrame）。 */
    private boolean restoreBufferOnFrame;
    private boolean listening;
    private boolean destroyed;
    /**
     * 这一段不带盲区画面：相机层这一回丢了它（共享预览流挂不下、会话配不上时它最先让路，JPEG 之后），或者窗口挂不上。
     * 到窗口本来就该移走为止（离开 D 挡 30 秒、熄屏、原厂画面出现）不再挂，下一次再挂时重试 —— 一段里最多一次，
     * 调度不会一直想把它加回去；配不上多半是一时的，不该从此不显示（ultracode 审查 core-1）。
     */
    private boolean refused;
    /** 这一段里打灯时已经提示过一次「无法显示盲区画面」。 */
    private boolean refusalShown;
    private int retryCount;
    private Runnable retryRunnable;
    private Runnable watchdog;
    /** 这一次显示的时刻、第一帧来没来：黑匣子里看「打灯后多久出画面」。 */
    private long shownAtMs;
    private boolean firstFrameSeen;

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
    public static void applyAim() {
        SideViewService svc = instance;
        if (svc != null && svc.window != null) {
            svc.window.applyAim();
        }
    }

    /** 改了最低车速：照新的门槛再判一次。 */
    public static void applyConfig() {
        SideViewService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::evaluate);
        }
    }

    /** 卡顿报告里的一行：窗口、输出声明和登记的状态。从监测线程读，是近似值。 */
    public static String describeForStall() {
        SideViewService svc = instance;
        if (svc == null) {
            return "side view: service not running";
        }
        SideViewWindow w = svc.window;
        SingleCamera camera = svc.boundCamera;
        return "side view: listening=" + svc.listening + " refused=" + svc.refused
                + " window=" + (w == null ? "none" : w.describe())
                + " boundCamera=" + (camera != null ? camera.getCameraId() : "none")
                + " claimed=" + CameraNeeds.current().isHeld(CameraNeeds.Holder.SIDE_VIEW)
                + " inSession=" + svc.sideInSession();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BlackBox.attach(this, "Service:SideViewService");
        BlackBox.noteImportant("后台服务 SideViewService onCreate");
        appConfig = new AppConfig(this);
        instance = this;
        ScreenState.addListener(screenListener);
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
        trigger.reset();
        if (refused) {
            // 熄屏也是这一段的结束（同离开 D 挡 30 秒、原厂画面）：亮屏后再挂窗口时重试。服务退出也走这里，那就只是结束
            refused = false;
            refusalShown = false;
            BlackBox.note(destroyed ? "盲区画面：服务停下，不带它的这一段结束"
                    : "盲区画面：熄屏，不带它的这一段结束，亮屏后再挂窗口时重试");
        }
        detachWindow();
        AppLog.i(TAG, "盲区画面：停止监听");
    }

    /** 主线程上：照最新的读数判一次（{@link SideViewTrigger}），挂、显示、收起、移走窗口。 */
    private void evaluate() {
        handler.removeCallbacks(evaluateRunnable);
        if (!listening || destroyed) {
            return;
        }
        VehicleState s = Telemetry.get().latest();
        Readings r = Telemetry.get().readings();
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = s.turnSignal;
        in.hazard = s.hazard;
        in.gear = s.gear;
        in.stock360Shown = s.stockSurroundShown;
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        in.speedKmh = s.speedKmh;
        in.minSpeedKmh = appConfig.getSideViewMinSpeed();
        long now = SystemClock.uptimeMillis();
        SideViewTrigger.Result result = trigger.update(now, in, ScreenState.dark());
        if (refused && !result.attached) {
            // 这一段过去了（离开 D 挡 30 秒、熄屏、原厂画面）：下一次挂窗口时重试
            refused = false;
            refusalShown = false;
            BlackBox.note("盲区画面：不带它的这一段结束，下一次挂窗口时重试");
        }
        if (refused) {
            if (result.shown && !refusalShown) {
                // 打了灯却显示不了：说一句，这一段只说一次
                refusalShown = true;
                showRefusal();
            }
        } else {
            apply(result, in);
        }
        if (result.wakeAt > 0) {
            handler.postAtTime(evaluateRunnable, result.wakeAt);
        }
    }

    private void apply(SideViewTrigger.Result result, SideViewDecision.Input in) {
        SideViewWindow w = window;
        if (!result.attached) {
            if (w != null) {
                BlackBox.note("盲区画面：移走窗口（" + (ScreenState.dark() ? "熄屏"
                        : SideViewDecision.factoryViewActive(in) ? "原厂画面在：挡位 " + in.gear
                                + "、原厂 360 " + in.stock360Shown + "、原厂小窗 " + in.stockPopupShown
                                + "、泊车辅助 " + in.parkAssistOn
                        : result.graceEnded ? "离开 D 挡 " + SideViewTrigger.READY_GRACE_MS / 1000 + " 秒"
                        : "显示结束，不在 D 挡（挡位 " + in.gear + "）") + "）");
                detachWindow();
            }
            return;
        }
        if (w == null) {
            BlackBox.note(result.shown
                    ? "盲区画面：显示「" + LaneCycle.labelOf(result.lane) + "」（窗口没备着，现在挂）"
                    : "盲区画面：D 挡，窗口备着");
            openWindow(result.lane, result.shown);
            return;
        }
        if (result.shown) {
            if (!w.isShowing() || w.lane() != result.lane) {
                BlackBox.note("盲区画面：显示「" + LaneCycle.labelOf(result.lane) + "」");
                noteShown();
                w.show(result.lane);
            }
        } else if (w.isShowing()) {
            noteIfNoFrame();
            BlackBox.note("盲区画面：收起，窗口备着");
            w.conceal();
        }
    }

    /** 点了关闭按钮：关掉这一次打灯。 */
    private void onCloseTapped() {
        BlackBox.note("盲区画面：点了关闭，这一次打灯不再显示");
        noteIfNoFrame();
        trigger.dismiss();
        evaluate();
    }

    // ================================================================= 窗口

    private void openWindow(int lane, boolean show) {
        SideViewWindow w = new SideViewWindow(this, appConfig, this::onCloseTapped);
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
            // 悬浮窗权限没了就停下服务，和开着时没有权限一样
            window = null;
            boolean permitted = WakeUpHelper.hasOverlayPermission(this);
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
    private void takeBackKeptCanvas() {
        SideViewWindow w = window;
        if (w == null || !w.isAttached()) {
            return;
        }
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        if (pipeline == null || pipeline.isReleased()) {
            return;
        }
        SurfaceTexture kept = pipeline.takeKeptSide();
        if (kept == null) {
            return;
        }
        try {
            w.getTextureView().setSurfaceTexture(kept);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "接回留着的盲区画面画布失败: " + e);
            if (!MultiCameraManager.keepSideCanvas(pipeline, kept)) {
                kept.release();
            }
            return;
        }
        restoreBufferOnFrame = true;
        BlackBox.note("盲区画面：接回留着的画布（同一块画布，通道不动）");
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
        if (st == boundTexture) {
            unbindCamera();
            withdrawOutput();
        }
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        boolean kept = MultiCameraManager.keepSideCanvas(pipeline, st);
        if (!kept) {
            AppLog.i(TAG, "盲区画面画布没了、管线里没有环视：照旧放掉，还在会话里的由通道调度马上摘");
            ChannelScheduler.wakeAll();
        }
        return kept;
    }

    /** 移走窗口：放开登记、撤回输出，再当场移走（画布在移走那一下交给管线，见 {@link #handCanvasToPipeline}）。 */
    private void detachWindow() {
        cancelRetry();
        cancelWatchdog();
        SideViewWindow w = window;
        if (w == null) {
            unbindCamera();
            withdrawOutput();
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
    }

    private void noteShown() {
        shownAtMs = SystemClock.uptimeMillis();
        firstFrameSeen = false;
    }

    /** 显示过、到收起时一帧都没来：记下来，诊断报告里看得到（PR #19 的做法）。 */
    private void noteIfNoFrame() {
        SideViewWindow w = window;
        if (firstFrameSeen || w == null || !w.isShowing()) {
            return;
        }
        SingleCamera camera = boundCamera;
        BlackBox.note("盲区画面收起：显示了 " + (SystemClock.uptimeMillis() - shownAtMs) + " ms 一帧都没来；"
                + (camera == null ? "没接上相机" : "环视" + (camera.isCameraOpened() ? "开着" : "没开")
                + "、会话里" + (sideInSession() ? "带着" : "没有") + "盲区画面、最近错误 " + camera.lastErrorName()));
    }

    /** 每来一帧响一次。挂窗口时环视要是还没开过，预览尺寸是 null（开相机时才选），帧来了再补上。 */
    private void onFrame() {
        SideViewWindow w = window;
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
            BlackBox.note("盲区画面出画面：显示后 " + (SystemClock.uptimeMillis() - shownAtMs) + " ms，几何"
                    + (w.hasGeometry() ? "已知" : "未知"));
        }
    }

    // ================================================================= 相机

    /**
     * 声明盲区画面输出、登记 SIDE_VIEW —— 只做这两件事（同超级镜子的 {@code bindCamera}）。
     *
     * <p>同一块画布再声明一次、同一项再登记一次都不算变化：看门狗每 2 秒走到这里一次，什么都没变就什么都不做。
     * 拿不到环视那一路（冷启动、管线还没建好）要重试。</p>
     */
    private void bindCamera(SurfaceTexture surfaceTexture) {
        SideViewWindow w = window;
        if (ScreenState.dark() || w == null || !w.isAttached()
                || surfaceTexture == null || surfaceTexture.isReleased()) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
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
        boolean declared = camera.declareSide(surfaceTexture, why -> onSideDropped(declaredOn, surfaceTexture, why));
        CameraNeeds needs = CameraNeeds.current();
        boolean claimed = !needs.isHeld(CameraNeeds.Holder.SIDE_VIEW);
        needs.claim(CameraNeeds.Holder.SIDE_VIEW);
        if (declared) {
            manager.channelScheduler().wake();
        }
        if (declared || claimed) {
            AppLog.i(TAG, "盲区画面" + (declared ? "声明了输出" : "的输出早已声明")
                    + (claimed ? "、登记了 SIDE_VIEW" : "，SIDE_VIEW 早已登记")
                    + "：开环视、把画布加进会话都由通道调度做（环视此刻 "
                    + manager.channelScheduler().condition(CameraSlots.KEY_SURROUND)
                    + "，预览尺寸 " + previewSize + "，缓冲区 " + camera.getPreviewBufferSize() + "）");
        }
    }

    /**
     * 相机层丢了盲区画面（调度在主线程上派发）：共享预览流挂不下、或者会话配不上时它最先让路（JPEG 之后），
     * 预览、超级镜子、录像不受影响。这一段不再挂（{@link #refused}），不然调度会一直想把它加回去，一次次重建环视会话。
     */
    private void onSideDropped(SingleCamera camera, SurfaceTexture texture, SingleCamera.Drop why) {
        if (destroyed || camera != boundCamera || texture != boundTexture) {
            return;
        }
        if (why != SingleCamera.Drop.CONFIG_FAILED) {
            AppLog.w(TAG, "盲区画面的画布离开了会话（" + why + "）");
            return;
        }
        BlackBox.noteImportant("盲区画面：相机层这一回不带它（共享预览流挂不下或会话配不上），这一段不再挂"
                + "（离开 D 挡 30 秒、熄屏或原厂画面之后再试）");
        refuse();
    }

    /** 这一段不带盲区画面（{@link #refused}）：放开登记、撤回、移走窗口；正打着灯的话当场说一句。 */
    private void refuse() {
        refused = true;
        refusalShown = false;
        detachWindow();
        // 不在这里直接判：可能正在 apply 里（窗口挂不上）
        handler.post(evaluateRunnable);
    }

    private void showRefusal() {
        try {
            android.widget.Toast.makeText(com.kooo.evcam.settings.Languages.localized(this),
                    R.string.msg_side_view_unavailable, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.w(TAG, "提示弹不出来: " + e);
        }
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

    /** 盲区画面的输出此刻在环视的会话里（按会话配好时记下的快照）。从监测线程读时是近似值。 */
    private boolean sideInSession() {
        SingleCamera camera = boundCamera;
        SurfaceTexture texture = boundTexture;
        return camera != null && texture != null
                && camera.sessionOutputs().carries(new ChannelRules.Out(ChannelRules.Kind.SIDE, texture));
    }

    /** 每隔几秒重新声明、重新登记一次：管线换了、冷启动时还没建好都能接上（同超级镜子的看门狗）。 */
    private void startWatchdog() {
        cancelWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                SideViewWindow w = window;
                if (w != null && w.isAttached() && !ScreenState.dark() && w.getTextureView().isAvailable()) {
                    bindCamera(w.getTextureView().getSurfaceTexture());
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "环视始终拿不到，盲区画面放弃绑定（看门狗接着试）");
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

    /** 放开登记。登记表一变，监听就叫醒调度：环视还有别人要就不动，没人要了照登记表保持或者关。 */
    private void unbindCamera() {
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.SIDE_VIEW);
    }

    /**
     * 撤回盲区画面输出。撤回不碰会话：画布还活着就是「留着的输出」，等下一次本来要动、或者关相机时一起摘
     * （移走窗口时画布交给管线留着，见 {@link #handCanvasToPipeline}）。
     */
    private void withdrawOutput() {
        SingleCamera camera = boundCamera;
        MultiCameraManager manager = boundManager;
        SurfaceTexture texture = boundTexture;
        boundCamera = null;
        boundManager = null;
        boundTexture = null;
        if (camera != null && texture != null && camera.withdrawSide(texture)) {
            AppLog.i(TAG, "盲区画面撤回了输出（相机 " + camera.getCameraId() + "）：还在会话里的由通道调度摘");
            if (manager != null) {
                manager.channelScheduler().wake();
            }
        }
    }

    @Override
    public void onDestroy() {
        BlackBox.noteImportant("后台服务 SideViewService onDestroy");
        destroyed = true;
        instance = null;
        ScreenState.removeListener(screenListener);
        stopListening();
        detachWindow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
