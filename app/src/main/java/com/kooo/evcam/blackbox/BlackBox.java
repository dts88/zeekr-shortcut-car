package com.kooo.evcam.blackbox;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 黑匣子：把「我们请求了什么」和「实际发生了什么」成对记下来。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>启动、保活、退出这一块是好几轮改动叠出来的，而它到底有没有生效，<b>代码上看不出来</b>：
 * 应用能做的只是发出请求（起服务、拿唤醒锁、注册广播、申请权限），至于这台车机的 ROM
 * 是照做、是悄悄忽略、还是明着拒绝，只有实测知道。已经栽过一次：看到 {@code START_STICKY}
 * 就以为服务会被拉起来，漏了「那是个普通后台服务」这一层。</p>
 *
 * <p>所以这里<b>只记录，不判断，也不做任何动作</b>。攒够一段时间的时间线之后，
 * 「哪些是系统真的听了、哪些是它装没听见」自己就浮出来了。</p>
 *
 * <h3>每一行都自带三个时间</h3>
 *
 * <pre>
 *   09-20 14:32:07.412  up=1832s  slept=417s  pid=12841  service CameraForegroundService onCreate
 * </pre>
 *
 * <ul>
 *   <li>{@code up} —— 开机以来的<b>清醒</b>时长（{@code uptimeMillis}，不含深度睡眠）；</li>
 *   <li>{@code slept} —— 开机以来<b>深度睡眠</b>的累计时长（{@code elapsedRealtime} 减 {@code uptimeMillis}）。
 *       停一夜之后这个数涨了多少，就是车机真正睡了多久 —— 「防止休眠有没有用」直接看它；</li>
 *   <li>{@code pid} —— 进程号。换了号就是换了一条命，中间那段空白就是「我们不在」。</li>
 * </ul>
 *
 * <h3>高频广播只数不记</h3>
 *
 * <p>{@code BATTERY_CHANGED} 这种在车上可能几秒一次，全记下来会把文件刷爆。所以
 * {@link #count} 的做法是：<b>第一次立刻记</b>（「它到底会不会来」这个问题，第一次就回答完了），
 * 之后只累加，每隔一段时间汇总一行。</p>
 *
 * <p>文件在应用私有目录的 {@code blackbox/} 下，重启还在，诊断报告和「保存日志」都会带上。</p>
 */
public final class BlackBox {

    private static final String TAG = "BlackBox";
    private static final String DIR = "blackbox";
    private static final String FILE = "events.log";
    private static final String OLD_FILE = "events.1.log";

    /** 超过这么大就轮换，只留上一份。 */
    private static final long FILE_LIMIT_BYTES = 512L * 1024L;

    /** 计数类事件多久汇总一行。 */
    private static final long COUNT_FLUSH_MS = 5 * 60 * 1000L;

    /** 还没拿到 Context 时先攒在内存里的上限。 */
    private static final int PENDING_LIMIT = 200;

    private static final Object LOCK = new Object();
    private static final List<String> PENDING = new ArrayList<>();
    private static final Map<String, int[]> COUNTS = new LinkedHashMap<>();

    private static volatile Context appContext;
    private static volatile boolean started;
    private static long lastCountFlushMs;

    private BlackBox() {
    }

    // ================================================================= 接上

    /**
     * 进程里<b>最早跑到的那个组件</b>调用，记下「这一条命是被谁开的」。
     *
     * <p>这一行最值钱：它直接回答「退出之后还有什么会把我们拉起来」。
     * 重复调用无害，只有第一次算数。</p>
     *
     * @param starter 谁在调用，例如 {@code "ContentProvider"}、{@code "Service:CameraForegroundService"}、
     *                {@code "Receiver:android.intent.action.SCREEN_ON"}
     */
    public static void attach(Context context, String starter) {
        if (context == null) {
            return;
        }
        boolean first;
        synchronized (LOCK) {
            first = !started;
            if (first) {
                started = true;
            }
            if (appContext == null) {
                appContext = context.getApplicationContext();
            }
        }
        if (!first) {
            return;
        }
        noteImportant("==== 进程启动，起因: " + starter + " ====");
        // 这一次进程起来时开关是什么样的 —— 事后看一段时间线，才知道当时在什么设置下
        noteImportant("开关: " + describeSwitches(appContext));
        appendPreviousExits();
    }

    /**
     * 几个和启动、保活、相机去留相关的开关，一行。
     *
     * <p>进程每次启动记一遍，诊断报告导出时再列一遍，设置页里改动时另记一笔 ——
     * 于是任何一段时间线都能对上当时的开关。只列会影响「谁在什么时候用相机」的那几个；
     * 全部设置的原始值在诊断报告 6.1 里。</p>
     */
    public static String describeSwitches(Context context) {
        if (context == null) {
            return "(没有 context)";
        }
        try {
            com.kooo.evcam.AppConfig c = new com.kooo.evcam.AppConfig(context);
            return "开机自启动=" + onOff(c.isAutoStartOnBoot())
                    + " 自动录制=" + onOff(c.isAutoStartRecording())
                    + " 熄屏录制=" + onOff(c.isScreenOffRecordingEnabled())
                    + (c.isScreenOffRecordingStoredOn() && !c.isScreenOffRecordingEnabled()
                            ? "(存着是开，开发者选项没解锁，没生效)" : "")
                    + " 定时保活=" + onOff(c.isKeepAliveEnabled()) + "(开关未接线)"
                    + " 常驻唤醒锁=" + onOff(c.isPersistentWakeLockEnabled())
                    + " 超级后视镜=" + onOff(c.isRearViewEnabled())
                    + " 按键模式=" + onOff(c.isRearViewButtonMode())
                    + " 录制悬浮按钮=" + onOff(c.isRecordingFloatingEnabled());
        } catch (Throwable t) {
            return "(读开关失败: " + t + ")";
        }
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    /** 已经接上了没有。没接上时事件先攒在内存里。 */
    public static boolean isAttached() {
        return appContext != null;
    }

    // ================================================================= 记

    /** 普通事件：写盘并 flush，但不等落盘。 */
    public static void note(String event) {
        write(event, false);
    }

    /**
     * 要紧的事件：<b>同步落盘</b>。
     *
     * <p>进程启动、退出、服务销毁这类 —— 记完下一刻可能就没了，不能赌缓存。</p>
     */
    public static void noteImportant(String event) {
        write(event, true);
    }

    /**
     * 高频事件：第一次立刻记，之后只计数，每 {@link #COUNT_FLUSH_MS} 汇总一行。
     *
     * @param kind 事件种类，例如广播的 action
     */
    public static void count(String kind) {
        boolean firstTime;
        synchronized (LOCK) {
            int[] slot = COUNTS.get(kind);
            if (slot == null) {
                slot = new int[]{0};
                COUNTS.put(kind, slot);
                firstTime = true;
            } else {
                firstTime = false;
            }
            slot[0]++;
        }
        if (firstTime) {
            note("首次收到: " + kind);
        }
        flushCountsIfDue();
    }

    /** 汇总一行「这五分钟里各种事件来了多少次」。 */
    public static void flushCountsIfDue() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder line = null;
        synchronized (LOCK) {
            if (COUNTS.isEmpty() || now - lastCountFlushMs < COUNT_FLUSH_MS) {
                return;
            }
            lastCountFlushMs = now;
            line = new StringBuilder("计数汇总:");
            for (Map.Entry<String, int[]> entry : COUNTS.entrySet()) {
                line.append(' ').append(shortAction(entry.getKey()))
                        .append('=').append(entry.getValue()[0]);
            }
            COUNTS.clear();
        }
        note(line.toString());
    }

    // ================================================================= 写

    private static void write(String event, boolean sync) {
        String line = stamp() + "  " + event;
        Context context = appContext;
        if (context == null) {
            synchronized (LOCK) {
                if (PENDING.size() < PENDING_LIMIT) {
                    PENDING.add(line);
                }
            }
            return;
        }
        List<String> batch = null;
        synchronized (LOCK) {
            if (!PENDING.isEmpty()) {
                batch = new ArrayList<>(PENDING);
                PENDING.clear();
            }
        }
        try {
            File file = fileIn(context);
            rotateIfBig(file);
            synchronized (LOCK) {
                try (FileOutputStream out = new FileOutputStream(file, true);
                     Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                    if (batch != null) {
                        for (String pending : batch) {
                            writer.write(pending);
                            writer.write('\n');
                        }
                    }
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                    if (sync) {
                        out.getFD().sync();
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "写黑匣子失败: " + t);
        }
    }

    private static File fileIn(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, FILE);
    }

    private static void rotateIfBig(File file) {
        try {
            if (file.length() < FILE_LIMIT_BYTES) {
                return;
            }
            File old = new File(file.getParentFile(), OLD_FILE);
            if (old.exists() && !old.delete()) {
                return;
            }
            if (!file.renameTo(old)) {
                AppLog.w(TAG, "轮换失败，继续往原文件写");
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "轮换出错: " + t);
        }
    }

    // ================================================================= 上一条命是怎么没的

    /**
     * 把系统记的「上几次进程退出原因」并进同一条时间线。
     *
     * <p>进程被杀的那一刻我们什么都跑不了，但系统替我们记了 —— 是内存不足、是用户操作、
     * 还是崩溃，这里一次性补上。</p>
     */
    private static void appendPreviousExits() {
        Context context = appContext;
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            List<ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (exits == null || exits.isEmpty()) {
                note("（系统没有记录过上一次进程退出）");
                return;
            }
            noteNewestAnrTrace(context, exits);
            for (ApplicationExitInfo exit : exits) {
                note("上次退出: " + wallClock(exit.getTimestamp())
                        + " pid=" + exit.getPid()
                        + " 原因=" + reasonName(exit.getReason())
                        + " status=" + exit.getStatus()
                        + " 当时重要性=" + exit.getImportance()
                        + (exit.getDescription() != null ? " (" + exit.getDescription() + ")" : ""));
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "读进程退出原因失败: " + t);
        }
    }

    /** 抄过的最后一次 ANR 的时间戳存在这里，同一次 ANR 只抄一遍。 */
    private static final String SEEN_PREFS = "blackbox_seen";
    private static final String KEY_LAST_ANR = "last_anr_ts";
    /** 主线程抄多少帧。够看出卡在哪一个调用里就行。 */
    private static final int ANR_FRAMES = 25;

    /**
     * ANR 的那一次，把主线程卡在哪抄进来。
     *
     * <p>2026-09-24 那次进程是以 ANR 结束的（弹出「应用无响应」，用户点了关闭），
     * 可当时没有任何东西说明主线程卡在哪 —— 只能从旁证猜。系统其实替 ANR 留了一份
     * 线程快照（{@code getTraceInputStream}），只是要等进程重新起来才读得到，所以放在这里。</p>
     *
     * <p>只抄 {@code "main"} 那一段：ANR 问的就是它。同一次 ANR 以后每次启动都会出现在
     * 「上次退出」里，所以记下时间戳，只抄一遍。每一帧单独一行，保持「一行一件事」。</p>
     */
    private static void noteNewestAnrTrace(Context context, List<ApplicationExitInfo> exits) {
        ApplicationExitInfo anr = null;
        for (ApplicationExitInfo exit : exits) {
            if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                anr = exit;   // 系统按新到旧给，第一个就是最近的
                break;
            }
        }
        if (anr == null) {
            return;
        }
        SharedPreferences seen = context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE);
        if (seen.getLong(KEY_LAST_ANR, 0L) >= anr.getTimestamp()) {
            return;
        }
        seen.edit().putLong(KEY_LAST_ANR, anr.getTimestamp()).apply();

        List<String> frames = mainThreadOf(anr);
        noteImportant("ANR 现场：" + wallClock(anr.getTimestamp()) + " pid=" + anr.getPid()
                + (frames.isEmpty() ? "，系统没留主线程快照" : "，主线程如下"));
        for (String frame : frames) {
            note("ANR 主线程 | " + frame);
        }
    }

    private static List<String> mainThreadOf(ApplicationExitInfo anr) {
        List<String> out = new ArrayList<>();
        try (InputStream in = anr.getTraceInputStream()) {
            if (in == null) {
                return out;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean inMain = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!inMain) {
                    if (line.startsWith("\"main\"")) {
                        inMain = true;
                        out.add(line.trim());
                    }
                    continue;
                }
                if (line.trim().isEmpty()) {
                    break;   // 线程和线程之间隔一个空行
                }
                out.add(line.trim());
                if (out.size() >= ANR_FRAMES) {
                    break;
                }
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "读 ANR 快照失败: " + t);
        }
        return out;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_CRASH: return "崩溃";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native崩溃";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "依赖进程没了";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "占资源太多";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "自己退的(System.exit)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "初始化失败";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "内存不足";
            case ApplicationExitInfo.REASON_OTHER: return "其它";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "权限变化";
            case ApplicationExitInfo.REASON_SIGNALED: return "收到信号";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "用户要求";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "用户停止";
            case ApplicationExitInfo.REASON_UNKNOWN: return "未知";
            default: return "reason=" + reason;
        }
    }

    // ================================================================= 导出

    /** 诊断报告 / 保存日志用。只取末尾一段，完整的在文件里。 */
    public static String export(Context context, int maxChars) {
        if (context == null) {
            return "（没有上下文）\n";
        }
        StringBuilder sb = new StringBuilder();
        try {
            File file = new File(new File(context.getFilesDir(), DIR), FILE);
            if (!file.isFile()) {
                return "还没有记录。\n";
            }
            String text = tail(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8), maxChars);
            sb.append(text);
            if (!text.endsWith("\n")) {
                sb.append('\n');
            }
        } catch (IOException e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        return sb.toString();
    }

    // ================================================================= 零碎

    private static String stamp() {
        long up = SystemClock.uptimeMillis();
        long slept = SystemClock.elapsedRealtime() - up;
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  up=" + (up / 1000) + "s"
                + "  slept=" + (slept / 1000) + "s"
                + "  pid=" + Process.myPid();
    }

    private static String wallClock(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    /**
     * 只要末尾这么多字，而且<b>从完整的一行开始</b>。
     *
     * <p>从半行开始的时间线会让人把两条记录读成一条 —— 报告里最不该出现的就是
     * 「看起来像真的」的假象。</p>
     */
    static String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int from = text.length() - maxChars;
        int cut = text.indexOf('\n', from);
        String kept = cut < 0 ? text.substring(from) : text.substring(cut + 1);
        return "…（更早的在文件里）\n" + kept;
    }

    /** 广播 action 太长，记短名就够认。 */
    static String shortAction(String action) {
        if (action == null) {
            return "null";
        }
        int dot = action.lastIndexOf('.');
        return dot >= 0 && dot < action.length() - 1 ? action.substring(dot + 1) : action;
    }
}
