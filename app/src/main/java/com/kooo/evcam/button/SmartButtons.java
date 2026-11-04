package com.kooo.evcam.button;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.overlay.QuickActions;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.sound.DriveSound;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 灵动贴（广播名 ZEEKR_button；项目所有者 2026-10-30 从 zeekr-shortcut-lab 搬来；2026-11-04 扫描、连接、绑定照 Lab 3.5.x 重写，车主定）。
 * 做法是 Lab 在车上验证过、又对过中国版「极+」的那一套（Lab {@code docs/bluetooth-button.md} 第十一节、findings §2.92、§2.93），
 * 代码在这里按本项目重写。
 *
 * <h3>怎么听</h3>
 * <ul>
 *   <li>扫描按名字过滤（{@link SmartButtonAd#NAME}），结果用 PendingIntent 由系统交给 {@link ScanReceiver}：应用在后台、被系统收掉了也送得到。
 *       低延迟、积极匹配、每包都报、只收传统广播（「极+」的设置）。</li>
 *   <li>按名字过滤以后只有按下时才有广播，平时没有是正常的；带过滤的扫描安卓也不会因为扫得久就降级。所以扫描开着就不动（车主 2026-11-01：
 *       不反复强行启动），只在这些时候停、开：灵动贴的开关、绑定（一台都没绑时）、蓝牙开关、车机睡醒、扫描起不来、退出，还有连接重试（见下）。</li>
 *   <li>开之前先停一次、隔 {@link #START_DELAY_MS} 再开：上一个进程开的 PendingIntent 扫描不会跟着进程停。安卓 12 一个 uid 30 秒里开扫描超过 5 次
 *       会被悄悄拒，容器里别的应用也算在这个 uid 上：{@link #STARTS_WINDOW_MS} 里最多开 {@link #MAX_STARTS} 次（{@link #startWait}）。</li>
 *   <li>没开启灵动贴（设置里的开关）、没有绑定的也没在绑定、用户退出了：不扫（车主 2026-11-04：Lab 同时开着时，Lab 好用蓝牙做测试）。
 *       退出时当场停（{@link #userExits}），不然进程没了系统还接着扫，又把应用叫起来。</li>
 *   <li>车机要睡时和相机一样放开，扫描、连接都停（{@link #headUnitSleeps}）。醒来等 {@link #SETTLE_MS}，向系统重新要蓝牙服务（{@link StaleGatt}），
 *       再开始扫描（{@link #headUnitWoke}，车主 2026-11-01）。蓝牙跟着点火走，开关的广播车上没收到过：扫描开着时每 {@link #WATCH_MS} 看一眼蓝牙开着没有；
 *       蓝牙没开、扫描起不来的，隔 {@link #RETRY_MS} 再试，一阵里第一次起不来把现场记进黑匣子（{@link ScanProbe}）。</li>
 * </ul>
 *
 * <h3>按法</h3>
 * <ul>
 *   <li>广播里的计数变了就是按了一下（{@link SmartButtonAd}）。计数存着，进程重启后照样接得上。</li>
 *   <li>单击、双击查这一台选的动作（{@link SmartButtonAction}），在主线程上做：悬浮按钮那几项走 {@link QuickActions}（同一份），
 *       写车的（驾驶模式、「极+」那一套）交给 {@link CarMoves}，媒体、语音走 {@link MediaMove}，升挡、降挡交给模拟声浪
 *       （{@link DriveSound#shift}）。写车的没做成提示一句（{@link #carResult}）。没开启灵动贴、用户退出以后什么都不做。</li>
 *   <li>长按（约 3 秒、背光闪）只用来连接，不绑动作（车主 2026-11-04）。</li>
 *   <li>同一下从广播、通知各来一遍只算一次（{@link SmartButtonAd#samePress}）。</li>
 *   <li>正在绑定时绑过的按了不做（「极+」）；拿绑过的来长按，告诉一声，这次绑定结束。</li>
 * </ul>
 *
 * <h3>连接</h3>
 * <p>只在长按时连（{@link ButtonLink}）：订按键通知、查版本、读电量、查序列号，之后连着，灵动贴最后一下按完 15 秒自己断开。连着时它不广播，
 * 按键从通知来，照样做选的动作。同时只连一台：另一台长按时，连好的那一台断开、换它，还在连的不换。第一次连时扫描开着，第二次起先停扫描再连，
 * 连好了、不连了接着扫。连上过的断开以后，记着的计数作废：连着时按的几下没走广播，下一包广播一定是新按的（「极+」连上就作废，一个意思）。</p>
 *
 * <h3>电量、版本、序列号</h3>
 * <p>电量取广播里带的（长按那一下带）和连着时读到、推来的：按键那一下会掉到 0 / 25，连着时取 {@link #BATTERY_WINDOW_MS} 里最高的（Lab 3.5.4）。
 * 版本取广播里的和查到的，序列号只有查到的。都是运行状态，不导出。</p>
 *
 * <h3>绑定</h3>
 * <p>设置里点「绑定灵动贴」（{@link #startBinding}）：{@link #BIND_WINDOW_MS} 里长按一台没绑过的，连上、订好按键通知就算绑上
 * （「极+」也是），顺便查好版本、电量、序列号。最多 {@link #MAX_BOUND} 台，按地址记。</p>
 *
 * <h3>线程</h3>
 * <p>蓝牙的事都在自己的线程上（{@code SmartButton}）；扫描结果从主线程（{@link ScanReceiver}）转过去。绑定的结果、设置页要的变化、
 * 按下去的动作交给主线程。</p>
 */
public final class SmartButtons {

    private static final String TAG = "SmartButtons";

    /** 没听上（蓝牙关着、没权限、扫描起不来、拿不到扫描器）多久再试。 */
    static final long RETRY_MS = 5_000L;
    /** 车机醒了等这么久，再向系统要蓝牙服务、开始扫描（车主 2026-11-01）。 */
    static final long SETTLE_MS = 10_000L;
    /** 在扫的时候多久看一眼蓝牙开着没有。 */
    static final long WATCH_MS = 2_000L;
    /** 先停、隔这么久再开（「极+」）。 */
    static final long START_DELAY_MS = 500L;
    /** 开扫描的次数按这么长一阵算，一阵里最多开 {@link #MAX_STARTS} 次（安卓 12 按 uid 算 5 次，容器里别的应用也算，Lab 3.5.4）。 */
    static final long STARTS_WINDOW_MS = 30_000L;
    static final int MAX_STARTS = 3;
    /** 连按驾驶模式向上、向下时，这么久（毫秒）之内接着上一次的目标算。 */
    static final long DRIVE_TARGET_MS = 2000L;
    /** 绑定等多久（「极+」15 秒；长按要 3 秒，再给连接留点，Lab）。 */
    static final long BIND_WINDOW_MS = 30_000L;
    /** 绑定的时间到了还在连：隔这么久再看（连接自己的超时管着它）。 */
    static final long BIND_RECHECK_MS = 1_000L;
    /** 最多绑几台（「极+」）。 */
    public static final int MAX_BOUND = 10;
    /** 连着时电量取这么久里最高的：按键那一下会掉（Lab 3.5.3 车上）。 */
    static final long BATTERY_WINDOW_MS = 10_000L;

    private static final int SCAN_REQUEST = 1;

    /** 运行状态（计数、电量、版本、序列号）：不是设置，不导出（{@code settings.SettingsBackup} 只带设置和视频流配置）。 */
    static final String STATE = "smart_button_state";
    /** 以前「按完顺手读电量」记的上次试的时刻：那件事去掉了（2026-11-04），起来时把留下的删掉。 */
    private static final String RETIRED_BATTERY_TRY = "batteryTry.";

    /** 在不在听（诊断报告用）。 */
    public enum Listen {
        /** 还没装上。 */
        NOT_STARTED,
        /** 设置里没开启灵动贴：不扫。 */
        DISABLED,
        /** 用户退出了：不扫。 */
        EXITED,
        /** 没有绑定的灵动贴、也没在绑定：不扫。 */
        IDLE,
        /** 这台车机没有蓝牙。 */
        NO_BLUETOOTH,
        /** 蓝牙关着：开了接着扫。 */
        BLUETOOTH_OFF,
        /** 没有扫描权限：{@link #RETRY_MS} 后再看。 */
        NO_PERMISSION,
        /** 车机要睡了：蓝牙放开，醒来再说。 */
        ASLEEP,
        /** 车机刚醒：等 {@link #SETTLE_MS}。 */
        SETTLING,
        /** 第二次起连之前先停着扫描。 */
        CONNECTING,
        /** 刚开过太多次：等到这一阵过去。 */
        THROTTLED,
        /** 停了，隔 {@link #START_DELAY_MS} 就开。 */
        STARTING,
        /** 在听。 */
        LISTENING,
        /** 扫描起不来：{@link #RETRY_MS} 后再试。 */
        FAILED
    }

    /** 一次绑定的结果。 */
    public enum BindResult {
        /** 绑上了。 */
        BOUND,
        /** 长按的是绑过的那一台。 */
        ALREADY_BOUND,
        /** 时间到了，没看到没绑过的长按。 */
        NOT_FOUND,
        /** 看到了、没连上。 */
        CONNECT_FAILED,
        /** 已经绑满 {@link #MAX_BOUND} 台。 */
        FULL
    }

    /** 绑定的进展给谁（主线程）。 */
    public interface BindListener {
        /** 正在连一台长按了的（{@code true}），或者没连上、接着等（{@code false}）。 */
        void onConnecting(boolean connecting);

        /** 这次绑定结束了；{@code address} 是那一台，没有是 null。 */
        void onBindResult(BindResult result, String address);
    }

    /** 这次进程里一台按过几下、最后一下（诊断报告用）。 */
    public static final class Pressed {
        public final int count;
        public final long lastAtWallMs;
        /** 最后一下的按法（{@link SmartButtonAd#SINGLE} 等；0 是认不出）。 */
        public final int lastGesture;
        /** 最后一下是连着时从通知来的（不是广播）。 */
        public final boolean viaNotification;

        Pressed(int count, long lastAtWallMs, int lastGesture, boolean viaNotification) {
            this.count = count;
            this.lastAtWallMs = lastAtWallMs;
            this.lastGesture = lastGesture;
            this.viaNotification = viaNotification;
        }
    }

    /** 一台最近一下（去掉另一路又来的那一遍）。 */
    private static final class Last {
        final int gesture;
        final boolean viaNotification;
        final long at;

        Last(int gesture, boolean viaNotification, long at) {
            this.gesture = gesture;
            this.viaNotification = viaNotification;
            this.at = at;
        }
    }

    /** 连着时一台的电量读数：按键那一下会掉，取 {@link #BATTERY_WINDOW_MS} 里最高的（纯逻辑，{@code SmartButtonsTest}）。 */
    static final class BatteryWindow {
        private final ArrayDeque<long[]> reads = new ArrayDeque<>();

        /** 加一个读数（{@code now} 是开机起的毫秒），返回这一阵里最高的。 */
        int add(long now, int percent) {
            reads.addLast(new long[]{now, percent});
            while (now - reads.peekFirst()[0] > BATTERY_WINDOW_MS) {
                reads.pollFirst();
            }
            int max = 0;
            for (long[] read : reads) {
                max = Math.max(max, (int) read[1]);
            }
            return max;
        }
    }

    private static volatile SmartButtons instance;

    private final Context app;
    private final Handler bt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences state;
    private final BluetoothAdapter adapter;
    /** 扫描结果交给 {@link ScanReceiver}；开、停用的是同一个。 */
    private final PendingIntent scanIntent;

    // ---- 只在 bt 线程上碰
    /** 系统那边可能还开着我们的扫描（这次进程开过、或者上一个进程留下的）：停的时候要真的去停。 */
    private boolean maybeScanning = true;
    /** 每停一次加一：隔一会儿再开的那一下看它变没变。 */
    private int scanGeneration;
    private boolean startPending;
    /** 最近开扫描的时刻（从早到晚，最多 {@link #MAX_STARTS} 个）。 */
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    /** 看到蓝牙开着的时刻（{@link SystemClock#elapsedRealtime}）；关着是 0。 */
    private long bluetoothOnSince;
    /** 车机要睡了，蓝牙放开着（{@link #headUnitSleeps}）。 */
    private boolean asleep;
    /** 车机醒了，等到这一刻才开始扫描（{@link #headUnitWoke}）。 */
    private long settledAt;
    /** 车机醒来的时刻，开始听时记一笔；0 = 记过了。 */
    private long wokeAt;
    /** 蓝牙重启过（车机睡过、蓝牙关过）：下次开扫描之前先向系统要一份现在的服务（{@link #refreshGatt}）。 */
    private boolean gattStale;
    /** 正在连的、连着的那一台；同时只有一台。 */
    private ButtonLink link;
    /** 绑定到这一刻为止（{@link SystemClock#elapsedRealtime}）。 */
    private long bindUntil;
    /** 这次绑定里没连上的次数。 */
    private int bindFailures;
    private final Map<String, Last> lastPress = new HashMap<>();
    private final Map<String, BatteryWindow> batteryWindows = new HashMap<>();
    /** 认不出广播的那几台：每台这次进程只记一次。 */
    private final Set<String> unreadable = new HashSet<>();
    /** 这一阵最后一次记下的 GATT 服务情况，一样的不再记。 */
    private String gattNote = "";

    // ---- 跨线程
    /** 绑定了的几台（地址）。 */
    private volatile Set<String> bound = Collections.emptySet();
    private volatile boolean enabled;
    private volatile boolean exited;
    private volatile boolean binding;
    private volatile boolean scanning;
    private volatile Listen listen = Listen.NOT_STARTED;
    private volatile String listenDetail = "";
    private volatile String connecting;
    /** 上次收到扫描结果的时刻（{@link SystemClock#elapsedRealtime}）；这次进程还没收到过是 0。 */
    private volatile long heardAt;
    /** 这次进程收到几包：只在 bt 线程上写。 */
    private volatile long packets;
    /** 上次开起来以后扫描起不来了几次：只在 bt 线程上写。 */
    private volatile int failures;
    /** 这次进程换过几次容器手里的 GATT 服务（{@link StaleGatt}）：只在 bt 线程上写。 */
    private volatile int gattReplaced;
    /** 这次进程因为开得太勤等过几回：只在 bt 线程上写。 */
    private volatile int throttled;
    private final Map<String, Pressed> pressed = new HashMap<>();

    // ---- 只在主线程上碰
    /** 刚要切到的驾驶模式、什么时候要的（{@link #stepDriveMode}）；0 = 没有。 */
    private int driveTarget;
    private long driveTargetAt;
    private BindListener bindListener;
    private final List<Runnable> changeListeners = new ArrayList<>();

    /** 进程起来时装一次（{@code ZeekrShortcutApp}，扫描结果把进程叫起来时 {@link ScanReceiver} 也叫）。重复叫无害。 */
    public static synchronized void install(Context context) {
        if (instance != null) {
            return;
        }
        SmartButtons buttons = new SmartButtons(context.getApplicationContext());
        instance = buttons;
        buttons.start();
    }

    /** 装上的那一个；还没装是 null。 */
    public static SmartButtons get() {
        return instance;
    }

    private SmartButtons(Context app) {
        this.app = app;
        HandlerThread thread = new HandlerThread("SmartButton");
        thread.start();
        bt = new Handler(thread.getLooper());
        state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        BluetoothAdapter a = null;
        try {
            BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            a = manager == null ? null : manager.getAdapter();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "no Bluetooth adapter: " + e);
        }
        adapter = a;
        PendingIntent pi = null;
        try {
            // 系统要往里填结果，安卓 12 起得是可变的；目标写明了是自己的接收器
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            pi = PendingIntent.getBroadcast(app, SCAN_REQUEST,
                    new Intent(app, ScanReceiver.class).setAction(ScanReceiver.ACTION), flags);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "no PendingIntent for the scan: " + e);
        }
        scanIntent = pi;
    }

    private void start() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(adapterState, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(adapterState, filter);
        }
        enabled = new AppConfig(app).isSmartButtonsEnabled();
        exited = UserExit.isExited(app);
        bound = loadBound();
        boolean asleepNow = ScreenState.androidOff();
        bt.post(() -> {
            asleep = asleepNow;
            forgetRetiredState();
            // 上一个进程留下的扫描也在这里停（不该扫的话），该扫的话停了重开
            ensureScan();
        });
    }

    // ================================================================= 给设置页的

    /** 有没有蓝牙。 */
    public boolean hasBluetooth() {
        return adapter != null;
    }

    /** 蓝牙开着没有。 */
    public boolean bluetoothOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 绑定、听、连要的运行时权限：安卓 12 起是「附近的设备」（扫描、连接），之前是定位。 */
    public static String[] permissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean canScan(Context context) {
        return granted(context, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public static boolean canConnect(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT);
    }

    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** 车机醒了要不要先等（纯逻辑）：车机刚醒等够 {@link #SETTLE_MS}；在绑定不等，人就在设置页上看着。 */
    static boolean waitToSettle(long now, long settledAt, boolean binding) {
        return !binding && now < settledAt;
    }

    /**
     * 现在能不能开扫描（纯逻辑）：{@code starts} 是最近开过的时刻（从早到晚）。{@link #STARTS_WINDOW_MS} 里已经开满 {@link #MAX_STARTS} 次，
     * 就等到最早的那一次过了这一阵。返回要等的毫秒，0 = 现在就能开。
     */
    static long startWait(List<Long> starts, long now) {
        int recent = 0;
        long oldest = 0;
        for (long at : starts) {
            if (now - at < STARTS_WINDOW_MS) {
                if (recent == 0) {
                    oldest = at;
                }
                recent++;
            }
        }
        return recent < MAX_STARTS ? 0 : oldest + STARTS_WINDOW_MS - now;
    }

    /** 设置里开、关灵动贴（主线程，设置已经写好）：关了不扫、不连、不绑，连着的断开。 */
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            bindListener = null;
        }
        bt.post(() -> {
            if (!on) {
                if (binding) {
                    stopBinding();
                }
                if (link != null) {
                    link.close("smart buttons turned off");
                }
            }
            BlackBox.noteImportant(on ? "灵动贴：开启" : "灵动贴：关闭（不扫描蓝牙、不连接）");
            ensureScan();
        });
    }

    /** 开着灵动贴没有。 */
    public boolean enabled() {
        return enabled;
    }

    /** 权限刚给了、或者别的条件变了：马上再看一次要不要扫（不等 {@link #RETRY_MS}）。在扫的不动。 */
    public void refresh() {
        bound = loadBound();
        bt.post(() -> {
            if (!scanning && !startPending) {
                ensureScan();
            }
        });
    }

    /** 用户退出（{@code CameraKeeper#beginExit}，哪个线程都行）：当场停扫描，不然进程没了系统还接着扫、把应用叫起来；连着的断开。 */
    public void userExits() {
        exited = true;
        stopIntentScan();
        bt.post(() -> {
            if (binding) {
                stopBinding();
            }
            if (link != null) {
                link.close("user exit");
            }
            ensureScan();
        });
    }

    /** 用户退出后又手动打开了（{@link UserExit#clear}）：接着听。 */
    public void resumeAfterExit() {
        bt.post(() -> {
            exited = false;
            ensureScan();
        });
    }

    /** 车机要睡（{@link ScreenState}，安卓熄屏，主线程）：和相机一样放开，扫描、连接都停，也不再看蓝牙开没开，醒来再说。 */
    public void headUnitSleeps() {
        bt.post(() -> {
            asleep = true;
            if (link != null) {
                link.close("head unit sleeps");
            }
            ensureScan();
            if (!bound.isEmpty()) {
                BlackBox.note("灵动贴：车机要睡，放开蓝牙");
            }
        });
    }

    /** 车机醒了（{@link ScreenState}，安卓亮了，主线程）：等 {@link #SETTLE_MS}，向系统重新要蓝牙服务，再开始扫描。 */
    public void headUnitWoke() {
        bt.post(() -> {
            long now = SystemClock.elapsedRealtime();
            asleep = false;
            settledAt = now + SETTLE_MS;
            wokeAt = now;
            bluetoothRestarted();
            ensureScan();
        });
    }

    /**
     * 开始绑定（主线程）：{@link #BIND_WINDOW_MS} 里长按一台没绑过的。进展、结果交给 {@code listener}（主线程），除非先 {@link #cancelBinding}。
     * 蓝牙、权限、开关、台数由设置页先看过。
     */
    public void startBinding(BindListener listener) {
        bindListener = listener;
        bt.post(() -> {
            binding = true;
            bindUntil = SystemClock.elapsedRealtime() + BIND_WINDOW_MS;
            bindFailures = 0;
            bt.removeCallbacks(bindTimeout);
            bt.postDelayed(bindTimeout, BIND_WINDOW_MS);
            BlackBox.noteImportant("灵动贴：开始绑定（" + BIND_WINDOW_MS / 1000 + " 秒）");
            ensureScan();
        });
    }

    /** 不绑了（主线程，人关了框）：正在连、还没绑上的那一台断开。 */
    public void cancelBinding() {
        bindListener = null;
        bt.post(() -> {
            if (!binding) {
                return;
            }
            BlackBox.note("灵动贴：不绑了");
            stopBinding();
            if (link != null && link.binding && !link.wasReady()) {
                link.close("binding cancelled");
            }
        });
    }

    /** 绑定的灵动贴、电量这些变了时叫谁（主线程）。 */
    public void addChangeListener(Runnable listener) {
        if (!changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /** 删掉一台（主线程）：地址、两种按法选的动作、计数、电量、版本、序列号都不要了；连着的断开。 */
    public void forget(String address) {
        new AppConfig(app).removeSmartButton(address);
        bound = loadBound();
        BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(address) + "：删除");
        bt.post(() -> {
            state.edit().remove(counterKey(address)).remove(batteryKey(address)).remove(batteryAtKey(address))
                    .remove(versionKey(address)).remove(serialKey(address)).remove(RETIRED_BATTERY_TRY + address).apply();
            lastPress.remove(address);
            if (link != null && link.address.equals(address)) {
                link.close("forgotten");
            }
            ensureScan();
        });
        synchronized (pressed) {
            pressed.remove(address);
        }
        publishChange();
    }

    /** 这一台的电量（%）；没有过是 -1。 */
    public int battery(String address) {
        return state.getInt(batteryKey(address), -1);
    }

    /** 这一台的固件版本（「1.2.1」）；没有过是 null。 */
    public String version(String address) {
        return state.getString(versionKey(address), null);
    }

    /** 这一台的序列号；没查到过是 null。 */
    public String serial(String address) {
        return state.getString(serialKey(address), null);
    }

    // ================================================================= 给诊断报告的

    public Listen listen() {
        return listen;
    }

    /** 没在听的原因的细节（英文）。 */
    public String listenDetail() {
        return listenDetail;
    }

    public boolean binding() {
        return binding;
    }

    /** 正在连、连着的那一台；没有是 null。 */
    public String connecting() {
        return connecting;
    }

    /** 离上次收到扫描结果多久了（毫秒，含深睡）；这次进程还没收到过是 -1。 */
    public long heardAgoMs() {
        long at = heardAt;
        return at == 0 ? -1 : SystemClock.elapsedRealtime() - at;
    }

    /** 这次进程收到几包。 */
    public long packets() {
        return packets;
    }

    /** 上次开起来以后扫描起不来了几次。 */
    public int failures() {
        return failures;
    }

    /** 这次进程换过几次容器手里的 GATT 服务。 */
    public int gattReplaced() {
        return gattReplaced;
    }

    /** 这次进程因为开扫描太勤等过几回。 */
    public int throttled() {
        return throttled;
    }

    /** 现在这个电量记下的时刻（墙上毫秒）；没有过是 0。 */
    public long batteryReadAt(String address) {
        return state.getLong(batteryAtKey(address), 0);
    }

    /** 这次进程里这一台按过的；没按过是 null。 */
    public Pressed pressed(String address) {
        synchronized (pressed) {
            return pressed.get(address);
        }
    }

    // ================================================================= 扫描（bt 线程）

    private Set<String> loadBound() {
        return Collections.unmodifiableSet(new HashSet<>(new AppConfig(app).getSmartButtons()));
    }

    /** 去掉的做法留下的运行状态删掉（已删除功能不留残骸）。 */
    private void forgetRetiredState() {
        SharedPreferences.Editor editor = null;
        for (String key : state.getAll().keySet()) {
            if (key.startsWith(RETIRED_BATTERY_TRY)) {
                if (editor == null) {
                    editor = state.edit();
                }
                editor.remove(key);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    /** 为什么不该扫；该扫是 null。 */
    private Listen whyNotScan() {
        if (!enabled) {
            return Listen.DISABLED;
        }
        if (exited) {
            return Listen.EXITED;
        }
        if (asleep) {
            return Listen.ASLEEP;
        }
        if (bound.isEmpty() && !binding) {
            return Listen.IDLE;
        }
        if (link != null && link.pausesScan) {
            return Listen.CONNECTING;
        }
        return null;
    }

    /** 该扫没在扫就开，不该扫的停。在扫的不动。 */
    private void ensureScan() {
        Listen not = whyNotScan();
        if (not != null) {
            stopScan();
            note(not, "");
            return;
        }
        if (!scanning && !startPending) {
            startScan();
        }
    }

    private void startScan() {
        bt.removeCallbacks(retry);
        if (adapter == null || scanIntent == null) {
            note(Listen.NO_BLUETOOTH, adapter == null ? "" : "no PendingIntent");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (waitToSettle(now, settledAt, binding)) {
            note(Listen.SETTLING, "");
            bt.postDelayed(retry, settledAt - now);
            return;
        }
        if (!bluetoothOn()) {
            bluetoothRestarted();
            note(Listen.BLUETOOTH_OFF, "");
            bt.postDelayed(retry, RETRY_MS);
            return;
        }
        if (bluetoothOnSince == 0) {
            bluetoothOnSince = now;
        }
        if (!canScan(app)) {
            note(Listen.NO_PERMISSION, "");
            bt.postDelayed(retry, RETRY_MS);
            return;
        }
        long wait = startWait(new ArrayList<>(starts), now);
        if (wait > 0) {
            throttled = throttled + 1;
            note(Listen.THROTTLED, wait + " ms");
            bt.postDelayed(retry, wait);
            return;
        }
        if (gattStale) {
            gattStale = false;
            refreshGatt();
        }
        // 上一个进程、上一轮开的 PendingIntent 扫描不会自己停：先停一次，隔一会儿再开（「极+」的做法）
        stopIntentScan();
        maybeScanning = false;
        startPending = true;
        int generation = scanGeneration;
        note(Listen.STARTING, "");
        bt.postDelayed(() -> startNow(generation), START_DELAY_MS);
    }

    private void startNow(int generation) {
        if (generation != scanGeneration || !startPending) {
            return;
        }
        startPending = false;
        if (whyNotScan() != null) {
            ensureScan();
            return;
        }
        BluetoothLeScanner s = scanner();
        if (s == null) {
            listenFailed("no scanner");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        starts.addLast(now);
        while (starts.size() > MAX_STARTS) {
            starts.pollFirst();
        }
        maybeScanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(true)
                .setReportDelay(0)
                .build();
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setDeviceName(SmartButtonAd.NAME).build());
        int result;
        try {
            result = s.startScan(filters, settings, scanIntent);
        } catch (RuntimeException e) {
            listenFailed("startScan: " + e);
            return;
        }
        if (result != 0) {
            listenFailed("startScan returned " + result);
            return;
        }
        scanning = true;
        if (failures > 0) {
            BlackBox.note("灵动贴：扫描开起来了（之前起不来 " + failures + " 次）");
            failures = 0;
            gattNote = "";
        }
        note(Listen.LISTENING, "");
        if (wokeAt != 0) {
            BlackBox.note("灵动贴：车机醒来 " + seconds(now - wokeAt) + " 秒开始听；看到蓝牙开着 " + seconds(now - bluetoothOnSince)
                    + " 秒；" + ScanProbe.scanFacts(app, adapter));
            wokeAt = 0;
        }
        bt.postDelayed(watch, WATCH_MS);
    }

    private void stopScan() {
        scanGeneration++;
        startPending = false;
        bt.removeCallbacks(retry);
        bt.removeCallbacks(watch);
        if (maybeScanning) {
            stopIntentScan();
            maybeScanning = false;
        }
        scanning = false;
    }

    /** 停我们的扫描（哪个线程都行；没开着也无害）。 */
    private void stopIntentScan() {
        BluetoothLeScanner s = scanner();
        if (s == null || scanIntent == null) {
            return;
        }
        try {
            s.stopScan(scanIntent);
        } catch (RuntimeException ignored) {
            // 蓝牙已经关了之类：停不了就算了
        }
    }

    /** 蓝牙关着时是 null。 */
    private BluetoothLeScanner scanner() {
        try {
            return adapter == null ? null : adapter.getBluetoothLeScanner();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 隔一会儿再看要不要扫（蓝牙没开、没权限、起不来、刚醒、开得太勤）。 */
    private final Runnable retry = this::ensureScan;

    private final Runnable watch = this::watchBluetooth;

    /** 在扫的时候隔一会儿看一眼蓝牙：开关的广播车上没收到过，车机没睡、人很快又回来时，蓝牙关过又开，手里这一轮已经没用了。只是读一下状态。 */
    private void watchBluetooth() {
        if (!scanning) {
            return;
        }
        if (!bluetoothOn()) {
            BlackBox.note("灵动贴：蓝牙关了，扫描停下，开了再开");
            bluetoothRestarted();
            stopScan();
            if (link != null) {
                link.close("Bluetooth off");
            }
            ensureScan();
            return;
        }
        bt.postDelayed(watch, WATCH_MS);
    }

    /** 蓝牙重启过（车机睡过、蓝牙关过）：手里的服务不能用了。 */
    private void bluetoothRestarted() {
        bluetoothOnSince = 0;
        gattStale = true;
    }

    /** 向系统要一份现在的蓝牙服务：容器手里那份死了就换（{@link StaleGatt}），活着什么都不动。情况一样的不再记。换了返回 true。 */
    private boolean refreshGatt() {
        StaleGatt.Result gatt = StaleGatt.replaceIfDead(adapter);
        if (!gatt.text.equals(gattNote)) {
            gattNote = gatt.text;
            BlackBox.note("灵动贴：容器手里的 GATT 服务：" + gatt.text);
        }
        if (gatt.replaced) {
            gattReplaced = gattReplaced + 1;
        }
        return gatt.replaced;
    }

    /**
     * 没开成：{@link #RETRY_MS} 后再试；要是容器手里的 GATT 服务死了，换一个马上试（{@link StaleGatt}）。
     * 一阵里第一次起不来把现场记下来：错误码本身看不出是哪一步（{@link ScanProbe}）。
     */
    private void listenFailed(String detail) {
        scanning = false;
        startPending = false;
        note(Listen.FAILED, detail);
        failures = failures + 1;
        if (failures == 1) {
            BlackBox.note("灵动贴：扫描起不来（" + detail + "），每 " + RETRY_MS / 1000 + " 秒再试；蓝牙 " + stateName(adapterState())
                    + "；" + ScanProbe.gattFacts(adapter));
            for (String line : ScanProbe.bluetoothLog()) {
                BlackBox.note("灵动贴现场：" + line);
            }
        }
        boolean replaced = refreshGatt();
        bt.removeCallbacks(retry);
        bt.postDelayed(retry, replaced ? 0 : RETRY_MS);
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000f);
    }

    private int adapterState() {
        try {
            return adapter.getState();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                return "off";
            case BluetoothAdapter.STATE_TURNING_ON:
                return "turning on";
            case BluetoothAdapter.STATE_ON:
                return "on";
            case BluetoothAdapter.STATE_TURNING_OFF:
                return "turning off";
            default:
                return String.valueOf(state);
        }
    }

    private void note(Listen now, String detail) {
        if (listen != now) {
            AppLog.i(TAG, "listen: " + listen + " -> " + now + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
        listen = now;
        listenDetail = detail;
    }

    // ================================================================= 扫描结果

    /** {@link ScanReceiver} 收到的（主线程）：拆出来交给 bt 线程。 */
    void onScanIntent(Intent intent) {
        int error;
        List<ScanResult> results;
        try {
            error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0);
            results = resultsOf(intent);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "scan intent unreadable: " + e);
            return;
        }
        long at = SystemClock.elapsedRealtime();
        bt.post(() -> onScanResults(error, results, at));
    }

    @SuppressWarnings("deprecation")
    private static List<ScanResult> resultsOf(Intent intent) {
        List<ScanResult> list = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class)
                : intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        return list == null ? Collections.emptyList() : list;
    }

    private void onScanResults(int error, List<ScanResult> results, long at) {
        Listen not = whyNotScan();
        if (not != null && not != Listen.CONNECTING) {
            // 不该扫（关了、退出了、要睡了、没有要找的）系统还在送：上一回的扫描还开着，再停一次，结果不要
            maybeScanning = true;
            stopScan();
            return;
        }
        if (error > 0) {
            if (scanning) {
                listenFailed("scan failed " + error);
            }
            return;
        }
        for (ScanResult result : results) {
            packets = packets + 1;
            heardAt = at;
            offer(result);
        }
    }

    /** 一包：绑过的交给按键，正在绑定时没绑过的交给绑定。 */
    private void offer(ScanResult result) {
        String address;
        byte[] raw;
        try {
            address = result.getDevice().getAddress();
            ScanRecord record = result.getScanRecord();
            raw = record == null ? null : record.getBytes();
        } catch (RuntimeException e) {
            return;
        }
        SmartButtonAd.Advert ad = SmartButtonAd.parse(raw);
        if (ad == null) {
            if (unreadable.add(address)) {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：广播认不出，不算（"
                        + SmartButtonAd.hex(raw == null || raw.length <= 31 ? raw : Arrays.copyOf(raw, 31)) + "）");
            }
            return;
        }
        if (bound.contains(address)) {
            onAdvert(address, ad);
        } else if (binding) {
            onUnbound(address, ad);
        }
    }

    // ================================================================= 按键（bt 线程）

    /** 绑过的一台的一包广播：电量、版本记下；计数和存着的不一样就是按了一下，同一阵里重复的包计数一样，不算。 */
    private void onAdvert(String address, SmartButtonAd.Advert ad) {
        boolean changed = ad.battery >= 0 && keepBattery(address, ad.battery);
        changed |= keepVersion(address, ad.version);
        int last = state.getInt(counterKey(address), -1);
        if (!SmartButtonAd.isNew(ad.counter, last)) {
            if (changed) {
                publishChange();
            }
            return;
        }
        state.edit().putInt(counterKey(address), ad.counter).apply();
        if (changed) {
            publishChange();
        }
        if (binding) {
            if (ad.gesture == SmartButtonAd.LONG) {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：正在绑定，长按的是绑过的这一台");
                endBinding(BindResult.ALREADY_BOUND, address);
            } else {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：正在绑定，绑过的按了不做");
            }
            return;
        }
        press(address, ad.gesture, false);
        if (ad.gesture == SmartButtonAd.LONG) {
            connect(address);
        }
    }

    /** 正在绑定时一台没绑过的：长按了（背光闪）就连它，别的等它长按。 */
    private void onUnbound(String address, SmartButtonAd.Advert ad) {
        if (ad.gesture != SmartButtonAd.LONG) {
            return;
        }
        if (link != null && (link.address.equals(address) || !link.isReady())) {
            return;
        }
        if (bound.size() >= MAX_BOUND) {
            endBinding(BindResult.FULL, address);
            return;
        }
        if (link != null) {
            link.close("another button long-pressed");
        }
        if (!canConnect(app)) {
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：绑定，长按了，没有连接权限");
            return;
        }
        BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(address) + "：绑定，长按了，连它");
        open(new ButtonLink(app, bt, adapter, address, true, linkListener));
        main.post(() -> {
            BindListener l = bindListener;
            if (l != null) {
                l.onConnecting(true);
            }
        });
    }

    /** 一下（bt 线程）：另一路又来的同一下不算；单击、双击做选的动作，长按、长按松开不做事。 */
    private void press(String address, int gesture, boolean viaNotification) {
        long now = SystemClock.elapsedRealtime();
        Last prev = lastPress.get(address);
        if (prev != null && SmartButtonAd.samePress(prev.gesture, prev.viaNotification, prev.at, gesture, viaNotification, now)) {
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：同一下又从" + (viaNotification ? "通知" : "广播")
                    + "来了一遍（晚 " + (now - prev.at) + " 毫秒），只算一次");
            return;
        }
        lastPress.put(address, new Last(gesture, viaNotification, now));
        synchronized (pressed) {
            Pressed old = pressed.get(address);
            pressed.put(address, new Pressed(old == null ? 1 : old.count + 1, System.currentTimeMillis(), gesture,
                    viaNotification));
        }
        if (gesture == SmartButtonAd.SINGLE || gesture == SmartButtonAd.DOUBLE) {
            main.post(() -> act(address, gesture));
        } else if (gesture == 0) {
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：按了，按法认不出" + (viaNotification ? "（通知）" : ""));
        }
    }

    /** 主线程：这一台这种按法选了什么就做什么。 */
    private void act(String address, int clicks) {
        if (!enabled || UserExit.isExited(app)) {
            return;
        }
        AppConfig config = new AppConfig(app);
        if (!config.getSmartButtons().contains(address)) {
            return;
        }
        SmartButtonAction action = SmartButtonAction.fromKey(config.getSmartButtonAction(address, clicks));
        BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(address) + "：" + (clicks == SmartButtonAd.DOUBLE ? "双击" : "单击") + " → " + action.key);
        if (action.floating != null) {
            try {
                QuickActions.perform(app, action.floating, CameraKeeper.Why.BUTTON);
            } catch (RuntimeException e) {
                // 应用在后台、又没有前台服务撑着时，安卓不让起服务（超级镜子、录制）：这一下不做，进程不能因为它崩
                AppLog.w(TAG, "action " + action.key + " failed: " + e);
            }
        } else if (action.driveMode != 0) {
            writeDriveMode(action.driveMode);
        } else if (action.driveModeStep != 0) {
            stepDriveMode(action.driveModeStep);
        } else if (action.gearStep != 0) {
            DriveSound.shift(action.gearStep);
        } else if (action.car != null) {
            String label = action.label(app);
            CarMoves.run(app, action.key, action.car, result -> carResult(label, result));
        } else if (action.media != null && !action.media.perform(app)) {
            Toast.makeText(app, app.getString(R.string.msg_car_action_unavailable, action.label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    /** 写车的没做成（主线程）：提示一句为什么。做成了不提示：车上看得到。 */
    private void carResult(String label, CarMoves.Result result) {
        String text;
        switch (result) {
            case DONE:
                return;
            case NO_CHANGE:
                text = app.getString(R.string.msg_car_action_no_change, label);
                break;
            case POWER_SAVING:
                text = app.getString(R.string.msg_car_action_power_saving);
                break;
            case MOVING:
                text = app.getString(R.string.msg_car_action_standstill);
                break;
            case NOT_FITTED:
                text = app.getString(R.string.msg_car_action_no_fragrance);
                break;
            case UNAVAILABLE:
            default:
                text = app.getString(R.string.msg_car_action_unavailable, label);
                break;
        }
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
    }

    /**
     * 驾驶模式走一级（{@link SmartButtonAction#nextDriveMode}）：到头不动、不提示；此刻不在舒适、标准、运动里（雪地、读不到）不切换。
     * 连按时接着上一次的目标算，车机回报晚了也不会少走一级（{@link #DRIVE_TARGET_MS}）。
     */
    private void stepDriveMode(int step) {
        boolean recent = driveTarget != 0 && SystemClock.elapsedRealtime() - driveTargetAt < DRIVE_TARGET_MS;
        Integer from = recent ? Integer.valueOf(driveTarget) : Telemetry.get().readings().code(Signal.DRIVE_MODE);
        int to = SmartButtonAction.nextDriveMode(from, step);
        if (to == 0) {
            Toast.makeText(app, R.string.msg_drive_mode_failed, Toast.LENGTH_SHORT).show();
        } else if (to != from) {
            writeDriveMode(to);
        }
    }

    /** 切驾驶模式（{@link CarMoves#driveMode}）：没切成提示「驾驶模式没有切换」，连按的目标作废。 */
    private void writeDriveMode(int mode) {
        driveTarget = mode;
        driveTargetAt = SystemClock.elapsedRealtime();
        CarMoves.run(app, "drive_mode", CarMoves.driveMode(mode), result -> {
            if (result != CarMoves.Result.DONE) {
                driveTarget = 0;
                Toast.makeText(app, R.string.msg_drive_mode_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================================================================= 连接（bt 线程）

    /** 绑过的一台长按了：连它。同时只连一台：连好的那一台断开、换它，还在连的不换。 */
    private void connect(String address) {
        if (adapter == null || !canConnect(app)) {
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：长按了，没有连接权限，不连");
            return;
        }
        if (link != null) {
            if (link.address.equals(address)) {
                return;
            }
            if (!link.isReady()) {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：长按了，正在连另一台，这次不连");
                return;
            }
            link.close("another button long-pressed");
        }
        BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：长按了，连它");
        open(new ButtonLink(app, bt, adapter, address, false, linkListener));
    }

    private void open(ButtonLink l) {
        link = l;
        connecting = l.address;
        l.start();
    }

    private final ButtonLink.Listener linkListener = new ButtonLink.Listener() {
        @Override
        public void attempt(ButtonLink l, int n) {
            if (link != l || n < 2 || l.pausesScan) {
                return;
            }
            // 车上一边扫一边连失败过（Lab 2.14.3）：第二次起先停扫描再连，连好了、不连了接着扫
            l.pausesScan = true;
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(l.address) + "：第 " + n + " 次连，先停扫描");
            ensureScan();
        }

        @Override
        public void ready(ButtonLink l) {
            if (link != l) {
                return;
            }
            l.pausesScan = false;
            ensureScan();
            if (!l.binding) {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(l.address) + "：连好了（第 " + l.attempts + " 次，"
                        + seconds(l.ageMs()) + " 秒）");
                return;
            }
            if (bound.size() >= MAX_BOUND) {
                endBinding(BindResult.FULL, l.address);
                l.close("already " + MAX_BOUND + " bound");
                return;
            }
            new AppConfig(app).addSmartButton(l.address);
            bound = loadBound();
            BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(l.address) + "：绑好了（第 " + l.attempts + " 次连上，"
                    + seconds(l.ageMs()) + " 秒）");
            endBinding(BindResult.BOUND, l.address);
            publishChange();
        }

        @Override
        public void reply(ButtonLink l, SmartButtonAd.Reply r, byte[] raw) {
            if (link != l) {
                return;
            }
            String id = SmartButtonAd.shortId(l.address);
            if (r.version != null) {
                BlackBox.note("灵动贴 " + id + "：固件版本 " + r.version);
                if (keepVersion(l.address, r.version)) {
                    publishChange();
                }
            } else if (r.serial != null) {
                BlackBox.note("灵动贴 " + id + "：序列号 " + r.serial);
                if (keepSerial(l.address, r.serial)) {
                    publishChange();
                }
            } else if (!bound.contains(l.address)) {
                BlackBox.note("灵动贴 " + id + "：还没绑上，通知 " + SmartButtonAd.hex(raw) + " 不算");
            } else if (binding) {
                BlackBox.note("灵动贴 " + id + "：正在绑定，绑过的按了不做");
            } else {
                press(l.address, r.gesture, true);
            }
        }

        @Override
        public void battery(ButtonLink l, int percent) {
            if (link != l || !bound.contains(l.address)) {
                return;
            }
            BatteryWindow window = batteryWindows.get(l.address);
            if (window == null) {
                window = new BatteryWindow();
                batteryWindows.put(l.address, window);
            }
            int steady = window.add(SystemClock.elapsedRealtime(), percent);
            if (keepBattery(l.address, steady)) {
                BlackBox.note("灵动贴 " + SmartButtonAd.shortId(l.address) + "：电量 " + steady + "%");
                publishChange();
            }
        }

        @Override
        public void closed(ButtonLink l, String why) {
            if (link != l) {
                return;
            }
            link = null;
            connecting = null;
            batteryWindows.remove(l.address);
            String id = SmartButtonAd.shortId(l.address);
            if (l.everConnected && bound.contains(l.address)) {
                // 连着时按的几下没走广播：计数作废，下一包广播一定是新按的
                state.edit().remove(counterKey(l.address)).apply();
            }
            BlackBox.note("灵动贴 " + id + "：" + (l.wasReady() ? "断开（" : "没连好（") + why + "，连了 " + l.attempts + " 次）");
            if (l.binding && !bound.contains(l.address) && binding) {
                bindFailures = bindFailures + 1;
                if (SystemClock.elapsedRealtime() >= bindUntil) {
                    endBinding(BindResult.CONNECT_FAILED, l.address);
                } else {
                    main.post(() -> {
                        BindListener listener = bindListener;
                        if (listener != null) {
                            listener.onConnecting(false);
                        }
                    });
                }
            }
            ensureScan();
            publishChange();
        }
    };

    // ================================================================= 绑定（bt 线程）

    private final Runnable bindTimeout = this::bindTimeUp;

    /** 绑定的时间到了：还在连的等它连完（它自己的超时管着），不然就结束。 */
    private void bindTimeUp() {
        if (!binding) {
            return;
        }
        if (link != null && link.binding && !link.isClosed() && !link.wasReady()) {
            bt.postDelayed(bindTimeout, BIND_RECHECK_MS);
            return;
        }
        endBinding(bindFailures > 0 ? BindResult.CONNECT_FAILED : BindResult.NOT_FOUND, null);
    }

    private void endBinding(BindResult result, String address) {
        if (!binding) {
            return;
        }
        stopBinding();
        BlackBox.noteImportant("灵动贴：绑定结束（" + result + (address == null ? "" : "，" + SmartButtonAd.shortId(address)) + "）");
        main.post(() -> {
            BindListener l = bindListener;
            bindListener = null;
            if (l != null) {
                l.onBindResult(result, address);
            }
        });
    }

    private void stopBinding() {
        binding = false;
        bt.removeCallbacks(bindTimeout);
        ensureScan();
    }

    // ================================================================= 电量、版本、序列号（bt 线程）

    /** 记下电量和记下的时刻（变了才写：长按那一阵每包都带，连着时每秒推一次）；变了返回 true。 */
    private boolean keepBattery(String address, int percent) {
        if (state.getInt(batteryKey(address), -1) == percent) {
            return false;
        }
        state.edit().putInt(batteryKey(address), percent).putLong(batteryAtKey(address), System.currentTimeMillis()).apply();
        return true;
    }

    private boolean keepVersion(String address, String version) {
        if (version == null || version.equals(state.getString(versionKey(address), null))) {
            return false;
        }
        state.edit().putString(versionKey(address), version).apply();
        return true;
    }

    private boolean keepSerial(String address, String serial) {
        if (serial == null || serial.isEmpty() || serial.equals(state.getString(serialKey(address), null))) {
            return false;
        }
        state.edit().putString(serialKey(address), serial).apply();
        return true;
    }

    /** 蓝牙开关：开了接着听，关了先停（正在连的也收尾）。 */
    private final BroadcastReceiver adapterState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int now = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            bt.post(() -> {
                if (!bound.isEmpty()) {
                    BlackBox.note("灵动贴：蓝牙 " + stateName(now));
                }
                if (now == BluetoothAdapter.STATE_ON) {
                    // 蓝牙刚开好，手里要是还有一轮扫描，那也是上一次开着时的（关的广播可能没收到）：停掉，开新的
                    bluetoothRestarted();
                    stopScan();
                    ensureScan();
                } else if (now == BluetoothAdapter.STATE_TURNING_OFF || now == BluetoothAdapter.STATE_OFF) {
                    bluetoothRestarted();
                    stopScan();
                    if (link != null) {
                        link.close("Bluetooth off");
                    }
                    ensureScan();
                }
            });
        }
    };

    private void publishChange() {
        main.post(() -> {
            for (Runnable listener : new ArrayList<>(changeListeners)) {
                listener.run();
            }
        });
    }

    private static String counterKey(String address) {
        return "counter." + address;
    }

    private static String batteryKey(String address) {
        return "battery." + address;
    }

    private static String batteryAtKey(String address) {
        return "batteryAt." + address;
    }

    private static String versionKey(String address) {
        return "version." + address;
    }

    private static String serialKey(String address) {
        return "serial." + address;
    }
}
