package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.ChannelScheduler;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.camera.StallWatch;

/**
 * 超级镜子的窗口生命周期与相机登记。
 *
 * <h3>2.11 起：只声明、只登记（大纲 §2 阶段 4b）</h3>
 *
 * <p>项目所有者 2026-10-10 定：通道调度（{@link ChannelScheduler}）是唯一动相机的地方。这里只做两件事 ——
 * 把窗口 TextureView 的 SurfaceTexture 声明成环视的后视镜输出（{@link SingleCamera#declareMirror}：以 SurfaceTexture 作身份，
 * 相机要写的 Surface 由 SingleCamera 建会话时建，一块画布只建一个），和在登记表上登记 MIRROR。环视开不开、什么时候开、
 * 什么时候把后视镜加进会话、被别的程序拿着时等不等，都由调度判。</p>
 *
 * <p><b>放开登记不撤回输出。</b>贴边、熄屏只放登记：画面只要还活着就留在会话里，等下一次本来要动通道、或者关相机时一起摘
 * （减输出能拖就拖，channel-logic §二.4）。只有画布销毁、服务退出时才撤回。以前（2.10）这里自己动相机：
 * {@code setMainFloatingSurface} + {@code recreateSession}，贴边、熄屏、放回来、亮屏各重建一次环视会话，
 * 环视关着时还自己去开 —— 那是调度之外的第二个动手方（阶段 3 先拿掉了「自己开」，阶段 4b 拿掉其余的）。</p>
 *
 * <p><b>关后视镜时画布由管线留着</b>（大纲 §7.7，项目所有者 2026-10-11「按建议，留住」；做法同 §7.6 主界面的预览画布）：
 * 窗口移走（服务退出、窗口被拆）、画布要被系统收走时 onSurfaceTextureDestroyed 返回 false，画布交给管线
 * （{@link MultiCameraManager#keepMirrorCanvas}）—— 它还活着，就只是环视会话里一个「留着的输出」，关后视镜时环视（含环视的录像）
 * 一步不动；没人接回的，等调度照「减输出」的规矩摘掉、或者随环视关了再放。新开的后视镜窗口接回同一块
 * （{@link #takeBackKeptCanvas}）：同一块画布就是同一个输出，通道零变动。以前（阶段 4b）放掉画布，它成了死输出，
 * 调度排一步马上摘，摘到之前环视整条重复请求停着（大纲 §6 风险 2）。</p>
 *
 * <p>后视镜和主界面预览共用环视的那一条预览流（Surface Sharing，见 SingleCamera 建会话那一段）。
 * <b>没有新建 GL 管线</b> —— 那是这台车机上最容易出问题的地方。</p>
 *
 * <h3>后座舱（切换范围「后视 + 后座舱」，项目所有者 2026-10-19）</h3>
 *
 * <p>窗口还有一块后座舱的画布（{@link RearViewMirrorView#getCabinTextureView}）：这一档时照同一套声明成后座舱的
 * 超级镜子输出（{@link #bindCabin}），登记还是那一项 MIRROR —— 调度照切换范围判后座舱要不要（ChannelRules.World#mirrorCabin）。
 * 看后视时后座舱也带着，切过去不用等；换出这一档撤回。熄屏、贴边只放登记，窗口移走时画布交给管线留着，新窗口接回 ——
 * 两块画布是同一个 {@link MirrorCanvas}，只是声明在不同的一路上。管线里还没有后座舱这一路（刚换到这一档、管线正在重建）时
 * 不声明，管线建好时再对一次（{@link #pipelineReplaced}）。两块画布总有一块看不见，看不见的 TextureView 不自己建画布，
 * 由这里建（{@link MirrorCanvas#canvas}）。</p>
 */
public class RearViewMirrorService extends Service {

    private static final String TAG = "RearViewMirrorSvc";

    /** 设置页要能改到正在显示的那个窗口。 */
    private static volatile RearViewMirrorService instance;

    /** 拿不到环视那一路（冷启动时管线可能还没建好）时的重试间隔与上限。 */
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private RearViewMirrorView mirrorView;
    /** 环视那块画布（窗口一直有）。声明它的 {@link #bindCamera} 同时登记 MIRROR。 */
    private final MirrorCanvas surround = new MirrorCanvas(CameraSlots.KEY_SURROUND, false);
    /** 后座舱那块画布：切换范围是「后视 + 后座舱」时声明（{@link #bindCabin}，见类说明）。 */
    private final MirrorCanvas cabin = new MirrorCanvas(CameraSlots.KEY_CABIN_REAR, true);
    private int retryCount;
    private Runnable retryRunnable;
    private Runnable watchdog;
    /** 点了「点击恢复」之后多久看一次画面回来没有（开相机、加输出都是调度异步做的，当场问一定还没好）。 */
    private static final long EXPLAIN_AFTER_MS = 2500L;
    /**
     * 环视还在起来、或者在出画面而后视镜还没加进会话时，从点下去起最多接着看多久：一步的封顶加一次通道安静
     * （见 {@link #explainIfStillDark}）。
     */
    private static final long EXPLAIN_GIVE_UP_MS = ChannelRules.STEP_MAX_MS + ChannelRules.QUIET_MS;
    /** 最近一次点「点击恢复」的时刻（uptime）。 */
    private long explainSince;
    private final Runnable explainRunnable = this::explainIfStillDark;
    /** 看门狗间隔：管线换了（换车型）、冷启动时还没建好，都能在两秒内重新声明、重新登记上。 */
    private static final long WATCHDOG_INTERVAL_MS = 2000L;

    public static void start(Context context) {
        context.startService(new Intent(context, RearViewMirrorService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RearViewMirrorService.class));
    }

    /** Redraw the existing window after the label setting changes; no camera restart. */
    public static void refreshCameraLabel() {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.invalidate();
        }
    }

    /** 设置页开关了按键模式之后通知正在显示的窗口。 */
    public static void applyButtonMode(Context context) {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.applyButtonModeFromConfig();
        }
    }

    /** 设置页改了尺寸后通知正在显示的窗口。 */
    public static void applySize(Context context) {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.applySizeFromConfig();
        }
    }

    /** 设置页改了鱼眼校正后通知正在显示的窗口。 */
    public static void applyCorrection(Context context) {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.applyCorrectionFromConfig();
        }
    }

    /** 设置页改了切换范围后通知正在显示的窗口：换环；换到、换出「后视 + 后座舱」时接上或撤回后座舱的画布。 */
    public static void applyLaneMode(Context context) {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.applyLaneModeFromConfig();
            svc.syncCabin();
        }
    }

    /**
     * 卡顿监测用：窗口此刻是否真的在屏幕上画。
     *
     * <p>窗口被系统藏起来（例如系统弹窗盖住悬浮窗）时 TextureView 不画，
     * 没有新画面是正常的，不能算卡。从监测线程读，是近似值。</p>
     */
    public static boolean isWindowVisibleForStall() {
        RearViewMirrorService svc = instance;
        RearViewMirrorView view = svc != null ? svc.mirrorView : null;
        return view != null && view.isShowing()
                && view.getWindowVisibility() == android.view.View.VISIBLE;
    }

    /** 卡顿报告里的一行：窗口、输出声明和登记的状态。从监测线程读，是近似值。 */
    public static String describeForStall() {
        RearViewMirrorService svc = instance;
        if (svc == null) {
            return "mirror: service not running";
        }
        RearViewMirrorView view = svc.mirrorView;
        if (view == null) {
            return "mirror: service running, no window";
        }
        SingleCamera camera = svc.surround.camera;
        SingleCamera cabinCamera = svc.cabin.camera;
        TextureView tv = view.getTextureView();
        return "mirror: showing=" + view.isShowing()
                + " windowVisibility=" + view.getWindowVisibility()
                + " shown=" + view.isShown()
                + " docked=" + view.isDocked()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " textureAvailable=" + tv.isAvailable()
                + " boundCamera=" + (camera != null ? camera.getCameraId() : "none")
                + " cameraOpened=" + (camera != null && camera.isCameraOpened())
                + " claimed=" + CameraNeeds.current().isHeld(CameraNeeds.Holder.MIRROR)
                + " inSession=" + svc.surround.inSession()
                + " showingCabin=" + view.isShowingCabin()
                + " cabinCamera=" + (cabinCamera != null ? cabinCamera.getCameraId() : "none")
                + " cabinInSession=" + svc.cabin.inSession()
                + " bindRetries=" + svc.retryCount;
    }

    /**
     * 屏幕黑着就不登记相机 —— 这一条是<b>实测拿到的</b>：
     *
     * <p>熄屏两秒后这个窗口就已经在显示「点击恢复」，因为屏幕黑了本来就没有帧可看；
     * 但它对相机的那一份登记还在，于是「熄屏后没人要就关相机」那一步只能报告
     * 「相机还有人要: MIRROR，不关」。相机就这样开着进了深睡。等十几分钟后车机醒来，
     * 会话已经是上一世的：关它的那次调用卡在 binder 里一秒多，紧接着
     * DISCONNECTED（日志里的 error -4 是基座自定义码，不是资源耗尽），最后靠看门狗重开，花了 9.4 秒。
     * 运气差的那次，是连重开都失败，只能重启车机。</p>
     *
     * <p>所以熄屏就放开登记：看不见的画面不值得占着相机睡过去。亮屏再登记回来。
     * 后视镜在 {@link #unbindCamera} 里注销自己那一份登记，熄屏时没人要了调度现在就关（熄屏不保持，环视先单独关），
     * 后视镜的输出跟着关相机一起摘。2.11 起放开登记不撤回输出、不重建会话（大纲 §2 阶段 4b）：以前摘下时还要重建一次
     * 环视会话，关相机得先把排着的那一次作废。录像中熄屏也一样只放登记 —— 以前录像中不摘（{@code recordingHoldsCamera}），
     * 是怕摘掉时那一次重建配置失败、丢的是录像输出；现在放开登记不引起重建，这个特例没有意义了，删了。</p>
     *
     * <h3>屏幕亮没亮，只问 ScreenState</h3>
     *
     * <p>2026-09-24 那份黑匣子：四次熄屏都有「熄屏：后视镜放开相机」，四次醒来
     * <b>一次「亮屏：后视镜重新接相机」都没有</b> —— 车机深睡醒来后，{@code ACTION_SCREEN_ON}
     * 根本不送到这里。1.24.0 把「屏幕黑着」记成一个标记、只等这个广播来清掉，于是标记永远清不掉：
     * {@link #bindCamera} 一律拒绝，连用户点「点击恢复」也被拒 —— 那就是「点了没反应」（1.27.0 修）。</p>
     *
     * <p>1.63.0 起这里不再自己记标记、不再自己注册广播：屏幕状态只有 ScreenState 一份，
     * 亮了就按固定顺序叫到这里。熄屏 v3 起「熄屏」是屏幕真黑了（安卓熄屏，或者车辆信号说黑）：车主下车那一下就放开相机，
     * 开车门亮屏时接回来；车机要睡、车机黑着醒了都还是熄屏，不叫到这里。</p>
     */
    private final com.kooo.evcam.screen.ScreenState.Listener screenListener =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏：后视镜放开相机"
                            + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
                    unbindCamera();
                }

                @Override
                public void onScreenOn() {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("亮屏：后视镜重新接相机");
                    rebindNow();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:RearViewMirrorService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("后台服务 RearViewMirrorService onCreate");
        appConfig = new AppConfig(this);
        instance = this;
        // 熄屏放开、亮屏登记：听进程里唯一那份屏幕状态（主线程回调）
        com.kooo.evcam.screen.ScreenState.addListener(screenListener);
        // 管线被放掉重建了：新的一份建好当场重新声明、登记（见 pipelineReplaced）
        CameraManagerHolder.getInstance().addReplacedListener(pipelineReplaced);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "RearViewMirrorService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        // 只记系统做 sticky 重启的那种（flags 里有 RETRY / REDELIVERY，或 intent 为空）：
        // 「START_STICKY 到底生不生效」看它。例行的启动不记，和前台服务一样
        if (flags != 0 || intent == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("RearViewMirrorService onStartCommand flags=" + flags
                    + (intent == null ? " intent=null(sticky重启)" : "")
                    + " startId=" + startId);
        }
        if (!appConfig.isRearViewEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，后视镜无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        showMirror();
        // 被杀了要不要重启：跟着「开机自启动」开关（规格 §3）。悬浮窗本身由 Recovery 按设置恢复，不用自己粘着
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    private void showMirror() {
        if (mirrorView != null && mirrorView.isShowing()) {
            return;
        }
        mirrorView = new RearViewMirrorView(this, appConfig);
        // 新窗口还不知道画面尺寸：下一次声明时交给它（MirrorCanvas.attach）
        surround.attach(mirrorView);
        cabin.attach(mirrorView);
        mirrorView.setCanvasHost(canvasHost);
        mirrorView.setResumeAction(() -> rebindNow(true));
        mirrorView.setDockListener(this::onDockChanged);
        mirrorView.show();
        takeBackKeptCanvas();
        // 看不见的那块画布不会自己建、也就不会来 onSurfaceTextureAvailable（停在后座舱上打开时环视那块）：现在就对一次
        ensureStillBound();
        startWatchdog();
    }

    /** 窗口问的两件事：画布在不在出画面（换到另一台摄像头时）、画布刚可见（缓冲区尺寸要拨回去）。 */
    private final RearViewMirrorView.CanvasHost canvasHost = new RearViewMirrorView.CanvasHost() {
        @Override
        public boolean streaming(boolean onCabin) {
            return (onCabin ? cabin : surround).streaming();
        }

        @Override
        public void shown(boolean onCabin) {
            (onCabin ? cabin : surround).restoreOnFrame = true;
        }
    };

    /**
     * 上一个后视镜窗口留下的画布接回来（大纲 §7.7，项目所有者 2026-10-11「按建议，留住」；留是 {@link MirrorCanvas#handToPipeline}）。
     *
     * <p>在窗口加上之后、第一次画之前做（加窗口只排一次布局和绘制，这一趟主线程走完之前不会画）：
     * {@code TextureView.setSurfaceTexture} 之后视图不再自己建画布，也就不会来 onSurfaceTextureAvailable ——
     * 所以这里当场照「画布准备好」走一遍声明：同一块画布就是同一个输出，会话里还带着它，
     * 通道零变动、画面马上就有。黑着屏、窗口贴边收起时照两道守卫先不声明、不登记，画布照样归这个窗口，
     * 亮屏、放回来时再声明。没留下的照旧等系统建（看不见的那块由 {@link MirrorCanvas#canvas} 建）。</p>
     *
     * <p>后座舱那块先接：环视那块一声明就会顺带对一次后座舱（{@link #syncCabin}），那时它还没接回的话会另建一块。
     * 切换范围已经不含后座舱的不接：留在管线手里，照旧等它离开后座舱的会话再放。</p>
     *
     * <p>窗口没加上（{@code show()} 失败）就不接：视图永远挂不上窗口，也就永远不会放它 —— 留在管线手里，照旧由调度放。
     * 留着画布的只会是进程里那一份管线（管线属于应用，后视镜服务退出不放它），这里不建。</p>
     */
    private void takeBackKeptCanvas() {
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        if (pipeline == null || pipeline.isReleased()) {
            return;
        }
        if (appConfig.rearViewWantsCabinRear()) {
            cabin.takeBack(pipeline);
        }
        surround.takeBack(pipeline);
    }

    /** 每隔几秒重新声明、重新登记一次（见 {@link #ensureStillBound}）。 */
    private void startWatchdog() {
        cancelWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                ensureStillBound();
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

    /**
     * 声明后视镜输出、登记 MIRROR —— 只做这两件事（大纲 §2 阶段 4b）。
     *
     * <p>两道守卫留着：屏幕黑着不登记（见 {@link #screenListener}）；贴边收起不登记（那条窄边上只写名字，
     * 不该为它要一路画面）。通往这里的路不止一条（画布准备好、接回上一个窗口留下的画布、看门狗到点、放回来、亮屏、
     * 点「点击恢复」、管线换了），所以守在入口，而不是守在每一个调用方。</p>
     *
     * <p>同一块画布再声明一次、同一项再登记一次都不算变化：看门狗每 2 秒走到这里一次，什么都没变就什么都不做、
     * 不写日志、不叫醒调度。声明变了叫醒这一份管线的调度；登记变了，登记表的监听自己会叫醒它。</p>
     *
     * <p>拿不到环视那一路（冷启动、管线还没建好）要重试，而不是一次不成就放弃 —— 那样后视镜会永远黑着。</p>
     */
    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (com.kooo.evcam.screen.ScreenState.dark()) {
            AppLog.d(TAG, "屏幕黑着，后视镜先不登记相机");
            return;
        }
        if (mirrorView == null || surfaceTexture == null || surfaceTexture.isReleased()) {
            return;
        }
        if (mirrorView.isDocked()) {
            AppLog.i(TAG, "后视镜处于贴边状态，暂不登记相机");
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
        boolean declared = surround.declare(camera, manager, surfaceTexture);
        CameraNeeds needs = CameraNeeds.current();
        boolean claimed = !needs.isHeld(CameraNeeds.Holder.MIRROR);
        needs.claim(CameraNeeds.Holder.MIRROR);
        if (declared) {
            manager.channelScheduler().wake();
        }
        if (claimed) {
            // 只在登记上的那一刻开始盯：看门狗每 2 秒都走到这里，每次都重新开始盯的话宽限期永远过不完
            StallWatch.armMirror(true);
        }
        if (declared || claimed) {
            AppLog.i(TAG, "后视镜" + (declared ? "声明了输出" : "的输出早已声明")
                    + (claimed ? "、登记了 MIRROR" : "，MIRROR 早已登记")
                    + "：开环视、把后视镜加进会话都由通道调度做（环视此刻 "
                    + manager.channelScheduler().condition(CameraSlots.KEY_SURROUND)
                    + "，预览尺寸 " + camera.getPreviewSize() + "，缓冲区 " + camera.getPreviewBufferSize() + "）");
        }
        // 后座舱那块跟着走：通往这里的每一条路（画布准备好、接回、看门狗、放回来、亮屏、点恢复）后座舱都要同样对一次
        syncCabin();
    }

    // ------------------------------------------------------------------ 后座舱（切换范围「后视 + 后座舱」）

    /** 后座舱那块画布照切换范围对一次：这一档就声明（看不见的画布这里建，{@link MirrorCanvas#canvas}），换出这一档就撤回。 */
    private void syncCabin() {
        if (!appConfig.rearViewWantsCabinRear()) {
            cabin.withdraw();
            return;
        }
        SurfaceTexture canvas = cabin.canvas();
        if (canvas != null) {
            bindCabin(canvas);
        }
    }

    /**
     * 声明后座舱的超级镜子输出 —— 和 {@link #bindCamera} 同样的守卫（屏幕黑着、贴边收起不声明），登记还是 MIRROR
     * （bindCamera 登记，它最后会叫到这里；后座舱的画布先准备好时也照样先声明，等登记上了调度才加进会话）。
     *
     * <p>管线里还没有后座舱这一路（刚换到这一档、管线正在重建）就不声明、不重试：管线建好时 {@link #pipelineReplaced}
     * 当场再对一次，看门狗每两秒也会走到。</p>
     */
    private void bindCabin(SurfaceTexture surfaceTexture) {
        if (com.kooo.evcam.screen.ScreenState.dark() || mirrorView == null || mirrorView.isDocked()
                || surfaceTexture == null || surfaceTexture.isReleased()) {
            return;
        }
        if (!appConfig.rearViewWantsCabinRear()) {
            cabin.withdraw();
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        SingleCamera camera = manager != null ? manager.getCamera(CameraSlots.KEY_CABIN_REAR) : null;
        if (camera == null) {
            return;
        }
        if (cabin.declare(camera, manager, surfaceTexture)) {
            manager.channelScheduler().wake();
            AppLog.i(TAG, "后视镜声明了后座舱的输出：开后座舱、把画布加进会话都由通道调度做（后座舱此刻 "
                    + manager.channelScheduler().condition(CameraSlots.KEY_CABIN_REAR)
                    + "，预览尺寸 " + camera.getPreviewSize() + "）");
        }
    }

    /**
     * 管线被放掉重建了（换车型、上一次列相机出错、切换范围换到「后视 + 后座舱」而管线里还没有后座舱）：新的一份建好就当场
     * 重新声明、登记，不等看门狗那两秒 —— 新的一份按次序开环视、后座舱时会话里就带着后视镜的画布，不用随后再改一次输出。
     * 和主界面接上新的一份是同一个办法（CameraManagerHolder.addReplacedListener、whenBuilt）。
     */
    private final Runnable pipelineReplaced = () -> {
        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        holder.whenBuilt(holder.getCameraManager(), built -> handler.post(this::ensureStillBound));
    };

    /**
     * 看门狗：重新声明、重新登记 —— 只做这两件（大纲 §2 阶段 4b）。
     *
     * <p>不再看相机开没开、更不去开：开不开、什么时候开由通道调度定。它还要管的只剩两种情形：
     * 管线换了（换车型时 CameraManagerHolder 重建了一份，后视镜得声明到新那份的环视上），
     * 和冷启动时管线还没建好、重试用完了。两种都由 {@link #bindCamera} 自己认出来；什么都没变就什么都不做。</p>
     *
     * <p>贴边收起、屏幕黑着时不登记，本来就是故意的，别把它又登记回去。深睡醒来亮屏广播不来的那种，
     * ScreenState 自己每两秒问一次系统，亮了会叫 onScreenOn 登记回来。</p>
     */
    private void ensureStillBound() {
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        if (mirrorView.isDocked() || com.kooo.evcam.screen.ScreenState.dark()) {
            return;
        }
        SurfaceTexture canvas = surround.canvas();
        if (canvas != null) {
            retryCount = 0;
            bindCamera(canvas);
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "相机始终不可用，后视镜放弃绑定");
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

    /**
     * 贴边收起只放开相机登记，放回来再登记 —— 输出声明不动，画面留在会话里（大纲 §2 阶段 4b）。
     *
     * <p>收起后屏幕上只剩 72px 宽的一条，里面什么也看不出来：窗口把画布设成不可见、不再逐帧画
     * （{@code RearViewMirrorView.syncDockState}）。放开登记之后环视要是没人要了，调度照登记表处理
     * （亮屏、前台服务在时保持 30 秒，之后关）；后视镜的输出不为贴边单独摘 —— 它还活着，留到下一次本来要动通道、
     * 或者关相机时一起摘（channel-logic §二.4）。所以放回来时只要会话里还带着它，画面马上就有，不用重建；
     * 不在了（这期间环视关过、或者改过一次输出），调度按要的输出排一步加上。</p>
     *
     * <p>「后视 + 后座舱」时后座舱也一样：贴边放开登记，后座舱没别人要就保持 30 秒后关 —— 照「关座舱前先关环视」，
     * 那一下环视（含环视录像）断约 0.5–1 秒、录像多切一个文件（车主 2026-10-16 定贴边照现行调度逻辑，不始终保持）。</p>
     *
     * <p>以前（2.10）贴边就摘掉这一路输出、重建一次会话，放回来再重建一次，画面比窗口晚到零点几秒。</p>
     */
    private void onDockChanged(boolean docked) {
        if (docked) {
            com.kooo.evcam.blackbox.BlackBox.note(surround.inSession()
                    ? "后视镜贴边收起：放开相机登记，画面留在会话里（不重建）"
                    : "后视镜贴边收起：放开相机登记（画面这时不在会话里）");
            unbindCamera();
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.note(surround.inSession()
                ? "后视镜放回来：画面还在会话里，直接接着显示"
                : "后视镜放回来：画面已不在会话里，排一步加上");
        rebindNow();
    }

    private void rebindNow() {
        rebindNow(false);
    }

    /**
     * 重新声明、重新登记：放回来时、亮屏时、以及画面停住时点了那句提示。
     *
     * @param explainIfItFails 用户主动点的那一次传 true —— 点了没反应是最难受的，
     *                         画面回不来就得说出为什么（相机被占着、还是别的）
     */
    private void rebindNow(boolean explainIfItFails) {
        if (explainIfItFails && com.kooo.evcam.screen.ScreenState.dark()) {
            // 人点了，那就是人回来了：记下来的还黑着，就是亮屏还没被察觉。让 ScreenState 现在就问 —— 安卓那一半现问，
            // 车辆说黑着的让车辆信号当场读一遍；真亮了它会照亮屏的顺序走一遍，其中就有这里的 onScreenOn
            com.kooo.evcam.blackbox.BlackBox.noteImportant("点了后视镜：接回相机");
            com.kooo.evcam.screen.ScreenState.refresh();
        }
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        SurfaceTexture canvas = surround.canvas();
        if (canvas == null) {
            AppLog.w(TAG, "后视镜画布还没准备好，暂不登记相机");
            return;
        }
        retryCount = 0;
        bindCamera(canvas);
        if (explainIfItFails) {
            // 开相机、加输出都是调度异步做的，当场问一定还没好。等两秒半再看；连着点只留最后那一次
            explainSince = android.os.SystemClock.uptimeMillis();
            handler.removeCallbacks(explainRunnable);
            handler.postDelayed(explainRunnable, EXPLAIN_AFTER_MS);
        }
    }

    /**
     * 点了「点击恢复」之后画面还是没回来，说一句为什么。
     *
     * <p>这一条是给「点了没有任何作用」那种情形写的：相机被别的程序占着时，
     * 我们这边怎么点都没用，而屏幕上什么都不说 —— 人只能猜是应用坏了，
     * 于是去重启应用、清缓存、重装，全都不会有效果，因为占用记录在相机服务那边。</p>
     *
     * <p>2.11 起照通道调度对这一路的判断说（大纲 §2 阶段 4b）：被别的程序拿着、或者被拿着的另一路挡着
     * （{@link ChannelRules.Condition#TAKEN}，含 BLOCKED）→ 「被占用」—— 被断开那种报的是 DISCONNECTED、不带 IN_USE，
     * 以前要另查一遍；在救、救了 3 轮停手 → 「无法打开」带最后一次报错；在出画面、会话里带着后视镜 → 不说。
     * 在出画面、后视镜还没加进会话（调度那一步「改输出」还排着）→ 和「还在起来」一样接着看，到点也不说 ——
     * 相机开着，两句都不是真的（2026-10-11 审查）。看的是窗口上正显示的那一路：环视，或者后座舱。</p>
     *
     * <p><b>还在起来（{@link ChannelRules.Condition#BRINGING_UP}）就再等等，不当场说。</b>调度开环视前要等通道安静 3 秒、
     * 等别的路手上那一步做完：倒车时原厂 360 拿着环视，挂出倒挡它一放开，人顺手一点，两秒半时多半正在等那 3 秒 ——
     * 这时说「无法打开」不是真的，再过一两秒画面就回来了。所以每两秒半再看一次，最多看到点下去之后
     * {@link #EXPLAIN_GIVE_UP_MS}（一步的封顶加一次安静）；到那时还在起来，就是真的开不起来（比如此刻用不了相机：
     * 主界面不在前、前台服务是后台起的），照「无法打开」说。</p>
     */
    private void explainIfStillDark() {
        if (mirrorView == null || !mirrorView.isShowing() || mirrorView.isDocked()
                || com.kooo.evcam.screen.ScreenState.dark()) {
            // 这期间收起来了、熄屏了、窗口没了：后视镜不再要画面，没什么可解释的
            return;
        }
        boolean onCabin = mirrorView.isShowingCabin();
        MirrorCanvas shown = onCabin ? cabin : surround;
        SingleCamera camera = shown.camera;
        MultiCameraManager manager = shown.manager;
        ChannelRules.Condition condition = manager != null
                ? manager.channelScheduler().condition(shown.cameraKey) : null;
        boolean waiting = android.os.SystemClock.uptimeMillis() - explainSince < EXPLAIN_GIVE_UP_MS;
        if (condition == ChannelRules.Condition.STREAMING) {
            if (shown.inSession()) {
                AppLog.i(TAG, "点击恢复之后" + (onCabin ? "后座舱" : "环视") + "在出画面、会话里带着后视镜，不用提示");
                return;
            }
            // 在出画面、后视镜还没加进会话：调度那一步「改输出」要等通道安静、等别的路手上那一步做完，
            // 可能排在座舱按次序开的后面好几秒（2026-10-11 审查）。接着等；到点还没加上也不说「无法打开」——
            // 相机是开着的，那句话不是真的，只记一行
            if (waiting) {
                handler.postDelayed(explainRunnable, EXPLAIN_AFTER_MS);
            } else {
                AppLog.w(TAG, "点击恢复之后" + (onCabin ? "后座舱" : "环视") + "在出画面，后视镜还没加进会话：等通道调度那一步，不提示");
            }
            return;
        }
        if (condition == ChannelRules.Condition.BRINGING_UP && waiting) {
            handler.postDelayed(explainRunnable, EXPLAIN_AFTER_MS);
            return;
        }
        String error = camera == null ? null : camera.lastErrorName();
        String message = condition == ChannelRules.Condition.TAKEN
                ? getString(R.string.mirror_resume_busy)
                : getString(R.string.mirror_resume_failed, error == null ? "?" : error);
        AppLog.w(TAG, "点击恢复之后画面仍然没回来: " + (onCabin ? "后座舱 " : "环视 ") + condition + "，最后一次报错 " + error);
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.w(TAG, "提示弹不出来: " + e);
        }
    }

    /**
     * 放开相机登记 —— 只放登记（贴边、熄屏；画布没了、服务退出时紧接着还要撤回输出）。
     *
     * <p>输出声明不动：画面只要还活着就留在会话里，不重建（大纲 §2 阶段 4b）。登记表一变，监听就叫醒调度：
     * 环视还有别人要（在录、主界面在前）就什么都不动；没人要了照登记表的规矩保持或者关。</p>
     */
    private void unbindCamera() {
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.MIRROR);
        StallWatch.armMirror(false);
    }

    // ------------------------------------------------------------------ 画布

    /**
     * 超级镜子的一块画布，和它声明在哪一路相机上 —— 环视一块，后座舱一块（切换范围「后视 + 后座舱」），同一套做法：
     * 声明 / 撤回、拨回缓冲区尺寸、看不见时自己建、窗口移走时交给管线留着、新窗口接回。两块不同的只有三处：
     * 声明是谁叫的（环视那块的 {@link #bindCamera} 同时登记 MIRROR、拿不到相机会重试；后座舱那块的 {@link #bindCabin}
     * 照切换范围）、画面尺寸怎么交给窗口、环视那块被收走时顺带放开登记（那是整个窗口要走了）。
     */
    private final class MirrorCanvas {
        final String cameraKey;
        final boolean cabin;
        /**
         * 声明在哪一路相机上。放开登记（贴边、熄屏）不清它 —— 声明留着，画面留在会话里；撤回输出（{@link #withdraw}）时才清。
         */
        SingleCamera camera;
        /** 那一路属于哪一份管线：声明、撤回之后叫醒它的通道调度，点了「点击恢复」之后读它的判断。 */
        MultiCameraManager manager;
        /** 声明出去的那块画布（输出的身份）。 */
        SurfaceTexture texture;
        /**
         * 已经交给窗口的画面尺寸。看门狗每 2 秒重新声明一次，尺寸没变就不再交 —— 交一次窗口就重算一次取景、写一行日志、
         * 重画一次。换了窗口（{@link #attach}）、撤回输出时清掉。
         */
        Size source;
        /**
         * 下一次出帧时把缓冲区尺寸拨回去：接回上一个窗口留下的画布、自己建的画布、画布刚设成可见、窗口重新可见 ——
         * 视图第一次画它（建图层那一下）会把缓冲区尺寸改成窗口的布局尺寸，又不叫 onSurfaceTextureAvailable；
         * 紧跟着的就是那一次 onSurfaceTextureUpdated（2026-10-11，§7.7）。
         */
        boolean restoreOnFrame;

        MirrorCanvas(String cameraKey, boolean cabin) {
            this.cameraKey = cameraKey;
            this.cabin = cabin;
        }

        /** 窗口上的那块 TextureView；没有窗口是 null。 */
        TextureView view() {
            RearViewMirrorView window = mirrorView;
            if (window == null) {
                return null;
            }
            return cabin ? window.getCabinTextureView() : window.getTextureView();
        }

        /** 新窗口：交给旧窗口的画面尺寸作废，挂上监听。 */
        void attach(RearViewMirrorView window) {
            source = null;
            restoreOnFrame = false;
            (cabin ? window.getCabinTextureView() : window.getTextureView()).setSurfaceTextureListener(listener);
        }

        final TextureView.SurfaceTextureListener listener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                bind(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                restoreBufferSize(st);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                // 画布要被系统收走了（窗口移走：服务退出、窗口被拆）：交给管线留着就返回 false，TextureView 不放（大纲 §7.7）
                return !handToPipeline(st);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                if (restoreOnFrame) {
                    restoreOnFrame = false;
                    restoreBufferSize(st);
                }
                // 每显示一帧新画面响一次（看不见的那块不响）。卡顿监测靠它判断后视镜是不是卡住了，
                // 窗口自己靠它判断要不要把那张过时的画面盖起来
                StallWatch.mirrorFrame();
                RearViewMirrorView window = mirrorView;
                if (window != null) {
                    window.noteFrame(cabin);
                }
            }
        };

        /** 画布准备好了：照这一块的规矩声明（环视那块同时登记）。 */
        void bind(SurfaceTexture st) {
            if (cabin) {
                bindCabin(st);
            } else {
                bindCamera(st);
            }
        }

        /**
         * 这块画布的 SurfaceTexture；看不见、还没有的由这里建一块（同接回留着的画布那条路：{@code setSurfaceTexture}，
         * 第一次画出来时把缓冲区尺寸拨回去）。
         *
         * <p>TextureView 只在第一次画的时候自己建画布，看不见就一直不建。「后视 + 后座舱」时两块画布总有一块看不见：
         * 看后视时后座舱那块要一直带着（切过去不用等），停在后座舱上打开窗口时环视那块也看不见 —— 那块不建，后视镜就登记不上。
         * 看得见、还没画过的不建：它马上自己建，来 onSurfaceTextureAvailable。贴边收起时两块都看不见、也都不要画面，不建。</p>
         *
         * @return 画布；没有窗口、看得见还没画过、或者贴边收起时还没有的，是 null
         */
        SurfaceTexture canvas() {
            TextureView view = view();
            if (view == null) {
                return null;
            }
            if (view.isAvailable()) {
                return view.getSurfaceTexture();
            }
            if (view.getVisibility() == android.view.View.VISIBLE || mirrorView.isDocked()) {
                return null;
            }
            SurfaceTexture created = new SurfaceTexture(false);
            view.setSurfaceTexture(created);
            restoreOnFrame = true;
            AppLog.i(TAG, (cabin ? "后座舱" : "环视") + "那块画布看不见、自己不建：建了一块交给它");
            return created;
        }

        /**
         * 声明在这一路上；换了一路相机（管线重建了）或者换了一块画布（新窗口）先从原来那里撤回。画面尺寸变了交给窗口。
         *
         * @return true：声明变了（调用方叫醒调度）
         */
        boolean declare(SingleCamera to, MultiCameraManager of, SurfaceTexture st) {
            if (to != camera || st != texture) {
                withdraw();
                camera = to;
                manager = of;
                texture = st;
                restoreBufferSize(st);
            }
            Size preview = to.getPreviewSize();
            RearViewMirrorView window = mirrorView;
            if (preview != null && !preview.equals(source) && window != null) {
                if (cabin) {
                    // 摆正、填满窗口照的是相机的画面尺寸和配置里那一格，和主界面摆这一路一样（SingleCamera.applyLaneTransform）
                    window.setCabinSource(preview.getWidth(), preview.getHeight(), to.laneRotation(),
                            to.isSourceMirrored());
                } else {
                    // 几何按合成流的真实尺寸算，不是按缓冲区尺寸。这一路不在相机列表里时尺寸要等它回来才算得出，看门狗到时候补上
                    window.setSourceSize(preview);
                }
                source = preview;
            }
            return to.declareMirror(st);
        }

        /**
         * 把缓冲区尺寸拨回相机会话配置时用的那个。
         *
         * <p><b>窗口一改大小就必须做这件事。</b>TextureView 在尺寸变化时会把自己
         * SurfaceTexture 的默认缓冲区尺寸设成<b>自己的布局尺寸</b>，
         * 也就是这个悬浮窗的大小 —— 于是相机不再输出 1280×5140 的合成流，
         * 而是输出一张窗口那么大的图；后视镜再从里面取四分之一放大回整个窗口。
         * 表现就是「拖过尺寸之后画面变糊」，而且不会自己恢复。</p>
         *
         * <p>{@code SingleCamera} 每次建会话前也做同样的事（见那里的注释），
         * 但两次建会话之间的每一次缩放、每一次重建图层都得由这里兜住。</p>
         *
         * <p>用会话配置时那个尺寸而不是别的：改成第三个值会和
         * {@code OutputConfiguration} 对不上，那是另一种坏法。</p>
         */
        void restoreBufferSize(SurfaceTexture st) {
            SingleCamera bound = camera;
            if (st == null || bound == null) {
                return;
            }
            Size buffer = bound.getPreviewBufferSize();
            if (buffer == null || buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
                return;
            }
            st.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
            AppLog.d(TAG, (cabin ? "后座舱" : "环视") + "画布缓冲区尺寸已恢复为 " + buffer);
        }

        /**
         * 撤回输出：画布要被收走、服务退出、换了一路相机 / 一块画布、换出「后视 + 后座舱」时。撤回不碰会话 ——
         * 画布还活着就是「留着的输出」，等下一次本来要动、或者关相机时一起摘（关后视镜时画布交给管线留着，
         * 见 {@link #handToPipeline}）；已经被系统收走的，调度排一步马上摘。
         */
        void withdraw() {
            SingleCamera bound = camera;
            MultiCameraManager owner = manager;
            SurfaceTexture st = texture;
            camera = null;
            manager = null;
            texture = null;
            source = null;
            if (bound != null && st != null && bound.withdrawMirror(st)) {
                AppLog.i(TAG, "后视镜撤回了" + (cabin ? "后座舱" : "环视") + "的输出（相机 " + bound.getCameraId()
                        + "）：还在会话里的由通道调度摘");
                if (owner != null) {
                    owner.channelScheduler().wake();
                }
            }
        }

        /** 这块画布此刻在那一路的会话里（按会话配好时记下的快照）。从监测线程读时是近似值。 */
        boolean inSession() {
            SingleCamera bound = camera;
            SurfaceTexture st = texture;
            return bound != null && st != null
                    && bound.sessionOutputs().carries(new ChannelRules.Out(ChannelRules.Kind.MIRROR, st));
        }

        /** 这块画布此刻在出画面：那一路在出画面（通道调度的判断），会话里带着这块画布。 */
        boolean streaming() {
            MultiCameraManager owner = manager;
            return owner != null && inSession()
                    && owner.channelScheduler().condition(cameraKey) == ChannelRules.Condition.STREAMING;
        }

        /** 新窗口接回上一个窗口留下的这块画布（{@link #takeBackKeptCanvas}），当场照「画布准备好」声明一次。 */
        void takeBack(MultiCameraManager pipeline) {
            TextureView view = view();
            if (view == null) {
                return;
            }
            SurfaceTexture kept = pipeline.takeKeptMirror(cameraKey);
            if (kept == null) {
                return;
            }
            try {
                view.setSurfaceTexture(kept);
            } catch (RuntimeException e) {
                // 接不上：还给管线，照旧等它离开会话再放；还不回去（管线里已经没有这一路）就放掉，这块画布不在它的会话里
                AppLog.w(TAG, "接回留着的" + (cabin ? "后座舱" : "环视") + "画布失败: " + e);
                if (!MultiCameraManager.keepMirrorCanvas(pipeline, cameraKey, kept)) {
                    kept.release();
                }
                return;
            }
            restoreOnFrame = true;
            com.kooo.evcam.blackbox.BlackBox.note("后视镜重新打开：接回留着的" + (cabin ? "后座舱" : "环视")
                    + "画布（同一块画布，通道不动）");
            bind(kept);
        }

        /**
         * 这块画布要被系统收走了（onSurfaceTextureDestroyed：窗口移走 —— 服务退出、窗口被拆）：交给管线留着
         * （大纲 §7.7，项目所有者 2026-10-11「按建议，留住」；做法同 §7.6 主界面的预览画布）。
         *
         * <p>以前（阶段 4b）返回 true，TextureView 把它放掉：它成了会话里的死输出，调度排一步马上摘；摘到之前那一路整条重复请求
         * 停着 —— 预览、录像一起停（大纲 §6 风险 2），而那一步要等安静、等别的路手上那一步，碰上另一路正被别人接手时
         * 能停十几秒以上。现在画布还活着：撤回输出之后它只是「留着的输出」，关后视镜时那一路一步不动；下一次本来要动它、
         * 或者关它时一起摘，摘掉以后由调度放（{@link MultiCameraManager#keepMirrorCanvas}）。新开的后视镜窗口可以接回同一块
         * （{@link #takeBack}）。</p>
         *
         * <p>服务退出时 {@link #onDestroy} 已经放开登记、撤回过了（窗口随后当场移走）；服务还在而窗口被拆的，这里先撤回 ——
         * 环视那块还放开登记、取消重试（整个窗口要走了；排着的那一次拿的是这块画布）。
         * 退出、换车型时管线已经放了、它的调度还在按次序关：会话里还带着这块画布的，照样交给那一份的调度，随那一路关了再放
         * （2.11.4 R1：会话里的画布只由通道调度放，{@link MultiCameraManager#ownerOf}）。哪一份都不带着它、也没有这一路时
         * 照旧让 TextureView 放，叫醒调度 —— 这个回调返回之后 TextureView 才放，post 出去的那一轮看到的就是此刻的样子。</p>
         *
         * @return true：画布交给管线了（回调返回 false）；false：照旧让 TextureView 放
         */
        boolean handToPipeline(SurfaceTexture st) {
            restoreOnFrame = false;
            if (!cabin) {
                cancelRetry();
            }
            if (st == texture) {
                if (!cabin) {
                    unbindCamera();
                }
                withdraw();
            }
            MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
            boolean kept = MultiCameraManager.keepMirrorCanvas(pipeline, cameraKey, st);
            if (kept) {
                com.kooo.evcam.blackbox.BlackBox.note("后视镜窗口移走：" + (cabin ? "后座舱" : "环视")
                        + "画布交给管线留着，那一路不动（没人接回的等它离开会话再放）");
            } else {
                AppLog.i(TAG, "后视镜" + (cabin ? "后座舱" : "环视") + "画布没了、管线里没有这一路：照旧放掉，还在会话里的由通道调度马上摘");
                ChannelScheduler.wakeAll();
            }
            return kept;
        }
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("RearViewMirrorService onDestroy");
        instance = null;
        com.kooo.evcam.screen.ScreenState.removeListener(screenListener);
        CameraManagerHolder.getInstance().removeReplacedListener(pipelineReplaced);
        cancelRetry();
        cancelWatchdog();
        handler.removeCallbacks(explainRunnable);
        // 服务退出：放开登记、撤回输出。窗口接着当场移走（RearViewMirrorView.hide），画布要被收走的那一下
        // （onSurfaceTextureDestroyed → MirrorCanvas.handToPipeline）交给管线留着，那一路不动（大纲 §7.7）
        unbindCamera();
        surround.withdraw();
        cabin.withdraw();
        if (mirrorView != null) {
            mirrorView.hide();
            mirrorView = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
