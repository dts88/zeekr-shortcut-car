package com.kooo.evcam.screen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.ScreenOffRecording;
import com.kooo.evcam.recovery.Recovery;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕亮着还是黑着 —— 进程里唯一的一份，也是唯一写它的地方。
 *
 * <h3>熄屏 = 屏幕真黑了（项目所有者 2026-10-12 D1，设计 v3 §1）</h3>
 *
 * <p>这台 7X 上安卓的熄屏广播、{@code isInteractive} 只在车机下电时才变：车主下车、中控屏真黑了，安卓还说「亮着」，
 * 要再过四到六分钟（哨兵模式开着时一直不变）。屏幕真黑看得到的是车辆信号（{@link VehicleScreen}）。所以「熄屏」的所有功能
 * 都跟着屏幕走：安卓熄屏，或者车辆说黑 —— 车辆信号读不到时只看安卓（和 2.11.1 一样）。两样各自变出四条边
 * （{@link ScreenTransitions}）：</p>
 *
 * <ul>
 *   <li><b>熄屏</b>（E1）：唤醒锁、录像那张表（{@link RecordingCoordinator#screenOff}）、监听者（相机层放开座舱、主界面、后视镜）；</li>
 *   <li><b>车机要睡</b>（E2，黑着时安卓熄屏 = 车机下电）：只叫录像那张表再判一次（{@link RecordingCoordinator#headUnitSleeps}）——
 *       现在停、按次序关（这时只剩环视）；监听者不叫（屏幕早就黑了）；</li>
 *   <li><b>车机醒着、屏幕黑着</b>（E2'，黑着时安卓又亮了：车机自己醒了一阵）：撤掉「车机要睡」、照表接不接
 *       （{@link RecordingCoordinator#headUnitWoke}），按开机自启动恢复服务（不拉界面）；监听者不叫（还黑着）；</li>
 *   <li><b>亮屏</b>（E3）：和以前的亮屏一样，按固定顺序走一遍。</li>
 * </ul>
 *
 * <p>顺序固定：熄屏录制的唤醒锁（{@link ScreenOffRecording}）→ 录像的规矩（{@link RecordingCoordinator}）→ 相机（谁要用就登记，
 * 调度照登记表和「熄屏放开座舱」开关，{@code ChannelRules.releasedByDark}）→ 界面（因熄屏退下去的主界面亮屏接回来，
 * {@link Recovery#bringBackUiAfterScreenOn}）→ 主界面、后视镜、相机层自己登记的监听者。每条边出了错写一行黑匣子，
 * 看得出做到了哪一步。</p>
 *
 * <h3>安卓那一半</h3>
 *
 * <p>熄屏广播可靠；深睡醒来之后的亮屏广播一次都没到过（2026-09-24）。所以安卓熄屏着的时候每 {@link #WAKE_POLL_MS} 问一次系统
 * （{@link #WAKE_POLL}，深睡时计时停住，醒来两秒内必然问到）—— 它只看安卓，永远结束不了车辆说的黑。{@link #refresh} 也只重读安卓。</p>
 *
 * <p><b>安卓刚亮的那一下不信记着的车辆读数</b>（设计 v3.1 C2/C6）：睡之前最后读到的是「黑」，醒来先拿它判，每个早上都会被当成
 * 「车机自己醒了」。所以安卓亮了先让车辆信号当场读一遍（{@link Telemetry#reconcileNow}），读回来再判 E2' 还是 E3，最多等
 * {@link #WAKE_SETTLE_MAX_MS}（等满了、接口没连着就只看安卓）。这一段只有一个在等（世代号），广播、轮询、{@link #refresh}
 * 都不会再起第二个；等的时候安卓又熄屏就作废（还算车机睡着）。车辆接口连不上的<b>先再连一次</b>，在连就等它连上后的第一份读数
 * 或者连不上的结果（和进程刚起来那一段同一个判法，{@link ScreenTransitions#linkSettled}），同样最多等那么久 —— 以前先判边、
 * 后再连：这一遍只看安卓判成亮屏、座舱开回来，连上以后又读到黑、再放开（审查 rev3 F1）。</p>
 *
 * <p><b>不知道屏幕黑没黑时不动手</b>（C11）：进程起来时安卓亮着，车辆信号还没读到（约 0.1–1.2 秒），这时屏幕可能早就黑了 ——
 * 恢复（{@link Recovery}）、接回录像都排到知道了再做（{@link #whenKnown}），最多等 {@link #STARTUP_SETTLE_MAX_MS}。
 * 和上面安卓刚亮那一段是同一件事：屏幕此刻不知道（{@link #known}）。</p>
 *
 * <p>状态只在主线程上写，换的时候整份换（{@link Facts}）；哪个线程都能读（相机关闭线程上的「熄屏后 +N ms」、诊断报告）。</p>
 */
public final class ScreenState {

    private static final String TAG = "ScreenState";
    /** 安卓熄屏着的时候多久问一次系统「亮了没」。 */
    static final long WAKE_POLL_MS = 2_000L;
    /** 安卓亮了以后最多等醒来那一遍车辆信号这么久，等满了只看安卓（C6）。 */
    static final long WAKE_SETTLE_MAX_MS = 2_000L;
    /** 进程起来、安卓亮着时最多等第一份车辆判断（或车辆接口的结果）这么久（C11）。 */
    static final long STARTUP_SETTLE_MAX_MS = 2_500L;

    /** 主线程上收到。只在熄屏（E1）、亮屏（E3）时叫 —— 车机要睡、车机黑着醒了都还是熄屏，不叫。 */
    public interface Listener {
        void onScreenOff();

        void onScreenOn();
    }

    /** 这一次熄屏是谁判的。 */
    public enum Source {
        /** 车辆信号，看到了它从亮变黑的那一下。 */
        VEHICLE,
        /** 车辆信号，读到时已经黑着（进程刚起来、或者之前读不到）：起点不明。 */
        VEHICLE_READ,
        /** 安卓熄屏（车辆信号读不到、或者说亮着）。 */
        ANDROID
    }

    /** 此刻屏幕的样子（{@link #describeNow}，诊断报告用）：一次取齐的一份，不可变。文字由报告那边写。 */
    public static final class Now {
        /** 熄屏（屏幕真黑了）。 */
        public final boolean dark;
        /** 这一次熄屏是谁判的；亮着是 null。 */
        public final Source source;
        /** 熄屏多久了（毫秒，含深睡）；亮着、不知道什么时候黑的是 -1。 */
        public final long darkForMs;
        /** 安卓熄屏着（判断用的那一份；安卓刚亮、还在等车辆信号时还是 true）。 */
        public final boolean androidOff;
        /** 黑着时安卓熄屏多久了（车机要睡起）；没有是 -1。 */
        public final long androidOffForMs;
        /** 判断用着的车辆判断：TRUE 黑，FALSE 亮，null 只看安卓。 */
        public final Boolean vehicle;
        /** 此刻读数里的屏幕电源事件（TRUE = 1）、点火原值；读不到是 null。 */
        public final Boolean screenPowerEvent;
        public final Integer ignition;
        /** 车辆接口此刻的结果；核心还没登记是 null。 */
        public final Telemetry.CarLink carLink;
        /** 此刻 {@code isInteractive}（现问系统）。 */
        public final boolean interactive;
        /** 屏幕此刻知道（不在等第一份车辆判断、不在等安卓刚亮那一遍）。 */
        public final boolean known;
        /** 在等安卓刚亮那一遍车辆信号。 */
        public final boolean wakePending;

        Now(boolean dark, Source source, long darkForMs, boolean androidOff, long androidOffForMs, Boolean vehicle,
            Boolean screenPowerEvent, Integer ignition, Telemetry.CarLink carLink, boolean interactive, boolean known,
            boolean wakePending) {
            this.dark = dark;
            this.source = source;
            this.darkForMs = darkForMs;
            this.androidOff = androidOff;
            this.androidOffForMs = androidOffForMs;
            this.vehicle = vehicle;
            this.screenPowerEvent = screenPowerEvent;
            this.ignition = ignition;
            this.carLink = carLink;
            this.interactive = interactive;
            this.known = known;
            this.wakePending = wakePending;
        }
    }

    /** 判断状态和它带的几样，整份换（不可变）。 */
    private static final class Facts {
        final ScreenTransitions.State state;
        /** 这一次熄屏的那一刻（elapsedRealtime，含深睡）；0 = 亮着，或者进程起来时就黑着（不知道什么时候黑的）。 */
        final long darkAtMs;
        /** 黑着时安卓熄屏的那一刻（elapsedRealtime）；0 = 安卓没熄屏，或者不知道。 */
        final long androidOffAtMs;
        /** 这一次熄屏是谁判的；亮着是 null。 */
        final Source source;
        /** 上一次看到的两项原值（黑匣子里写「0 → 1」）；车辆接口没连着是 null。 */
        final Boolean event;
        final Integer ignition;

        Facts(ScreenTransitions.State state, long darkAtMs, long androidOffAtMs, Source source, Boolean event,
              Integer ignition) {
            this.state = state;
            this.darkAtMs = darkAtMs;
            this.androidOffAtMs = androidOffAtMs;
            this.source = source;
            this.event = event;
            this.ignition = ignition;
        }
    }

    /** 屏幕此刻知不知道（C6、C11）。 */
    private enum Settle {
        /** 知道。 */
        NONE,
        /** 进程刚起来、安卓亮着，在等第一份车辆判断。 */
        STARTUP,
        /** 安卓刚亮，在等醒来那一遍车辆信号。 */
        WAKE
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    /** 不知道屏幕黑没黑时排着、知道了再做的（{@link #whenKnown}）。主线程。 */
    private static final List<Runnable> WHEN_KNOWN = new ArrayList<>();
    private static Context app;
    private static boolean installed;
    private static volatile Facts facts = new Facts(ScreenTransitions.initial(false), 0, 0, null, null, null);
    private static volatile Settle settle = Settle.NONE;
    /** 等的那一段的世代号：作废了的、等满了的，晚到的回调认得出来（主线程）。 */
    private static int settleGen;
    /** 安卓是怎么亮的（broadcast / noticed）、醒来那一遍等满了没读回来：亮屏、E2' 那一行写（主线程）。 */
    private static String wakeHow = "";
    private static boolean wakeCapped;
    /**
     * 安卓刚亮的这一段在等车辆接口连上（连不上的再连了一次、或者赶上它在连）：车辆信号的那一批读数到了就看
     * （{@link #vehicleChanged}），不等 {@link Telemetry#reconcileNow} 的回调。一段里只再连一次（主线程）。
     */
    private static boolean wakeOnLink;

    private ScreenState() {
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}）：广播只能动态注册，而主界面可能不在。车辆信号的核心登记在这里
     * （{@link Telemetry#keepCore}：进程活着一直读离散信号，不开定位），监听者第一个挂上 —— 同一批读数里先判屏幕，
     * 录像协调器的那一份（哨兵模式、挡位）在后面。这里不会当场跨过任何一条边：车辆信号在自己的线程上连、读到了才排到主线程，
     * 录像意图（{@code RecordingIntent}）在这之后才接上。
     */
    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        app = context.getApplicationContext();
        boolean off = !interactive(app);
        facts = new Facts(ScreenTransitions.initial(off), 0, 0, off ? Source.ANDROID : null, null, null);
        Telemetry telemetry = Telemetry.get();
        telemetry.addListener(VEHICLE);
        telemetry.keepCore(app);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    androidWentOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    androidWentOn("broadcast");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
        if (off) {
            startWakePoll();
        } else if (telemetry.carLink() != Telemetry.CarLink.UNAVAILABLE) {
            // 安卓亮着，屏幕可能早就黑了（车主走开以后进程才起来）：读到第一份车辆判断之前不动手（C11）
            settle = Settle.STARTUP;
            settleGen++;
            MAIN.postDelayed(STARTUP_CAP, STARTUP_SETTLE_MAX_MS);
        }
        AppLog.i(TAG, "installed, androidOff=" + off + " settle=" + settle);
        // 2026-10-12 只加记录：每块显示屏的状态变了进黑匣子，谁也不听它
        DisplayWatch.install(app);
    }

    /** 熄屏着（屏幕真黑了：安卓熄屏，或者车辆说黑）。任何线程都能问。不知道的那一小段里（{@link #known}）照已知的说。 */
    public static boolean dark() {
        return facts.state.dark();
    }

    /** 这一次熄屏是谁判的；亮着是 null。任何线程都能问。 */
    public static Source source() {
        Facts f = facts;
        return f.state.dark() ? f.source : null;
    }

    /** 安卓熄屏着（判断用的那一份：安卓刚亮、还在等车辆信号时还算熄屏）。黑着时它就是「车机要睡了 / 睡着」。 */
    public static boolean androidOff() {
        return facts.state.androidOff;
    }

    /**
     * 车辆信号此刻判着屏幕（有车辆的判断）：下一次熄屏是车辆说黑，车机还醒着（E1）。没有时只看安卓 —— 车辆接口没连着
     * （在连、连不上、这台车机没有）、连着却两项读不到超过 {@link ScreenTransitions#KEEP_UNKNOWN_MS}、进程刚起来还没读到：
     * 下一次熄屏就是安卓熄屏，也就是车机下电（E1 和 E2 一起来，降级）。录制键上的小字照它判熄屏那一刻
     * （{@code ui.ScreenOffHintView}，审查 rev3 B1）。任何线程都能问。
     */
    public static boolean vehicleJudges() {
        return facts.state.vehicle != null;
    }

    /**
     * 屏幕此刻知道：不在等进程起来后的第一份车辆判断，也不在等安卓刚亮那一遍车辆信号（C6、C11）。
     * 主界面熄屏 15 秒那一步在不知道的时候过一会儿再看（C7）。
     */
    public static boolean known() {
        return settle == Settle.NONE;
    }

    /**
     * 熄屏多久了（毫秒，含深睡）；亮着、或者不知道什么时候黑的，是 -1。
     * 熄屏后的那几步在黑匣子里记成「熄屏后 +N ms」（{@code BlackBox.afterScreenOff}），任何线程都能问。
     */
    public static long darkForMs() {
        Facts f = facts;
        return f.state.dark() && f.darkAtMs > 0 ? SystemClock.elapsedRealtime() - f.darkAtMs : -1;
    }

    /**
     * 黑着时安卓熄屏多久了（车机要睡起，毫秒）；安卓没熄屏、亮着、不知道是 -1。黑匣子里跟在「熄屏后 +N ms」后面
     * 记「安卓熄屏起 +N ms」（{@code BlackBox.afterScreenOff}）。任何线程都能问。
     */
    public static long androidOffForMs() {
        Facts f = facts;
        return f.state.dark() && f.androidOffAtMs > 0 ? SystemClock.elapsedRealtime() - f.androidOffAtMs : -1;
    }

    /** 此刻的样子，一次取齐（诊断报告）。任何线程都能调。 */
    public static Now describeNow() {
        Facts f = facts;
        Settle s = settle;
        long now = SystemClock.elapsedRealtime();
        Telemetry telemetry = Telemetry.get();
        Readings r = telemetry.readings();
        boolean dark = f.state.dark();
        return new Now(dark, dark ? f.source : null, dark && f.darkAtMs > 0 ? now - f.darkAtMs : -1,
                f.state.androidOff, dark && f.androidOffAtMs > 0 ? now - f.androidOffAtMs : -1, f.state.vehicle,
                r.bool(Signal.SCREEN_POWER_EVENT), r.code(Signal.IGNITION), telemetry.carLink(),
                DisplayWatch.interactiveNow(), s == Settle.NONE, s == Settle.WAKE);
    }

    /**
     * 重新问一遍安卓（主线程）：变了就当收到了相应的广播（安卓刚亮的照样先等车辆信号读回来）。车辆的判断不在这里改 ——
     * 只看安卓的这一下结束不了车辆说的黑；不过有人在（主界面回到前台、点了后视镜）而车辆说黑着时，让车辆信号当场读一遍，
     * 读到亮了照常亮屏（C3）。
     *
     * @return 此刻是不是熄屏着（{@link #dark}）
     */
    public static boolean refresh() {
        Context context = app;
        if (context == null) {
            return dark();
        }
        if (interactive(context)) {
            androidWentOn("noticed");
            ScreenTransitions.State s = facts.state;
            if (!s.androidOff && Boolean.TRUE.equals(s.vehicle)) {
                Telemetry.get().reconcileNow(false, NOTHING);
            }
        } else {
            androidWentOff();
        }
        return dark();
    }

    /**
     * 屏幕此刻知道就当场做，不知道（进程刚起来在等第一份车辆判断、安卓刚亮在等那一遍车辆信号）就排着，知道了按排的先后做（主线程）。
     * 恢复（{@link Recovery}）、接回录像（{@link RecordingCoordinator#resumeIfWanted}）走它：黑着的屏幕上不拉界面、不开不该开的录像。
     */
    public static void whenKnown(Runnable action) {
        if (settle == Settle.NONE) {
            action.run();
            return;
        }
        WHEN_KNOWN.add(action);
    }

    /** 主线程调。 */
    public static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    // ================================================================= 输入（主线程）

    private static final Runnable NOTHING = () -> {
    };

    /** 车辆信号（Telemetry 在主线程上叫；车辆接口的结果变了也叫一次，那时读数可能没变）。 */
    private static final Telemetry.Listener VEHICLE = ScreenState::vehicleChanged;

    private static void vehicleChanged(Readings readings) {
        Telemetry.CarLink link = Telemetry.get().carLink();
        boolean connected = link == Telemetry.CarLink.CONNECTED;
        Boolean event = connected ? readings.bool(Signal.SCREEN_POWER_EVENT) : null;
        Integer ignition = connected ? readings.code(Signal.IGNITION) : null;
        Boolean fresh = VehicleScreen.verdict(event, ignition);
        long now = SystemClock.elapsedRealtime();
        ScreenTransitions.State before = facts.state;
        ScreenTransitions.State next = ScreenTransitions.verdict(before, fresh, connected, now);
        // 进程起来后的第一份、安卓刚亮时等着连上的那一份：读到了判断，或者车辆接口有了结果（连上了要等那一遍读数发布出来：
        // 连上的通知比读数早 40 ms）
        boolean linkSettled = ScreenTransitions.linkSettled(link, fresh, readings.knownCount());
        boolean startupEnds = settle == Settle.STARTUP && linkSettled;
        boolean wakeEnds = settle == Settle.WAKE && wakeOnLink && linkSettled;
        if (startupEnds) {
            endSettle();
        }
        if (connected && before.vehicle != null && next.vehicle == null) {
            BlackBox.noteImportant("车辆信号连接：screen_power_event、点火读不到了 "
                    + (now - before.unknownSinceMs) / 1000 + " 秒，熄屏只看安卓");
        }
        move(next, event, ignition);
        MAIN.removeCallbacks(UNKNOWN_CHECK);
        if (next.unknownSinceMs > 0) {
            MAIN.postDelayed(UNKNOWN_CHECK,
                    Math.max(0L, next.unknownSinceMs + ScreenTransitions.KEEP_UNKNOWN_MS - now));
        }
        if (startupEnds) {
            drainWhenKnown();
        }
        if (wakeEnds) {
            // 连上后的第一份（醒来后读的）或者连不上的结果：照它判 E2' 还是 E3
            wakeRead(settleGen, false);
        }
    }

    /** 两项读不到、照上一次的判断算的时限到了：照此刻的读数再判一次（读数不变就不会再有回调）。 */
    private static final Runnable UNKNOWN_CHECK = () -> vehicleChanged(Telemetry.get().readings());

    /** 进程起来后等第一份车辆判断，等满了：只看安卓。 */
    private static final Runnable STARTUP_CAP = () -> {
        if (settle == Settle.STARTUP) {
            endSettle();
            AppLog.i(TAG, "no vehicle verdict within " + STARTUP_SETTLE_MAX_MS + "ms after start: Android only");
            drainWhenKnown();
        }
    };

    /** 安卓熄屏（广播、{@link #refresh}）。 */
    private static void androidWentOff() {
        if (settle == Settle.WAKE) {
            // 安卓亮了一下，醒来那一遍车辆信号还没读回来就又熄屏：不算醒，还算车机睡着（C6）。安卓熄屏着，轮询照常
            endSettle();
            BlackBox.noteImportant("安卓亮屏（" + wakeHow + "）又熄屏了，没等到车辆信号：还算车机睡着");
            drainWhenKnown();
            return;
        }
        Facts f = facts;
        if (f.state.androidOff) {
            return;
        }
        boolean startup = settle == Settle.STARTUP;
        if (startup) {
            endSettle();
        }
        move(ScreenTransitions.androidOff(f.state), f.event, f.ignition);
        if (startup) {
            drainWhenKnown();
        }
    }

    /**
     * 安卓亮了（广播、轮询、{@link #refresh}）：先让车辆信号当场读一遍，读回来再判（{@link #wakeRead}）；车辆接口连不上的先再连、
     * 等连上后的第一份（{@link #awaitLink}）。只有一个在等：已经在等的不再起第二个。
     */
    private static void androidWentOn(String how) {
        if (!facts.state.androidOff || settle == Settle.WAKE) {
            return;
        }
        settle = Settle.WAKE;
        final int gen = ++settleGen;
        wakeHow = how;
        MAIN.removeCallbacks(WAKE_CAP);
        MAIN.postDelayed(WAKE_CAP, WAKE_SETTLE_MAX_MS);
        Telemetry telemetry = Telemetry.get();
        if (!awaitLink(telemetry)) {
            telemetry.reconcileNow(true, () -> wakeRead(gen, false));
        }
    }

    /**
     * 安卓刚亮、要判 E2' 还是 E3 之前：车辆接口连不上（{@link Telemetry.CarLink#FAILED}）就先再连一次 —— 车机睡醒，车机服务可能刚起来；
     * 此刻在连（刚叫的、或者赶上了每 5 分钟那一次）就等它：连上后的第一份读数是醒来后读的，连不上就只看安卓
     * （{@link #vehicleChanged} 收，{@link ScreenTransitions#linkSettled}），最多等到 {@link #WAKE_SETTLE_MAX_MS}。
     * 一段只等一次（{@link #wakeOnLink}）。以前先判这条边、判完再连：这一遍只看安卓判成亮屏，座舱开回来、主界面拉起来，
     * 连上以后又读到黑，再放开（审查 rev3 F1）。
     *
     * @return true：在等车辆接口，这一段接着等
     */
    private static boolean awaitLink(Telemetry telemetry) {
        if (wakeOnLink) {
            return false;
        }
        telemetry.retryLinkIfFailed();
        if (telemetry.carLink() != Telemetry.CarLink.CONNECTING) {
            return false;
        }
        wakeOnLink = true;
        return true;
    }

    /** 安卓亮了以后等醒来那一遍车辆信号，等满了：没读回来，只看安卓。 */
    private static final Runnable WAKE_CAP = () -> wakeRead(settleGen, true);

    /**
     * 醒来那一遍车辆信号读回来了（监听者那一批已经先到，读数是醒来后的），或者等着的车辆接口连上了、有了结果，或者等满了：
     * 再问一次安卓、对一下世代号，然后照这一遍判 E2'（车辆还说黑）还是 E3。醒来那一遍读的时候车辆接口断了（连着两遍没读到）的，
     * 先再连一次、接着等（{@link #awaitLink}），不拿「只看安卓」先判。
     */
    private static void wakeRead(int gen, boolean capped) {
        if (settle != Settle.WAKE || gen != settleGen) {
            return;
        }
        Telemetry telemetry = Telemetry.get();
        if (!capped && awaitLink(telemetry)) {
            return;
        }
        endSettle();
        Context context = app;
        if (context != null && !interactive(context)) {
            BlackBox.noteImportant("安卓亮屏（" + wakeHow + "）又熄屏了，没等到车辆信号：还算车机睡着");
            drainWhenKnown();
            return;
        }
        boolean connected = telemetry.carLinkConnected();
        Readings r = telemetry.readings();
        Boolean event = connected ? r.bool(Signal.SCREEN_POWER_EVENT) : null;
        Integer ignition = connected ? r.code(Signal.IGNITION) : null;
        wakeCapped = capped && connected;
        Boolean fresh = capped ? null : VehicleScreen.verdict(event, ignition);
        move(ScreenTransitions.androidOn(facts.state, fresh, connected), event, ignition);
        drainWhenKnown();
    }

    /** 安卓熄屏着的时候每两秒问一次系统「亮了没」：深睡醒来亮屏广播不来。只看安卓。 */
    private static final Runnable WAKE_POLL = new Runnable() {
        @Override
        public void run() {
            if (!facts.state.androidOff) {
                return;
            }
            Context context = app;
            if (context != null && interactive(context)) {
                androidWentOn("noticed");
            }
            MAIN.postDelayed(this, WAKE_POLL_MS);
        }
    };

    private static void startWakePoll() {
        MAIN.removeCallbacks(WAKE_POLL);
        MAIN.postDelayed(WAKE_POLL, WAKE_POLL_MS);
    }

    private static void endSettle() {
        settle = Settle.NONE;
        settleGen++;
        wakeOnLink = false;
        MAIN.removeCallbacks(STARTUP_CAP);
        MAIN.removeCallbacks(WAKE_CAP);
    }

    private static void drainWhenKnown() {
        if (WHEN_KNOWN.isEmpty()) {
            return;
        }
        List<Runnable> queued = new ArrayList<>(WHEN_KNOWN);
        WHEN_KNOWN.clear();
        for (Runnable action : queued) {
            try {
                action.run();
            } catch (RuntimeException e) {
                AppLog.w(TAG, "deferred action failed: " + e);
            }
        }
    }

    // ================================================================= 状态和边（主线程）

    /** 换成新的状态；跨过了一条边就照固定顺序做那条边的事（{@link ScreenTransitions#edge}）。 */
    private static void move(ScreenTransitions.State next, Boolean event, Integer ignition) {
        Facts before = facts;
        ScreenTransitions.Edge edge = ScreenTransitions.edge(before.state, next);
        long now = SystemClock.elapsedRealtime();
        long darkAt = before.darkAtMs;
        long offAt = before.androidOffAtMs;
        Source source = before.source;
        if (edge == ScreenTransitions.Edge.DARK) {
            darkAt = now;
            offAt = next.androidOff ? now : 0;
            source = !Boolean.TRUE.equals(next.vehicle) ? Source.ANDROID
                    : before.state.vehicle == null ? Source.VEHICLE_READ : Source.VEHICLE;
        } else if (edge == ScreenTransitions.Edge.HEAD_UNIT_SLEEPS) {
            offAt = now;
        } else if (edge == ScreenTransitions.Edge.WOKE_WHILE_DARK) {
            offAt = 0;
        } else if (edge == ScreenTransitions.Edge.LIT) {
            darkAt = 0;
            offAt = 0;
            source = null;
        }
        Facts after = new Facts(next, darkAt, offAt, source, event, ignition);
        facts = after;
        if (edge == null) {
            return;
        }
        try {
            switch (edge) {
                case DARK:
                    wentDark(before, after);
                    break;
                case HEAD_UNIT_SLEEPS:
                    headUnitSleeps(after);
                    break;
                case WOKE_WHILE_DARK:
                    wokeWhileDark(after);
                    break;
                default:
                    wentLit(before, after);
                    break;
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "edge " + edge + " failed", e);
            BlackBox.noteImportant("熄屏判断：" + edge + " 这一步出错（" + e + "），后面的没做完");
        }
    }

    /** E1：亮 → 黑。 */
    private static void wentDark(Facts before, Facts after) {
        BlackBox.noteImportant("熄屏（" + (after.source == Source.ANDROID
                ? "安卓熄屏；" + (after.state.vehicle == null ? "车辆信号读不到" : "车辆信号说亮着")
                + "：screen_power_event=" + VehicleScreen.eventText(after.event)
                + "、点火=" + VehicleScreen.ignitionText(after.ignition)
                : (after.source == Source.VEHICLE_READ
                ? "车辆信号，读到时已经黑着（进程刚起来、或者之前读不到），起点不明：screen_power_event="
                + VehicleScreen.eventText(after.event) + "、点火=" + VehicleScreen.ignitionText(after.ignition)
                : "车辆信号：screen_power_event"
                + VehicleScreen.change(VehicleScreen.eventText(before.event), VehicleScreen.eventText(after.event))
                + "、点火" + VehicleScreen.change(VehicleScreen.ignitionText(before.ignition),
                VehicleScreen.ignitionText(after.ignition)))
                + (after.state.androidOff ? "；安卓也熄屏了" : "；安卓还亮着"))
                + "）");
        ScreenOffRecording.onScreenOff(app);
        RecordingCoordinator.get(app).screenOff(after.state.androidOff);
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onScreenOff();
        }
        if (after.state.androidOff) {
            startWakePoll();
        }
    }

    /** E2：黑着，安卓熄屏 —— 车机要睡。显示屏的状态 DisplayWatch 早一秒已经记过，这里不再现问（C19）。 */
    private static void headUnitSleeps(Facts after) {
        long darkMs = after.darkAtMs > 0 ? after.androidOffAtMs - after.darkAtMs : -1;
        BlackBox.noteImportant("车机要睡（安卓熄屏）：" + (darkMs < 0 ? "熄屏起点不明" : "熄屏后 +" + darkMs / 1000 + " 秒")
                + "；车辆信号 screen_power_event=" + VehicleScreen.eventText(after.event)
                + "、点火=" + VehicleScreen.ignitionText(after.ignition));
        RecordingCoordinator.get(app).headUnitSleeps();
        startWakePoll();
    }

    /** E2'：黑着，安卓又亮了，车辆还说黑 —— 车机醒着、屏幕黑着。不叫监听者（还黑着），不拉界面。 */
    private static void wokeWhileDark(Facts after) {
        MAIN.removeCallbacks(WAKE_POLL);
        long darkMs = after.darkAtMs > 0 ? SystemClock.elapsedRealtime() - after.darkAtMs : -1;
        BlackBox.noteImportant("安卓亮屏（" + wakeHow + "），车辆信号还黑着（screen_power_event="
                + VehicleScreen.eventText(after.event) + "、点火=" + VehicleScreen.ignitionText(after.ignition)
                + "）：车机醒着、屏幕黑着" + (darkMs < 0 ? "" : "，熄屏后 +" + darkMs / 1000 + " 秒"));
        RecordingCoordinator.get(app).headUnitWoke();
        // 车机醒着：按开机自启动的规矩恢复服务；黑着不拉界面，录像接不接协调器照表判
        Recovery.restore(app, "wake");
    }

    /** E3：黑 → 亮。和以前的亮屏一样走一遍。 */
    private static void wentLit(Facts before, Facts after) {
        MAIN.removeCallbacks(WAKE_POLL);
        long darkMs = before.darkAtMs > 0 ? SystemClock.elapsedRealtime() - before.darkAtMs : -1;
        // 总原则（规格 §0）：停车熄屏是特殊情况；屏幕亮了，特殊情况就结束了，回到用户设定的状态
        BlackBox.noteImportant("亮屏（" + (before.state.androidOff
                ? "安卓：" + wakeHow + (wakeCapped ? "；车辆信号 " + WAKE_SETTLE_MAX_MS / 1000 + " 秒内没读回来，只看安卓"
                : after.state.vehicle == null ? "；车辆信号读不到"
                : "；车辆信号：screen_power_event=" + VehicleScreen.eventText(after.event)
                + "、点火=" + VehicleScreen.ignitionText(after.ignition))
                : after.state.vehicle == null ? "车辆信号读不到了，只看安卓：安卓亮着"
                : "车辆信号：screen_power_event"
                + VehicleScreen.change(VehicleScreen.eventText(before.event), VehicleScreen.eventText(after.event))
                + "、点火" + VehicleScreen.change(VehicleScreen.ignitionText(before.ignition),
                VehicleScreen.ignitionText(after.ignition)) + "；安卓亮着")
                + "）" + (darkMs < 0 ? "" : "，熄屏 " + darkMs / 1000 + " 秒"));
        wakeCapped = false;
        ScreenOffRecording.onScreenOn();
        RecordingCoordinator.get(app).screenOn();
        Recovery.bringBackUiAfterScreenOn(app);
        // 亮屏 = 车机醒了、能正常运行：按开机自启动的规矩恢复该恢复的（排到主线程下一轮，在监听者之后）
        Recovery.restore(app, "screen-on");
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onScreenOn();
        }
    }

    private static boolean interactive(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }
}
