package com.kooo.evcam.zeekr;

import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
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

        // 每一节计时：生成报告时界面会卡一下，先弄清是哪一节慢
        Timings timings = new Timings();
        timings.run("device", () -> appendDevice(sb));
        timings.run("cameras", () -> appendCameras(sb, context));
        timings.run("frame rates", () -> appendPreviewFrameRates(sb));
        timings.run("stall watch", () -> appendStallWatch(sb, context));
        timings.run("multi mapping", () -> appendMultiMapping(sb, context));
        timings.run("displays", () -> appendDisplays(sb, context));
        timings.run("vehicle signals", () -> appendSignalSources(sb, context));
        timings.run("shutdown probe", () -> appendShutdownProbe(sb, context));
        timings.run("storage", () -> appendStorage(sb, context));
        timings.run("config", () -> appendConfig(sb, context));
        timings.run("floating layout", () -> appendFloatingLayout(sb, context));
        timings.run("encoders", () -> appendEncoders(sb, context));
        timings.run("lane transforms", () -> appendLaneTransforms(sb, context));
        timings.run("share", () -> com.kooo.evcam.share.ShareDiagnostics.appendTo(sb, context));
        timings.run("playback", () -> PlaybackCapabilityProbe.appendTo(sb, context));
        timings.run("vehicle enumeration", () -> VehicleEnumeration.appendTo(sb, context));
        timings.run("logcat", () -> appendLogcat(sb));

        String took = timings.describe();
        sb.append('\n').append("## 生成耗时（从长到短）").append('\n').append(took).append('\n');
        AppLog.i(TAG, "report sections: " + took.replace('\n', ' '));

        sb.append('\n').append("===== 报告结束 =====").append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------

    /** 每一节用了多久。 */
    private static final class Timings {
        private final List<String> names = new ArrayList<>();
        private final List<Long> millis = new ArrayList<>();
        private long total;

        void run(String name, Runnable section) {
            long start = android.os.SystemClock.uptimeMillis();
            try {
                section.run();
            } finally {
                long took = android.os.SystemClock.uptimeMillis() - start;
                names.add(name);
                millis.add(took);
                total += took;
            }
        }

        /** 按用时从长到短。 */
        String describe() {
            Integer[] order = new Integer[names.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Long.compare(millis.get(b), millis.get(a)));
            StringBuilder out = new StringBuilder("total " + total + "ms");
            for (int i : order) {
                out.append('\n').append("  ").append(names.get(i)).append(' ')
                        .append(millis.get(i)).append("ms");
            }
            return out.toString();
        }
    }

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
                    appendFpsRanges(sb, cc);

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

    /**
     * 相机声明的目标帧率范围。
     *
     * <p>「这几路最高能跑多少帧」以前报告里没有 —— 而设置里那个「原始帧率 25」
     * 是代码里写死的假设，不是从相机读来的。两者对不上时，录出来的文件会是第三个数。</p>
     */
    /**
     * 各路预览此刻的实测出帧率。
     *
     * <p>不需要录制就有数，所以它是这条视频流本身的上限 ——
     * 录制时只会更低。设置里那个帧率是我们这边的天花板，压不高它。</p>
     */
    private static void appendPreviewFrameRates(StringBuilder sb) {
        sb.append("## 2.2 各路实测出帧率（预览，不含录制）").append('\n');
        sb.append(com.kooo.evcam.camera.PreviewFrameRates.describe()).append('\n');
        sb.append("说明: 这是相机送出来的帧率，录制只会更低。").append('\n').append('\n');
    }

    private static void appendStallWatch(StringBuilder sb, Context context) {
        sb.append("## 2.3 卡顿监测（后视镜 / 录制卡住时自动留下的现场）").append('\n');
        try {
            // 页面放不下太长的文字，报告只带最新的一段；完整的在「保存日志」里
            sb.append(com.kooo.evcam.camera.StallWatch.exportText(context, 64 * 1024)).append('\n');
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 熄火时车机有没有通知过我们。
     *
     * <p>这一节是为了回答一个很具体的问题：断电前能不能先把正在录的那一段正常关掉。
     * 能收到关机广播就能，收不到就只能靠事后修索引。</p>
     */
    private static void appendShutdownProbe(StringBuilder sb, Context context) {
        sb.append("## 2.4 关机信号探测（熄火时车机有没有通知应用）").append('\n');
        try {
            sb.append(ShutdownProbe.describe(context));
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    private static void appendFpsRanges(StringBuilder sb, CameraCharacteristics cc) {
        android.util.Range<Integer>[] ranges =
                cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            sb.append("  帧率范围: 未声明").append('\n');
            return;
        }
        StringBuilder line = new StringBuilder();
        int highest = 0;
        for (android.util.Range<Integer> range : ranges) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(range.getLower()).append('-').append(range.getUpper());
            highest = Math.max(highest, range.getUpper());
        }
        sb.append("  帧率范围 (").append(ranges.length).append("): ")
                .append(line).append("   最高 ").append(highest).append(" fps").append('\n');
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
            // 权威答案：系统直接告诉我们哪些相机组合可以同时打开
            sb.append('\n');
            sb.append("可并发打开的相机组合（系统 API）: ");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    java.util.Set<java.util.Set<String>> sets = cm.getConcurrentCameraIds();
                    if (sets == null || sets.isEmpty()) {
                        sb.append("系统未声明任何并发组合").append('\n');
                        sb.append("  >> 注意：这只说明 HAL 没有「保证」任何组合，"
                                + "不等于不允许并发。").append('\n');
                        sb.append("  >> 实测：自定义配置下三路画面可以同时显示，"
                                + "所以并发本身是可行的，只是没写进声明。").append('\n');
                    } else {
                        sb.append(sets).append('\n');
                        boolean tripleOk = false;
                        for (java.util.Set<String> set : sets) {
                            if (compositeId != null && set.contains(compositeId) && set.size() >= 3) {
                                tripleOk = true;
                                break;
                            }
                        }
                        sb.append("  >> 含合成流的三路组合: ")
                                .append(tripleOk ? "受支持" : "未在声明之列").append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("查询失败: ").append(t).append('\n');
                }
            } else {
                sb.append("需要 Android 11 以上才有该 API").append('\n');
            }
            sb.append('\n');

            sb.append("说明：座舱两路是按相机 id 顺序取的，不保证对应后排/驾驶位。").append('\n');
            sb.append("若 3 路配置显示不出画面，需要确认的是：").append('\n');
            sb.append("  a) 上面这两路 id 是不是真的对应后排/驾驶位摄像头；").append('\n');
            sb.append("  b) 三路同开时合成流会不会被降级或拒绝"
                    + "（它比另外两路大得多）。").append('\n');
            sb.append("  已知：自定义配置下三路可以同时出画面，"
                    + "所以相机本身与并发都不是障碍。").append('\n');
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
     * 车辆信号探测。
     *
     * <p>之前这里按猜出来的类名找 CarSignalManager，全都没找到就下了结论 ——
     * 那是错的。真正的读法见 {@link VehicleSignalProbe}。</p>
     */
    private static void appendSignalSources(StringBuilder sb, Context context) {
        VehicleSignalProbe.appendTo(sb, context);
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
            // 帧率、码率、分段、每一路的尺寸都在配置里，整份摆出来比逐项抄一遍准
            sb.append(new com.kooo.evcam.profile.ProfileStore(context).current()).append('\n');
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
     * 悬浮窗与悬浮按钮的位置、大小。
     *
     * <p>用途是「把手动调好的位置定为新的默认值」：先在车机上把各个悬浮元素拖到
     * 合适的位置和大小，再导出这份报告，把本节的数字发回来，就能写进代码当默认值。</p>
     *
     * <p>所以这里同时给出<b>当前值</b>与<b>现行默认值</b> —— 只有能看出差别，
     * 才知道哪些需要改。位置是像素，同时附上屏幕尺寸与密度，换算才有依据。</p>
     */
    private static void appendFloatingLayout(StringBuilder sb, Context context) {
        sb.append("## 7. 悬浮窗位置与大小").append('\n');
        sb.append("把悬浮元素拖到合适位置后导出本报告，把这一节发回即可设为默认值。").append('\n');
        try {
            AppConfig cfg = new AppConfig(context);

            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            sb.append('\n').append("[屏幕]").append('\n');
            sb.append("     应用可用区域: ").append(dm.widthPixels).append(" x ")
                    .append(dm.heightPixels).append(" px（不含状态栏/导航栏）").append('\n');
            // 悬浮窗用的是整屏坐标，只报可用区域会让人把位置算错一整条系统栏
            try {
                android.view.WindowManager wm = (android.view.WindowManager)
                        context.getSystemService(Context.WINDOW_SERVICE);
                android.util.DisplayMetrics real = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(real);
                sb.append("     整屏: ").append(real.widthPixels).append(" x ")
                        .append(real.heightPixels).append(" px（悬浮窗坐标用这个）").append('\n');
            } catch (Throwable t) {
                sb.append("     整屏尺寸读取失败: ").append(t).append('\n');
            }
            sb.append("     密度: ").append(dm.density).append("  (1dp = ")
                    .append(dm.density).append("px, densityDpi=").append(dm.densityDpi)
                    .append(")").append('\n');

            sb.append('\n').append("[主屏悬浮窗（摄像头画面窗口）]").append('\n');
            sb.append("     开关: ").append(cfg.isMainFloatingEnabled() ? "开" : "关").append('\n');
            sb.append("     摄像头: ").append(cfg.getMainFloatingCamera()).append('\n');
            appendValueVsDefault(sb, "位置 X", cfg.getMainFloatingX(), 100);
            appendValueVsDefault(sb, "位置 Y", cfg.getMainFloatingY(), 100);
            appendValueVsDefault(sb, "宽度", cfg.getMainFloatingWidth(), 480);
            appendValueVsDefault(sb, "高度", cfg.getMainFloatingHeight(), 320);

            sb.append('\n').append("[录制悬浮按钮（开始/停止录制）]").append('\n');
            int rx = cfg.getRecordingFloatingX();
            int ry = cfg.getRecordingFloatingY();
            sb.append("     开关: ").append(cfg.isRecordingFloatingEnabled() ? "开" : "关").append('\n');
            if (rx < 0 || ry < 0) {
                sb.append("     位置: 尚未拖动过（使用内置默认位置）").append('\n');
            } else {
                sb.append("     位置 X = ").append(rx).append(" px").append('\n');
                sb.append("     位置 Y = ").append(ry).append(" px").append('\n');
            }
            sb.append("     按钮大小 = ").append(cfg.getRecordingFloatingButtonSizeDp())
                    .append(" dp").append('\n');
            sb.append("     时间字号 = ").append(cfg.getRecordingFloatingTimeTextSizeSp())
                    .append(" sp").append('\n');

            // 这一组键（floating_window_*）里现在只剩透明度还在用，
            // 它画的是 FloatingButtonView —— 一个点击打开应用、随录制状态变色的
            // 悬浮按钮，不是画中画小窗。上一版这里的名字是错的。
            sb.append('\n').append("[悬浮按钮（打开应用/状态指示）]").append('\n');
            int fx = cfg.getFloatingWindowX();
            int fy = cfg.getFloatingWindowY();
            if (fx < 0 || fy < 0) {
                sb.append("     位置: 尚未拖动过（使用内置默认位置）").append('\n');
            } else {
                sb.append("     位置 X = ").append(fx).append(" px").append('\n');
                sb.append("     位置 Y = ").append(fy).append(" px").append('\n');
            }
            sb.append("     大小档位 = ").append(cfg.getFloatingWindowSize()).append('\n');
            sb.append("     透明度 = ").append(cfg.getFloatingWindowAlpha()).append('\n');

            sb.append('\n').append(">> 要把当前位置设为默认值，请把以上三组数字连同屏幕尺寸一起发回。")
                    .append('\n');
            sb.append(">> 注意：位置是像素值，只在同尺寸屏幕上通用；屏幕尺寸变了需要重新取。")
                    .append('\n');
        } catch (Throwable t) {
            sb.append("!! 读取失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    /** 当前值与默认值并排显示；相同就标出来，一眼能看出哪些是手动调过的。 */
    private static void appendValueVsDefault(StringBuilder sb, String label, int value, int fallback) {
        sb.append("     ").append(label).append(" = ").append(value);
        if (value == fallback) {
            sb.append("  (与当前默认值相同)");
        } else {
            sb.append("  (当前默认值 ").append(fallback).append(")");
        }
        sb.append('\n');
    }

    /**
     * 抓一段本应用的 logcat，便于排查启动/相机错误。
     */
    /**
     * 硬件编码器到底允许什么。
     *
     * <h3>为什么要把这些数抄出来</h3>
     *
     * <p>码率上限一直是代码里写死的一个数（上游给小得多的画面定的 8 Mbps），
     * 从来没有对照过这台车机自己声明的范围。同理，录制曾经写死 HEVC Level 4 ——
     * 而 Level 4 的最大画面是 1920×1080，环视四宫格是它的三倍。这两件事在车上
     * 都看不出来：编码器不会报错，只会安静地把画质压下去。</p>
     *
     * <p>所以这一节只做一件事：把编码器<b>自己声明</b>的尺寸范围、码率范围和
     * Profile/Level 抄出来。有了这几行，「20 Mbps 会不会被夹」就不再是猜的。</p>
     */
    /**
     * 每一路的摆位现在是什么状态。
     *
     * <h3>为什么要把这个印出来</h3>
     *
     * <p>座舱旋转连修三次都「没反应」，而三次的原因各不相同：第一次被别处的矩阵
     * 盖掉，第二次代码没跑到，第三次判断条件是车型而车型不是想的那个值。
     * 每一次都要再发一版才知道猜错没有。</p>
     *
     * <p>这几行让它当场可见：配置里有没有这一格、那一格写着什么、最近一次摆位
     * 做成了什么。下次再「没反应」，报告里就有答案。</p>
     */
    private static void appendLaneTransforms(StringBuilder sb, Context context) {
        sb.append("## 8.1 每一路的摆位").append('\n');
        try {
            com.kooo.evcam.profile.Profile profile =
                    new com.kooo.evcam.profile.ProfileStore(context).current();
            for (String key : new String[]{"front", "back", "left", "right"}) {
                String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(key);
                if (role == null) {
                    continue;
                }
                com.kooo.evcam.profile.CameraProfile camera = profile.camera(role);
                sb.append(key).append(" (").append(role).append("): ");
                if (camera == null || camera.lanes.isEmpty()) {
                    sb.append("配置里没有这一路").append('\n');
                    continue;
                }
                for (com.kooo.evcam.profile.LaneLayout lane : camera.lanes) {
                    sb.append('\n').append("    ").append(lane);
                }
                sb.append('\n');
                com.kooo.evcam.camera.MultiCameraManager manager = com.kooo.evcam.camera
                        .CameraManagerHolder.getInstance().getCameraManager();
                com.kooo.evcam.camera.SingleCamera single =
                        manager == null ? null : manager.getCamera(key);
                sb.append("    最近一次摆位: ")
                        .append(single == null ? "相机没起来" : single.getLaneTransformNote())
                        .append('\n');
            }
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    private static void appendEncoders(StringBuilder sb, Context context) {
        sb.append("## 8. 硬件编码器能力").append('\n');
        int[] target = encodeTarget(context);
        if (target != null) {
            sb.append("环视这一路要编的尺寸: ").append(target[0]).append('x').append(target[1])
                    .append("  (").append((long) target[0] * target[1] / 10000).append(" 万像素)")
                    .append('\n');
        }
        appendEncoder(sb, MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 / HEVC", target);
        appendEncoder(sb, MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 / AVC", target);
        sb.append('\n');
    }

    /** 环视那一路最终送进编码器的尺寸；算不出来返回 null。 */
    private static int[] encodeTarget(Context context) {
        try {
            int[] max = com.kooo.evcam.profile.ProfileSizes.declaredMax(
                    context, com.kooo.evcam.profile.CameraProfile.ROLE_COMPOSITE);
            if (max == null) {
                return null;
            }
            com.kooo.evcam.camera.EncodeSize size = com.kooo.evcam.camera.EncodeSize.forSource(
                    com.kooo.evcam.zeekr.StreamLayoutTable.compositeCameraId(), max[0], max[1], true);
            return size.width > 0 ? new int[]{size.width, size.height} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendEncoder(StringBuilder sb, String mime, String label, int[] target) {
        sb.append("### ").append(label).append('\n');
        boolean found = false;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    continue;
                }
                boolean matches = false;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }
                found = true;
                String name = info.getName();
                boolean software = name.contains("c2.android") || name.contains("OMX.google");
                sb.append(name).append(software ? "  (软编码)" : "  (硬编码)").append('\n');
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null) {
                    sb.append("  尺寸: ").append(video.getSupportedWidths()).append(" x ")
                            .append(video.getSupportedHeights()).append('\n');
                    sb.append("  码率: ").append(video.getBitrateRange()).append(" bps")
                            .append('\n');
                    if (target != null) {
                        boolean ok = false;
                        try {
                            ok = video.isSizeSupported(target[0], target[1]);
                        } catch (Exception ignored) {
                            // 有的实现对超范围的尺寸直接抛，那就是「不支持」
                        }
                        sb.append("  能编 ").append(target[0]).append('x').append(target[1])
                                .append(": ").append(ok ? "能" : "不能").append('\n');
                    }
                }
                MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;
                if (levels != null && levels.length > 0) {
                    sb.append("  声明的 Profile/Level: ");
                    for (int i = 0; i < levels.length; i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(levels[i].profile).append('/').append(levels[i].level);
                    }
                    sb.append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        if (!found) {
            sb.append("(这台设备没有这种编码器)").append('\n');
        }
    }

    private static void appendLogcat(StringBuilder sb) {
        sb.append("## 9. 最近日志（本应用相关）").append('\n');
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
