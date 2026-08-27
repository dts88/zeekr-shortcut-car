package com.kooo.evcam.zeekr;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在车机的所有 Camera2 设备里，找出真正提供四联合成流的那一个。
 *
 * <p>App Lab 里摄像头的编号并不固定，写死 {@code cameraIds[0]} 很容易开到一个
 * 普通摄像头或者虚拟设备。这里改为<b>按能力查找</b>：遍历每个相机声明的输出尺寸，
 * 谁声明了合成流尺寸就用谁。找不到就如实返回空结果，让界面提示用户，
 * 而不是随便挑一个然后黑屏。</p>
 */
public final class ZeekrCameraLocator {

    private static final String TAG = "ZeekrCameraLocator";

    private ZeekrCameraLocator() {
    }

    /** 查找结果。 */
    public static final class Result {
        /** 提供合成流的相机 id；未找到时为 null。 */
        public final String cameraId;
        /** 选中的合成流尺寸；未找到时为 null。 */
        public final Size size;
        /** 该相机声明的全部合成流候选尺寸，供设置页手动切换。 */
        public final List<Size> candidates;
        /** 诊断信息，直接显示给用户。 */
        public final String diagnostics;

        Result(String cameraId, Size size, List<Size> candidates, String diagnostics) {
            this.cameraId = cameraId;
            this.size = size;
            this.candidates = candidates;
            this.diagnostics = diagnostics;
        }

        public boolean found() {
            return cameraId != null && size != null;
        }
    }

    /**
     * 遍历所有相机，找出提供合成流的那一个。
     *
     * <p>优先返回声明了<b>已知实测尺寸</b>的相机；都没有的话，退而选择声明了
     * 长条尺寸的相机。全都没有则返回未找到。</p>
     */
    public static Result locate(CameraManager cameraManager) {
        if (cameraManager == null) {
            return new Result(null, null, new ArrayList<Size>(), "CameraManager 为空");
        }

        StringBuilder log = new StringBuilder();
        String fallbackId = null;
        Size fallbackSize = null;
        List<Size> fallbackCandidates = new ArrayList<>();

        try {
            String[] ids = cameraManager.getCameraIdList();
            log.append("共发现 ").append(ids.length).append(" 个相机\n");

            for (String id : ids) {
                Size[] declared = declaredSizes(cameraManager, id);
                if (declared == null || declared.length == 0) {
                    log.append("  相机 ").append(id).append("：未声明输出尺寸\n");
                    continue;
                }

                List<Size> candidates = ZeekrCompositeProfile.listCompositeCandidates(declared);
                if (candidates.isEmpty()) {
                    log.append("  相机 ").append(id).append("：无合成流尺寸（共 ")
                            .append(declared.length).append(" 个尺寸）\n");
                    continue;
                }

                Size chosen = ZeekrCompositeProfile.selectCompositeSize(declared);
                log.append("  相机 ").append(id).append("：候选 ").append(candidates)
                        .append(" -> 选用 ").append(chosen).append('\n');

                if (ZeekrCompositeProfile.isKnownSize(chosen)) {
                    log.append("命中已知实测尺寸，使用相机 ").append(id);
                    return new Result(id, chosen, candidates, log.toString());
                }
                if (fallbackId == null) {
                    fallbackId = id;
                    fallbackSize = chosen;
                    fallbackCandidates = candidates;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            AppLog.e(TAG, "枚举相机失败", e);
            log.append("枚举相机失败: ").append(e.getMessage());
            return new Result(null, null, new ArrayList<Size>(), log.toString());
        }

        if (fallbackId != null) {
            log.append("未命中已知尺寸，改用推断结果：相机 ").append(fallbackId);
            return new Result(fallbackId, fallbackSize, fallbackCandidates, log.toString());
        }

        log.append("本机未提供四联合成流，无法使用极氪合成模式");
        AppLog.w(TAG, log.toString());
        return new Result(null, null, new ArrayList<Size>(), log.toString());
    }

    /**
     * 取一个相机声明的全部输出尺寸。
     *
     * <p>合并 PRIVATE 与 SurfaceTexture 两条清单：不同车机 HAL 把合成流挂在其中
     * 一条上的情况都出现过，合并后再去重可以少漏一种。</p>
     */
    private static Size[] declaredSizes(CameraManager cameraManager, String cameraId) {
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Set<Size> merged = new LinkedHashSet<>();
            addAll(merged, map.getOutputSizes(ImageFormat.PRIVATE));
            addAll(merged, map.getOutputSizes(SurfaceTexture.class));
            return merged.toArray(new Size[0]);
        } catch (CameraAccessException | IllegalArgumentException | RuntimeException e) {
            AppLog.w(TAG, "读取相机 " + cameraId + " 能力失败: " + e.getMessage());
            return null;
        }
    }

    private static void addAll(Set<Size> target, Size[] sizes) {
        if (sizes == null) {
            return;
        }
        for (Size size : sizes) {
            if (size != null) {
                target.add(size);
            }
        }
    }
}
