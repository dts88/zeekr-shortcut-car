package com.kooo.evcam.screen;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.blackbox.ChangeTracker;

import java.util.Map;
import java.util.TreeMap;

/**
 * 每块显示屏的状态（亮、灭、打盹……）变了就进黑匣子一行。<b>只记录，不做任何判断，谁也不听它。</b>
 *
 * <h3>为什么要（2026-10-12，项目所有者同意先只加记录）</h3>
 *
 * <p>这台 7X 上车主下车走开，中控屏真的黑了，安卓却还是「可交互」：没有熄屏广播，黑匣子心跳一直 {@code screenOn=true}；
 * 熄屏广播要等约 4 分钟后下电才来（先 U 盘写不进 EIO，不到 1 秒熄屏，再 3–5 秒进程被结束或冻住）。当时在车上试了一次，
 * 看哪个信号和「屏幕真黑了」对得上：这里是安卓自己的那一份（每块屏的 {@link Display#getState()}），车辆信号那一份在
 * {@code telemetry.EcarxSource}（{@code DarkScreenProbes}）。结果是车辆信号对得上（屏幕电源事件、点火），安卓这一份和熄屏广播一样
 * 只在下电时变 —— 熄屏判断（{@link ScreenState}）看车辆信号；这里照旧只记录，诊断报告「显示屏」那一节也读它
 * （{@link #describeNow}、{@link #stateName}）。</p>
 *
 * <h3>怎么记</h3>
 *
 * <ul>
 *   <li>进程里注册一次 {@link DisplayManager.DisplayListener}（跟 {@link ScreenState#install} 一起），回调在<b>自己的一条线程</b>上：
 *       黑匣子那一行的时刻是在收到的那条线程上取的，主线程卡住的那几秒（平台笔记「主线程会在相机来回开关时卡上好几秒」；
 *       车主下车那一刻车机正好拿走后座舱相机）会让这一行晚到、和车辆信号那几行的先后也乱了 —— 时刻正是这次要看的（评审，2026-10-12）。
 *       装上时记一行「显示屏起点」：每块屏的编号、名字、状态；</li>
 *   <li>{@code onDisplayChanged} 为很多事都会来（刷新率、模式……），<b>状态没变的一律不记</b>；变了记一行
 *       「显示屏：编号（名字）旧 → 新；可交互=是 / 否」（此刻的 {@code isInteractive}）；</li>
 *   <li>屏多了、少了也记，带上此刻有哪几块屏。只记看得到的屏：别的应用私有的虚拟屏拿掉时也会通知到这里，
 *       可那些查不到、没见过，不记。虚拟屏每次编号都是新的，各算各的限量就限不住，所以多了、少了那几行合起来限量
 *       （{@link #SHOWN}）；拿掉的屏连同记下的东西一起扔掉，账本不跟着越记越大；</li>
 *   <li>每一项限量（{@link ChangeTracker}：5 分钟最多 8 行），来回跳也刷不爆黑匣子；压下过的，窗口一过就掐着点补一行停在哪儿，
 *       不等下一次屏的事件（可能永远不来：下电时进程说没就没）。</li>
 * </ul>
 */
public final class DisplayWatch {

    private static final String TAG = "DisplayWatch";
    /** 每一项一个窗口里最多记几次变化，多出来的只数（{@link ChangeTracker}）。 */
    private static final int MAX_LINES_PER_WINDOW = 8;
    private static final long WINDOW_MS = 5 * 60_000L;
    /** 屏被拿掉了，记成这个状态。 */
    private static final String REMOVED = "REMOVED";
    /** {@link #STATES} 里屏多了、少了合起来的那一项，值是此刻看得到的屏的编号（{@link #shown}）；别的项的键都是屏的编号。 */
    private static final String SHOWN = "displays";

    private static boolean installed;
    private static volatile DisplayManager displays;
    private static volatile PowerManager power;
    /** 自己的线程（装上时起）：监听回调、补记都在它上面。 */
    private static volatile Handler watch;

    // 以下几样只在自己的线程上碰
    /** 每块屏的状态（键是编号），加上屏多了、少了那一项（{@link #SHOWN}）。 */
    private static final ChangeTracker STATES = new ChangeTracker(MAX_LINES_PER_WINDOW, WINDOW_MS);
    /** 看得到的屏的名字，按编号排（屏拿掉以后就查不到名字了，先记着；拿掉时一起删）。 */
    private static final Map<Integer, String> NAMES = new TreeMap<>();
    /** 补记排在了哪一刻（{@code elapsedRealtime}）；-1 = 没排。 */
    private static long drainAtMs = -1;
    private static final Runnable DRAIN = DisplayWatch::drainNow;

    private DisplayWatch() {
    }

    /** {@link ScreenState#install} 里调；只有第一次算数。出什么错都只写日志，不影响别的。 */
    static synchronized void install(Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        try {
            power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            final DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                AppLog.w(TAG, "no DisplayManager, display states not logged");
                return;
            }
            displays = dm;
            HandlerThread thread = new HandlerThread("DisplayWatch");
            thread.start();
            final Handler h = new Handler(thread.getLooper());
            watch = h;
            h.post(() -> start(dm, h));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "install failed: " + e);
        }
    }

    /**
     * 自己的线程上：先挂监听再数一遍屏。中间多出来、少了的屏不会漏也不会错：回调排在这一步后面，
     * 没见过的当新增，数过的再来一次只是状态没变。
     */
    private static void start(DisplayManager dm, Handler h) {
        try {
            dm.registerDisplayListener(LISTENER, h);
            StringBuilder all = new StringBuilder();
            for (Display d : dm.getDisplays()) {
                int id = d.getDisplayId();
                String state = stateName(d.getState());
                NAMES.put(id, d.getName());
                STATES.seed(key(id), state);
                append(all, label(id, d.getName()) + state);
            }
            STATES.seed(SHOWN, shown());
            BlackBox.note("显示屏起点：" + all + "；可交互=" + (interactiveNow() ? "是" : "否"));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "start failed: " + e);
        }
    }

    /**
     * 此刻每块屏的状态，现问系统（不用记下来的）：「0（名字）ON，2（名字）OFF」。诊断报告用（要一次 DisplayManager 调用：
     * 不放在熄屏、车机要睡那几步里，那时 DisplayWatch 自己早一秒已经记过了），任何线程都能调；没装上或者出错是「?」。
     */
    public static String describeNow() {
        DisplayManager dm = displays;
        if (dm == null) {
            return ChangeTracker.UNKNOWN;
        }
        try {
            StringBuilder all = new StringBuilder();
            for (Display d : dm.getDisplays()) {
                append(all, label(d.getDisplayId(), d.getName()) + stateName(d.getState()));
            }
            return all.toString();
        } catch (RuntimeException e) {
            return ChangeTracker.UNKNOWN;
        }
    }

    /** 此刻 {@code PowerManager.isInteractive()}；拿不到、出错都当是（和 {@link ScreenState} 一样）。任何线程都能调，不抛。 */
    public static boolean interactiveNow() {
        PowerManager pm = power;
        try {
            return pm == null || pm.isInteractive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** {@link Display} 的状态写成名字（系统那个 {@code stateToString} 是隐藏的）。 */
    public static String stateName(int state) {
        switch (state) {
            case Display.STATE_UNKNOWN: return "UNKNOWN";
            case Display.STATE_OFF: return "OFF";
            case Display.STATE_ON: return "ON";
            case Display.STATE_DOZE: return "DOZE";
            case Display.STATE_DOZE_SUSPEND: return "DOZE_SUSPEND";
            case Display.STATE_VR: return "VR";
            case Display.STATE_ON_SUSPEND: return "ON_SUSPEND";
            default: return "STATE_" + state;
        }
    }

    /** 自己的线程上收到。 */
    private static final DisplayManager.DisplayListener LISTENER = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            seen(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            gone(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            seen(displayId);
        }
    };

    /** 屏多了，或者变了（状态没变的什么都不记）。查不到的屏（别的应用私有的虚拟屏）不记。 */
    private static void seen(int displayId) {
        try {
            DisplayManager dm = displays;
            Display d = dm == null ? null : dm.getDisplay(displayId);
            if (d == null) {
                return;
            }
            String name = d.getName();
            String state = stateName(d.getState());
            long now = SystemClock.elapsedRealtime();
            noteOverflow(now);
            String k = key(displayId);
            boolean known = STATES.value(k) != null;
            NAMES.put(displayId, name);
            if (known) {
                noteChange(displayId, name, STATES.change(k, state, now));
            } else {
                STATES.seed(k, state);
                noteShown(now, displayId, name, ChangeTracker.UNKNOWN + " → " + state, true);
            }
            scheduleDrain();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "display " + displayId + ": " + e);
        }
    }

    /** 屏少了一块：只记见过的（没见过的是别的应用私有的虚拟屏），记完连同记下的东西一起扔掉。 */
    private static void gone(int displayId) {
        try {
            String k = key(displayId);
            String last = STATES.value(k);
            if (last == null) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            noteOverflow(now);
            String name = NAMES.remove(displayId);
            STATES.forget(k);
            noteShown(now, displayId, name, last + " → " + REMOVED, false);
            scheduleDrain();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "display " + displayId + " removed: " + e);
        }
    }

    /** 一块屏的状态变了（没超量）：一行，写完就落盘。 */
    private static void noteChange(int displayId, String name, String change) {
        if (change == null) {
            return;
        }
        BlackBox.noteImportant("显示屏：" + label(displayId, name) + change + "；可交互=" + (interactiveNow() ? "是" : "否"));
    }

    /**
     * 屏多了（{@code added}）或者少了一块：一行，带上此刻有哪几块屏（「0,2 → 0,2,5」）。和别的屏多了、少了合起来限量（{@link #SHOWN}），
     * 超量的只数，窗口过了补一行最后有哪几块屏。
     */
    private static void noteShown(long now, int displayId, String name, String change, boolean added) {
        String shown = STATES.change(SHOWN, shown(), now);
        if (shown == null) {
            return;
        }
        BlackBox.noteImportant("显示屏：" + label(displayId, name) + change + (added ? "（新增）" : "")
                + "；现有屏 " + shown + "；可交互=" + (interactiveNow() ? "是" : "否"));
    }

    /**
     * 超量压下过的项，窗口过了补一行最后停在了哪儿（{@link ChangeTracker#drain}）；
     * 写下的时刻不是变化的时刻，标「限量后补记」。
     */
    private static void noteOverflow(long now) {
        for (Map.Entry<String, String> item : STATES.drain(now).entrySet()) {
            if (SHOWN.equals(item.getKey())) {
                BlackBox.note("显示屏（限量后补记，时刻不是变化的时刻）：现有屏 " + item.getValue());
            } else {
                int id = Integer.parseInt(item.getKey());
                BlackBox.note("显示屏（限量后补记，时刻不是变化的时刻）：" + label(id, NAMES.get(id)) + item.getValue());
            }
        }
    }

    /** 有压下过的项，就掐着它窗口过完的那一刻排一次补记（{@link ChangeTracker#nextDrainAt}）；已经排了、而且不晚，就不动。 */
    private static void scheduleDrain() {
        Handler h = watch;
        long at = STATES.nextDrainAt();
        if (h == null || at < 0 || (drainAtMs >= 0 && drainAtMs <= at)) {
            return;
        }
        h.removeCallbacks(DRAIN);
        drainAtMs = at;
        h.postDelayed(DRAIN, Math.max(0L, at - SystemClock.elapsedRealtime()));
    }

    private static void drainNow() {
        drainAtMs = -1;
        try {
            noteOverflow(SystemClock.elapsedRealtime());
            scheduleDrain();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "catch-up failed: " + e);
        }
    }

    private static String key(int displayId) {
        return String.valueOf(displayId);
    }

    /** 看得到的屏的编号：「0,2,5」。 */
    private static String shown() {
        StringBuilder ids = new StringBuilder();
        for (Integer id : NAMES.keySet()) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        return ids.toString();
    }

    /** 「0（名字）」。 */
    private static String label(int displayId, String name) {
        return displayId + "（" + (name == null ? ChangeTracker.UNKNOWN : name) + "）";
    }

    private static void append(StringBuilder all, String item) {
        if (all.length() > 0) {
            all.append('，');
        }
        all.append(item);
    }
}
