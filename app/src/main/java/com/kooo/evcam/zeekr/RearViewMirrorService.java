package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.camera.StallWatch;
import com.kooo.evcam.CameraForegroundService;

/**
 * 超级后视镜的窗口生命周期与相机绑定。
 *
 * <p>相机绑定沿用主屏悬浮窗那条已经在跑的路径：把窗口 TextureView 的 Surface 交给
 * {@code SingleCamera.setMainFloatingSurface()}，由相机会话把它当作一路附加输出。
 * <b>没有新建 GL 管线，也没有改相机会话的结构</b> —— 那是这台车机上最容易出问题的地方。</p>
 *
 * <p>后视镜和主屏悬浮窗共用同一个附加输出槽位，因此两者不同时存在。
 * 这是有意的：它们本来就是同一件事的两种形态，同时挂两路只会白白多占一路输出。</p>
 */
public class RearViewMirrorService extends Service {

    private static final String TAG = "RearViewMirrorSvc";

    /** 设置页要能改到正在显示的那个窗口。 */
    private static volatile RearViewMirrorService instance;

    /** 绑不上相机时的重试间隔与上限 —— 冷启动时相机可能还没就绪。 */
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private RearViewMirrorView mirrorView;
    private SingleCamera boundCamera;
    private int retryCount;
    private Runnable retryRunnable;
    private Runnable watchdog;
    /** 相机看门狗间隔：够快到切回前台就恢复，又不至于空转太频繁。 */
    private static final long WATCHDOG_INTERVAL_MS = 2000L;

    public static void start(Context context) {
        context.startService(new Intent(context, RearViewMirrorService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RearViewMirrorService.class));
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

    /** 设置页改了「只看前后」后通知正在显示的窗口。 */
    public static void applyLaneMode(Context context) {
        RearViewMirrorService svc = instance;
        if (svc != null && svc.mirrorView != null) {
            svc.mirrorView.applyLaneModeFromConfig();
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

    /** 卡顿报告里的一行：窗口和相机绑定的状态。 */
    public static String describeForStall() {
        RearViewMirrorService svc = instance;
        if (svc == null) {
            return "mirror: service not running";
        }
        RearViewMirrorView view = svc.mirrorView;
        if (view == null) {
            return "mirror: service running, no window";
        }
        SingleCamera camera = svc.boundCamera;
        TextureView tv = view.getTextureView();
        return "mirror: showing=" + view.isShowing()
                + " windowVisibility=" + view.getWindowVisibility()
                + " shown=" + view.isShown()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " textureAvailable=" + tv.isAvailable()
                + " boundCamera=" + (camera != null ? camera.getCameraId() : "none")
                + " cameraOpened=" + (camera != null && camera.isCameraOpened())
                + " bindRetries=" + svc.retryCount;
    }

    /**
     * 屏幕是不是黑着。黑着就不占相机 —— 这一条是<b>实测拿到的</b>：
     *
     * <p>熄屏两秒后这个窗口就已经在显示「点击恢复」，因为屏幕黑了本来就没有帧可看；
     * 但它对相机的那一份登记还在，于是主界面那个「熄屏 15 秒关相机」的任务只能报告
     * 「相机还有人要: MIRROR，不关」。相机就这样开着进了深睡。等十几分钟后车机醒来，
     * 会话已经是上一世的：关它的那次调用卡在 binder 里一秒多，紧接着
     * DISCONNECTED（日志里的 error -4 是基座自定义码，不是资源耗尽），最后靠看门狗重开，花了 9.4 秒。
     * 运气差的那次，是连重开都失败，只能重启车机。</p>
     *
     * <p>所以现在熄屏就放手：看不见的画面不值得占着相机睡过去。亮屏再接回来。</p>
     *
     * <h3>但「亮屏」这个广播靠不住（1.27.0 修的回归）</h3>
     *
     * <p>2026-09-24 那份黑匣子：四次熄屏都有「熄屏：后视镜放开相机」，四次醒来
     * <b>一次「亮屏：后视镜重新接相机」都没有</b> —— 而每次醒来三十秒内心跳就写着
     * {@code screenOn=true}。也就是说车机深睡醒来后，{@code ACTION_SCREEN_ON}
     * 根本不送到这里。</p>
     *
     * <p>1.24.0 把「屏幕黑着」记成一个标记、只等这个广播来清掉，于是标记永远清不掉：
     * {@link #bindCamera} 一律拒绝，连用户点「点击恢复」也被拒 —— 那就是「点了没反应」。
     * 同一个点击在新进程里（标记重新从 PowerManager 读）一点就好，证实了是这个标记。</p>
     *
     * <p>所以这个字段现在只表示「收到过熄屏、放开过相机」；<b>该不该接相机，一律问
     * {@link #screenIsDark()} 读实时状态</b>。看门狗两秒一次，屏幕一亮就接回来，
     * 不管广播来不来。</p>
     */
    private boolean screenOff;

    /**
     * 屏幕此刻是不是黑的 —— 问系统，不信记下来的标记。
     *
     * <p>熄屏广播是可靠的（实测四次四次都到），亮屏广播在深睡醒来之后一次都没到。
     * 所以「黑」可以由广播告诉我们，「亮」只能自己去看。</p>
     */
    private boolean screenIsDark() {
        android.os.PowerManager power =
                (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        if (power == null) {
            return screenOff;
        }
        return !power.isInteractive();
    }


    /** 熄屏之后等多久再确认没人要相机。车机熄屏六秒就深睡，这一步不能慢。 */
    private static final long CLOSE_AFTER_SCREEN_OFF_MS = 1500;

    private final android.content.BroadcastReceiver screenWatch =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenOff = true;
                        if (recordingHoldsCamera()) {
                            // 正在录像：相机反正不会关，摘掉后视镜什么也省不下，
                            // 只会多一次会话重建 —— 见 recordingHoldsCamera 的说明。录完再摘
                            com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏：正在录像，后视镜先不放开，录完再放");
                            return;
                        }
                        com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏：后视镜放开相机");
                        unbindCamera();
                        handler.postDelayed(RearViewMirrorService.this::closeCamerasIfNobodyWants,
                                CLOSE_AFTER_SCREEN_OFF_MS);
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        screenOff = false;
                        com.kooo.evcam.blackbox.BlackBox.noteImportant("亮屏：后视镜重新接相机");
                        rebindNow();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:RearViewMirrorService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("后台服务 RearViewMirrorService onCreate");
        appConfig = new AppConfig(this);
        instance = this;
        android.os.PowerManager power =
                (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        screenOff = power != null && !power.isInteractive();
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        // 和主界面那个屏幕监听用同一种注册方式（系统广播，不对外）
        registerReceiver(screenWatch, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
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
        return START_STICKY;
    }

    private void showMirror() {
        if (mirrorView != null && mirrorView.isShowing()) {
            return;
        }
        mirrorView = new RearViewMirrorView(this, appConfig);
        mirrorView.getTextureView().setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                        bindCamera(st);
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                        restoreBufferSize(st);
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        unbindCamera();
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {
                        // 每显示一帧新画面响一次。卡顿监测靠它判断后视镜是不是卡住了，
                        // 窗口自己靠它判断要不要把那张过时的画面盖起来
                        StallWatch.mirrorFrame();
                        RearViewMirrorView view = mirrorView;
                        if (view != null) {
                            view.noteFrame();
                        }
                    }
                });
        mirrorView.setResumeAction(() -> rebindNow(true));
        mirrorView.setDockListener(this::onDockChanged);
        mirrorView.show();
        startWatchdog();
    }

    /** 每隔几秒确认相机还在，断了就接回来。 */
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
     * 把窗口的 Surface 接到相机上。
     *
     * <p>相机可能还没就绪（冷启动、或用户还没进过主界面），所以失败要重试，
     * 而不是一次不成就放弃 —— 那样后视镜会永远黑着。</p>
     */
    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (screenIsDark()) {
            // 画布准备好、看门狗到点、贴边放回来 —— 通往这里的路不止一条，
            // 所以这一条守在入口，而不是守在每一个调用方
            AppLog.d(TAG, "屏幕黑着，后视镜先不接相机");
            return;
        }
        if (mirrorView == null || surfaceTexture == null) {
            return;
        }
        if (mirrorView.isDocked()) {
            // 上次就是贴边收起来的：那条窄边上只写名字，不该为它开一路推流
            AppLog.i(TAG, "后视镜处于贴边状态，暂不接相机");
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
        }
        SingleCamera camera = manager != null ? manager.getCamera("front") : null;
        if (camera == null) {
            scheduleRetry(surfaceTexture);
            return;
        }

        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            // 几何按合成流的真实尺寸算，不是按缓冲区尺寸
            mirrorView.setSourceSize(previewSize);
        }
        boundCamera = camera;
        restoreBufferSize(surfaceTexture);

        Surface surface = new Surface(surfaceTexture);
        camera.setMainFloatingSurface(surface, surfaceTexture);

        if (camera.isCameraOpened()) {
            camera.recreateSession(false);
        } else {
            final SingleCamera cam = camera;
            CameraForegroundService.whenReady(this, cam::openCamera);
        }
        retryCount = 0;
        // 登记：这一路相机后视镜要用。没人登记时别处才会关它
        com.kooo.evcam.camera.CameraNeeds.current().claim(com.kooo.evcam.camera.CameraNeeds.Holder.MIRROR);
        StallWatch.armMirror(true);
        AppLog.i(TAG, "后视镜已接到相机，预览尺寸 " + previewSize
                + "，缓冲区 " + camera.getPreviewBufferSize());
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
     * <p>{@code SingleCamera} 在创建会话前也做同样的事（见那里的注释），
     * 但会话只创建一次，之后的每一次缩放都得由这里兜住。</p>
     *
     * <p>用会话配置时那个尺寸而不是别的：改成第三个值会和
     * {@code OutputConfiguration} 对不上，那是另一种坏法。</p>
     */
    private void restoreBufferSize(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || boundCamera == null) {
            return;
        }
        Size buffer = boundCamera.getPreviewBufferSize();
        if (buffer == null || buffer.getWidth() <= 0 || buffer.getHeight() <= 0) {
            return;
        }
        surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        AppLog.d(TAG, "后视镜缓冲区尺寸已恢复为 " + buffer);
    }

    /**
     * 相机被别处关掉后重新接上。
     *
     * <p>录制、息屏等路径都可能关掉相机；关掉之后这个窗口就冻在最后一帧上。
     * 定期确认一下，断了就接回来，比等着某个通知可靠 ——
     * 关相机的地方有好几处，不是每一处都会想到通知这里。</p>
     */
    /**
     * 录像是不是正拿着相机。
     *
     * <h3>为什么录像中熄屏，后视镜不摘</h3>
     *
     * <p>摘掉后视镜会让那一路重建一次会话。录像中重建万一配置失败，
     * {@code SingleCamera} 会按顺序丢掉「可选」的输出再重试：先丢副屏、再丢后视镜，
     * <b>都没得丢了就丢掉录像输出</b> —— 那一路照样有预览，只是不再往录像里送帧。
     * 1.24.0 之前熄屏时后视镜还挂着，失败了被牺牲的是它；1.24.0 起它先走了，
     * 剩下能丢的就只有录像。「1.19 哨兵模式下手动录像能一直录，1.28 熄屏后停了」
     * 是在这一处对上的（2026-09-26，还没被日志抓到）。</p>
     *
     * <p>而录像中相机本来就不会被关（登记表里有录像这一项），摘掉后视镜省不下任何东西。
     * 所以录像中不摘，录完了由看门狗补摘 —— 那时再放开相机，照样赶在深睡之前。</p>
     */
    private boolean recordingHoldsCamera() {
        return com.kooo.evcam.camera.CameraNeeds.current()
                .isHeld(com.kooo.evcam.camera.CameraNeeds.Holder.RECORDING);
    }

    private void ensureStillBound() {
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        if (screenIsDark() && boundCamera != null && !recordingHoldsCamera()) {
            // 熄屏那一刻在录像，所以没摘；现在录完了，补上
            com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏中录像结束：后视镜放开相机");
            unbindCamera();
            handler.postDelayed(this::closeCamerasIfNobodyWants, CLOSE_AFTER_SCREEN_OFF_MS);
            return;
        }
        if (mirrorView.isDocked() || screenIsDark()) {
            // 贴边收起、或者屏幕黑着，本来就是故意不接相机的，别把它又接回去
            return;
        }
        if (screenOff) {
            // 屏幕已经亮了，亮屏广播却没来 —— 深睡醒来就是这样，车机停车后自己醒那一次也是。
            // 总原则（规格 §0）：用户开着后视镜，后视镜就该开着；停车时放开相机是特殊情况，
            // 醒来特殊情况就结束了，回到用户设定的状态。车机自己醒的那一次同样接（§1.3 选 a）。
            // 1.28.0 曾在开发者选项里放过一个「亮屏后自动接回相机」、默认等人点，1.34.0 删掉
            screenOff = false;
            com.kooo.evcam.blackbox.BlackBox.noteImportant("亮屏但没收到广播：后视镜自己接回相机");
        }
        if (boundCamera != null && boundCamera.isCameraOpened()) {
            return;
        }
        TextureView tv = mirrorView.getTextureView();
        if (tv.isAvailable()) {
            AppLog.i(TAG, "相机已不在，后视镜重新绑定");
            retryCount = 0;
            bindCamera(tv.getSurfaceTexture());
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
     * 贴边收起就停掉这一路推流，放回来再接上。
     *
     * <p>收起后屏幕上只剩 72px 宽的一条，里面什么也看不出来，却要相机一直多推一路
     * 输出、窗口每帧重画一遍整张纹理。这一路停掉之后，如果没有别人在用这台相机
     * （没在录、主界面也不在前台），相机会跟着整个关掉。</p>
     *
     * <p>代价是放回来要重建一次会话，画面会比窗口晚到零点几秒。</p>
     */
    private void onDockChanged(boolean docked) {
        if (docked) {
            unbindCamera();
            return;
        }
        if (screenOff && !screenIsDark()) {
            // 把贴边的窗口拉回来，也是人的动作
            screenOff = false;
        }
        rebindNow();
    }

    private void rebindNow() {
        rebindNow(false);
    }

    /**
     * 重新接上相机：放回来时、以及画面停住时点了那句提示。
     *
     * @param explainIfItFails 用户主动点的那一次传 true —— 点了没反应是最难受的，
     *                         接不上就得说出为什么（相机被占着、还是别的）
     */
    private void rebindNow(boolean explainIfItFails) {
        if (explainIfItFails && screenOff && !screenIsDark()) {
            // 人点了，那就是人回来了
            screenOff = false;
            com.kooo.evcam.blackbox.BlackBox.noteImportant("点了后视镜：接回相机");
        }
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        TextureView tv = mirrorView.getTextureView();
        if (!tv.isAvailable()) {
            AppLog.w(TAG, "后视镜画布还没准备好，暂不接相机");
            return;
        }
        retryCount = 0;
        bindCamera(tv.getSurfaceTexture());
        if (explainIfItFails) {
            // 开相机是异步的，当场问「开了没」一定是没开。等两秒半再看
            handler.postDelayed(this::explainIfStillDark, 2500);
        }
    }

    /**
     * 点了「点击恢复」之后画面还是没回来，说一句为什么。
     *
     * <p>这一条是给「点了没有任何作用」那种情形写的：相机被别的程序占着时，
     * 我们这边怎么点都没用，而屏幕上什么都不说 —— 人只能猜是应用坏了，
     * 于是去重启应用、清缓存、重装，全都不会有效果，因为占用记录在相机服务那边。</p>
     */
    private void explainIfStillDark() {
        SingleCamera camera = boundCamera;
        if (camera != null && camera.isCameraOpened()) {
            return;
        }
        String error = camera == null ? null : camera.lastErrorName();
        String message = error != null && error.contains("IN_USE")
                ? getString(R.string.mirror_resume_busy)
                : getString(R.string.mirror_resume_failed, error == null ? "?" : error);
        AppLog.w(TAG, "点击恢复之后相机仍然没打开: " + error);
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.w(TAG, "提示弹不出来: " + e);
        }
    }

    /**
     * 熄屏之后确认一遍：没人要相机就整个关掉。
     *
     * <p>主界面那边也有同样一步，但它<b>可能根本不在</b> —— 退出应用之后只剩这几个
     * 服务，那时候没人会去关相机，于是又变成开着相机睡过去。所以这里也管一次；
     * 两边都关是无害的，漏了才有害。</p>
     */
    private void closeCamerasIfNobodyWants() {
        if (!screenIsDark()) {
            return;
        }
        if (appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled()) {
            // 熄屏录制生效时，主界面刻意让相机保持活跃（1.19.0 起就是这样），这里不能替它关。
            // 1.24.0 加这一步时漏了这一条
            return;
        }
        com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
        if (needs.heldByAnyone()) {
            AppLog.d(TAG, "熄屏，但相机还有人要: " + needs.describe());
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            // 关是相机线程去做的，这里不等；每一路关完时各自记一行「相机 X 已关」，带用时
            manager.closeAllCameras("screen off (mirror)");
            com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏：让相机去关（后视镜这边）");
            AppLog.i(TAG, "熄屏且没人要相机，关掉");
        }
    }

    private void unbindCamera() {
        cancelRetry();
        com.kooo.evcam.camera.CameraNeeds.current().release(com.kooo.evcam.camera.CameraNeeds.Holder.MIRROR);
        StallWatch.armMirror(false);
        if (boundCamera != null) {
            try {
                boundCamera.setMainFloatingSurface(null, null);
                boundCamera.recreateSession(false);
            } catch (Exception e) {
                AppLog.w(TAG, "解绑相机失败: " + e);
            }
            boundCamera = null;
        }
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("RearViewMirrorService onDestroy");
        instance = null;
        try {
            unregisterReceiver(screenWatch);
        } catch (Exception e) {
            AppLog.w(TAG, "屏幕监听取消失败: " + e);
        }
        cancelRetry();
        cancelWatchdog();
        unbindCamera();
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
