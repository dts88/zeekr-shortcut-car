package com.kooo.evcam.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.RecordingIntent;
import com.kooo.evcam.recording.RecordingStops;

/**
 * 主界面不在前台时的录制开关。
 *
 * <p>只有一个用户：录制悬浮按钮（{@link RecordingFloatingService}）。应用退到后台时按钮
 * 绑定这个服务，让它去开 / 停录像 —— 走的是和主界面那颗按钮同一个入口
 * {@link RecordingCoordinator}：同一套拒录、看门狗、接回，前台通知也由那边起。
 * 以前这里直接调相机管理器不带参数的那个重载，写入看门狗、存储检查、「只录 U 盘」全绕过了
 * （2026-09-27 审查）。应用在前台时按钮走的是发广播给主界面那条路，不经过这里。</p>
 *
 * <p>它以前是 EVCam「新录制架构」的中枢：自带一套全景合成引擎、录制状态机和补盲叠加层。
 * 那套东西在这台车上从没跑起来过（引擎只在四路以上时启用，极氪最多三路），1.44.0 删了，
 * 只留下开始 / 停止这一件事。</p>
 */
public class CameraRecordingService extends Service {
    private static final String TAG = "CameraRecordingService";

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private boolean isServiceRunning = false;

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
        isServiceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "CameraRecordingService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "录制服务销毁");
        // 录像不属于这个服务：它被系统收走时录像照常，由 RecordingCoordinator 管
        isServiceRunning = false;
    }

    // ========== 录制控制 ==========

    /**
     * 悬浮按钮按了「开始」：人开的，前面的失败都不算了；查过盘就录，不等环视。
     * 相机管线由协调器自己从 CameraManagerHolder 拿（管线属于应用，2.11 阶段 6），这里不用先建、先交过去。
     */
    public void startRecording() {
        mainHandler.post(() -> {
            RecordingCoordinator coordinator = RecordingCoordinator.get(this);
            RecordingIntent.current().noteUserStarted();
            coordinator.resetBudget();
            coordinator.request(RecordingCoordinator.Why.FLOATING);
        });
    }

    /** 悬浮按钮按了「停止」：人停的，这一趟不再自动接。 */
    public void stopRecording() {
        mainHandler.post(() -> {
            RecordingIntent.current().noteUserStopped();
            RecordingCoordinator.get(this).stop(RecordingStops.Reason.USER);
        });
    }

    public boolean isRecording() {
        return RecordingCoordinator.get(this).isRecording();
    }

    public boolean isServiceRunning() {
        return isServiceRunning;
    }
}
