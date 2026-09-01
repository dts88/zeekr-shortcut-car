package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

/**
 * 全局单例，持有 MultiCameraManager 实例。
 * 允许在后台（Service）中初始化摄像头，不依赖 MainActivity。
 * TextureView 可以在 MainActivity 打开后再绑定。
 */
public class CameraManagerHolder {
    private static final String TAG = "CameraManagerHolder";
    private static CameraManagerHolder instance;
    private MultiCameraManager cameraManager;
    /**
     * 这份实例是按哪个车型建的。
     *
     * <p>切换车型只提示「重启应用后生效」，但前台服务会让进程一直活着，
     * 这个单例连同它的摄像头映射也就跟着留下来了。不记住车型的话，
     * 换了配置仍然会复用旧映射 —— 表现就是画面全黑。</p>
     */
    private String initializedCarModel;

    private CameraManagerHolder() {}

    public static synchronized CameraManagerHolder getInstance() {
        if (instance == null) {
            instance = new CameraManagerHolder();
        }
        return instance;
    }

    /**
     * 获取已初始化的 MultiCameraManager，如果未初始化则在后台初始化（TextureView=null）。
     * 可从 Service 或 Activity 调用。
     */
    public synchronized MultiCameraManager getOrInit(Context context) {
        String currentModel = new AppConfig(context).getCarModel();
        if (cameraManager != null && !cameraManager.isReleased()
                && initializedCarModel != null && !initializedCarModel.equals(currentModel)) {
            AppLog.w(TAG, "车型已从 " + initializedCarModel + " 改为 " + currentModel
                    + "，丢弃旧的摄像头映射并重建");
            cameraManager.release();
            cameraManager = null;
        }
        if (cameraManager != null && !cameraManager.isReleased()) {
            return cameraManager;
        }

        if (cameraManager != null) {
            AppLog.w(TAG, "Holder 中的 CameraManager 已被 release，丢弃并重新创建");
            cameraManager = null;
        }

        AppLog.d(TAG, "后台初始化摄像头（无 TextureView）...");
        AppConfig appConfig = new AppConfig(context);

        cameraManager = new MultiCameraManager(context.getApplicationContext());

        // 获取摄像头数量
        int cameraCount = getCameraCount(appConfig);
        cameraManager.setMaxOpenCameras(cameraCount);

        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                AppLog.e(TAG, "CameraManager service not available");
                return cameraManager;
            }
            String[] cameraIds = cm.getCameraIdList();
            if (cameraIds.length == 0) {
                AppLog.e(TAG, "No cameras available");
                return cameraManager;
            }

            // 根据车型配置初始化摄像头（TextureView 全部传 null）
            initCamerasByCarModel(appConfig, cm, cameraIds);

            // 设置录制模式
            boolean useCodecRecording = appConfig.shouldUseCodecRecording();
            cameraManager.setCodecRecordingMode(useCodecRecording);

            // 注意：不在后台调用 openAllCameras()
            // 部分设备/系统会禁止后台应用访问摄像头（CAMERA_DISABLED by policy）
            // 摄像头会在悬浮窗设置 Surface 并调用 recreateSession 时按需打开

            initializedCarModel = currentModel;
            AppLog.d(TAG, "后台摄像头对象初始化完成，共 " + cameraCount
                    + " 个摄像头（车型 " + currentModel + "，未打开硬件）");
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "后台初始化摄像头失败: " + e.getMessage());
        }

        return cameraManager;
    }

    /**
     * 获取已初始化的 MultiCameraManager（不自动初始化）
     */
    public synchronized MultiCameraManager getCameraManager() {
        return cameraManager;
    }

    /**
     * 设置已有的 MultiCameraManager（由 MainActivity 初始化时调用）
     */
    public synchronized void setCameraManager(MultiCameraManager manager) {
        this.cameraManager = manager;
    }

    /**
     * 释放资源
     */
    public synchronized void release() {
        if (cameraManager != null) {
            cameraManager.release();
            cameraManager = null;
        }
        initializedCarModel = null;
    }

    /** 这份实例是按哪个车型建的；未初始化时为 null。 */
    public synchronized String getInitializedCarModel() {
        return initializedCarModel;
    }

    private int getCameraCount(AppConfig appConfig) {
        String carModel = appConfig.getCarModel();
        if (AppConfig.CAR_MODEL_ZEEKR_7X_MULTI.equals(carModel)) {
            return 3; // 环视 + 两路座舱
        } else if (appConfig.isCustomCarModel()) {
            return appConfig.getCameraCount();
        }
        return 1; // 极氪7X：一路合成流，也是兜底
    }

    /**
     * 按车型建立摄像头映射（与 MainActivity 同一套，但 TextureView 全部传 null）。
     *
     * <p>只剩三种：{@code getCarModel()} 读出来已经 sanitize 过，
     * 设置里列的就那三项，别的值会被拨回 zeekr_7x。银河 E5/L7、星舰7、
     * 手机模式那几个分支走不到，连同它们的映射方法一起删了。</p>
     */
    private void initCamerasByCarModel(AppConfig appConfig, CameraManager cm, String[] cameraIds) {
        String carModel = appConfig.getCarModel();

        if (AppConfig.CAR_MODEL_ZEEKR_7X_MULTI.equals(carModel)) {
            initCamerasForZeekrMulti(appConfig, cm, cameraIds);
        } else if (appConfig.isCustomCarModel()) {
            initCamerasForCustomModel(appConfig, cameraIds);
        } else {
            initCamerasForZeekrComposite(cm, cameraIds);
        }
    }

    /**
     * 极氪7X 后台初始化：只开一路合成流。
     *
     * <p>与前台一样按能力查找提供合成流的相机，避免后台悬浮窗打开错误的那一路。
     * 找不到时退回第一个相机，行为与前台保持一致。</p>
     */
    private void initCamerasForZeekrComposite(CameraManager cm, String[] cameraIds) {
        if (cameraIds.length == 0) {
            return;
        }
        com.kooo.evcam.zeekr.ZeekrCameraLocator.Result located =
                com.kooo.evcam.zeekr.ZeekrCameraLocator.locate(cm);
        String cameraId = located.found() ? located.cameraId : cameraIds[0];
        AppLog.i(TAG, "后台合成流相机: " + cameraId
                + (located.found() ? " (" + located.size + ")" : " (未找到合成流，已退回)"));
        cameraManager.initCameras(
                cameraId, null, null, null,
                null, null, null, null);
        if (located.found()) {
            SingleCamera cam = cameraManager.getCamera("front");
            if (cam != null) {
                cam.setPreferredSize(located.size);
            }
        }
    }

    /**
     * 极氪7X 多路的后台初始化。
     *
     * <p>与前台共用 {@link com.kooo.evcam.zeekr.ZeekrMultiPlan}。以前这里各写了一份，
     * 规则还不一致 —— 后台这份根本不看手动指定的相机映射，于是同一台车前后台分配
     * 出来的槽位可能不同。</p>
     *
     * <p>环视这一路同样钉住合成流尺寸，与前台一致 —— 否则前后台两条初始化路径会
     * 协商出不同的分辨率，同一台车表现还不一样。</p>
     */
    private void initCamerasForZeekrMulti(AppConfig cfg, CameraManager cm, String[] cameraIds) {
        if (cameraIds.length == 0) {
            return;
        }
        com.kooo.evcam.zeekr.ZeekrCameraLocator.Result located =
                com.kooo.evcam.zeekr.ZeekrCameraLocator.locate(cm);

        com.kooo.evcam.zeekr.ZeekrMultiPlan plan = com.kooo.evcam.zeekr.ZeekrMultiPlan.build(
                cameraIds,
                located.found() ? located.cameraId : null,
                cfg.getCameraOverride("front"),
                cfg.getCameraOverride("back"),
                cfg.getCameraOverride("left"));

        cameraManager.initCameras(
                plan.compositeId, null,
                plan.cabin1Id, null,
                plan.cabin2Id, null,
                null, null);
        // 只钉住环视，座舱两路仍按全局目标分辨率各自挑
        if (plan.compositeIsReal && located.found()) {
            SingleCamera cam = cameraManager.getCamera("front");
            if (cam != null) {
                cam.setPreferredSize(located.size);
            }
        }
        AppLog.i(TAG, "后台极氪多路映射: " + plan);
    }

    private void initCamerasForCustomModel(AppConfig appConfig, String[] cameraIds) {
        String frontId = appConfig.getCameraId("front");
        String backId = appConfig.getCameraId("back");
        String leftId = appConfig.getCameraId("left");
        String rightId = appConfig.getCameraId("right");

        int count = appConfig.getCameraCount();
        switch (count) {
            case 1:
                cameraManager.initCameras(frontId, null, null, null, null, null, null, null);
                break;
            case 2:
                cameraManager.initCameras(frontId, null, backId, null, null, null, null, null);
                break;
            default:
                cameraManager.initCameras(frontId, null, backId, null, leftId, null, rightId, null);
                break;
        }
    }
}
