package com.kooo.evcam.camera;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.update.UpdateFlow;
import com.kooo.evcam.zeekr.RearViewMirrorService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 卡顿监测：超级后视镜或录制卡住时，当场留下一份现场报告。
 *
 * <h3>为什么要有</h3>
 *
 * <p>「隐藏主界面、开着后视镜在录制，偶尔后视镜画面卡住」—— 大概百分之一的概率，
 * 事后记不清当时的情形，日志里也没有能判断原因的东西。等打开应用去导出日志时，
 * 现场已经被「打开主界面」这个动作改掉了（回到前台会重新检查、重开相机）。
 * 所以要在卡住的那一刻自己把现场记下来。</p>
 *
 * <h3>盯哪几路信号</h3>
 *
 * <ul>
 *   <li>后视镜窗口的新画面：TextureView 每显示一帧新画面回调一次；</li>
 *   <li>每路相机的出帧结果（onCaptureCompleted），以及请求失败、某一路输出没拿到帧；</li>
 *   <li>每路录制编码线程收到的帧，单次绘制 / 编码 / 写文件的用时，分段切换用时；</li>
 *   <li>主线程和各相机线程有没有被堵住。</li>
 * </ul>
 *
 * <h3>怎么对照着看</h3>
 *
 * <ul>
 *   <li>后视镜停了，相机结果和录制帧都还在 → 问题在预览这条流或后视镜窗口本身；</li>
 *   <li>三者一起停，编码线程栈停在写文件 / 分段切换里 → 录制把相机拖住了；</li>
 *   <li>三者一起停，线程都没堵 → 相机或 HAL 那一侧；</li>
 *   <li>主线程堵住 → 窗口画不出来，和相机无关。</li>
 * </ul>
 *
 * <h3>不做什么</h3>
 *
 * <p><b>只记录，不修复。</b>自动恢复会把证据抹掉；而且 {@code SingleCamera} 里已经有一套
 * 按出帧结果触发的恢复（重建会话、重开相机），这里再动手只会和它打架。</p>
 *
 * <p>报告写在应用私有目录的 {@code stall/} 下，重启后还在。「保存日志」和诊断报告都会带上。
 * 报告是写给维护者看的，不做翻译。</p>
 */
public final class StallWatch {

    private static final String TAG = "StallWatch";

    /** 有东西要盯时每半秒看一次；没有时两秒看一次，只为发现「开始要盯了」。 */
    private static final long TICK_ACTIVE_MS = 500L;
    private static final long TICK_IDLE_MS = 2000L;
    /** 有东西在盯时，每分钟往日志里写一行各路帧率 —— 卡住之前的走势靠它看。 */
    private static final long HEALTH_INTERVAL_MS = 60_000L;
    /** 单次绘制 / 编码 / 写文件超过这么久，当场记一笔。 */
    private static final long SLOW_OP_MS = 300L;
    /** 同一个线程被堵住的日志（带栈）30 秒内只记一次。 */
    private static final long LOOPER_LOG_GAP_MS = 30_000L;
    /** 同一路相机的丢帧 / 请求失败日志 5 秒内只记一次（带累计数）。 */
    private static final long TROUBLE_LOG_GAP_MS = 5_000L;
    /** 线程排队超过这么久才在状态里显示「在等」，短的只是正常调度。 */
    private static final long LOOPER_SHOW_WAIT_MS = 100L;
    /** 报告文件超过这么大就轮换，只留上一份。 */
    private static final long FILE_LIMIT_BYTES = 512L * 1024L;
    private static final int STALL_TAIL_LINES = 200;
    private static final int RECOVER_TAIL_LINES = 60;
    private static final int LOGCAT_LINES = 300;
    private static final int STACK_DEPTH = 32;
    private static final long MB = 1024L * 1024L;
    private static final String DIR = "stall";
    private static final String FILE = "reports.log";
    private static final String OLD_FILE = "reports.1.log";

    private static final Heartbeat MIRROR = new Heartbeat();
    private static final Map<String, Heartbeat> CAPTURE = new ConcurrentHashMap<>();
    private static final Map<String, Heartbeat> ENCODER = new ConcurrentHashMap<>();
    private static final Map<String, Trouble> TROUBLE = new ConcurrentHashMap<>();
    private static final Map<String, LooperProbe> LOOPERS = new ConcurrentHashMap<>();
    /** 所有建过的相机实例。弱引用：报告只是顺便看一眼，不能拖住已经不用的相机。 */
    private static final Map<SingleCamera, Boolean> CAMERAS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // 以下只在监测线程上读写
    private static final Map<String, StallRules.State> STATES = new HashMap<>();
    private static final Map<String, Boolean> SAVED = new HashMap<>();
    private static final StallRules.Budget BUDGET = new StallRules.Budget();
    private static long lastHealthMs = Long.MIN_VALUE / 2;
    private static boolean mirrorWasWatched;

    private static final Runnable TICK = StallWatch::tick;
    private static final Object FILE_LOCK = new Object();

    private static volatile Context appContext;
    private static volatile Handler watchHandler;
    private static volatile boolean foreground;

    private StallWatch() {
    }

    // ------------------------------------------------------------------ 给各处调用的入口

    /** 监测用的时钟：开机以来的毫秒数，不含深度睡眠 —— 车停着睡一晚，醒来不该算「卡了一整夜」。 */
    public static long now() {
        return SystemClock.uptimeMillis();
    }

    /** 进程启动时调用一次。 */
    public static synchronized void start(Context context) {
        if (watchHandler != null || context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        watchLooper("main", new Handler(Looper.getMainLooper()));
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        watchHandler = new Handler(thread.getLooper());
        watchHandler.postDelayed(TICK, TICK_IDLE_MS);
    }

    public static Heartbeat capture(String cameraId) {
        return CAPTURE.computeIfAbsent(String.valueOf(cameraId), key -> new Heartbeat());
    }

    public static Heartbeat encoder(String cameraId) {
        return ENCODER.computeIfAbsent(String.valueOf(cameraId), key -> new Heartbeat());
    }

    public static void registerCamera(SingleCamera camera) {
        if (camera != null) {
            CAMERAS.put(camera, Boolean.TRUE);
        }
    }

    /** 后视镜窗口显示了一帧新画面。 */
    public static void mirrorFrame() {
        MIRROR.beat(now());
    }

    /** 后视镜接上相机时开始盯，解绑时停。 */
    public static void armMirror(boolean on) {
        if (on) {
            MIRROR.arm(now(), StallRules.ARM_GRACE_MS);
        } else {
            MIRROR.disarm();
        }
        AppLog.d(TAG, "mirror watch " + (on ? "on" : "off"));
    }

    public static void setForeground(boolean value) {
        if (foreground != value) {
            foreground = value;
            AppLog.d(TAG, "main window " + (value ? "foreground" : "background"));
        }
    }

    public static void watchLooper(String name, Handler handler) {
        if (name != null && handler != null) {
            LOOPERS.put(name, new LooperProbe(name, handler));
        }
    }

    public static void unwatchLooper(String name) {
        if (name != null) {
            LOOPERS.remove(name);
        }
    }

    /** 记一次操作用时；慢的当场写日志，写文件卡住时一眼能看到是哪一次、卡了多久。 */
    public static void noteOp(Heartbeat beat, String cameraId, String op, long startMs) {
        long took = now() - startMs;
        beat.noteOp(op, took);
        if (took >= SLOW_OP_MS) {
            AppLog.w(TAG, "slow " + op + " on camera " + cameraId + ": " + took + "ms");
        }
    }

    /**
     * 在编码线程上跑一件会占住它一阵子的事，并记下用时。
     *
     * <p>分段切换就是这种事：收尾上一个文件、重建编码器都在编码线程上做，
     * 这期间相机送来的录制帧没人取。卡住时报告里能看到「正在分段切换，已经多久」。</p>
     */
    public static void runTask(Heartbeat beat, String cameraId, String task, Runnable body) {
        long start = now();
        beat.beginTask(task, start);
        try {
            body.run();
        } finally {
            beat.endTask();
            long took = now() - start;
            beat.noteOp(task, took);
            AppLog.i(TAG, task + " on camera " + cameraId + " took " + took + "ms");
        }
    }

    /** 某一路输出没拿到这一帧（CaptureCallback.onCaptureBufferLost）。 */
    public static void bufferLost(String cameraId, String target) {
        Trouble trouble = trouble(cameraId);
        trouble.lost.incrementAndGet();
        trouble.lastLostTarget = target;
        maybeLogTrouble(trouble, cameraId);
    }

    /** 一次请求失败（CaptureCallback.onCaptureFailed）。 */
    public static void captureFailed(String cameraId, int reason) {
        Trouble trouble = trouble(cameraId);
        trouble.failed.incrementAndGet();
        trouble.lastFailReason = reason;
        maybeLogTrouble(trouble, cameraId);
    }

    /**
     * 「保存日志」和诊断报告用：这一刻的现场，加上之前自动留下的报告。
     *
     * @param maxReportChars 报告最多带多少字符，从最新的往前取；诊断报告页面放不下太长的文字
     */
    public static String exportText(Context context, int maxReportChars) {
        long now = now();
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("===== stall watch: state at export | ")
                .append(wallClock(System.currentTimeMillis())).append(" =====\n");
        appendState(sb, now, true);
        sb.append("===== stall watch: saved reports, oldest first =====\n");
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        String saved = readReports(ctx);
        if (saved.isEmpty()) {
            sb.append("(none saved)\n");
        } else if (saved.length() > maxReportChars) {
            sb.append("(showing the newest ").append(maxReportChars).append(" characters)\n");
            sb.append(saved, saved.length() - maxReportChars, saved.length());
        } else {
            sb.append(saved);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 检查

    private static void tick() {
        try {
            check(now());
        } catch (Throwable t) {
            AppLog.w(TAG, "check failed: " + t);
        }
        Handler handler = watchHandler;
        if (handler != null) {
            handler.postDelayed(TICK, isActive() ? TICK_ACTIVE_MS : TICK_IDLE_MS);
        }
    }

    private static boolean isActive() {
        if (MIRROR.isArmed()) {
            return true;
        }
        for (Heartbeat beat : ENCODER.values()) {
            if (beat.isArmed()) {
                return true;
            }
        }
        return false;
    }

    private static void check(long now) {
        boolean active = isActive();

        for (LooperProbe probe : LOOPERS.values()) {
            long blocked = probe.blockedMs(now);
            if (blocked > StallRules.LOOPER_STALL_MS && now - probe.lastLogMs >= LOOPER_LOG_GAP_MS) {
                probe.lastLogMs = now;
                Thread thread = probe.handler.getLooper().getThread();
                StringBuilder stack = new StringBuilder();
                appendStack(stack, thread, thread.getStackTrace());
                AppLog.w(TAG, "thread " + probe.name + " has not run a queued task for "
                        + blocked + "ms\n" + stack);
            }
            if (active) {
                probe.poke(now);
            }
        }

        // 息屏、窗口被系统藏起来时画面本来就不更新，不算卡；重新可见时从头计时
        boolean screenOn = isScreenOn();
        boolean mirrorWatched = MIRROR.isArmed() && screenOn
                && RearViewMirrorService.isWindowVisibleForStall();
        if (mirrorWatched && !mirrorWasWatched) {
            MIRROR.rebase(now, StallRules.ARM_GRACE_MS);
        }
        mirrorWasWatched = mirrorWatched;
        step("mirror", mirrorWatched, MIRROR.ageMs(now), StallRules.MIRROR_STALL_MS, now,
                "super mirror showed no new frame");

        for (Map.Entry<String, Heartbeat> entry : ENCODER.entrySet()) {
            Heartbeat beat = entry.getValue();
            step("recorder " + entry.getKey(), beat.isArmed(), beat.ageMs(now),
                    StallRules.ENCODER_STALL_MS, now, "recording received no camera frame");
        }

        if (active && now - lastHealthMs >= HEALTH_INTERVAL_MS) {
            lastHealthMs = now;
            AppLog.i(TAG, health(now, screenOn));
        }
    }

    private static void step(String key, boolean watched, long ageMs, long thresholdMs,
                             long now, String what) {
        StallRules.State state = STATES.get(key);
        if (state == null) {
            state = new StallRules.State();
            STATES.put(key, state);
        }
        StallRules.Transition transition = StallRules.step(state, watched, ageMs, thresholdMs, now);
        if (transition == StallRules.Transition.STALLED) {
            String headline = "STALL " + key + ": " + what + " for " + ageMs + "ms";
            if (BUDGET.tryTake(now)) {
                SAVED.put(key, true);
                AppLog.w(TAG, headline + ", saving report");
                append(report(headline, now, STALL_TAIL_LINES, true));
            } else {
                SAVED.put(key, false);
                AppLog.w(TAG, headline + ", report limit reached, not saved");
            }
        } else if (transition == StallRules.Transition.RECOVERED) {
            long lasted = now - state.stalledSinceMs();
            String headline = (watched ? "RECOVER " : "END (no longer watched) ")
                    + key + " after " + lasted + "ms";
            AppLog.w(TAG, headline);
            if (Boolean.TRUE.equals(SAVED.get(key))) {
                append(report(headline, now, RECOVER_TAIL_LINES, false));
            }
        }
    }

    private static String health(long now, boolean screenOn) {
        StringBuilder sb = new StringBuilder("health");
        sb.append(" fg=").append(foreground).append(" screen=").append(screenOn ? "on" : "off");
        float mirrorRate = MIRROR.takeRate(now);
        sb.append(" | mirror ").append(MIRROR.isArmed() ? fps(mirrorRate) : "off");
        for (Map.Entry<String, Heartbeat> entry : new TreeMap<>(CAPTURE).entrySet()) {
            sb.append(" | cam ").append(entry.getKey()).append(' ')
                    .append(fps(entry.getValue().takeRate(now)));
            Trouble trouble = TROUBLE.get(entry.getKey());
            if (trouble != null) {
                long lost = trouble.lost.get();
                long failed = trouble.failed.get();
                if (lost != trouble.lostAtHealth || failed != trouble.failedAtHealth) {
                    sb.append(" lost+").append(lost - trouble.lostAtHealth)
                            .append(" failed+").append(failed - trouble.failedAtHealth);
                }
                trouble.lostAtHealth = lost;
                trouble.failedAtHealth = failed;
            }
        }
        for (Map.Entry<String, Heartbeat> entry : new TreeMap<>(ENCODER).entrySet()) {
            Heartbeat beat = entry.getValue();
            float rate = beat.takeRate(now);
            String slowest = beat.takeSlowestOp();
            if (beat.isArmed()) {
                sb.append(" | rec ").append(entry.getKey()).append(' ').append(fps(rate));
                if (slowest != null) {
                    sb.append(" slowest ").append(slowest);
                }
            }
        }
        sb.append(" | max lag");
        for (LooperProbe probe : sortedLoopers()) {
            sb.append(' ').append(probe.name).append('=').append(probe.takeMaxLag()).append("ms");
        }
        Runtime rt = Runtime.getRuntime();
        sb.append(" | heap ").append((rt.totalMemory() - rt.freeMemory()) / MB)
                .append('/').append(rt.maxMemory() / MB).append("MB");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 报告

    private static String report(String headline, long now, int tailLines, boolean full) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("===== ").append(headline).append(" | ")
                .append(wallClock(System.currentTimeMillis())).append(" =====\n");
        appendState(sb, now, full);
        sb.append("--- app log, last ").append(tailLines).append(" lines ---\n");
        for (String line : AppLog.tail(tailLines)) {
            sb.append(line).append('\n');
        }
        if (full) {
            sb.append("--- logcat, this process ---\n");
            appendLogcat(sb);
        }
        sb.append("===== end =====\n\n");
        return sb.toString();
    }

    private static void appendState(StringBuilder sb, long now, boolean withStacks) {
        Context ctx = appContext;
        sb.append("app ").append(ctx != null ? safe(() -> UpdateFlow.currentVersion(ctx)) : "?")
                .append(" mainWindowForeground=").append(foreground)
                .append(" screenOn=").append(isScreenOn())
                .append(" uptime=").append(now).append("ms\n");
        sb.append(safe(RearViewMirrorService::describeForStall)).append('\n');
        sb.append("signals: ").append(describeBeats(now)).append('\n');
        sb.append("threads: ").append(describeLoopers(now)).append('\n');
        sb.append("main screen surround preview: ")
                .append(safe(com.kooo.evcam.zeekr.FourLaneContainer::describeAttached)).append('\n');
        appendCameras(sb);
        sb.append("main window preview: ").append(safe(PreviewFrameRates::describe)).append('\n');
        appendMemory(sb, ctx);
        if (withStacks) {
            sb.append("--- thread stacks ---\n");
            appendThreads(sb);
        }
    }

    private static String describeBeats(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append("mirror ").append(describeBeat(MIRROR, now, true));
        for (Map.Entry<String, Heartbeat> entry : new TreeMap<>(CAPTURE).entrySet()) {
            sb.append(" | capture results ").append(entry.getKey()).append(' ')
                    .append(describeBeat(entry.getValue(), now, false));
            Trouble trouble = TROUBLE.get(entry.getKey());
            if (trouble != null) {
                sb.append(", lost buffers ").append(trouble.lost.get())
                        .append(" (last on ").append(trouble.lastLostTarget).append(')')
                        .append(", failed captures ").append(trouble.failed.get())
                        .append(" (last reason ").append(trouble.lastFailReason).append(')');
            }
        }
        for (Map.Entry<String, Heartbeat> entry : new TreeMap<>(ENCODER).entrySet()) {
            sb.append(" | recorder ").append(entry.getKey()).append(' ')
                    .append(describeBeat(entry.getValue(), now, true));
        }
        return sb.toString();
    }

    private static String describeBeat(Heartbeat beat, long now, boolean showWatch) {
        StringBuilder sb = new StringBuilder();
        if (showWatch) {
            sb.append(beat.isArmed() ? "[watched] " : "[not watched] ");
        }
        long age = beat.ageMs(now);
        if (beat.count() == 0) {
            sb.append("no frame yet");
        } else if (age < 0) {
            sb.append("waiting for first frame, ").append(beat.count()).append(" total");
        } else {
            sb.append("last ").append(age).append("ms ago, ").append(beat.count()).append(" total");
        }
        String task = beat.task();
        if (task != null) {
            sb.append(", busy with ").append(task).append(" for ").append(beat.taskAgeMs(now)).append("ms");
        }
        return sb.toString();
    }

    private static String describeLoopers(long now) {
        StringBuilder sb = new StringBuilder();
        for (LooperProbe probe : sortedLoopers()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            long blocked = probe.blockedMs(now);
            sb.append(probe.name)
                    .append(blocked >= LOOPER_SHOW_WAIT_MS ? " WAITING " + blocked + "ms" : " ok")
                    .append(" (max lag ").append(probe.maxLagMs).append("ms)");
        }
        return sb.length() == 0 ? "none watched" : sb.toString();
    }

    private static void appendCameras(StringBuilder sb) {
        List<SingleCamera> cameras;
        synchronized (CAMERAS) {
            cameras = new ArrayList<>(CAMERAS.keySet());
        }
        if (cameras.isEmpty()) {
            sb.append("cameras: none created\n");
            return;
        }
        for (SingleCamera camera : cameras) {
            sb.append(safe(camera::describeForStall)).append('\n');
        }
    }

    private static void appendMemory(StringBuilder sb, Context ctx) {
        Runtime rt = Runtime.getRuntime();
        sb.append("memory: java ").append((rt.totalMemory() - rt.freeMemory()) / MB)
                .append('/').append(rt.maxMemory() / MB).append("MB, native ")
                .append(Debug.getNativeHeapAllocatedSize() / MB).append("MB");
        if (ctx != null) {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(info);
                    sb.append(", system available ").append(info.availMem / MB)
                            .append("MB low=").append(info.lowMemory);
                }
            } catch (Exception e) {
                sb.append(", system memory unavailable: ").append(e);
            }
            try {
                // 每个存储卷上各有一个应用目录，U 盘也在里面：看录制那块盘还剩多少
                for (File dir : ctx.getExternalFilesDirs(null)) {
                    if (dir != null) {
                        StatFs fs = new StatFs(dir.getPath());
                        sb.append(" | free ").append(fs.getAvailableBytes() / MB)
                                .append("MB on ").append(dir.getPath());
                    }
                }
            } catch (Exception e) {
                sb.append(" | storage unavailable: ").append(e);
            }
        }
        sb.append('\n');
    }

    /**
     * 关键线程的栈。
     *
     * <p>不能只靠 {@code Thread.getAllStackTraces()}：车上的虚拟化容器里它只列出几条无关的线程，
     * 主线程和相机线程都不在里面 —— 2026-09-14 的诊断报告里一条关键线程的栈都没有。
     * 所以登记过的线程（主线程、相机线程、编码线程）直接从各自的 Looper 取，
     * 再和系统列出来的合在一起。</p>
     */
    private static void appendThreads(StringBuilder sb) {
        Map<Thread, StackTraceElement[]> all = new HashMap<>(Thread.getAllStackTraces());
        int listedBySystem = all.size();
        for (LooperProbe probe : LOOPERS.values()) {
            Thread thread = probe.handler.getLooper().getThread();
            if (!all.containsKey(thread)) {
                all.put(thread, thread.getStackTrace());
            }
        }
        List<Thread> threads = new ArrayList<>(all.keySet());
        Collections.sort(threads, (a, b) -> a.getName().compareTo(b.getName()));
        int skipped = 0;
        for (Thread thread : threads) {
            if (isInteresting(thread)) {
                appendStack(sb, thread, all.get(thread));
            } else {
                skipped++;
            }
        }
        sb.append('(').append(skipped).append(" other threads not shown; the system listed ")
                .append(listedBySystem).append(")\n");
    }

    private static boolean isInteresting(Thread thread) {
        String name = thread.getName();
        return "main".equals(name) || name.startsWith("Camera-") || name.startsWith("Encoder-")
                || name.startsWith("CodecRecorder-") || name.startsWith("CameraRecording")
                || thread.getState() == Thread.State.BLOCKED;
    }

    private static void appendStack(StringBuilder sb, Thread thread, StackTraceElement[] stack) {
        sb.append('"').append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
        if (stack == null) {
            return;
        }
        int shown = Math.min(stack.length, STACK_DEPTH);
        for (int i = 0; i < shown; i++) {
            sb.append("    at ").append(stack[i]).append('\n');
        }
        if (stack.length > shown) {
            sb.append("    ... ").append(stack.length - shown).append(" more\n");
        }
    }

    /** 相机框架在本进程里打的日志（CameraDevice、BufferQueue 这些）不经过 AppLog，只能从 logcat 拿。 */
    private static void appendLogcat(StringBuilder sb) {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "time", "-t", String.valueOf(LOGCAT_LINES),
                    "--pid=" + Process.myPid()});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("(logcat unavailable: ").append(e).append(")\n");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    // ------------------------------------------------------------------ 文件

    private static void append(String text) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                AppLog.w(TAG, "cannot create " + dir);
                return;
            }
            File current = new File(dir, FILE);
            if (current.length() > FILE_LIMIT_BYTES) {
                File old = new File(dir, OLD_FILE);
                if (old.exists() && !old.delete()) {
                    AppLog.w(TAG, "cannot delete " + old);
                }
                if (!current.renameTo(old)) {
                    AppLog.w(TAG, "cannot rotate " + current);
                }
            }
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
                writer.write(text);
            } catch (IOException e) {
                AppLog.w(TAG, "cannot write report: " + e.getMessage());
            }
        }
    }

    private static String readReports(Context ctx) {
        if (ctx == null) {
            return "";
        }
        File dir = new File(ctx.getFilesDir(), DIR);
        StringBuilder sb = new StringBuilder();
        synchronized (FILE_LOCK) {
            for (String name : new String[]{OLD_FILE, FILE}) {
                File file = new File(dir, name);
                if (!file.isFile()) {
                    continue;
                }
                try {
                    sb.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    sb.append("(cannot read ").append(name).append(": ").append(e.getMessage()).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 小工具

    private static Trouble trouble(String cameraId) {
        return TROUBLE.computeIfAbsent(String.valueOf(cameraId), key -> new Trouble());
    }

    private static void maybeLogTrouble(Trouble trouble, String cameraId) {
        long now = now();
        if (now - trouble.lastLogMs < TROUBLE_LOG_GAP_MS) {
            return;
        }
        trouble.lastLogMs = now;
        AppLog.w(TAG, "camera " + cameraId + ": lost buffers " + trouble.lost.get()
                + " (last on " + trouble.lastLostTarget + "), failed captures " + trouble.failed.get()
                + " (last reason " + trouble.lastFailReason + ")");
    }

    private static List<LooperProbe> sortedLoopers() {
        List<LooperProbe> list = new ArrayList<>(LOOPERS.values());
        Collections.sort(list, (a, b) -> a.name.compareTo(b.name));
        return list;
    }

    private static boolean isScreenOn() {
        Context ctx = appContext;
        if (ctx == null) {
            return true;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private static String fps(float rate) {
        return rate < 0 ? "n/a" : String.format(Locale.US, "%.1ffps", rate);
    }

    private static String wallClock(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(millis));
    }

    private static String safe(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return "(unavailable: " + e + ")";
        }
    }

    /** 一路相机的请求失败 / 丢帧计数。 */
    private static final class Trouble {
        final AtomicLong lost = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        volatile String lastLostTarget = "-";
        volatile int lastFailReason = -1;
        volatile long lastLogMs = Long.MIN_VALUE / 2;
        // 只有监测线程读写
        long lostAtHealth;
        long failedAtHealth;
    }

    /**
     * 看一个线程有没有被堵住：往它的队列里丢一个空任务，看多久才被执行。
     * 上一个还没执行就不再丢，所以堵住时队列里只会多一个。
     */
    private static final class LooperProbe implements Runnable {
        final String name;
        final Handler handler;
        volatile boolean pending;
        volatile long postedAtMs;
        volatile long maxLagMs;
        long lastLogMs = Long.MIN_VALUE / 2;

        LooperProbe(String name, Handler handler) {
            this.name = name;
            this.handler = handler;
        }

        @Override
        public void run() {
            long lag = now() - postedAtMs;
            if (lag > maxLagMs) {
                maxLagMs = lag;
            }
            pending = false;
        }

        void poke(long nowMs) {
            if (pending) {
                return;
            }
            postedAtMs = nowMs;
            pending = true;
            if (!handler.post(this)) {
                // 线程已经退出
                pending = false;
            }
        }

        long blockedMs(long nowMs) {
            return pending ? nowMs - postedAtMs : 0L;
        }

        long takeMaxLag() {
            long value = maxLagMs;
            maxLagMs = 0L;
            return value;
        }
    }
}
