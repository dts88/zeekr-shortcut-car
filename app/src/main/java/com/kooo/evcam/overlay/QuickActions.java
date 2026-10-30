package com.kooo.evcam.overlay;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.RecordingIntent;
import com.kooo.evcam.recording.RecordingStops;
import com.kooo.evcam.service.CameraRecordingService;

/**
 * 悬浮按钮和灵动贴按下去做的那件事（{@link FloatingAction}）：两边走这同一份，不各写一套（2026-10-30 灵动贴搬进来时从
 * 悬浮按钮服务里拿出来）。
 *
 * <p>录制那一条按应用在不在前台分三种情况（主界面在前：交给主界面；在后台：直接告诉录像协调器，不拉起主界面；
 * 没在跑：拉起主界面并开录），其余几条都是一句话的事。都在主线程上叫。</p>
 */
public final class QuickActions {

    private static final String TAG = "QuickActions";

    private QuickActions() {
    }

    /**
     * 做一件事。{@code why} 说是谁按的（{@link RecordingCoordinator.Why#FLOATING} 悬浮按钮、{@link RecordingCoordinator.Why#BUTTON}
     * 灵动贴）：录像意图和黑匣子照它记。
     */
    public static void perform(Context context, FloatingAction action, RecordingCoordinator.Why why) {
        switch (action) {
            case TOGGLE_RECORDING:
                toggleRecording(context, why);
                return;
            case TAKE_PHOTO:
                takePhoto(context);
                return;
            case TOGGLE_MIRROR:
                toggleMirror(context);
                return;
            case LOCK_FOOTAGE:
                com.kooo.evcam.storage.AutoLock.get().lockNow(context);
                return;
            case TOGGLE_DRIVE_SOUND:
                toggleDriveSound(context, why);
                return;
            case OPEN_APP:
            default:
                openApp(context);
        }
    }

    /**
     * 开 / 关模拟声浪（设置 → 系统「模拟声浪（试验性）」，2026-10-24 起不归开发者选项管）：开车时一键关的地方。
     * 每按一次回一句现在是开还是关。马上生效（{@code DriveSound.refresh}）。
     */
    private static void toggleDriveSound(Context context, RecordingCoordinator.Why why) {
        AppConfig config = new AppConfig(context);
        boolean on = !config.isDriveSoundEnabled();
        config.setDriveSoundEnabled(on);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: " + (why == RecordingCoordinator.Why.BUTTON ? "灵动贴" : "悬浮按钮") + " → 模拟声浪" + (on ? "开" : "关"));
        com.kooo.evcam.sound.DriveSound.refresh(context);
        Toast.makeText(context, on ? R.string.msg_drive_sound_on : R.string.msg_drive_sound_off,
                Toast.LENGTH_SHORT).show();
    }

    private static void openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    /**
     * 拍一张：和主界面的拍照键走同一个入口（相机层的 {@code takePhoto}）。
     *
     * <p>主界面在不在前台、相机开没开都一样：相机没开就先开（登记表的规则），出了画面再拍，
     * 提示按真的存下了几路说。以前这里发广播给主界面、或者把主界面拉起来等 3 秒碰运气 ——
     * 相机没开时拍不到，却照样提示「已保存」。</p>
     */
    private static void takePhoto(Context context) {
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(context)
                .takePhoto(new com.kooo.evcam.ui.PhotoFeedback(context));
    }

    private static void toggleMirror(Context context) {
        boolean on = new AppConfig(context).isRearViewEnabled();
        if (!OverlayCoordinator.setRearViewEnabled(context, !on)) {
            Toast.makeText(context, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
        }
    }

    // ========== 录制 ==========

    private static void toggleRecording(Context context, RecordingCoordinator.Why why) {
        AppState appState = appState(context);
        AppLog.d(TAG, "toggleRecording (" + why + "), app state: " + appState);
        switch (appState) {
            case FOREGROUND:
                // 主界面在前：交给主界面，和点它的录制键一样
                Intent toggle = new Intent("com.kooo.evcam.action.TOGGLE_RECORDING");
                toggle.setPackage(context.getPackageName());
                context.sendBroadcast(toggle);
                return;
            case BACKGROUND:
                toggleInBackground(context, why);
                return;
            case NOT_RUNNING:
            default:
                // 没在跑：拉起主界面并开录
                Intent intent = new Intent(context, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra("auto_start_recording", true);
                context.startActivity(intent);
                Toast.makeText(context, R.string.msg_starting_recording, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 应用在后台：不拉起主界面，直接告诉录像协调器（在不在录照它此刻的样子：录像意图开着 —— 开录中、在录、等待摄像头 —— 按一下是停）。
     * 人开的，前面的失败都不算了；人停的，这一趟不再自动接。录制服务先起来，和以前一样。
     */
    private static void toggleInBackground(Context context, RecordingCoordinator.Why why) {
        try {
            context.startService(new Intent(context, CameraRecordingService.class));
        } catch (RuntimeException e) {
            // 后台起不了服务（没有前台服务撑着时安卓不让）：录像归协调器管，照样往下走
            AppLog.w(TAG, "startService(CameraRecordingService) failed: " + e);
        }
        RecordingCoordinator coordinator = RecordingCoordinator.get(context);
        if (coordinator.view() != RecordingCoordinator.View.IDLE) {
            RecordingIntent.current().noteUserStopped();
            coordinator.stop(RecordingStops.Reason.USER);
        } else {
            RecordingIntent.current().noteUserStarted();
            coordinator.resetBudget();
            coordinator.request(why);
        }
    }

    private enum AppState {
        /** 主界面在最上面。 */
        FOREGROUND,
        /** 应用的任务在，最上面不是主界面。 */
        BACKGROUND,
        /** 应用的任务不在。 */
        NOT_RUNNING
    }

    private static AppState appState(Context context) {
        android.app.ActivityManager activityManager =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(10);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.baseActivity != null && task.topActivity != null
                            && task.baseActivity.getPackageName().equals(context.getPackageName())) {
                        return MainActivity.class.getName().equals(task.topActivity.getClassName())
                                ? AppState.FOREGROUND : AppState.BACKGROUND;
                    }
                }
            }
        }
        return AppState.NOT_RUNNING;
    }
}
