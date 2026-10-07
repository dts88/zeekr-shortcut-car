package com.kooo.evcam.camera;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 别的程序占用 / 放开相机的那一刻，前后几秒里哪些应用起停了前台服务、切了前后台 —— 占用相机的嫌疑应用。
 *
 * <h3>为什么看这两样</h3>
 *
 * <p>相机服务只说「相机 N 被占用 / 空出来了」，不说是谁（{@link CameraAvailabilityWatch}）。
 * Android 11 起应用在后台用相机必须先起前台服务，所以拿相机的那个应用多半在那一刻前后起了前台服务，
 * 或者自己切到了前台；放开时反过来。使用情况访问（{@link UsageStatsManager}）查得到这两类事件。</p>
 *
 * <p><b>只是嫌疑，不是定论</b>：同一时刻起停前台服务、切前后台的可能另有其人；不走应用这一层的占用
 * （车机自己的服务、相机服务里的残留）根本不会出现在这里。黑匣子那一行写明「不是定论」。</p>
 *
 * <h3>规则（项目所有者 2026-10-07 定）</h3>
 *
 * <ul>
 *   <li>开发者选项里的开关，默认关（{@link AppConfig#isCameraHolderSuspectsEnabled}）；关着什么都不做；</li>
 *   <li>窗口是那一刻之前 {@link #BEFORE_MS} 到之后 {@link #AFTER_MS}，我们自己不算；</li>
 *   <li>「之后」那几秒的事件在回调那一刻还没发生，所以过 {@link #QUERY_DELAY_MS} 再查，
 *       在自己的后台线程上查 —— 不在主线程（回调在那里），也不在相机线程；每次争用写一行；</li>
 *   <li>没授权使用情况访问：每 {@link #NO_ACCESS_NOTE_MS} 最多提示一行；</li>
 *   <li>本进程里每一路查到过谁、几次，留在内存里给诊断报告 2.3.1（有上限）。</li>
 * </ul>
 *
 * <p>只记，不改任何行为。</p>
 */
public final class CameraHolderSuspects {

    private static final String TAG = "CameraHolderSuspects";

    /** 往前看多久：别的应用一般先起前台服务（或切到前台），再开相机。 */
    static final long BEFORE_MS = 10_000L;
    /** 往后看多久：相机服务的通知可能比那个应用自己的事件先到。 */
    static final long AFTER_MS = 3_000L;
    /** 那一刻过去多久再查：「之后」那几秒过完，再给系统一点时间把事件记下。 */
    static final long QUERY_DELAY_MS = AFTER_MS + 2_000L;
    /** 一行最多列几条，多出来的只写个数。 */
    static final int MAX_LISTED = 6;
    /** 没授权时多久最多提示一行。 */
    static final long NO_ACCESS_NOTE_MS = 30 * 60 * 1000L;
    /** 诊断报告的汇总：每一路最多记多少个不同的应用、最多记几路（内存有界）。 */
    static final int MAX_PACKAGES_PER_CAMERA = 32;
    static final int MAX_CAMERAS = 8;

    /** 事件种类，也是 {@link #describe} 里种类叫法的下标。 */
    static final int FGS_START = 0;
    static final int FGS_STOP = 1;
    static final int TO_FOREGROUND = 2;
    static final int TO_BACKGROUND = 3;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    /** 懒建：开关开着、真有争用时才起这条线程。 */
    private static Handler worker;
    /** 上一次提示「未授权」的时刻（开机起算），只在 {@link #worker} 上读写。 */
    private static long lastNoAccessNoteAt = -1;
    /** 相机编号 → 汇总，按编号排。由 {@link #LOCK} 保护。 */
    private static final Map<String, Tally> TALLY = new TreeMap<>();

    private CameraHolderSuspects() {
    }

    // ================================================================= 纯规则

    /** 一条使用情况事件里用得着的三样。 */
    static final class Event {
        final String pkg;
        /** {@link UsageEvents.Event} 的类型。 */
        final int type;
        /** 墙上时间（毫秒）。 */
        final long atMs;

        Event(String pkg, int type, long atMs) {
            this.pkg = pkg;
            this.type = type;
            this.atMs = atMs;
        }
    }

    /** 一个嫌疑：哪个应用、做了什么、比那一刻早（负）或晚（正）多少毫秒。 */
    static final class Suspect {
        final String pkg;
        final int kind;
        final long offsetMs;

        Suspect(String pkg, int kind, long offsetMs) {
            this.pkg = pkg;
            this.kind = kind;
            this.offsetMs = offsetMs;
        }
    }

    /** 使用情况事件的类型 → 种类；不关心的返回 -1。切到前台 / 后台就是 API 29 前的 MOVE_TO_FOREGROUND / BACKGROUND。 */
    static int kindOf(int usageEventType) {
        switch (usageEventType) {
            case UsageEvents.Event.FOREGROUND_SERVICE_START:
                return FGS_START;
            case UsageEvents.Event.FOREGROUND_SERVICE_STOP:
                return FGS_STOP;
            case UsageEvents.Event.ACTIVITY_RESUMED:
                return TO_FOREGROUND;
            case UsageEvents.Event.ACTIVITY_PAUSED:
                return TO_BACKGROUND;
            default:
                return -1;
        }
    }

    /**
     * 纯规则：窗口内（那一刻之前 {@link #BEFORE_MS} 到之后 {@link #AFTER_MS}，含两端）关心的事件，
     * 去掉我们自己；同一个应用的同一种事件只留离那一刻最近的一条（来回切的不刷屏）；
     * 按离那一刻的远近排，一样近的之前的先列。
     */
    static List<Suspect> find(List<Event> events, long atMs, String ownPackage) {
        Map<String, Suspect> closest = new LinkedHashMap<>();
        for (Event e : events) {
            int kind = kindOf(e.type);
            if (kind < 0 || e.pkg == null || e.pkg.equals(ownPackage)) {
                continue;
            }
            long offset = e.atMs - atMs;
            if (offset < -BEFORE_MS || offset > AFTER_MS) {
                continue;
            }
            String key = e.pkg + '\n' + kind;
            Suspect old = closest.get(key);
            if (old == null || Math.abs(offset) < Math.abs(old.offsetMs)) {
                closest.put(key, new Suspect(e.pkg, kind, offset));
            }
        }
        List<Suspect> out = new ArrayList<>(closest.values());
        Collections.sort(out, (a, b) -> {
            int byDistance = Long.compare(Math.abs(a.offsetMs), Math.abs(b.offsetMs));
            if (byDistance != 0) {
                return byDistance;
            }
            int byTime = Long.compare(a.offsetMs, b.offsetMs);
            if (byTime != 0) {
                return byTime;
            }
            int byPackage = a.pkg.compareTo(b.pkg);
            return byPackage != 0 ? byPackage : Integer.compare(a.kind, b.kind);
        });
        return out;
    }

    /**
     * 一行：{@code com.x.y <种类> -1.2 s；com.a.b <种类> +0.4 s}，最多列 {@link #MAX_LISTED} 条，
     * 多出来的写 {@code …+N}。
     *
     * @param kindNames 种类的叫法，按 {@link #FGS_START}、{@link #FGS_STOP}、{@link #TO_FOREGROUND}、
     *                  {@link #TO_BACKGROUND} 的顺序。只进黑匣子，由记黑匣子的那一句给
     */
    static String describe(List<Suspect> suspects, String... kindNames) {
        StringBuilder sb = new StringBuilder();
        int listed = Math.min(suspects.size(), MAX_LISTED);
        for (int i = 0; i < listed; i++) {
            Suspect s = suspects.get(i);
            if (i > 0) {
                sb.append('；');
            }
            sb.append(s.pkg).append(' ').append(kindNames[s.kind]).append(' ').append(seconds(s.offsetMs));
        }
        if (suspects.size() > listed) {
            sb.append("；…+").append(suspects.size() - listed);
        }
        return sb.toString();
    }

    /** 带正负号、一位小数的秒：-1.2 s、+0.4 s。 */
    static String seconds(long offsetMs) {
        return String.format(Locale.US, "%+.1f s", offsetMs / 1000.0);
    }

    /** 一路相机在本进程里查过几次、每个嫌疑应用出现在其中几次。诊断报告 2.3.1 用；应用个数有上限。 */
    public static final class Tally {
        private int lookups;
        private int dropped;
        private final Map<String, Integer> packages = new HashMap<>();

        /** 记一次查询的结果：同一个应用在这一次里不管出现几条，只算一次。 */
        void add(List<Suspect> found) {
            lookups++;
            Set<String> seen = new LinkedHashSet<>();
            for (Suspect s : found) {
                seen.add(s.pkg);
            }
            for (String pkg : seen) {
                Integer n = packages.get(pkg);
                if (n != null) {
                    packages.put(pkg, n + 1);
                } else if (packages.size() < MAX_PACKAGES_PER_CAMERA) {
                    packages.put(pkg, 1);
                } else {
                    dropped++;
                }
            }
        }

        Tally copy() {
            Tally c = new Tally();
            c.lookups = lookups;
            c.dropped = dropped;
            c.packages.putAll(packages);
            return c;
        }

        /** 查了几次（每次争用一次，未授权和查询失败的不算）。 */
        public int lookups() {
            return lookups;
        }

        /** 超出 {@link #MAX_PACKAGES_PER_CAMERA} 没记进来的次数。 */
        public int dropped() {
            return dropped;
        }

        /** 应用 → 次数，次数多的在前，一样多的按包名。 */
        public List<Map.Entry<String, Integer>> ranked() {
            List<Map.Entry<String, Integer>> out = new ArrayList<>(packages.entrySet());
            Collections.sort(out, (a, b) -> {
                int byCount = Integer.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            });
            return out;
        }
    }

    // ================================================================= 接上相机服务

    /** 开始听相机可用性的时候接上（{@link CameraAvailabilityWatch#start}）。 */
    static void attach(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /** 开关此刻开着没有（开发者选项关着时按关算）。 */
    public static boolean isEnabled(Context context) {
        try {
            return context != null && new AppConfig(context).isCameraHolderSuspectsEnabled();
        } catch (Throwable t) {
            AppLog.w(TAG, "read switch: " + t);
            return false;
        }
    }

    /** 使用情况访问授权了没有。权限页、诊断报告、这里查询前都问它。 */
    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // 没单独设过：按权限本身算（有的 ROM 走这条）
                return context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 别的程序刚占用（{@code taken}）或放开了相机 {@code cameraId}。
     *
     * <p>开关关着什么都不做。开着就记下此刻，过 {@link #QUERY_DELAY_MS} 在自己的线程上查、写一行；
     * 调用的地方（主线程上的相机服务回调）不等。</p>
     */
    static void lookAround(String cameraId, boolean taken) {
        Context app = appContext;
        if (app == null || !isEnabled(app)) {
            return;
        }
        long atMs = System.currentTimeMillis();
        try {
            worker().postDelayed(() -> {
                try {
                    report(app, cameraId, taken, atMs);
                } catch (Throwable t) {
                    // 只是一条线索，出什么错都不能把进程带走
                    AppLog.w(TAG, "report: " + t);
                }
            }, QUERY_DELAY_MS);
        } catch (Throwable t) {
            AppLog.w(TAG, "schedule: " + t);
        }
    }

    private static Handler worker() {
        synchronized (LOCK) {
            if (worker == null) {
                HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    /** 在 {@link #worker} 上：查那段时间的事件，写一行。 */
    private static void report(Context app, String cameraId, boolean taken, long atMs) {
        boolean granted = hasUsageAccess(app);
        if (granted) {
            lastNoAccessNoteAt = -1;  // 授权后又被收回的话，下一次立刻提示
        } else {
            long now = SystemClock.elapsedRealtime();
            if (lastNoAccessNoteAt >= 0 && now - lastNoAccessNoteAt < NO_ACCESS_NOTE_MS) {
                return;
            }
            lastNoAccessNoteAt = now;
        }
        List<Suspect> found = Collections.emptyList();
        String failure = null;
        if (granted) {
            try {
                found = find(query(app, atMs - BEFORE_MS, atMs + AFTER_MS + 1), atMs, app.getPackageName());
                tally(cameraId, found);
            } catch (Throwable t) {
                AppLog.w(TAG, "queryEvents: " + t);
                failure = t.getClass().getSimpleName();
            }
        }
        BlackBox.noteImportant("争用：" + clock(atMs) + (taken ? " 占用" : " 放开") + "相机 " + cameraId
                + " 的嫌疑应用（不是定论）："
                + (!granted ? "查不到（未授权使用情况访问，" + (NO_ACCESS_NOTE_MS / 60_000L) + " 分钟内不再提示）"
                : failure != null ? "查询失败（" + failure + "）"
                : found.isEmpty() ? "这段时间没有别的应用启动或停止前台服务、切换前后台"
                : describe(found, "前台服务启动", "前台服务停止", "切到前台", "切到后台")));
    }

    /** 这段时间（墙上时间，含头不含尾）里关心的那几类事件。 */
    private static List<Event> query(Context app, long beginMs, long endMs) {
        List<Event> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return out;
        }
        UsageEvents events = usm.queryEvents(beginMs, endMs);
        if (events == null) {
            return out;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (kindOf(e.getEventType()) >= 0) {
                out.add(new Event(e.getPackageName(), e.getEventType(), e.getTimeStamp()));
            }
        }
        return out;
    }

    private static void tally(String cameraId, List<Suspect> found) {
        synchronized (LOCK) {
            Tally tally = TALLY.get(cameraId);
            if (tally == null) {
                if (TALLY.size() >= MAX_CAMERAS) {
                    return;
                }
                tally = new Tally();
                TALLY.put(cameraId, tally);
            }
            tally.add(found);
        }
    }

    /** 本进程里每一路的汇总（拷贝），按相机编号排。诊断报告用。 */
    public static Map<String, Tally> tally() {
        Map<String, Tally> out = new TreeMap<>();
        synchronized (LOCK) {
            for (Map.Entry<String, Tally> e : TALLY.entrySet()) {
                out.put(e.getKey(), e.getValue().copy());
            }
        }
        return out;
    }

    private static String clock(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(wallMs));
    }
}
