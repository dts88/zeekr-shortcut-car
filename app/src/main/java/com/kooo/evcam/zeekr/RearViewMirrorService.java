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

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:RearViewMirrorService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("后台服务 RearViewMirrorService onCreate");
        appConfig = new AppConfig(this);
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // flags 里的 START_FLAG_RETRY / START_FLAG_REDELIVERY 直接说明
        // 这一次是不是系统在做 sticky 重启 —— 「START_STICKY 到底生不生效」看它
        com.kooo.evcam.blackbox.BlackBox.noteImportant("RearViewMirrorService onStartCommand flags=" + flags
                + (intent == null ? " intent=null(sticky重启)" : "")
                + " startId=" + startId);
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
    private void ensureStillBound() {
        if (mirrorView == null || !mirrorView.isShowing()) {
            return;
        }
        if (mirrorView.isDocked()) {
            // 贴边收起时本来就是故意不接相机的，别把它又接回去
            return;
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

    private void unbindCamera() {
        cancelRetry();
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
