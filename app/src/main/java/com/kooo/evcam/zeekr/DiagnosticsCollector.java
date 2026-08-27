package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Size;
import android.view.Display;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一次性把「这台车机到底给了我们什么」收集成一份纯文本报告。
 *
 * <p>做这个是因为在车机上逐项手工试太慢：要确认相机有几路、副屏存不存在、
 * 转向灯信号能不能读到、U 盘挂了几个，得来回翻好几个界面还看不全。
 * 这里一次跑完，结果可以直接导出发回来分析。</p>
 *
 * <p>只读，不改任何配置，也不打开相机。</p>
 */
public final class DiagnosticsCollector {

    private static final String TAG = "DiagnosticsCollector";

    /** logcat 抓取的行数上限，避免报告过大。 */
    private static final int LOGCAT_MAX_LINES = 400;

    private DiagnosticsCollector() {
    }

    /**
     * 生成完整诊断报告。可能有 I/O，请在后台线程调用。
     */
    public static String collect(Context context) {
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        sb.append("========================================").append('\n');
        sb.append(" 极氪即刻（车机版）诊断报告").append('\n');
        sb.append(" 生成时间: ").append(now).append('\n');
        sb.append("========================================").append('\n').append('\n');

        appendDevice(sb);
        appendCameras(sb, context);
        appendMultiMapping(sb, context);
        appendDisplays(sb, context);
        appendSignalSources(sb, context);
        appendStorage(sb, context);
        appendConfig(sb, context);
        appendLogcat(sb);

        sb.append('\n').append("===== 报告结束 =====").append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static void appendDevice(StringBuilder sb) {
        sb.append("## 1. 设备").append('\n');
        sb.append("制造商: ").append(Build.MANUFACTURER).append('\n');
        sb.append("型号:   ").append(Build.MODEL).append('\n');
        sb.append("设备:   ").append(Build.DEVICE).append('\n');
        sb.append("产品:   ").append(Build.PRODUCT).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n');
        sb.append("指纹:   ").append(Build.FINGERPRINT).append('\n').append('\n');
    }

    /**
     * 相机能力：这是设计多路配置最需要的一节。
     */
    private static void appendCameras(StringBuilder sb, Context context) {
        sb.append("## 2. 相机").append('\n');
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            sb.append("!! 拿不到 CameraManager").append('\n').append('\n');
            return;
        }
        try {
            String[] ids = cm.getCameraIdList();
            sb.append("相机数量: ").append(ids.length).append('\n').append('\n');

            for (String id : ids) {
                sb.append("--- 相机 ").append(id).append(" ---").append('\n');
                try {
                    CameraCharacteristics cc = cm.getCameraCharacteristics(id);

                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    sb.append("朝向: ").append(describeFacing(facing)).append('\n');

                    Integer level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    sb.append("硬件级别: ").append(describeLevel(level)).append('\n');

                    StreamConfigurationMap map =
                            cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map == null) {
                        sb.append("!! 无 StreamConfigurationMap（可能是虚拟相机）").append('\n').append('\n');
                        continue;
                    }

                    appendSizes(sb, "PRIVATE", map.getOutputSizes(ImageFormat.PRIVATE));
                    appendSizes(sb, "SurfaceTexture", map.getOutputSizes(SurfaceTexture.class));
                    appendSizes(sb, "JPEG", map.getOutputSizes(ImageFormat.JPEG));

                    // 这一路是不是四联合成流？
                    List<Size> composite = ZeekrCompositeProfile.listCompositeCandidates(
                            map.getOutputSizes(SurfaceTexture.class));
                    if (composite.isEmpty()) {
                        composite = ZeekrCompositeProfile.listCompositeCandidates(
                                map.getOutputSizes(ImageFormat.PRIVATE));
                    }
                    if (!composite.isEmpty()) {
                        sb.append(">> 合成流候选: ").append(composite).append('\n');
                        Size best = composite.get(0);
                        sb.append(">> ").append(ZeekrCompositeProfile.describe(best)).append('\n');
                    } else {
                        sb.append(">> 非合成流（普通单画面相机）").append('\n');
                    }
                } catch (Exception e) {
                    sb.append("!! 读取失败: ").append(e).append('\n');
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            sb.append("!! 枚举相机失败: ").append(e).append('\n').append('\n');
        }
    }

    private static void appendSizes(StringBuilder sb, String label, Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            sb.append(label).append(": 无").append('\n');
            return;
        }
        Size[] sorted = Arrays.copyOf(sizes, sizes.length);
        Arrays.sort(sorted, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Long.compare((long) b.getWidth() * b.getHeight(),
                        (long) a.getWidth() * a.getHeight());
            }
        });
        sb.append(label).append(" (").append(sorted.length).append("): ");
        int limit = Math.min(sorted.length, 12);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sorted[i]);
        }
        if (sorted.length > limit) {
            sb.append(" ...(其余 ").append(sorted.length - limit).append(" 个略)");
        }
        sb.append('\n');
    }

    private static String describeFacing(Integer facing) {
        if (facing == null) {
            return "未知";
        }
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK:
                return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "EXTERNAL";
            default:
                return "未知(" + facing + ")";
        }
    }

    private static String describeLevel(Integer level) {
        if (level == null) {
            return "未知";
        }
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            default:
                return "未知(" + level + ")";
        }
    }

    /**
     * 多路配置会怎么分配这些相机 —— 3 路配置显示不出来时，先看这一节。
     */
    private static void appendMultiMapping(StringBuilder sb, Context context) {
        sb.append("## 2.1 多路配置的相机分配").append('\n');
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            sb.append("!! 拿不到 CameraManager").append('\n').append('\n');
            return;
        }
        try {
            ZeekrCameraLocator.Result located = ZeekrCameraLocator.locate(cm);
            String compositeId = located.found() ? located.cameraId : null;
            sb.append("环视合成流: ")
                    .append(compositeId == null ? "未找到" : ("相机 " + compositeId + "  " + located.size))
                    .append('\n');

            String[] ids = cm.getCameraIdList();
            java.util.List<String> others = new java.util.ArrayList<>();
            for (String id : ids) {
                if (!id.equals(compositeId)) {
                    others.add(id);
                }
            }
            sb.append("其余相机: ").append(others).append('\n');
            sb.append("座舱 1 -> ").append(others.size() > 0 ? ("相机 " + others.get(0)) : "无").append('\n');
            sb.append("座舱 2 -> ").append(others.size() > 1 ? ("相机 " + others.get(1)) : "无").append('\n');
            sb.append('\n');
            sb.append("说明：座舱两路是按相机 id 顺序取的，不保证对应后排/驾驶位。").append('\n');
            sb.append("若 3 路配置显示不出画面，需要确认的是：").append('\n');
            sb.append("  a) 上面这两路 id 是不是真的对应后排/驾驶位摄像头；").append('\n');
            sb.append("  b) 车机是否允许同时打开合成流 + 这两路"
                    + "（合成流很大，HAL 可能不支持这种组合）。").append('\n');
            sb.append("  对照：用其他车型配置能显示这两路时，说明相机本身可用，"
                    + "问题就在分配或并发组合上。").append('\n');
        } catch (Exception e) {
            sb.append("!! 分配预览失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 副屏：决定「推送到副屏」这个功能有没有可能。
     */
    private static void appendDisplays(StringBuilder sb, Context context) {
        sb.append("## 3. 显示屏").append('\n');
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            sb.append("!! 拿不到 DisplayManager").append('\n').append('\n');
            return;
        }
        Display[] displays = dm.getDisplays();
        sb.append("屏幕数量: ").append(displays.length).append('\n');
        for (Display d : displays) {
            android.graphics.Point size = new android.graphics.Point();
            try {
                d.getRealSize(size);
            } catch (Exception ignored) {
                // 拿不到尺寸不影响其余信息
            }
            sb.append("  [id=").append(d.getDisplayId()).append("] ")
                    .append(d.getName())
                    .append("  ").append(size.x).append('x').append(size.y)
                    .append("  state=").append(d.getState())
                    .append('\n');
        }
        if (displays.length <= 1) {
            sb.append(">> 只有一块屏，副屏推送不可用").append('\n');
        } else {
            sb.append(">> 检测到多块屏，副屏推送有可能实现").append('\n');
        }
        sb.append('\n');
    }

    /**
     * 转向灯 / 车门信号的三种来源，逐个探测。
     */
    private static void appendSignalSources(StringBuilder sb, Context context) {
        sb.append("## 4. 车辆信号来源").append('\n');

        // 4.1 VHAL gRPC —— 本构建已换成空实现
        sb.append("[VHAL gRPC] ");
        try {
            boolean reachable = com.kooo.evcam.VhalSignalObserver.testConnection();
            sb.append(reachable ? "可达" : "不可达")
                    .append("（注：本构建为空实现，恒为不可达；该服务是吉利专有）").append('\n');
        } catch (Throwable t) {
            sb.append("探测异常: ").append(t).append('\n');
        }

        // 4.2 CarSignalManager —— 吉利车机 API，靠反射看类在不在
        sb.append("[CarSignalManager] ");
        String[] candidates = {
                "com.gwm.carsignal.CarSignalManager",
                "android.car.CarSignalManager",
                "com.geely.carsignal.CarSignalManager",
                "com.ecarx.xsf.car.CarSignalManager",
                "com.zeekr.car.CarSignalManager",
        };
        boolean foundAny = false;
        for (String cls : candidates) {
            try {
                Class.forName(cls);
                sb.append("找到 ").append(cls).append("  ");
                foundAny = true;
            } catch (Throwable ignored) {
                // 不存在就继续试下一个
            }
        }
        if (!foundAny) {
            sb.append("未找到任何已知的 CarSignalManager 类");
        }
        sb.append('\n');

        // 4.3 android.car —— 标准 Android Automotive API
        sb.append("[android.car] ");
        try {
            Class.forName("android.car.Car");
            sb.append("存在（车机支持 Android Automotive Car API，可作为信号来源的候选方向）");
        } catch (Throwable t) {
            sb.append("不存在");
        }
        sb.append('\n');

        // 4.4 logcat 可读性
        sb.append("[logcat] ");
        try {
            Process p = Runtime.getRuntime().exec("logcat -d -t 5");
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            int lines = 0;
            while (reader.readLine() != null && lines < 10) {
                lines++;
            }
            reader.close();
            sb.append(lines > 0 ? "可读（读到 " + lines + " 行）" : "可执行但读不到内容");
        } catch (Exception e) {
            sb.append("不可读: ").append(e);
        }
        sb.append('\n').append('\n');
    }

    private static void appendStorage(StringBuilder sb, Context context) {
        sb.append("## 5. 存储").append('\n');
        try {
            java.io.File internal = context.getExternalFilesDir(null);
            if (internal != null) {
                sb.append("内部存储: ").append(internal.getAbsolutePath())
                        .append("  剩余 ").append(StorageHelper.formatSize(internal.getUsableSpace()))
                        .append(" / ").append(StorageHelper.formatSize(internal.getTotalSpace()))
                        .append('\n');
            }
            List<StorageHelper.VolumeInfo> volumes = StorageHelper.listExternalVolumes(context);
            sb.append("外置存储卷数量: ").append(volumes.size()).append('\n');
            for (StorageHelper.VolumeInfo v : volumes) {
                sb.append("  ").append(v.describe()).append('\n');
                sb.append("     root=").append(v.root.getAbsolutePath()).append('\n');
                sb.append("     appDir=").append(v.appDir.getAbsolutePath()).append('\n');
            }
        } catch (Exception e) {
            sb.append("!! 读取存储信息失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    private static void appendConfig(StringBuilder sb, Context context) {
        sb.append("## 6. 当前配置").append('\n');
        try {
            AppConfig cfg = new AppConfig(context);
            sb.append("车型: ").append(cfg.getCarModel()).append('\n');
            sb.append("摄像头数量: ").append(cfg.getCameraCount()).append('\n');
            sb.append("录制画面排列: ").append(cfg.getRecordLayout()).append('\n');
            sb.append("录制帧率: ").append(cfg.getRecordFps()).append('\n');
            sb.append("预览低分辨率: ").append(cfg.isDecouplePreviewEnabled()).append('\n');
            sb.append("目标分辨率: ").append(cfg.getTargetResolution()).append('\n');
            sb.append("录制模式: ").append(cfg.getRecordingMode())
                    .append("  (实际用 Codec: ").append(cfg.shouldUseCodecRecording()).append(')').append('\n');
            sb.append("存储位置: ").append(cfg.getStorageLocation()).append('\n');
            sb.append("指定卷路径: ").append(
                    cfg.getCustomSdCardPath() == null ? "(自动)" : cfg.getCustomSdCardPath()).append('\n');
        } catch (Exception e) {
            sb.append("!! 读取配置失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 抓一段本应用的 logcat，便于排查启动/相机错误。
     */
    private static void appendLogcat(StringBuilder sb) {
        sb.append("## 7. 最近日志（本应用相关）").append('\n');
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-v", "time", "-t", String.valueOf(LOGCAT_MAX_LINES)});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> kept = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("evcam") || line.contains("EVCam")
                        || line.contains("Zeekr") || line.contains("Composite")
                        || line.contains("FourLane") || line.contains("Camera")
                        || line.contains("Signal") || line.contains("Codec")) {
                    kept.add(line);
                }
            }
            reader.close();
            if (kept.isEmpty()) {
                sb.append("(未抓到相关日志)").append('\n');
            } else {
                int from = Math.max(0, kept.size() - LOGCAT_MAX_LINES);
                for (int i = from; i < kept.size(); i++) {
                    sb.append(kept.get(i)).append('\n');
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "抓取 logcat 失败", e);
            sb.append("!! 抓取失败（车机可能不允许应用读取 logcat）: ").append(e).append('\n');
        }
    }
}
