package com.kooo.evcam.recording;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.MultiCameraManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 「要不要录、能不能录、录起来」这件事。
 *
 * <h3>为什么从 Activity 里搬出来</h3>
 *
 * <p>开始录制原本和「弹哪个 Toast、转哪个圈、按钮写什么字」混在一起。
 * 前者是决策与执行，后者是画面反馈 —— 两件事的正确性判据完全不同：
 * 前者错了会丢录像，后者错了只是看着别扭。混在一个方法里，
 * 想给前者加测试就得先有个 Activity。</p>
 *
 * <p>更实际的一条：Android 会在主题切换、配置变更、内存吃紧时<b>销毁并重建
 * Activity</b>。录制状态住在 Activity 里，就得靠一堆 {@code savedXxx} 变量
 * 在重建时接力。把决策搬出来是往「录制不依赖某个窗口活着」这个方向走的第一步。</p>
 *
 * <h3>这一步没搬什么</h3>
 *
 * <p>计时器、指示器、Toast 仍然由 Activity 做 —— 它们本来就是画面的事。
 * {@code isRecording} 这个字段也还留在 Activity 里（那边有四十多处在读它），
 * 通过回调保持同步；把那些读取一并迁走是下一步的事，不该和这次混在一起。</p>
 */
public class RecordingCoordinator {

    private static final String TAG = "RecordingCoordinator";

    /** 决策的结果告诉谁。实现方负责画面上的反馈。 */
    public interface Listener {
        /**
         * 录起来了。
         *
         * @param cameras     实际参与录制的几路
         * @param sdFellBack  用户选了 U 盘但没插，这次落到了内置存储
         */
        void onRecordingStarted(Set<String> cameras, boolean sdFellBack);

        void onRecordingStopped();

        /** 条件不满足，压根没开始。 */
        void onRecordingRefused(String reason);

        /** 条件满足但相机没起来。 */
        void onRecordingFailed(String reason);
    }

    private final Context context;
    private final AppConfig appConfig;
    private MultiCameraManager cameraManager;
    private Listener listener;

    public RecordingCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
    }

    public void setCameraManager(MultiCameraManager cameraManager) {
        this.cameraManager = cameraManager;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return cameraManager != null && cameraManager.isRecording();
    }

    /**
     * 开始录制。
     *
     * <p>把守的两条：至少选了一路，以及相机确实起来了。
     * 一路都没选时不是「录了个空的」，而是根本不该开始 —— 不想录有它自己的开关。</p>
     */
    public void start() {
        if (cameraManager == null || cameraManager.isRecording()) {
            return;
        }

        // 录哪几路 = 配置里启用了哪几路。这两件事本来就是同一件：
        // 关掉的相机不开、不录、不占流。
        Set<String> cameras = com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context);
        if (cameras.isEmpty()) {
            notifyRefused(context.getString(R.string.msg_keep_one_camera_refuse));
            return;
        }

        // 正常模式下不往内置存储录：行车记录是一直在写的，而车机闪存换不了。
        //
        // 这条规则原先只拦住了「手动按录制」那一条路，另外八处（开机自动录、
        // 悬浮按钮拉起、主题切换后恢复、定时自检、亮屏恢复）都是直接开录的 ——
        // 也就是说，说好的「没有 U 盘就不录」，实际上只有按按钮时才成立。
        // 判断放在这里，九条路才是同一个答案。
        if (StorageHelper.willRecordToInternal(context)
                && !StorageHelper.isInternalStorageAllowed()) {
            notifyRefused(context.getString(R.string.msg_refuse_no_external));
            return;
        }

        // 开发者模式下允许落到内置存储，但要让上层知道这次是回退
        boolean sdFellBack = StorageHelper.isSdCardFallback(context);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());

        if (!cameraManager.startRecording(timestamp, cameras)) {
            notifyFailed("录制失败");
            return;
        }

        // 前台服务：没有它，系统会在应用退到后台后把录制掐掉
        CameraForegroundService.start(context, "正在录制视频", "录制进行中，点击返回应用");
        FloatingWindowService.sendRecordingStateChanged(context, true);

        AppLog.d(TAG, "开始录制 " + cameras.size() + " 路: " + cameras);
        if (listener != null) {
            listener.onRecordingStarted(cameras, sdFellBack);
        }
    }

    public void stop() {
        if (cameraManager == null) {
            return;
        }
        cameraManager.stopRecording();
        CameraForegroundService.stop(context);
        FloatingWindowService.sendRecordingStateChanged(context, false);

        AppLog.d(TAG, "录制已停止，前台服务已关闭");
        if (listener != null) {
            listener.onRecordingStopped();
        }
    }

    private void notifyRefused(String reason) {
        AppLog.w(TAG, "不满足录制条件：" + reason);
        if (listener != null) {
            listener.onRecordingRefused(reason);
        }
    }

    private void notifyFailed(String reason) {
        AppLog.e(TAG, "录制启动失败：" + reason);
        if (listener != null) {
            listener.onRecordingFailed(reason);
        }
    }
}
