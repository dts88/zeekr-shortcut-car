/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.capture;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.screen.ScreenState;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * 拿着屏幕录制授权的那个前台服务（{@link ScreenCapture}）：同意以后起来，截第一张；之后每按一次截一张，不再问；
 * 熄屏、系统收回授权、应用退出（进程没了）就结束，下次再问（项目所有者 2026-11-15 选的「到熄屏或退出前一直有效」）。
 *
 * <ul>
 *   <li>安卓 10 起拿授权要先有一个「屏幕录制」类型的前台服务，所以先 {@code startForeground} 再 {@code getMediaProjection}。
 *       通知一条，标题是「截取屏幕」，用前台服务那个渠道。</li>
 *   <li>镜像整个中控屏的虚拟屏一直留着，平时不接画面（{@code setSurface(null)}，不合成、不耗电）；截的时候接上
 *       {@link ImageReader}，等来一帧就断开，存成 PNG（在自己的线程上，几百万像素压一张要一阵）。</li>
 *   <li>刚同意时系统的框还在收起，第一张等 {@link #FIRST_SHOT_DELAY_MS} 再截；{@link #FRAME_TIMEOUT_MS} 里没来一帧算截不到。
 *       正在截的这一张没完，再按不算。</li>
 * </ul>
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_DATA = "data";
    private static final int NOTIFICATION_ID = 1003;

    /** 刚同意时，系统的同意框还在收起：第一张等这么久再截。 */
    static final long FIRST_SHOT_DELAY_MS = 600;
    /** 接上画面以后等一帧最多等多久。 */
    static final long FRAME_TIMEOUT_MS = 2000;

    /** 拿着授权的那一个；没有是 null。只在主线程上碰。 */
    private static ScreenCaptureService current;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    /** 截屏的线程：接画面、等一帧、存文件都在这上面。 */
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int width;
    private int height;
    /** 正在截一张（主线程）。 */
    private boolean busy;

    private final ScreenState.Listener screen = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            end("screen off");
        }

        @Override
        public void onScreenOn() {
        }
    };

    private final Runnable timeout = this::noFrame;

    /** 拿着授权的那一个（主线程）；没有是 null。 */
    static ScreenCaptureService current() {
        return current;
    }

    /** 同意框回来了：起前台服务，拿授权，截第一张。 */
    static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCaptureService.class)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "start service failed: " + e);
            ScreenCapture.failed(context.getApplicationContext());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CameraForegroundService.channel(this))
                .setContentTitle(getString(R.string.floating_action_take_screenshot))
                .setSmallIcon(R.drawable.ic_stat_app)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "startForeground failed: " + e);
            BlackBox.note("截屏：前台服务起不来（" + e + "）");
            ScreenCapture.failed(getApplicationContext());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection == null && intent != null && intent.hasExtra(EXTRA_DATA)) {
            begin(intent.getIntExtra(EXTRA_CODE, 0), intent.getParcelableExtra(EXTRA_DATA));
        }
        return START_NOT_STICKY;
    }

    /** 拿授权、建虚拟屏（主线程）。 */
    private void begin(int resultCode, Intent data) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            projection = data == null ? null : manager.getMediaProjection(resultCode, data);
        } catch (RuntimeException e) {
            BlackBox.note("截屏：拿不到授权（" + e + "）");
            projection = null;
        }
        if (projection == null) {
            ScreenCapture.failed(getApplicationContext());
            stopSelf();
            return;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                end("projection stopped");
            }
        }, main);
        DisplayMetrics metrics = new DisplayMetrics();
        Display screen0 = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        screen0.getRealMetrics(metrics);
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        thread = new HandlerThread("ScreenCapture");
        thread.start();
        worker = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        try {
            display = projection.createVirtualDisplay("ZeekrShortcutCapture", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, worker);
        } catch (RuntimeException e) {
            BlackBox.note("截屏：建不了虚拟屏（" + e + "）");
            ScreenCapture.failed(getApplicationContext());
            end("no virtual display");
            return;
        }
        current = this;
        ScreenState.addListener(screen);
        BlackBox.note("截屏：已授权（" + width + "×" + height + "），到熄屏或退出应用为止不再问");
        capture(FIRST_SHOT_DELAY_MS);
    }

    /** 截一张（主线程）：{@code delayMs} 以后接上画面。正在截的那一张没完不算。 */
    void capture(long delayMs) {
        if (busy || display == null) {
            return;
        }
        busy = true;
        worker.postDelayed(this::grab, delayMs);
    }

    /** 截屏线程：接上画面，等来一帧。 */
    private void grab() {
        if (display == null || reader == null) {
            return;
        }
        Image stale = reader.acquireLatestImage();
        if (stale != null) {
            stale.close();
        }
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            r.setOnImageAvailableListener(null, null);
            worker.removeCallbacks(timeout);
            detach();
            Bitmap bitmap;
            try {
                bitmap = toBitmap(image);
            } finally {
                image.close();
            }
            save(bitmap);
        }, worker);
        display.setSurface(reader.getSurface());
        worker.postDelayed(timeout, FRAME_TIMEOUT_MS);
    }

    /** 截屏线程：等不来一帧。 */
    private void noFrame() {
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
        }
        detach();
        BlackBox.note("截屏：" + FRAME_TIMEOUT_MS + " ms 没等来画面，没截到");
        main.post(() -> {
            busy = false;
            ScreenCapture.failed(getApplicationContext());
        });
    }

    /** 截屏线程：断开画面（平时不合成）。 */
    private void detach() {
        if (display != null) {
            display.setSurface(null);
        }
    }

    /** 一帧换成位图：每行末尾可能有对齐用的空位，先按整行宽拷，再裁掉。 */
    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowPixels = plane.getRowStride() / plane.getPixelStride();
        Bitmap wide = Bitmap.createBitmap(rowPixels, image.getHeight(), Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(buffer);
        if (rowPixels == image.getWidth()) {
            return wide;
        }
        Bitmap exact = Bitmap.createBitmap(wide, 0, 0, image.getWidth(), image.getHeight());
        wide.recycle();
        return exact;
    }

    /** 截屏线程：存到 U 盘照片目录的 raw 下。 */
    private void save(Bitmap bitmap) {
        Context app = getApplicationContext();
        File dir = ScreenCapture.folder(app);
        boolean saved = false;
        File file = null;
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
            file = new File(dir, ScreenCapture.fileName(new Date()));
            try (FileOutputStream out = new FileOutputStream(file)) {
                saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                AppLog.w(TAG, "save failed: " + e);
            }
        }
        bitmap.recycle();
        if (saved) {
            BlackBox.note("截屏：已存 " + file.getName() + "（" + width + "×" + height + "）");
        } else {
            BlackBox.note("截屏：存不下来（" + (dir == null ? "没有 U 盘" : dir.getAbsolutePath()) + "）");
        }
        boolean ok = saved;
        boolean noDrive = dir == null;
        main.post(() -> {
            busy = false;
            if (ok) {
                Toast.makeText(app, R.string.msg_screenshot_saved, Toast.LENGTH_SHORT).show();
            } else if (noDrive) {
                Toast.makeText(app, R.string.msg_screenshot_no_drive, Toast.LENGTH_SHORT).show();
            } else {
                ScreenCapture.failed(app);
            }
        });
    }

    /** 结束（主线程）：放开授权、虚拟屏，停前台服务。下次按再问。 */
    private void end(String why) {
        if (current == this) {
            current = null;
        }
        ScreenState.removeListener(screen);
        boolean had = projection != null;
        VirtualDisplay d = display;
        display = null;
        MediaProjection p = projection;
        projection = null;
        ImageReader r = reader;
        reader = null;
        if (d != null) {
            d.release();
        }
        if (p != null) {
            p.stop();
        }
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                if (r != null) {
                    r.close();
                }
            });
            thread.quitSafely();
            worker = null;
        }
        busy = false;
        if (had) {
            BlackBox.note("截屏：授权结束（" + why + "），下次按再问");
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (projection != null || current == this) {
            end("service destroyed");
        }
        super.onDestroy();
    }
}
