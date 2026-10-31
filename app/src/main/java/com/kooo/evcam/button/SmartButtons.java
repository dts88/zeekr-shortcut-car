package com.kooo.evcam.button;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
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

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.overlay.QuickActions;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.screen.DisplayWatch;
import com.kooo.evcam.sound.DriveSound;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灵动贴（广播名 ZEEKR_button；项目所有者 2026-10-30 从 zeekr-shortcut-lab 搬来）。做法是 Lab 在车上验证过的那一套
 * （Lab {@code docs/bluetooth-button.md}、findings §2.51–§2.65），代码在这里按本项目重写。
 *
 * <h3>怎么听</h3>
 * <ul>
 *   <li>「连接」只记下地址（{@link #connect}），以后不连着它、只听它的广播：按键只在按下时广播，一直连着耗它的电，
 *       而且它空闲 15 秒就自己断开（findings §2.61）。</li>
 *   <li>用不带过滤器的扫描、在回调里按地址筛：车上按地址过滤的扫描收不到它的广播（§2.61）。代价是安卓熄屏时会停掉这种扫描 ——
 *       熄屏时按了不算。连续扫 30 分钟安卓会降级，{@link #RESTART_MS} 重开一次；蓝牙关了先停、开了接着听；
 *       没听上 {@link #RETRY_MS} 后再试（§2.64：以前只试一次，没成就一直没在听）。</li>
 *   <li>扫描看着还在、其实收不到的情况系统不一定告诉我们（车机睡一觉醒来，蓝牙可能已经重启过）：不带过滤器的扫描周围什么设备都报，
 *       亮着屏 {@link #DEAF_MS} 一包都没收到，就重开（{@link #watchScan}）。听不到的每一阵都进黑匣子（{@link ScanProbe}）。</li>
 *   <li>蓝牙重启过以后容器手里的 GATT 服务还是死的，扫描、连接一直失败：换成系统现在这份，马上重开（{@link StaleGatt}）。</li>
 *   <li>广播里的按键计数变了就是按了一下（{@link SmartButtonAd}）。上一次的计数存着，进程重启后的第一下照样算；
 *       连接时拿搜索那会儿看到的计数当起点。</li>
 *   <li>按法查这一台选的动作（{@link SmartButtonAction}），在主线程上做：悬浮按钮那几项走 {@link QuickActions}（同一份），
 *       驾驶模式走 {@link Telemetry#setDriveMode}，升挡、降挡交给模拟声浪（{@link DriveSound#shift}）。用户退出以后什么都不听（{@link UserExit}）。</li>
 * </ul>
 *
 * <h3>电量</h3>
 * <p>连接时读一次；之后离上次读到超过 {@link #BATTERY_STALE_MS}（没读成的至少隔 {@link #BATTERY_RETRY_MS}），
 * 按完一下等 {@link #BATTERY_AFTER_PRESS_MS}（双击的第二下也按完）就连上读一次、读完断开。连着的这一两秒里按键不广播，
 * 按法从点击次数的通知来，照样做选的动作。连着时先停扫描：车机上一边扫一边连，连接会一下一下地失败（§2.61）。
 * 直接连（不等它自己广播），没连上隔 {@link #RECONNECT_MS} 再连，最多 {@link #CONNECT_WINDOW_MS}；每一步最多等 {@link #STEP_TIMEOUT_MS}。
 * 只读：不往按键写任何东西，除了打开通知（蓝牙标准的订阅，写的是按键自己的描述符）。</p>
 *
 * <h3>线程</h3>
 * <p>蓝牙的事都在自己的线程上（{@code SmartButton}）；扫描回调在主线程上来，只筛一下就转过去。搜索结果、设置页要的变化、
 * 按下去的动作交给主线程。</p>
 */
public final class SmartButtons {

    private static final String TAG = "SmartButtons";

    /** 连续扫 30 分钟安卓会把扫描降级，提前重开。 */
    static final long RESTART_MS = 25 * 60_000L;
    /** 没听上（没权限、扫描失败、拿不到扫描器）多久再试。 */
    static final long RETRY_MS = 30_000L;
    /** 蓝牙关着时多久看一眼开了没有：开关的广播车上没收到过（2026-11-01 报告：蓝牙关了又开，一条都没来），醒来后全靠这个。 */
    static final long BLUETOOTH_POLL_MS = 5_000L;
    /** 在听的时候多久看一眼扫描还活着没有。 */
    static final long WATCH_MS = 15_000L;
    /** 连按驾驶模式向上、向下时，这么久（毫秒）之内接着上一次的目标算。 */
    static final long DRIVE_TARGET_MS = 2000L;
    /** 亮着屏这么久一个设备的广播都没收到，就当扫描聋了（车上周围一般十几台，Lab findings §2.51）。重开过还是没有，下一回等的时间翻倍。 */
    static final long DEAF_MS = 45_000L;
    /** 最多翻几倍：真的一台设备都没有的地方，隔 12 分钟重开一次就够了。 */
    static final int DEAF_MAX_DOUBLINGS = 4;
    /** 连上读电量最多试多久。 */
    static final long CONNECT_WINDOW_MS = 30_000L;
    /** 没连上隔多久再连。 */
    static final long RECONNECT_MS = 1_000L;
    /** 连上以后每一步（找服务、打开通知、读电量）最多等多久。 */
    static final long STEP_TIMEOUT_MS = 3_000L;
    /** 电量多久读一次。 */
    static final long BATTERY_STALE_MS = 24 * 3600_000L;
    /** 没读成的多久再试。 */
    static final long BATTERY_RETRY_MS = 3600_000L;
    /** 按完一下等多久再连（双击的第二下也按完）。 */
    static final long BATTERY_AFTER_PRESS_MS = 1_200L;
    /** 搜索结果最多多久交给界面一次。 */
    static final long SEARCH_PUBLISH_MS = 500L;

    private static final UUID BATTERY_LEVEL = uuid16(0x2a19);
    private static final UUID CLICKS = uuid16(0x2a60);
    private static final UUID CCCD = uuid16(0x2902);

    /** 运行状态（按键计数、电量、读电量的时刻）：不是设置，不导出（{@code settings.SettingsBackup} 只带设置和视频流配置）。 */
    private static final String STATE = "smart_button_state";

    /** 在不在听（诊断报告用）。 */
    public enum Listen {
        /** 还没装上。 */
        NOT_STARTED,
        /** 用户退出了：不听。 */
        EXITED,
        /** 没有连接过的灵动贴、也没在搜索：不扫。 */
        IDLE,
        /** 这台车机没有蓝牙。 */
        NO_BLUETOOTH,
        /** 蓝牙关着：开了接着听。 */
        BLUETOOTH_OFF,
        /** 没有扫描权限：{@link #RETRY_MS} 后再看。 */
        NO_PERMISSION,
        /** 连着一台读电量：先停着扫描。 */
        CONNECTING,
        /** 在听。 */
        LISTENING,
        /** 扫描起不来：{@link #RETRY_MS} 后再试。 */
        FAILED
    }

    /** 搜到的一台（没连接过的）。 */
    public static final class Found {
        public final String address;
        public final int rssi;
        /** 搜到时广播里的按键计数（连接时当起点）；读不出来是 -1。 */
        final int counter;

        Found(String address, int rssi, int counter) {
            this.address = address;
            this.rssi = rssi;
            this.counter = counter;
        }
    }

    /** 搜索结果给谁（主线程）。 */
    public interface SearchListener {
        /** 搜到的灵动贴（没连接过的），信号强的在前。 */
        void onFound(List<Found> found);
    }

    /** 这次进程里一台按过几下、最后一下（诊断报告用）。 */
    public static final class Pressed {
        public final int count;
        public final long lastAtWallMs;
        /** 最后一下几击（0 是按法认不出）。 */
        public final int lastClicks;
        /** 最后一下是连着读电量时从通知来的（不是广播）。 */
        public final boolean viaNotification;

        Pressed(int count, long lastAtWallMs, int lastClicks, boolean viaNotification) {
            this.count = count;
            this.lastAtWallMs = lastAtWallMs;
            this.lastClicks = lastClicks;
            this.viaNotification = viaNotification;
        }
    }

    private static volatile SmartButtons instance;

    private final Context app;
    private final Handler bt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences state;
    private final BluetoothAdapter adapter;

    // ---- 只在 bt 线程上碰
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    /** 这轮扫描开起来的时刻（{@link SystemClock#elapsedRealtime}）。 */
    private long listeningSince;
    private boolean exited;
    /** 正在连的那一台（连接时读电量、按完顺手读电量）；同时只连一台。 */
    private Link link;

    // ---- 跨线程
    /** 连接过的几台（地址）：扫描回调按它筛。 */
    private volatile Set<String> listening = Collections.emptySet();
    private volatile boolean searching;
    private volatile Listen listen = Listen.NOT_STARTED;
    private volatile String listenDetail = "";
    private volatile String connecting;
    /** 上次收到广播（什么设备的都算）的时刻（{@link SystemClock#elapsedRealtime}）；这次进程还没收到过是 0。 */
    private volatile long heardAt;
    /** 上次收到广播以来扫描失败了几次、聋了重开了几次：只在 bt 线程上写。 */
    private volatile int failures;
    private volatile int deafRestarts;
    /** 这次进程换过几次容器手里的 GATT 服务（{@link StaleGatt}）：只在 bt 线程上写。 */
    private volatile int gattReplaced;
    /** 这一阵最后一次记下的 GATT 服务情况，一样的不再记：只在 bt 线程上碰。 */
    private String gattNote = "";
    /** 刚要切到的驾驶模式、什么时候要的（主线程，{@link #stepDriveMode}）；0 = 没有。 */
    private int driveTarget;
    private long driveTargetAt;
    private final Map<String, Found> found = new LinkedHashMap<>();
    private final AtomicBoolean publishPending = new AtomicBoolean();
    private final Map<String, Pressed> pressed = new HashMap<>();

    // ---- 只在主线程上碰
    private final List<SearchListener> searchers = new ArrayList<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    /** 进程起来时装一次（{@code ZeekrShortcutApp}）：有连接过的灵动贴就开始听。重复叫无害。 */
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
    }

    private void start() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(adapterState, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(adapterState, filter);
        }
        boolean exitedNow = UserExit.isExited(app);
        listening = loadListening();
        bt.post(() -> {
            exited = exitedNow;
            ensureScan();
        });
    }

    // ================================================================= 给设置页的

    /** 有没有蓝牙。 */
    public boolean hasBluetooth() {
        return adapter != null;
    }

    /** 扫描聋了没有（纯逻辑）：安卓熄屏时系统本来就停着这种扫描，不算。时刻都是含深睡的；{@code restarts} 是这一阵已经重开过几次。 */
    static boolean deaf(boolean interactive, long now, long listeningSince, long heardAt, int restarts) {
        return interactive && now - Math.max(listeningSince, heardAt) >= DEAF_MS << Math.min(restarts, DEAF_MAX_DOUBLINGS);
    }

    /** 蓝牙开着没有。 */
    public boolean bluetoothOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 搜索、听、连要的运行时权限：安卓 12 起是「附近的设备」（扫描、连接），之前是定位。 */
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

    /** 权限刚给了、或者别的条件变了：马上再看一次要不要扫（不等 {@link #RETRY_MS}）。 */
    public void refresh() {
        listening = loadListening();
        // 没在扫的话 startScan 会撤掉排着的重试、马上起；在扫的话什么都不动（25 分钟的重开照旧）
        bt.post(this::ensureScan);
    }

    /** 用户退出后又手动打开了（{@link UserExit#clear}）：接着听。 */
    public void resumeAfterExit() {
        bt.post(() -> {
            exited = false;
            ensureScan();
        });
    }

    /** 开始搜（主线程）：搜到的不断交给 {@code listener}，直到 {@link #removeSearchListener}。 */
    public void addSearchListener(SearchListener listener) {
        if (searchers.contains(listener)) {
            return;
        }
        searchers.add(listener);
        if (searchers.size() == 1) {
            synchronized (found) {
                found.clear();
            }
            searching = true;
            bt.post(this::ensureScan);
        }
        listener.onFound(snapshotFound());
    }

    public void removeSearchListener(SearchListener listener) {
        searchers.remove(listener);
        if (searchers.isEmpty() && searching) {
            searching = false;
            bt.post(this::ensureScan);
        }
    }

    /** 连接过的灵动贴、电量变了时叫谁（主线程）。 */
    public void addChangeListener(Runnable listener) {
        if (!changeListeners.contains(listener)) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /**
     * 连接一台（主线程）：地址马上记下，从这时起按它就算数（选了动作才做事）；再连上读一次电量就断开，读没读到都不影响用。
     */
    public void connect(String address) {
        new AppConfig(app).addSmartButton(address);
        Found f;
        synchronized (found) {
            f = found.remove(address);
        }
        int seed = f == null ? -1 : f.counter;
        listening = loadListening();
        BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(address) + "：连接（记下地址，按键计数起点 " + seed + "）");
        bt.post(() -> {
            if (seed >= 0) {
                state.edit().putInt(counterKey(address), seed).apply();
            }
            state.edit().putLong(batteryTryKey(address), System.currentTimeMillis()).apply();
            if (link == null && adapter != null && canConnect(app)) {
                open(new Link(address, false));
            } else {
                ensureScan();
            }
        });
        publishChange();
    }

    /** 删掉一台（主线程）：地址、两种按法选的动作、按键计数、电量都不要了。 */
    public void forget(String address) {
        new AppConfig(app).removeSmartButton(address);
        listening = loadListening();
        BlackBox.noteImportant("灵动贴 " + SmartButtonAd.shortId(address) + "：删除");
        bt.post(() -> {
            state.edit().remove(counterKey(address)).remove(batteryKey(address)).remove(batteryAtKey(address))
                    .remove(batteryTryKey(address)).apply();
            if (link != null && link.address.equals(address)) {
                finish(link, "forgotten");
            }
            ensureScan();
        });
        synchronized (pressed) {
            pressed.remove(address);
        }
        publishChange();
    }

    /** 这一台上次读到的电量（%）；没读到过是 -1。 */
    public int battery(String address) {
        return state.getInt(batteryKey(address), -1);
    }

    // ================================================================= 给诊断报告的

    public Listen listen() {
        return listen;
    }

    /** 没在听的原因的细节（英文）。 */
    public String listenDetail() {
        return listenDetail;
    }

    public boolean searching() {
        return searching;
    }

    /** 正在连的那一台；没在连是 null。 */
    public String connecting() {
        return connecting;
    }

    /** 离上次收到广播（什么设备的都算）多久了（毫秒，含深睡）；这次进程还没收到过是 -1。 */
    public long heardAgoMs() {
        long at = heardAt;
        return at == 0 ? -1 : SystemClock.elapsedRealtime() - at;
    }

    /** 上次收到广播以来扫描失败了几次。 */
    public int failures() {
        return failures;
    }

    /** 上次收到广播以来聋了重开了几次。 */
    public int deafRestarts() {
        return deafRestarts;
    }

    /** 这次进程换过几次容器手里的 GATT 服务。 */
    public int gattReplaced() {
        return gattReplaced;
    }

    /** 上次读到电量的时刻（墙上毫秒）；没读到过是 0。 */
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

    private Set<String> loadListening() {
        return Collections.unmodifiableSet(new HashSet<>(new AppConfig(app).getSmartButtons()));
    }

    /** 该不该扫：没退出、没在连、有连接过的或者在搜索。该扫没扫就起，不该扫在扫就停。 */
    private void ensureScan() {
        boolean want = !exited && link == null && (searching || !listening.isEmpty());
        if (!want) {
            stopScan();
            note(exited ? Listen.EXITED : link != null ? Listen.CONNECTING : Listen.IDLE, "");
            return;
        }
        if (scanCallback == null) {
            startScan();
        }
    }

    private void startScan() {
        bt.removeCallbacks(restart);
        bt.removeCallbacks(watch);
        if (adapter == null) {
            note(Listen.NO_BLUETOOTH, "");
            return;
        }
        boolean on;
        try {
            on = adapter.isEnabled();
        } catch (RuntimeException e) {
            on = false;
        }
        if (!on) {
            // 蓝牙开了本该有广播（adapterState），车上没收到过：自己隔一会儿看一眼
            note(Listen.BLUETOOTH_OFF, "");
            bt.postDelayed(restart, BLUETOOTH_POLL_MS);
            return;
        }
        if (!canScan(app)) {
            note(Listen.NO_PERMISSION, "");
            bt.postDelayed(restart, RETRY_MS);
            return;
        }
        BluetoothLeScanner s;
        try {
            s = adapter.getBluetoothLeScanner();
        } catch (RuntimeException e) {
            s = null;
        }
        if (s == null) {
            listenFailed("no scanner");
            return;
        }
        ScanCallback cb = newScanCallback();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            // 不带过滤器：按地址过滤的扫描在车上收不到灵动贴的广播（Lab findings §2.61）
            s.startScan(null, settings, cb);
        } catch (RuntimeException e) {
            listenFailed("startScan: " + e);
            return;
        }
        scanner = s;
        scanCallback = cb;
        listeningSince = SystemClock.elapsedRealtime();
        note(Listen.LISTENING, "");
        bt.postDelayed(restart, RESTART_MS);
        bt.postDelayed(watch, WATCH_MS);
    }

    /**
     * 没听上：{@link #RETRY_MS} 后再试；要是容器手里的 GATT 服务死了，换一个马上试（{@link StaleGatt}）。
     * 一阵里第一次失败把现场记下来：错误码本身看不出是哪一步（{@link ScanProbe}）。
     */
    private void listenFailed(String detail) {
        note(Listen.FAILED, detail);
        failures = failures + 1;
        if (failures == 1) {
            BlackBox.note("灵动贴：扫描起不来（" + detail + "），每 " + RETRY_MS / 1000 + " 秒再试；蓝牙 " + stateName(adapterState())
                    + "；" + ScanProbe.gattFacts(adapter));
            for (String line : ScanProbe.bluetoothLog()) {
                BlackBox.note("灵动贴现场：" + line);
            }
        }
        StaleGatt.Result gatt = StaleGatt.replaceIfDead(adapter);
        if (!gatt.text.equals(gattNote)) {
            gattNote = gatt.text;
            BlackBox.note("灵动贴：容器手里的 GATT 服务：" + gatt.text);
        }
        if (gatt.replaced) {
            gattReplaced = gattReplaced + 1;
        }
        bt.removeCallbacks(restart);
        bt.postDelayed(restart, gatt.replaced ? 0 : RETRY_MS);
    }

    private void stopScan() {
        bt.removeCallbacks(restart);
        bt.removeCallbacks(watch);
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // 蓝牙已经关了之类：停不了就算了
            }
        }
        scanner = null;
        scanCallback = null;
    }

    /** 重开一次扫描（到点、或者上次没听上）。 */
    private final Runnable restart = () -> {
        stopScan();
        ensureScan();
    };

    private final Runnable watch = this::watchScan;

    /** 在听的时候隔一会儿看一眼：聋了就重开一次，一阵里只记第一次。 */
    private void watchScan() {
        long now = SystemClock.elapsedRealtime();
        if (!deaf(DisplayWatch.interactiveNow(), now, listeningSince, heardAt, deafRestarts)) {
            bt.postDelayed(watch, WATCH_MS);
            return;
        }
        deafRestarts = deafRestarts + 1;
        if (deafRestarts == 1) {
            BlackBox.note("灵动贴：" + (now - Math.max(listeningSince, heardAt)) / 1000
                    + " 秒（含车机睡着）没收到任何蓝牙广播，重开扫描；蓝牙 " + stateName(adapterState()));
        }
        AppLog.i(TAG, "deaf: restarting the scan");
        restart.run();
    }

    /** 隔了一阵又收到广播：这一阵的账记一句，清零。同一阵排着的后面几包到这里已经没账了。 */
    private void heardAgain(long silentMs) {
        boolean trouble = failures + deafRestarts > 0;
        if (silentMs >= 0 && silentMs < DEAF_MS && !trouble) {
            return;
        }
        BlackBox.note("灵动贴：" + (silentMs < 0 ? "这次进程第一次" : "隔 " + silentMs / 1000 + " 秒（含车机睡着）又")
                + "收到蓝牙广播；这一阵扫描失败 " + failures + " 次、听不到重开 " + deafRestarts + " 次");
        failures = 0;
        deafRestarts = 0;
        gattNote = "";
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

    private ScanCallback newScanCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                offer(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    offer(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                ScanCallback failed = this;
                bt.post(() -> {
                    if (scanCallback != failed) {
                        return;
                    }
                    scanner = null;
                    scanCallback = null;
                    bt.removeCallbacks(watch);
                    listenFailed("scan failed " + errorCode);
                });
            }
        };
    }

    /** 扫描回调（主线程）：什么设备都报，收到就是扫描活着；连接过的交给 bt 线程认按键；在搜索时名字是灵动贴的交给搜索。 */
    private void offer(ScanResult result) {
        long now = SystemClock.elapsedRealtime();
        long last = heardAt;
        heardAt = now;
        if (last == 0 || now - last >= DEAF_MS || failures + deafRestarts > 0) {
            bt.post(() -> heardAgain(last == 0 ? -1 : now - last));
        }
        try {
            String address = result.getDevice().getAddress();
            ScanRecord record = result.getScanRecord();
            if (listening.contains(address)) {
                byte[] raw = record == null ? null : record.getBytes();
                bt.post(() -> onAdvert(address, raw));
                return;
            }
            if (searching && SmartButtonAd.isButtonName(nameOf(result, record))) {
                byte[] raw = record == null ? null : record.getBytes();
                int rssi = result.getRssi();
                bt.post(() -> onFound(address, rssi, raw));
            }
        } catch (RuntimeException ignored) {
            // 单包出错不影响别的
        }
    }

    /** 广播里带的名字；没带的话问系统记着的名字（要连接权限）。 */
    private String nameOf(ScanResult result, ScanRecord record) {
        String name = record == null ? null : record.getDeviceName();
        if (name == null && canConnect(app)) {
            try {
                name = result.getDevice().getName();
            } catch (RuntimeException ignored) {
                // 没权限就不认
            }
        }
        return name;
    }

    // ================================================================= 搜索（bt 线程）

    private void onFound(String address, int rssi, byte[] raw) {
        if (listening.contains(address)) {
            return;
        }
        SmartButtonAd.Press press = SmartButtonAd.parse(raw);
        synchronized (found) {
            Found old = found.get(address);
            int counter = press != null ? press.counter : old != null ? old.counter : -1;
            found.put(address, new Found(address, rssi, counter));
        }
        if (publishPending.compareAndSet(false, true)) {
            main.postDelayed(publishFound, SEARCH_PUBLISH_MS);
        }
    }

    private final Runnable publishFound = () -> {
        publishPending.set(false);
        List<Found> list = snapshotFound();
        for (SearchListener listener : new ArrayList<>(searchers)) {
            listener.onFound(list);
        }
    };

    private List<Found> snapshotFound() {
        List<Found> list;
        synchronized (found) {
            list = new ArrayList<>(found.values());
        }
        list.removeIf(f -> listening.contains(f.address));
        list.sort((a, b) -> Integer.compare(b.rssi, a.rssi));
        return list;
    }

    // ================================================================= 按键（bt 线程）

    /** 连接过的一台的一包广播：计数和存着的不一样就是按了一下；同一阵里重复的包计数一样，不算。 */
    private void onAdvert(String address, byte[] raw) {
        SmartButtonAd.Press press = SmartButtonAd.parse(raw);
        if (press == null || !listening.contains(address)) {
            return;
        }
        int last = state.getInt(counterKey(address), -1);
        if (last == press.counter) {
            return;
        }
        state.edit().putInt(counterKey(address), press.counter).apply();
        if (last < 0) {
            // 没有起点（连接时没搜到它的计数）：这一包只当起点，下一下才算
            AppLog.i(TAG, SmartButtonAd.shortId(address) + ": first counter " + press.counter + ", taken as the baseline");
            return;
        }
        onPress(address, press.clicks, false);
        maybeReadBattery(address);
    }

    private void onPress(String address, int clicks, boolean viaNotification) {
        synchronized (pressed) {
            Pressed old = pressed.get(address);
            pressed.put(address, new Pressed(old == null ? 1 : old.count + 1, System.currentTimeMillis(), clicks,
                    viaNotification));
        }
        if (clicks == 0) {
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(address) + "：按了，按法认不出" + (viaNotification ? "（通知）" : ""));
            return;
        }
        main.post(() -> act(address, clicks));
    }

    /** 主线程：这一台这种按法选了什么就做什么。 */
    private void act(String address, int clicks) {
        if (UserExit.isExited(app)) {
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
                QuickActions.perform(app, action.floating, RecordingCoordinator.Why.BUTTON);
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
        }
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

    private void writeDriveMode(int mode) {
        driveTarget = mode;
        driveTargetAt = SystemClock.elapsedRealtime();
        Telemetry.get().setDriveMode(mode, ok -> {
            if (!ok) {
                driveTarget = 0;
                Toast.makeText(app, R.string.msg_drive_mode_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================================================================= 读电量（bt 线程）

    /** 电量离上次读到超过一天、上次试也过了一小时：按完这一下就顺手连上读一次。 */
    private void maybeReadBattery(String address) {
        long now = System.currentTimeMillis();
        if (link != null || adapter == null || !canConnect(app)
                || now - state.getLong(batteryAtKey(address), 0) < BATTERY_STALE_MS
                || now - state.getLong(batteryTryKey(address), 0) < BATTERY_RETRY_MS) {
            return;
        }
        state.edit().putLong(batteryTryKey(address), now).apply();
        bt.postDelayed(() -> {
            if (link == null && listening.contains(address)) {
                open(new Link(address, true));
            }
        }, BATTERY_AFTER_PRESS_MS);
    }

    /** 同时只连一台：先停扫描，直接连，最多 {@link #CONNECT_WINDOW_MS}。 */
    private void open(Link l) {
        link = l;
        connecting = l.address;
        stopScan();
        note(Listen.CONNECTING, SmartButtonAd.shortId(l.address));
        connectNow(l);
        bt.postDelayed(() -> {
            if (link == l && !l.done) {
                finish(l, l.connected ? "connected, battery not read in time" : "not connected in time");
            }
        }, CONNECT_WINDOW_MS);
    }

    private void connectNow(Link l) {
        if (l.done) {
            return;
        }
        closeGatt(l);
        l.attempts++;
        l.lastAttempt = SystemClock.elapsedRealtime();
        try {
            l.gatt = adapter.getRemoteDevice(l.address)
                    .connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException e) {
            finish(l, "connectGatt: " + e);
            return;
        }
        if (l.gatt == null) {
            retry(l);
        }
    }

    /** 没连上、或者连上又断了：隔 {@link #RECONNECT_MS} 再连（排着一次就不再排）。 */
    private void retry(Link l) {
        if (l.retryPending || l.done) {
            return;
        }
        l.retryPending = true;
        long wait = Math.max(0, l.lastAttempt + RECONNECT_MS - SystemClock.elapsedRealtime());
        bt.postDelayed(() -> {
            l.retryPending = false;
            connectNow(l);
        }, wait);
    }

    /** 收尾：断开、记一句，接着听。 */
    private void finish(Link l, String result) {
        if (l.done) {
            return;
        }
        l.done = true;
        clearStep(l);
        closeGatt(l);
        if (link == l) {
            link = null;
            connecting = null;
        }
        AppLog.i(TAG, SmartButtonAd.shortId(l.address) + (l.afterPress ? " battery check" : " connect")
                + " done after " + l.attempts + " attempt(s): " + result);
        ensureScan();
        publishChange();
    }

    private static void closeGatt(Link l) {
        if (l.gatt != null) {
            try {
                l.gatt.disconnect();
                l.gatt.close();
            } catch (RuntimeException ignored) {
                // 关不了就算了
            }
            l.gatt = null;
        }
    }

    private void step(Link l, Runnable onTimeout) {
        clearStep(l);
        l.stepTimeout = onTimeout;
        bt.postDelayed(onTimeout, STEP_TIMEOUT_MS);
    }

    private void clearStep(Link l) {
        if (l.stepTimeout != null) {
            bt.removeCallbacks(l.stepTimeout);
            l.stepTimeout = null;
        }
    }

    /** 这个回调是不是正在连的那一台的。 */
    private Link current(BluetoothGatt g) {
        Link l = link;
        return l != null && l.gatt == g && !l.done ? l : null;
    }

    /** 打开点击次数的通知（连着时按键从这里来）：开始写了返回 true，等它回话再读电量。 */
    @SuppressWarnings("deprecation")
    private boolean subscribeClicks(Link l, BluetoothGatt g) {
        BluetoothGattCharacteristic clicks = find(g, CLICKS);
        BluetoothGattDescriptor cccd = clicks == null ? null : clicks.getDescriptor(CCCD);
        if (cccd == null) {
            return false;
        }
        try {
            g.setCharacteristicNotification(clicks, true);
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(cccd)) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        step(l, () -> readBattery(l, g));
        return true;
    }

    private void readBattery(Link l, BluetoothGatt g) {
        if (l.done) {
            return;
        }
        clearStep(l);
        BluetoothGattCharacteristic battery = find(g, BATTERY_LEVEL);
        if (battery == null) {
            finish(l, "no battery characteristic");
            return;
        }
        try {
            if (!g.readCharacteristic(battery)) {
                finish(l, "battery read refused");
                return;
            }
        } catch (RuntimeException e) {
            finish(l, "battery read: " + e);
            return;
        }
        step(l, () -> finish(l, "battery read timed out"));
    }

    private static BluetoothGattCharacteristic find(BluetoothGatt g, UUID uuid) {
        try {
            for (BluetoothGattService service : g.getServices()) {
                BluetoothGattCharacteristic ch = service.getCharacteristic(uuid);
                if (ch != null) {
                    return ch;
                }
            }
        } catch (RuntimeException ignored) {
            // 找不到就是没有
        }
        return null;
    }

    /** 蓝牙回调在别的线程上来，一律转到 bt 线程。安卓 13 起的新回调和老回调都接：哪个版本只会叫其中一个。 */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            bt.post(() -> {
                Link l = current(g);
                if (l == null) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    l.connected = true;
                    step(l, () -> finish(l, "service discovery timed out"));
                    try {
                        if (!g.discoverServices()) {
                            finish(l, "discoverServices refused");
                        }
                    } catch (RuntimeException e) {
                        finish(l, "discoverServices: " + e);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // 按键在睡、没连上，或者连上了还没读到电量就断了：隔一会儿再连
                    clearStep(l);
                    retry(l);
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            bt.post(() -> {
                Link l = current(g);
                if (l == null) {
                    return;
                }
                clearStep(l);
                if (!subscribeClicks(l, g)) {
                    readBattery(l, g);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            bt.post(() -> {
                Link l = current(g);
                if (l != null && CCCD.equals(descriptor.getUuid())) {
                    readBattery(l, g);
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            changed(g, ch.getUuid(), ch.getValue());
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic ch,
                                            @NonNull byte[] value) {
            changed(g, ch.getUuid(), value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            read(g, ch.getUuid(), ch.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic ch,
                                         @NonNull byte[] value, int status) {
            read(g, ch.getUuid(), value, status);
        }
    };

    /** 连着时按键从点击次数的通知来。 */
    private void changed(BluetoothGatt g, UUID uuid, byte[] value) {
        if (!CLICKS.equals(uuid)) {
            return;
        }
        byte[] copy = value == null ? null : value.clone();
        bt.post(() -> {
            Link l = current(g);
            if (l != null) {
                onPress(l.address, SmartButtonAd.clicksOfNotification(copy), true);
            }
        });
    }

    private void read(BluetoothGatt g, UUID uuid, byte[] value, int status) {
        if (!BATTERY_LEVEL.equals(uuid)) {
            return;
        }
        byte[] copy = value == null ? null : value.clone();
        bt.post(() -> {
            Link l = current(g);
            if (l == null) {
                return;
            }
            clearStep(l);
            if (status != BluetoothGatt.GATT_SUCCESS || copy == null || copy.length == 0) {
                finish(l, "battery read failed, status " + status);
                return;
            }
            int percent = copy[0] & 0xFF;
            state.edit().putInt(batteryKey(l.address), percent)
                    .putLong(batteryAtKey(l.address), System.currentTimeMillis()).apply();
            BlackBox.note("灵动贴 " + SmartButtonAd.shortId(l.address) + "：电量 " + percent + "%");
            finish(l, "battery " + percent + "%");
        });
    }

    /** 蓝牙开关：开了接着听，关了先停（正在连的也收尾）。 */
    private final BroadcastReceiver adapterState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int now = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            bt.post(() -> {
                if (!listening.isEmpty()) {
                    BlackBox.note("灵动贴：蓝牙 " + stateName(now));
                }
                if (now == BluetoothAdapter.STATE_ON) {
                    // 蓝牙刚开好，手里要是还有一轮扫描，那也是上一次开着时的（关的广播可能没收到）：换新的
                    restart.run();
                } else if (now == BluetoothAdapter.STATE_TURNING_OFF || now == BluetoothAdapter.STATE_OFF) {
                    stopScan();
                    if (link != null) {
                        finish(link, "Bluetooth off");
                    }
                    note(Listen.BLUETOOTH_OFF, "");
                    bt.removeCallbacks(restart);
                    bt.postDelayed(restart, BLUETOOTH_POLL_MS);
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

    /** 正在连的那一台。 */
    private static final class Link {
        final String address;
        /** 按完顺手读电量（不是连接时那一次）。 */
        final boolean afterPress;
        BluetoothGatt gatt;
        boolean connected;
        boolean done;
        boolean retryPending;
        int attempts;
        long lastAttempt;
        Runnable stepTimeout;

        Link(String address, boolean afterPress) {
            this.address = address;
            this.afterPress = afterPress;
        }
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

    private static String batteryTryKey(String address) {
        return "batteryTry." + address;
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", value));
    }
}
