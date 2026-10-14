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
import com.kooo.evcam.camera.StepStats;
import com.kooo.evcam.screen.DisplayWatch;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.screen.VehicleScreen;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

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

    /** 报告里最多留多少行 logcat。 */
    private static final int LOGCAT_MAX_LINES = 400;
    /**
     * 从 logcat 读多少行再滤。本进程的 logcat 大半是容器和编解码框架的话
     * （见 {@code LogcatNoise}），只读最后 400 行的话，滤完只剩几秒。
     */
    private static final int LOGCAT_READ_LINES = 4000;

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
        timings.run("screen", () -> appendScreen(sb));
        timings.run("black box", () -> appendBlackBox(sb, context));
        timings.run("storage", () -> appendStorage(sb, context));
        timings.run("config", () -> appendConfig(sb, context));
        timings.run("vehicle signals", () -> appendVehicleSignals(sb));
        timings.run("lane transforms", () -> appendLaneTransforms(sb, context));
        timings.run("share", () -> com.kooo.evcam.share.ShareDiagnostics.appendTo(sb, context));
        timings.run("recordings", () -> RecentRecordings.appendTo(sb, context));
        timings.run("logcat", () -> appendLogcat(sb));
        timings.run("app warnings", () -> appendAppWarnings(sb, context));

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
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        sb.append(">> 前置：系统会把给预览、录像的画面左右翻一次；")
                                .append("App 在预览、录像、预览抓图三处各翻回一次，正常视角 = 不镜像")
                                .append('\n');
                    }

                    Integer level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    sb.append("硬件级别: ").append(describeLevel(level)).append('\n');
                    // 座舱镜头和环视会不会冲突：环视要是个「逻辑相机」、物理上包含座舱那一路，就冲突
                    sb.append("物理相机: ").append(cc.getPhysicalCameraIds()).append('\n');
                    int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    sb.append("能力: ").append(caps == null ? "?" : java.util.Arrays.toString(caps)).append('\n');

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

    /**
     * 相机服务眼里每一路空不空。
     *
     * <p>要找的是这一种：<b>被占用，但不是我们</b>。环视卡死、重启应用和重装都没用、
     * 只有重启车机才好 —— 如果那时这里写着「2 被占用（不是我们）」，
     * 占着它的就在相机服务那一侧，应用这边怎么重试都没用。</p>
     */
    private static void appendCameraAvailability(StringBuilder sb, Context context) {
        sb.append("## 2.3.1 相机可用性（相机服务视角）").append('\n');
        if (!com.kooo.evcam.camera.CameraAvailabilityWatch.heardAnything()) {
            sb.append("没收到过可用性回调（前台服务没起来，或者容器没转这条接口）").append('\n');
        } else {
            for (java.util.Map.Entry<String, long[]> e
                    : com.kooo.evcam.camera.CameraAvailabilityWatch.snapshot().entrySet()) {
                long[] v = e.getValue();
                sb.append("  相机 ").append(e.getKey()).append(": ")
                        .append(v[0] == 1 ? "空闲" : "被占用")
                        .append(v[0] == 1 ? "" : (v[2] == 1 ? "（我们开着）" : "（不是我们）"))
                        .append(v[1] >= 0 ? "，已持续 " + (v[1] / 1000) + " 秒" : "")
                        .append('\n');
            }
        }
        appendHolderSuspects(sb, context);
        sb.append('\n');
    }

    /**
     * 开发者选项「记录可能占用摄像头的应用」开着时：本进程里每一路查到过哪些嫌疑应用、各几次。
     * 每一次的明细在黑匣子（2.6）的「争用：… 的嫌疑应用（不是定论）」那几行。
     */
    private static void appendHolderSuspects(StringBuilder sb, Context context) {
        if (!com.kooo.evcam.camera.CameraHolderSuspects.isEnabled(context)) {
            return;
        }
        sb.append("  占用相机的嫌疑应用（不是定论，本进程内）: 使用情况访问 ")
                .append(com.kooo.evcam.camera.CameraHolderSuspects.hasUsageAccess(context)
                        ? "已授权" : "未授权，查不到")
                .append('\n');
        java.util.Map<String, com.kooo.evcam.camera.CameraHolderSuspects.Tally> tally =
                com.kooo.evcam.camera.CameraHolderSuspects.tally();
        if (tally.isEmpty()) {
            sb.append("    还没查过").append('\n');
            return;
        }
        for (java.util.Map.Entry<String, com.kooo.evcam.camera.CameraHolderSuspects.Tally> e
                : tally.entrySet()) {
            com.kooo.evcam.camera.CameraHolderSuspects.Tally t = e.getValue();
            List<java.util.Map.Entry<String, Integer>> ranked = t.ranked();
            sb.append("    相机 ").append(e.getKey()).append("（查了 ").append(t.lookups()).append(" 次）: ");
            if (ranked.isEmpty()) {
                sb.append("每次都没有别的应用起停前台服务或切换前后台");
            }
            int shown = Math.min(ranked.size(), 8);
            for (int i = 0; i < shown; i++) {
                sb.append(i > 0 ? ", " : "").append(ranked.get(i).getKey())
                        .append(" ×").append(ranked.get(i).getValue());
            }
            if (ranked.size() > shown) {
                sb.append("，另 ").append(ranked.size() - shown).append(" 个");
            }
            if (t.dropped() > 0) {
                sb.append("，超出上限未计 ").append(t.dropped()).append(" 次");
            }
            sb.append('\n');
        }
    }

    private static void appendStallWatch(StringBuilder sb, Context context) {
        sb.append("## 2.3 卡顿监测（后视镜 / 录制卡住时自动留下的现场）").append('\n');
        try {
            // 页面放不下太长的文字，报告只带最新的一段；完整的在「保存日志」里
            sb.append(com.kooo.evcam.camera.StallWatch.exportText(context, 32 * 1024)).append('\n');
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
        appendCameraAvailability(sb, context);
        appendChannel(sb);
        appendStepStats(sb, context);
    }

    /**
     * 相机步骤统计（项目所有者 2026-10-14：「识别前后座舱摄像头被卡住的原因或者统计」，2.11.4 R2）：最近 200 步，跨进程留着
     * （{@code camera.StepLog}）。按相机、动作数出画面、没画面、出错、关的用时；再按哨兵、主界面在不在前、离我们上一次关它多久、
     * 是不是车机刚放开它分开数；备选方案「关座舱时先短暂关环视」要比的那一组（R3，只记录）；最后最近 30 步。
     * 只读那个文件，不碰调度、不建管线。
     */
    private static void appendStepStats(StringBuilder sb, Context context) {
        sb.append("## 2.3.3 相机步骤统计").append('\n');
        try {
            sb.append(stepStatsText(com.kooo.evcam.camera.StepLog.read(context)));
        } catch (RuntimeException e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /** 「2.3.3 相机步骤统计」那一节的正文（纯函数，{@code DiagnosticsCollectorTest}）。 */
    static String stepStatsText(List<StepStats.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        if (entries.isEmpty()) {
            return "还没有记下的步骤（2.11.4 起每结束一步记一条）\n";
        }
        sb.append("最近 ").append(entries.size()).append(" 步（最多 ").append(StepStats.MAX).append(" 步，跨进程留着）：")
                .append(clock(entries.get(0).at)).append(" 到 ").append(clock(entries.get(entries.size() - 1).at))
                .append('\n');
        sb.append("按相机、动作：").append('\n');
        appendGroups(sb, entries, StepStats::laneMove, null);
        sb.append("按哨兵模式：").append('\n');
        appendGroups(sb, entries, StepStats::laneMove, StepStats::sentryBucket);
        sb.append("按主界面在不在前：").append('\n');
        appendGroups(sb, entries, StepStats::laneMove, StepStats::mainBucket);
        sb.append("按离我们上一次关它多久：").append('\n');
        appendGroups(sb, entries, StepStats::laneMove, StepStats::sinceCloseBucket);
        sb.append("按是不是车机刚放开它（放开以后我们还没关过它）：").append('\n');
        appendGroups(sb, entries, StepStats::laneMove, StepStats::releaseBucket);
        sb.append("备选方案「关座舱时先短暂关环视」要比的（只记录，没做）：座舱每一次关，按关的时候环视怎样分，关的用时、之后那一次开：")
                .append('\n');
        java.util.Map<String, StepStats.CloseComparison> closes = StepStats.cabinClosesBySurround(entries);
        if (closes.isEmpty()) {
            sb.append("  还没有座舱的关").append('\n');
        }
        for (java.util.Map.Entry<String, StepStats.CloseComparison> group : closes.entrySet()) {
            StepStats.CloseComparison c = group.getValue();
            sb.append("  环视").append(surroundText(group.getKey())).append("：").append(tallyText(StepStats.tally(c.closes)))
                    .append("；之后那一次开 ").append(c.nextOpens).append(" 次，没画面 ").append(c.nextNoFrames).append('\n');
        }
        sb.append("最近 30 步（新的在前）：").append('\n');
        for (int i = entries.size() - 1; i >= Math.max(0, entries.size() - 30); i--) {
            sb.append("  ").append(entryText(entries.get(i))).append('\n');
        }
        return sb.toString();
    }

    /** 一个维度一组一行：「  后座舱 开 · 哨兵 开：3 次：出画面 1、没画面 2、出错 0」；{@code by} 为 null 是只按相机、动作。 */
    private static void appendGroups(StringBuilder sb, List<StepStats.Entry> entries,
                                     java.util.function.Function<StepStats.Entry, String> lane,
                                     java.util.function.Function<StepStats.Entry, String> by) {
        final java.util.Map<String, List<StepStats.Entry>> groups = StepStats.groupBy(entries,
                e -> by == null ? lane.apply(e) : lane.apply(e) + "|" + by.apply(e));
        List<String> names = new ArrayList<>(groups.keySet());
        // 环视、后座舱、前座舱；同一路按动作的次序；同一个动作按分组的名字
        java.util.Collections.sort(names, (a, b) -> {
            StepStats.Entry x = groups.get(a).get(0);
            StepStats.Entry y = groups.get(b).get(0);
            int c = Integer.compare(StepStats.laneRank(x), StepStats.laneRank(y));
            if (c == 0) {
                c = x.move.compareTo(y.move);
            }
            return c != 0 ? c : a.compareTo(b);
        });
        for (String name : names) {
            String[] parts = name.split("\\|", 2);
            StepStats.Entry first = groups.get(name).get(0);
            sb.append("  ").append(laneText(first.key)).append(' ').append(moveText(first.move))
                    .append(parts.length > 1 ? " · " + bucketText(parts[1]) : "")
                    .append("：").append(tallyText(StepStats.tally(groups.get(name)))).append('\n');
        }
    }

    /** 一组的数：「12 次：出画面 10、没画面 2、出错 0」；关的那几次：「关 10 次，用时中位 0.2 秒、最长 6.2 秒，超过 1 秒 1 次」。 */
    static String tallyText(StepStats.Tally t) {
        StringBuilder sb = new StringBuilder().append(t.count).append(" 次");
        if (t.count > t.closes) {
            sb.append("：出画面 ").append(t.framed).append("、没画面 ").append(t.noFrames).append("、出错 ").append(t.errors);
        }
        if (t.closes > 0) {
            sb.append(t.count > t.closes ? "；" : "：").append("关 ").append(t.closes).append(" 次");
            if (t.closeMedianMs >= 0) {
                sb.append("，用时中位 ").append(secondsText(t.closeMedianMs)).append("、最长 ").append(secondsText(t.closeMaxMs));
            }
            sb.append("，超过 ").append(StepStats.SLOW_CLOSE_MS / 1000).append(" 秒 ").append(t.slowCloses).append(" 次");
            if (t.closeTimedOut > 0) {
                sb.append("（到点还没关完 ").append(t.closeTimedOut).append(" 次）");
            }
        }
        return sb.toString();
    }

    /** 分组的名字（{@code StepStats} 的那几个分组记号），中文。 */
    static String bucketText(String bucket) {
        switch (bucket) {
            case "sentry:0":
                return "哨兵 关";
            case "sentry:1":
                return "哨兵 开";
            case "sentry:2":
                return "哨兵 布防";
            case "sentry:?":
                return "哨兵 读不到";
            case "main:yes":
                return "主界面在前";
            case "main:no":
                return "主界面在后台";
            case "main:?":
                return "主界面 不知道";
            case "close:none":
                return "这一次进程里没关过它";
            case "close:<3":
                return "离上次关不到 3 秒";
            case "close:3-6":
                return "离上次关 3–6 秒";
            case "close:6-30":
                return "离上次关 6–30 秒";
            case "close:>30":
                return "离上次关 30 秒以上";
            case "release:yes":
                return "车机刚放开";
            case "release:no":
                return "不是车机刚放开";
            default:
                return bucket;
        }
    }

    /** 一步一行：「10-13 20:10:30 后座舱 开 → 15 秒没画面（一个都没回…）；哨兵 1、主界面后台、离上次关 4.5 秒、车机放开后 3.0 秒」。 */
    static String entryText(StepStats.Entry e) {
        StringBuilder sb = new StringBuilder().append(clock(e.at)).append(' ').append(laneText(e.key)).append(' ')
                .append(moveText(e.move)).append(" → ");
        switch (e.result) {
            case FRAMED:
                sb.append("出画面 ").append(secondsText(e.ms));
                break;
            case NO_FRAMES:
                sb.append(e.ms / 1000).append(" 秒没画面");
                break;
            case ERROR:
                sb.append("出事（").append(e.error == null ? "?" : e.error).append("），做了 ").append(secondsText(e.ms));
                break;
            case CLOSED:
                sb.append("关了 ").append(secondsText(e.ms));
                break;
            default:
                sb.append(secondsText(e.ms)).append(" 还没关完");
                break;
        }
        if (e.counts != null) {
            sb.append("（").append(com.kooo.evcam.camera.SingleCamera.kindText(e.counts)).append("：开始 ").append(e.starts)
                    .append("、结果 ").append(e.results).append("、失败 ").append(e.failures).append("、丢缓冲 ")
                    .append(e.lost).append('）');
        }
        if (e.oldCloseMs >= 0) {
            sb.append("，旧设备关了 ").append(secondsText(e.oldCloseMs));
        }
        sb.append("；哨兵 ").append(e.sentry < 0 ? "?" : String.valueOf(e.sentry))
                .append("、主界面").append(e.mainForeground < 0 ? "?" : e.mainForeground == 1 ? "在前" : "后台")
                .append("、离上次关 ").append(e.sinceCloseMs < 0 ? "没关过" : secondsText(e.sinceCloseMs));
        if (e.closeMs >= 0) {
            sb.append("（那次关了 ").append(secondsText(e.closeMs)).append('）');
        }
        if (e.releasedAgoMs >= 0) {
            sb.append("、车机拿过 ").append(duration(e.heldMs)).append("、放开 ").append(secondsText(e.releasedAgoMs)).append("前");
        }
        if (e.surround != null) {
            sb.append("、环视").append(surroundText(e.surround));
            if (e.surroundGapMs >= 0) {
                sb.append("（这段时间最长断帧 ").append(secondsText(e.surroundGapMs))
                        .append(e.surroundChanged == 1 ? "、会话被重建过" : "、会话没动").append('）');
            }
        }
        return sb.toString();
    }

    private static String laneText(String key) {
        return com.kooo.evcam.camera.MultiCameraManager.roleName(key);
    }

    private static String moveText(StepStats.Move move) {
        switch (move) {
            case OPEN:
                return "开";
            case REOPEN:
                return "救援重开";
            case REBUILD:
                return "救援重建";
            case APPLY:
                return "改输出";
            default:
                return "关";
        }
    }

    private static String surroundText(String state) {
        if ("STREAMING".equals(state)) {
            return "在出画面";
        }
        if ("OPEN".equals(state)) {
            return "开着、没在出画面";
        }
        return "CLOSED".equals(state) ? "关着" : "不知道";
    }

    /** 「0.3 秒」。 */
    private static String secondsText(long ms) {
        return String.format(Locale.US, "%.1f 秒", Math.max(0, ms) / 1000f);
    }

    /**
     * 通道调度（2.11 起唯一动相机的地方）此刻怎么看每一路，紧跟在相机服务视角那张表后面，两边对着看：
     * 该不该开、实际怎样，会话里的输出（要的、留着的、死的），被拿着、不在，开失败和判定，救援梯子，问相机服务；
     * 能不能用相机、手上那一步、在等什么。句子在 MultiCameraManager 里拼。只读调度记下的快照，不建管线。
     */
    private static void appendChannel(StringBuilder sb) {
        sb.append("## 2.3.2 通道调度（Channel）").append('\n');
        try {
            com.kooo.evcam.camera.MultiCameraManager manager =
                    com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            sb.append(manager == null ? "管线还没建（这个进程还没用过相机）\n" : manager.describeChannel());
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 黑匣子：启动、保活、退出这一块到底发生了什么。
     *
     * <p>应用只能发出请求，至于这台车机是照做还是悄悄忽略，代码上看不出来 ——
     * 这一节记的就是「请求」和「实际」成对的那份时间线。</p>
     */
    private static void appendBlackBox(StringBuilder sb, Context context) {
        sb.append("## 2.6 黑匣子（进程 / 服务 / 界面的生死时间线）").append('\n');
        try {
            sb.append(com.kooo.evcam.blackbox.BlackBox.export(context, 128 * 1024));
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
     * 副屏：决定「推送到副屏」这个功能有没有可能。每块屏的状态写名字（ON / OFF / DOZE……，{@link DisplayWatch#stateName}），
     * 不写系统给的数字，和黑匣子里「显示屏：…」那几行对得上。
     *
     * <p>这台 7X 上安卓的显示屏状态、可交互只在车机下电时才变（2026-10-12）：车主走开、中控屏真黑了，这里还写 ON、可交互。
     * 屏幕真黑没黑看下一节「3.1 熄屏」。</p>
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
                    .append("  state=").append(DisplayWatch.stateName(d.getState()))
                    .append('\n');
        }
        sb.append("可交互（isInteractive）: ").append(DisplayWatch.interactiveNow() ? "是" : "否").append('\n');
        if (displays.length <= 1) {
            sb.append(">> 只有一块屏，副屏推送不可用").append('\n');
        } else {
            sb.append(">> 检测到多块屏，副屏推送有可能实现").append('\n');
        }
        sb.append(">> 这台车上安卓的显示屏状态、可交互只在车机下电时才变；屏幕真黑没黑看 3.1").append('\n');
        sb.append('\n');
    }

    // ------------------------------------------------------------------ 熄屏（物理屏幕）、车辆信号

    /**
     * 熄屏（物理屏幕）：此刻屏幕真黑没黑、谁判的、从什么时候起；判断用的两项车辆信号和车辆接口；安卓那一半；车机要睡没有。
     *
     * <h3>为什么要这一节（熄屏 v3，项目所有者 2026-10-12 D1、D4）</h3>
     *
     * <p>「熄屏」跟着屏幕真黑走：安卓熄屏，或者车辆说黑（{@link VehicleScreen}）。这台车上安卓只在车机下电时才熄屏，
     * 车主走开后中控屏黑了四到六分钟（哨兵模式开着时一直）安卓还说亮着 —— 只看上面「显示屏」那一节，这一段在报告里看不见；
     * 座舱这时已经放开、环视照表接着录，导出时屏幕是哪一种黑、谁判的，这里一眼看得到。</p>
     *
     * <p>读的是 {@link ScreenState#describeNow} 一次取齐的那一份；文字在这里写（ScreenState 里不放中文，设计 v3.1 C20）。只读。</p>
     */
    private static void appendScreen(StringBuilder sb) {
        sb.append("## 3.1 熄屏（物理屏幕）").append('\n');
        try {
            ScreenState.Now n = ScreenState.describeNow();
            long wallNow = System.currentTimeMillis();
            sb.append("此刻: ").append(n.dark ? "熄屏（" + darkText(n.source, n.darkForMs) + "）" : "亮屏")
                    .append(n.dark && n.darkForMs >= 0 ? "，" + clock(wallNow - n.darkForMs) + " 起" : "")
                    .append('\n');
            sb.append("屏幕此刻: ").append(n.known ? "知道"
                    : n.wakePending ? "不知道 —— 安卓刚亮，在等醒来那一遍车辆信号（等到之前只按安卓熄屏算）"
                    : "不知道 —— 进程刚起来，在等第一份车辆判断（恢复、接回录像排着等）").append('\n');
            sb.append("车辆判断（判断用着的）: ").append(verdictText(n.vehicle)).append('\n');
            sb.append("  screen_power_event=").append(eventText(n.screenPowerEvent))
                    .append("，点火=").append(ignitionText(n.ignition))
                    .append(" → 此刻读数判下来: ").append(verdictText(VehicleScreen.verdict(n.screenPowerEvent, n.ignition)))
                    .append('\n');
            sb.append("  车辆接口: ").append(linkText(n.carLink)).append('\n');
            sb.append("安卓: 可交互=").append(n.interactive ? "是" : "否")
                    .append("，判断用的安卓熄屏=").append(n.androidOff ? "是" : "否").append('\n');
            sb.append("车机要睡（黑着时安卓熄屏）: ").append(!n.dark ? "没有（亮着）"
                    : !n.androidOff ? "没有（车机醒着：安卓亮着）"
                    : n.androidOffForMs < 0 ? "安卓熄屏着，起点不明（进程起来时就熄屏着）"
                    : clock(wallNow - n.androidOffForMs) + " 起，" + duration(n.androidOffForMs) + "前"
                    + (n.wakePending ? "；安卓刚又亮了，在等车辆信号" : "")).append('\n');
            sb.append("说明: 熄屏 = 安卓熄屏，或者车辆说黑（screen_power_event=1 且点火 ACC 或更低；")
                    .append("只读得到一项看那一项，都读不到只看安卓）。时刻是墙上时钟，多久前含深睡").append('\n');
        } catch (RuntimeException e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 车辆信号（导出这一刻）：车辆接口连没连上、谁登记着，熄屏判断和熄屏那张表看的几项读数。
     *
     * <p>熄屏 v3 起车辆信号进程活着一直在读离散信号（核心，熄屏判断登记的）；录像的信息条、「系统信息」页这些完整用户另外开浮点
     * 传感器和定位（设计 v3 §2）。以前报告里一项车辆读数都没有：熄屏那张表为什么那样判、屏幕黑没黑，只能翻黑匣子。</p>
     *
     * <p>只读：不登记、不叫车辆接口读一遍，读的是 {@link Telemetry} 此刻那一份快照。车速是浮点传感器，没有完整用户时不读，
     * 写「没在读」—— 不是读不到。</p>
     */
    private static void appendVehicleSignals(StringBuilder sb) {
        sb.append("## 6.2 车辆信号（导出这一刻）").append('\n');
        try {
            Telemetry telemetry = Telemetry.get();
            String detail = telemetry.carDetail();
            sb.append("  车辆接口: ").append(linkText(telemetry.carLink()))
                    .append(detail == null ? "" : "（" + detail + "）").append('\n');
            List<String> full = telemetry.fullUsers();
            sb.append("  登记: 核心").append(telemetry.coreKept() ? "在（熄屏判断：离散信号一直在读）" : "不在")
                    .append("；完整 ").append(full.isEmpty() ? "没有（浮点传感器、定位没在读）"
                            : full + "（浮点传感器、定位在读）").append('\n');
            Readings r = telemetry.readings();
            sb.append("  读数: 第 ").append(r.version).append(" 份，有数据的信号 ").append(r.knownCount()).append(" 个")
                    .append('\n');
            sb.append("  点火: ").append(ignitionText(r.code(Signal.IGNITION))).append('\n');
            sb.append("  screen_power_event: ").append(eventText(r.bool(Signal.SCREEN_POWER_EVENT))).append('\n');
            sb.append("  哨兵模式: ").append(sentryText(r.code(Signal.SENTRY_MODE))).append('\n');
            String gear = r.text(Signal.GEAR);
            sb.append("  挡位: ").append(gear == null ? "读不到" : gear).append('\n');
            Float speed = r.number(Signal.SPEED);
            sb.append("  车速: ").append(full.isEmpty() ? "没在读（没有完整用户，浮点传感器不读）"
                    : speed == null ? "读不到" : String.format(Locale.US, "%.1f km/h", speed)).append('\n');
        } catch (RuntimeException e) {
            sb.append("  !! 读取失败: ").append(e).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 这一次熄屏谁判的、从什么时候起，括号里的那一段：「车辆信号，12 分 3 秒前起」「安卓熄屏，进程起来时就黑着，起点不明」。
     * 报告「3.1 熄屏」和通道调度那一节（{@code MultiCameraManager.describeChannel} 的登记表那一行）都用它，两处写法一样。
     *
     * @param source    {@link ScreenState.Now#source}
     * @param darkForMs {@link ScreenState.Now#darkForMs}：-1 是不知道什么时候黑的
     */
    public static String darkText(ScreenState.Source source, long darkForMs) {
        String who = source == ScreenState.Source.ANDROID ? "安卓熄屏" : source == null ? "?" : "车辆信号";
        if (darkForMs < 0) {
            return who + "，进程起来时就黑着，起点不明";
        }
        // 读到时已经黑着（进程刚起来、或者之前读不到）：记下的是读到的那一刻，不是黑的那一刻
        return who + "，" + duration(darkForMs) + "前"
                + (source == ScreenState.Source.VEHICLE_READ ? "读到时已经黑着，起点不明" : "起");
    }

    /** 车辆的判断：黑 / 亮 / 说不上来（只看安卓）。 */
    static String verdictText(Boolean verdict) {
        return verdict == null ? "说不上来（只看安卓）" : verdict ? "黑" : "亮";
    }

    /** 屏幕电源事件：原值和意思，「1（黑）」「0（亮）」；读不到（255、没读过）是「读不到」。 */
    static String eventText(Boolean event) {
        return event == null ? "读不到" : VehicleScreen.eventText(event) + (event ? "（黑）" : "（亮）");
    }

    /** 点火：名字和原值（「ACC（0x00200104）」），没名字的只写原值（「0x00200102」），读不到「读不到」。 */
    static String ignitionText(Integer ignition) {
        if (ignition == null) {
            return "读不到";
        }
        String name = VehicleScreen.ignitionText(ignition);
        String raw = String.format(Locale.US, "0x%08X", ignition);
        return name.equals(raw) || name.equals(String.valueOf(ignition)) ? name : name + "（" + raw + "）";
    }

    /** 哨兵模式：0 关、1 开、2 布防（{@code Signal.Format.SENTRY}），带原值。 */
    static String sentryText(Integer sentry) {
        if (sentry == null) {
            return "读不到";
        }
        return (sentry == 0 ? "关" : sentry == 1 ? "开" : "布防") + "（" + sentry + "）";
    }

    /** 车辆接口此刻的结果。 */
    static String linkText(Telemetry.CarLink link) {
        if (link == null) {
            return "没人登记（核心还没登记，熄屏只看安卓）";
        }
        switch (link) {
            case CONNECTED:
                return "接上了";
            case CONNECTING:
                return "在连（熄屏先只看安卓）";
            case UNAVAILABLE:
                return "这台车机没有这套接口（熄屏只看安卓，不再试）";
            default:
                return "连不上（熄屏只看安卓；每 " + (Telemetry.RETRY_MS / 60_000L) + " 分钟、安卓亮屏时再试）";
        }
    }

    /** 整秒的时长：「31 秒」「12 分 3 秒」「2 小时 5 分」（和通道调度那一节一样的写法）。 */
    static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) {
            return s + " 秒";
        }
        if (s < 3600) {
            return (s / 60) + " 分 " + (s % 60) + " 秒";
        }
        return (s / 3600) + " 小时 " + (s % 3600 / 60) + " 分";
    }

    /** 墙上时钟「10-12 20:05:46」，和黑匣子每行开头对得上。 */
    private static String clock(long wallMs) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(wallMs));
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
        appendSwitches(sb, context);
    }

    /**
     * 开关状态：导出这一刻的，以及设置里全部项的原始值。
     *
     * <p>以前报告里只有视频流配置，没有开关 —— 看一份日志时说不出当时「开机自启动」
     * 开没开，只能从别的行去猜（比如「WakeLock not acquired (开机自启动未开启)」）。
     * 这里前半段是人看的那几个，后半段把 {@code app_config} 整份列出来：
     * 以后加了新开关，这里不用改也会出现。</p>
     *
     * <p>要看<b>过去某一刻</b>的开关，去黑匣子里找：每次进程启动都有一行「开关: …」，
     * 在设置里改动时有「开关变更: …」。</p>
     */
    private static void appendSwitches(StringBuilder sb, Context context) {
        sb.append("## 6.1 开关状态（导出这一刻）").append('\n');
        for (String item : com.kooo.evcam.blackbox.BlackBox.describeSwitches(context).split(" ")) {
            sb.append("  ").append(item).append('\n');
        }
        sb.append("  开发者选项=").append(com.kooo.evcam.settings.DeveloperMode.isUnlocked()
                ? "已解锁" : "未解锁").append('\n');
        sb.append("  用户退出后暂停自启动=").append(com.kooo.evcam.UserExit.isExited(context)
                ? "是（等手动打开或真正开机）" : "否").append('\n');
        sb.append('\n').append("全部设置的原始值（app_config）:").append('\n');
        try {
            java.util.Map<String, ?> all = context
                    .getSharedPreferences("app_config", Context.MODE_PRIVATE).getAll();
            for (java.util.Map.Entry<String, ?> e : new java.util.TreeMap<>(all).entrySet()) {
                String value = String.valueOf(e.getValue());
                if (value.length() > 80) {
                    value = value.substring(0, 80) + "…（" + value.length() + " 字）";
                }
                sb.append("  ").append(e.getKey()).append(" = ").append(value).append('\n');
            }
        } catch (Exception e) {
            sb.append("  !! 读不出来: ").append(e).append('\n');
        }
        sb.append('\n');
    }

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

    /**
     * 本进程最近的 logcat。
     *
     * <p>这台车机上应用只读得到自己进程的行，所以不再按关键词挑 —— 以前那样挑，
     * 主界面、后视镜这些标签不含关键词，整类被漏掉。改成滤掉已知的噪声
     * （容器的调用跟踪、编解码框架的配置细节），剩下的都留。</p>
     */
    /**
     * 应用自己记下的警告和错误（{@code AppLog} 落盘的那份），重启、升级后也在。
     *
     * <p>上面 logcat 那一节是系统的环形缓冲区，几个小时前的早被冲掉了；
     * 出事那一刻为什么坏，要在这里找。</p>
     */
    private static void appendAppWarnings(StringBuilder sb, Context context) {
        sb.append('\n').append("## 9.1 应用记下的警告和错误（重启后也在，最近 ")
                .append(APP_WARNINGS_MAX).append(" 条）").append('\n');
        List<String> entries = AppLog.readWarnings(context, APP_WARNINGS_MAX);
        if (entries.isEmpty()) {
            sb.append("(没有)").append('\n');
            return;
        }
        for (String entry : entries) {
            sb.append(entry).append('\n');
        }
    }

    private static final int APP_WARNINGS_MAX = 150;

    private static void appendLogcat(StringBuilder sb) {
        sb.append("## 9. 最近日志（本进程）").append('\n');
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "time", "-t", String.valueOf(LOGCAT_READ_LINES)});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> kept = new ArrayList<>();
            int dropped = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (com.kooo.evcam.camera.LogcatNoise.isNoise(line)) {
                    dropped++;
                } else {
                    kept.add(com.kooo.evcam.camera.LogcatNoise.clip(line));
                }
            }
            reader.close();
            if (kept.isEmpty()) {
                sb.append("(未抓到相关日志)").append('\n');
            } else {
                sb.append("（滤掉容器和编解码框架的 ").append(dropped).append(" 行）").append('\n');
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
