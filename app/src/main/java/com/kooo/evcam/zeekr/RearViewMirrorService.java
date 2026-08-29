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
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
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

    @Override
    public void onCreate() {
        super.onCreate();
        appConfig = new AppConfig(this);
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                        unbindCamera();
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture st) {
                    }
                });
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
            surfaceTexture.setDefaultBufferSize(
                    previewSize.getWidth(), previewSize.getHeight());
            // 几何按合成流的真实尺寸算，不是按缓冲区尺寸
            mirrorView.setSourceSize(previewSize);
        }

        Surface surface = new Surface(surfaceTexture);
        boundCamera = camera;
        camera.setMainFloatingSurface(surface, surfaceTexture);

        if (camera.isCameraOpened()) {
            camera.recreateSession(false);
        } else {
            final SingleCamera cam = camera;
            CameraForegroundService.whenReady(this, cam::openCamera);
        }
        retryCount = 0;
        AppLog.i(TAG, "后视镜已接到相机，预览尺寸 " + previewSize);
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

    private void unbindCamera() {
        cancelRetry();
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
