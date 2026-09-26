package com.kooo.evcam.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;

/**
 * 主界面不在前台时的录制开关。
 *
 * <p>只有一个用户：录制悬浮按钮（{@link RecordingFloatingService}）。应用退到后台时按钮
 * 绑定这个服务，让它在录制管线（{@link MultiCameraManager}）上开始 / 停止录制，
 * 并挂一条前台通知。应用在前台时按钮走的是发广播给主界面那条路，不经过这里。</p>
 *
 * <p>它以前是 EVCam「新录制架构」的中枢：自带一套全景合成引擎、录制状态机和补盲叠加层。
 * 那套东西在这台车上从没跑起来过（引擎只在四路以上时启用，极氪最多三路），1.44.0 删了，
 * 只留下开始 / 停止这一件事。</p>
 */
public class CameraRecordingService extends Service {
    private static final String TAG = "CameraRecordingService";
    private static final String CHANNEL_ID = "recording_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();

    // 录制线程：开始录制要等相机，不占主线程
    private HandlerThread recordingThread;
    private Handler recordingHandler;
    private Handler mainHandler;

    private boolean isServiceRunning = false;
    private boolean isRecording = false;

    public class LocalBinder extends Binder {
        public CameraRecordingService getService() {
            return CameraRecordingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "录制服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        recordingThread = new HandlerThread("CameraRecording");
        recordingThread.start();
        recordingHandler = new Handler(recordingThread.getLooper());
        isServiceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "CameraRecordingService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "录制服务销毁");
        stopRecording();
        if (recordingThread != null) {
            recordingThread.quitSafely();
            recordingThread = null;
        }
        isServiceRunning = false;
    }

    // ========== 录制控制 ==========

    public void startRecording() {
        if (isRecording) {
            AppLog.w(TAG, "已经在录制中");
            return;
        }

        recordingHandler.post(() -> {
            try {
                AppLog.d(TAG, "开始初始化录制");
                MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getOrInit(this);
                if (cameraManager == null) {
                    AppLog.e(TAG, "CameraManager 未初始化");
                    return;
                }
                // 后台服务不能自己打开相机，相机得是主界面在前台时开好的
                if (cameraManager.isReleased()) {
                    AppLog.e(TAG, "摄像头未初始化，请先打开应用界面");
                    return;
                }

                AppLog.d(TAG, "调用 cameraManager.startRecording()...");
                boolean success = cameraManager.startRecording();
                AppLog.d(TAG, "cameraManager.startRecording() 返回: " + success);
                if (success) {
                    isRecording = true;
                    startForeground(NOTIFICATION_ID, createNotification());
                    RecordingFloatingService.sendRecordingStateChanged(this, true);
                    AppLog.d(TAG, "录制已开始, isRecording=" + isRecording);
                } else {
                    AppLog.e(TAG, "启动录制失败");
                }
            } catch (Exception e) {
                AppLog.e(TAG, "开始录制失败", e);
            }
        });
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        // 使用独立线程停止录制，避免阻塞
        new Thread(() -> {
            try {
                AppLog.d(TAG, "停止录制（后台线程）");
                MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getOrInit(this);
                if (cameraManager != null) {
                    cameraManager.stopRecording();
                }
                mainHandler.post(() -> {
                    isRecording = false;
                    // 停止前台服务，但保持服务运行
                    stopForeground(true);
                    RecordingFloatingService.sendRecordingStateChanged(this, false);
                    AppLog.d(TAG, "录制已停止");
                });
            } catch (Exception e) {
                AppLog.e(TAG, "停止录制失败", e);
                mainHandler.post(() -> {
                    isRecording = false;
                    RecordingFloatingService.sendRecordingStateChanged(this, false);
                });
            }
        }, "StopRecordingThread").start();
    }

    public boolean isRecording() {
        return isRecording;
    }

    // ========== 通知 ==========

    private Notification createNotification() {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_recording_title))
                .setContentText(getString(R.string.notif_recording_text))
                .setSmallIcon(R.drawable.ic_nav_recording)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_recording),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notif_channel_recording_desc));

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public boolean isServiceRunning() {
        return isServiceRunning;
    }
}
