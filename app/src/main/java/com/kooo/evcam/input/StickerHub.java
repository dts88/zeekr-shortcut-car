package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a GATT link to saved smart stickers and turns a press into a shortcut.
 *
 * <p>The head unit firmware that pairs this sticker is not on every car. This
 * talks to it directly. Every saved sticker stays connected at the same time. A sticker
 * that is already up is left open. A stuck bond is cancelled before connect. The link stays up while {@link AppConfig#isStickerEnabled()}
 * is on, or while an experimental screen is open. The radio sleeps between
 * presses; a click is a notification, so dropping the link loses it.</p>
 */
public final class StickerHub {

    public static final int FAIL_BLUETOOTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_NO_SERVICE = 3;
    public static final int FAIL_GATT = 4;

    private static final String TAG = "StickerHub";
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    private static final int CONNECT_TRIES = 3;

    /** Open while the experimental screen is visible. Presses are shown there instead of run. */
    public interface Watch {
        void onState(String address, boolean connected);

        void onPacket(String address, byte[] raw);

        void onReady(String address);

        void onFail(String address, int reason, int status);

        void onLog(String line);

        void onRssi(String address, int rssi);

        void onPhase(String address, int phase);
    }

    public static final int PHASE_PAIRING = 1;
    public static final int PHASE_CONNECTING = 2;
    public static final int PHASE_DISCOVERING = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HandlerThread GATT_THREAD = new HandlerThread("sticker-gatt");
    private static final Handler GATT;

    static {
        GATT_THREAD.start();
        GATT = new Handler(GATT_THREAD.getLooper());
    }
    /** GATT from the moment connectGatt returns, including while still connecting. */
    private static final Map<String, BluetoothGatt> OPEN = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    /** Connected, service discovery not finished. The timeout must not close this link. */
    private static final Set<String> DISCOVERING = new HashSet<>();
    /** Reached STATE_CONNECTED for this address. A status-0 disconnect before that is noise. */
    private static final Set<String> UP = new HashSet<>();
    /** Earliest time to try a sticker again after the retries are used up. */
    private static final Map<String, Long> NEXT = new HashMap<>();
    private static final long KEEP_MS = 15000L;
    private static final Runnable KEEP = StickerHub::keep;
    /** Closed on purpose. The disconnect callback must not reconnect. */
    private static final Set<String> CLOSING = new HashSet<>();
    private static final List<Watch> WATCHES = new ArrayList<>();
    private static final Set<String> BONDING = new HashSet<>();
    /** Other links dropped so this head unit can bond a new sticker. */
    private static final Set<String> PAUSED = new HashSet<>();
    private static final Map<String, Runnable> BOND_TIMEOUT = new HashMap<>();
    private static String queuedAddress;
    /** Address that just timed out. The next dial is the other sticker. */
    private static String yield = "";
    /** Pause delay is still running. Do not bring the other stickers back yet. */
    private static int holding;
    private static final Map<String, ArrayDeque<BluetoothGattCharacteristic>> SUBSCRIBE = new HashMap<>();
    private static final Map<String, Integer> TRIES = new HashMap<>();
    private static Context app;
    /** Sticker screen is open, so a press is for saving, not for running the shortcut. */
    private static boolean capture;
    /** Any screen of this app is in front. Saved stickers stay up and a lost link comes back. */
    private static boolean inFront;
    /** True while the sticker screen is scanning. A connectGatt during that scan returns nothing. */
    private static boolean scanHold;
    /** The recording float is on screen, so retry keeps going after the app is hidden. */
    private static boolean floating;
    /** Sticker screen scan. connectGatt while that scan is running never answers on this radio. */
    private static Runnable stopScan;
    private static boolean bondsRegistered;

    private StickerHub() {
    }

    public static void addWatch(Watch watch) {
        if (watch == null) {
            return;
        }
        MAIN.post(() -> {
            if (!WATCHES.contains(watch)) {
                WATCHES.add(watch);
            }
        });
    }

    public static void removeWatch(Watch watch) {
        MAIN.post(() -> WATCHES.remove(watch));
    }

    public static void setCapture(boolean on) {
        capture = on;
    }

    /** The sticker screen registers this so a connect can stop the scan first. */
    public static void setScanStop(Runnable stop) {
        stopScan = stop;
    }

    /**
     * Free the radio for discovery. This head unit answers neither classic discovery nor an LE
     * scan while a sticker link is up. {@code on} drops those links; {@code off} brings them back.
     */
    public static void holdForScan(Context context, boolean on) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        MAIN.post(() -> {
            app = application;
            if (on) {
                scanHold = true;
                MAIN.removeCallbacks(KEEP);
                for (String address : new ArrayList<>(OPEN.keySet())) {
                    PAUSED.add(address);
                    close(address);
                }
                return;
            }
            if (!scanHold) {
                return;
            }
            scanHold = false;
            PAUSED.clear();
            syncOnMain(application);
        });
    }

    /** App came to the front, or left. A lost sticker is connected again while a screen is open. */
    public static void setAppInFront(Context context, boolean on) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        MAIN.post(() -> {
            boolean entered = on && !inFront;
            inFront = on;
            app = application;
            if (entered) {
                NEXT.clear();
            }
            syncOnMain(application);
            if (entered) {
                keep();
            }
        });
    }

    /** Recording float shown or hidden. Retry keeps running while that button is on screen. */
    public static void setFloatingShown(Context context, boolean on) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        MAIN.post(() -> {
            boolean shown = on && !floating;
            floating = on;
            app = application;
            if (shown) {
                NEXT.clear();
            }
            syncOnMain(application);
            if (shown) {
                keep();
            }
        });
    }

    /** Live GATT link, not merely a saved address. */
    public static boolean isUp(String address) {
        return UP.contains(StickerDevices.normalize(address));
    }

    /** Connect saved stickers, or drop them when the experiment is off and the screen is closed. */
    public static void sync(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        MAIN.post(() -> syncOnMain(application));
    }

    /** User tapped Connect. Tries once, without waiting in the background. */
    public static void probe(Context context, String address) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        String mac = StickerDevices.normalize(address);
        MAIN.post(() -> {
            app = application;
            releaseRadio();
            close(mac);
            TRIES.remove(mac);
            holding++;
            MAIN.postDelayed(() -> {
                holding = Math.max(0, holding - 1);
                pairThenConnect(application, mac);
            }, 700);
        });
    }

    /** Ask the open link for a fresh signal. No-op until connectGatt has returned. */
    public static void readRssi(String address) {
        String mac = StickerDevices.normalize(address);
        MAIN.post(() -> {
            BluetoothGatt gatt = OPEN.get(mac);
            if (gatt == null) {
                return;
            }
            try {
                gatt.readRemoteRssi();
            } catch (SecurityException ignored) {
                // permission dropped
            }
        });
    }

    public static void disconnect(String address) {
        String mac = StickerDevices.normalize(address);
        MAIN.post(() -> close(mac));
    }

    private static void syncOnMain(Context application) {
        app = application;
        if (UserExit.isExited(application)) {
            closeAll();
            return;
        }
        boolean hold = holding(application);
        if (!hold) {
            MAIN.removeCallbacks(KEEP);
            closeAll();
            return;
        }
        List<String> wanted = StickerDevices.parse(new AppConfig(application).getStickerDevices());
        Set<String> keep = new HashSet<>(wanted);
        for (String address : new ArrayList<>(OPEN.keySet())) {
            if (!keep.contains(address) && !PENDING.contains(address)) {
                close(address);
            }
        }
        if (!canConnect(application)) {
            return;
        }
        connectMissing();
        armKeep();
    }

    /** Bring a saved sticker back without opening the connect page. */
    private static void keep() {
        if (app == null || UserExit.isExited(app) || scanHold) {
            return;
        }
        if (!holding(app)) {
            return;
        }
        if (holding > 0 || !BONDING.isEmpty()) {
            armKeep();
            return;
        }
        connectMissing();
        armKeep();
    }

    /**
     * Direct connect. autoConnect never calls back on this radio, so the dots stayed grey.
     * Leaves any sticker that is already up alone.
     */
    private static void reopen(String address) {
        boolean busy = OPEN.containsKey(address) || PENDING.contains(address) || UP.contains(address);
        if (StickerRecover.allowConnect(scanHold, busy) != StickerRecover.CONNECT) {
            return;
        }
        if (app == null || !shouldHold(address)) {
            return;
        }
        connect(app, address, false);
    }

    /** Connect one saved sticker that is down. A second connectGatt while one is in flight never calls back. */
    private static void connectMissing() {
        if (app == null || UserExit.isExited(app) || scanHold || !holding(app)) {
            return;
        }
        if (holding > 0 || !BONDING.isEmpty() || !PENDING.isEmpty() || !DISCOVERING.isEmpty()) {
            return;
        }
        List<String> saved = StickerDevices.parse(new AppConfig(app).getStickerDevices());
        Set<String> skip = new HashSet<>(UP);
        skip.addAll(OPEN.keySet());
        skip.addAll(PENDING);
        skip.addAll(DISCOVERING);
        skip.addAll(PAUSED);
        String address = StickerRecover.due(StickerRecover.missing(saved, skip), false, yield);
        yield = "";
        if (address.isEmpty() || !shouldHold(address)) {
            return;
        }
        reopen(address);
    }

    private static void releaseRadio() {
        Runnable stop = stopScan;
        if (stop != null) {
            stop.run();
        }
        BluetoothAdapter adapter = app == null ? null : adapter(app);
        if (adapter != null) {
            try {
                if (adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }
            } catch (SecurityException ignored) {
                // permission dropped
            }
        }
    }

    private static void armKeep() {
        MAIN.removeCallbacks(KEEP);
        if (app == null) {
            return;
        }
        if (!holding(app)) {
            return;
        }
        MAIN.postDelayed(KEEP, KEEP_MS);
    }

    private static void pairThenConnect(Context application, String address) {
        if (!canConnect(application)) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothAdapter adapter = adapter(application);
        if (adapter == null || !adapter.isEnabled()) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            AppLog.w(TAG, "地址无效: " + address);
            afterAttempt();
            return;
        }
        int bond = BluetoothDevice.BOND_NONE;
        try {
            bond = device.getBondState();
        } catch (SecurityException e) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        if (!BONDING.isEmpty() && !BONDING.contains(address)) {
            queuedAddress = address;
            phase(address, PHASE_PAIRING);
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDING) {
            cancelBondProcess(device);
            AppLog.w(TAG, "取消卡住的配对 " + address);
            MAIN.postDelayed(() -> connect(application, address, false), 600);
            return;
        }
        connect(application, address, false);
    }

    private static void ensureBonds(Context context) {
        if (bondsRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(BONDS, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(BONDS, filter);
        }
        bondsRegistered = true;
    }

    private static final BroadcastReceiver BONDS = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
            MAIN.post(() -> onBond(device, state, previous));
        }
    };

    private static void onBond(BluetoothDevice device, int state, int previous) {
        if (device == null || app == null) {
            return;
        }
        String address;
        try {
            address = StickerDevices.normalize(device.getAddress());
        } catch (SecurityException e) {
            return;
        }
        if (!BONDING.contains(address)) {
            return;
        }
        if (state == BluetoothDevice.BOND_BONDED
                || (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING)) {
            BONDING.remove(address);
            Runnable waiting = BOND_TIMEOUT.remove(address);
            if (waiting != null) {
                MAIN.removeCallbacks(waiting);
            }
            AppLog.d(TAG, "配对结束 " + address + " state=" + state);
            connect(app, address, false);
        }
    }

    private static int othersUp(String address) {
        int count = UP.size();
        if (address != null && UP.contains(address)) {
            count--;
        }
        return count;
    }

    private static void connect(Context application, String address, boolean auto) {
        if (address.isEmpty() || OPEN.containsKey(address) || PENDING.contains(address)
                || PAUSED.contains(address)) {
            return;
        }
        if (!canConnect(application)) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothAdapter adapter = adapter(application);
        if (adapter == null || !adapter.isEnabled()) {
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            AppLog.w(TAG, "地址无效: " + address);
            return;
        }
        CLOSING.remove(address);
        releaseRadio();
        PENDING.add(address);
        phase(address, PHASE_CONNECTING);
        state(address, false);
        BluetoothGattCallback callback = callback(address);
        boolean gentle = auto || StickerRecover.gentleConnect(othersUp(address));
        BluetoothGatt gatt;
        try {
            gatt = device.connectGatt(application, gentle, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            PENDING.remove(address);
            fail(address, FAIL_BLUETOOTH, 0);
            return;
        }
        if (gatt == null) {
            PENDING.remove(address);
            fail(address, FAIL_GATT, -1);
            return;
        }
        OPEN.put(address, gatt);
        long wait = gentle ? 20_000L : CONNECT_TIMEOUT_MS;
        MAIN.postDelayed(() -> {
            if (PENDING.contains(address)) {
                refresh(OPEN.get(address));
                close(address);
                failOrRetry(address, FAIL_TIMEOUT, 0);
            }
        }, wait);
        AppLog.d(TAG, "连接智能贴 " + address + " auto=" + gentle);
    }

    private static BluetoothGattCallback callback(String address) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                MAIN.post(() -> {
                    if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        if (CLOSING.contains(address)) {
                            drop(address, gatt);
                            return;
                        }
                        UP.add(address);
                        OPEN.put(address, gatt);
                        PENDING.remove(address);
                        DISCOVERING.add(address);
                        state(address, true);
                        phase(address, PHASE_DISCOVERING);
                        MAIN.postDelayed(() -> {
                            if (!DISCOVERING.remove(address)) {
                                return;
                            }
                            connectMissing();
                        }, CONNECT_TIMEOUT_MS);
                        try {
                            gatt.discoverServices();
                        } catch (SecurityException e) {
                            close(address);
                            fail(address, FAIL_BLUETOOTH, 0);
                        }
                        return;
                    }
                    if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        boolean intentional = CLOSING.remove(address);
                        boolean wasUp = UP.contains(address);
                        if (intentional) {
                            UP.remove(address);
                            drop(address, gatt);
                            return;
                        }
                        if (!wasUp && status == 0) {
                            return;
                        }
                        UP.remove(address);
                        drop(address, gatt);
                        for (int action : StickerRecover.onDisconnect(false, wasUp, status, scanHold)) {
                            if (action == StickerRecover.CONNECT) {
                                scheduleReconnect(address);
                            }
                        }
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                MAIN.post(() -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        DISCOVERING.remove(address);
                        failOrRetry(address, FAIL_GATT, status);
                        return;
                    }
                    List<BluetoothGattCharacteristic> notifies = findNotifies(gatt);
                    if (notifies.isEmpty()) {
                        logServices(gatt);
                        close(address);
                        fail(address, FAIL_NO_SERVICE, 0);
                        return;
                    }
                    SUBSCRIBE.put(address, new ArrayDeque<>(notifies));
                    PENDING.remove(address);
                    DISCOVERING.remove(address);
                    ready(address);
                    writeNext(gatt, address);
                    connectMissing();
                });
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                byte[] value = characteristic.getValue();
                MAIN.post(() -> packet(address, value));
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                MAIN.post(() -> packet(address, value == null ? characteristic.getValue() : value));
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                MAIN.post(() -> writeNext(gatt, address));
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return;
                }
                MAIN.post(() -> {
                    for (Watch watch : new ArrayList<>(WATCHES)) {
                        watch.onRssi(address, rssi);
                    }
                });
            }
        };
    }

    private static List<BluetoothGattCharacteristic> findNotifies(BluetoothGatt gatt) {
        List<BluetoothGattCharacteristic> preferred = new ArrayList<>();
        List<BluetoothGattCharacteristic> rest = new ArrayList<>();
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return preferred;
        }
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                boolean canListen = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (!canListen) {
                    continue;
                }
                if (StickerMatch.isNotify(characteristic.getUuid())) {
                    preferred.add(characteristic);
                } else {
                    rest.add(characteristic);
                }
            }
        }
        preferred.addAll(rest);
        return preferred;
    }

    private static void writeNext(BluetoothGatt gatt, String address) {
        ArrayDeque<BluetoothGattCharacteristic> queue = SUBSCRIBE.get(address);
        if (queue == null) {
            return;
        }
        while (true) {
            BluetoothGattCharacteristic next = queue.poll();
            if (next == null) {
                SUBSCRIBE.remove(address);
                return;
            }
            if (subscribe(gatt, next)) {
                return;
            }
        }
    }

    /** @return true when a descriptor write is in flight */
    private static boolean subscribe(BluetoothGatt gatt, BluetoothGattCharacteristic notify) {
        try {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(StickerMatch.CCCD);
            if (cccd == null) {
                log("notify without cccd");
                return false;
            }
            boolean indicate = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] value = indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, value);
            } else {
                cccd.setValue(value);
                gatt.writeDescriptor(cccd);
            }
            return true;
        } catch (SecurityException e) {
            log("notify denied");
            return false;
        }
    }

    private static String lastPacket = "";
    private static long lastPacketAt;

    private static void packet(String address, byte[] raw) {
        String key = address + " " + StickerFrame.hex(raw);
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(lastPacket) && now - lastPacketAt < 80L) {
            return;
        }
        lastPacket = key;
        lastPacketAt = now;
        AppLog.d(TAG, key);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onPacket(address, raw);
        }
        if (capture || app == null || !linkWanted(app)) {
            return;
        }
        StickerFrame frame = StickerFrame.parse(raw);
        if (frame == null) {
            return;
        }
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(app).getButtonShortcuts());
        Shortcut hit = ShortcutBook.match(items, frame.control, 0,
                StickerFrame.deviceName(address), frame.press);
        if (hit != null) {
            ShortcutPerformer.perform(app, hit.action);
        }
    }

    private static void scheduleReconnect(String address) {
        MAIN.postDelayed(() -> {
            if (scanHold || app == null || OPEN.containsKey(address) || PENDING.contains(address)
                    || PAUSED.contains(address)) {
                return;
            }
            if (!shouldHold(address)) {
                return;
            }
            reopen(address);
        }, StickerRecover.RECONNECT_MS);
    }

    private static boolean shouldHold(String address) {
        if (app == null || UserExit.isExited(app)) {
            return false;
        }
        if (!holding(app)) {
            return false;
        }
        return StickerDevices.parse(new AppConfig(app).getStickerDevices()).contains(address);
    }

    private static void drop(String address, BluetoothGatt gatt) {
        BluetoothGatt current = OPEN.get(address);
        if (current != null && gatt != null && current != gatt) {
            try {
                gatt.close();
            } catch (SecurityException ignored) {
                // stale callback
            }
            return;
        }
        PENDING.remove(address);
        DISCOVERING.remove(address);
        OPEN.remove(address);
        UP.remove(address);
        BluetoothGatt dying = current != null ? current : gatt;
        if (dying != null) {
            try {
                dying.close();
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        state(address, false);
    }

    private static void close(String address) {
        cancelBond(address);
        CLOSING.add(address);
        UP.remove(address);
        PENDING.remove(address);
        DISCOVERING.remove(address);
        BluetoothGatt gatt = OPEN.remove(address);
        state(address, false);
        if (gatt == null) {
            CLOSING.remove(address);
            return;
        }
        try {
            gatt.disconnect();
            gatt.close();
        } catch (SecurityException ignored) {
            // permission dropped
        }
    }

    private static void closeAll() {
        PAUSED.clear();
        UP.clear();
        NEXT.clear();
        queuedAddress = null;
        holding = 0;
        yield = "";
        DISCOVERING.clear();
        for (String address : new ArrayList<>(OPEN.keySet())) {
            close(address);
        }
        PENDING.clear();
    }

    /** Drop every open link except the one about to bond. */
    private static boolean pauseOthers(String address) {
        boolean paused = false;
        for (String other : new ArrayList<>(OPEN.keySet())) {
            if (other.equals(address)) {
                continue;
            }
            PAUSED.add(other);
            close(other);
            paused = true;
        }
        if (paused) {
            AppLog.d(TAG, "先断开其它智能贴再配对 " + address);
        }
        return paused;
    }

    private static void resumePaused() {
        if (app == null || holding > 0 || !BONDING.isEmpty()) {
            return;
        }
        for (String address : PENDING) {
            if (!PAUSED.contains(address)) {
                return;
            }
        }
        List<String> again = new ArrayList<>(PAUSED);
        PAUSED.clear();
        for (String address : again) {
            if (!OPEN.containsKey(address) && !PENDING.contains(address)) {
                connect(app, address, false);
            }
        }
    }

    private static void afterAttempt() {
        if (queuedAddress != null && holding == 0 && BONDING.isEmpty()) {
            String next = queuedAddress;
            queuedAddress = null;
            if (app != null) {
                probe(app, next);
            }
            return;
        }
        resumePaused();
    }

    /** A stuck bond holds the radio. connectGatt then never answers, which is fail 2 status 0. */
    private static void cancelBondProcess(BluetoothDevice device) {
        try {
            java.lang.reflect.Method method = device.getClass().getMethod("cancelBondProcess");
            method.invoke(device);
        } catch (Exception ignored) {
            try {
                java.lang.reflect.Method method = device.getClass().getDeclaredMethod("cancelBondProcess");
                method.setAccessible(true);
                method.invoke(device);
            } catch (Exception ignored2) {
                // hidden API missing
            }
        }
    }

    private static void refresh(BluetoothGatt gatt) {
        if (gatt == null) {
            return;
        }
        try {
            java.lang.reflect.Method method = gatt.getClass().getMethod("refresh");
            method.invoke(gatt);
        } catch (Exception ignored) {
            // hidden API missing
        }
    }

    /** A saved sticker stays connected for the life of the process. */
    private static boolean holding(Context context) {
        if (awake() || linkWanted(context) || !WATCHES.isEmpty()) {
            return true;
        }
        return !StickerDevices.parse(new AppConfig(context).getStickerDevices()).isEmpty();
    }

    private static boolean awake() {
        return inFront || floating;
    }

    /** Switch on, or a sticker shortcut already saved. */
    private static boolean linkWanted(Context context) {
        AppConfig config = new AppConfig(context);
        if (config.isStickerEnabled()) {
            return true;
        }
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item != null && StickerFrame.isSticker(item.deviceName)) {
                return true;
            }
        }
        return false;
    }

    private static void logServices(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return;
        }
        for (BluetoothGattService service : services) {
            log(service.getUuid().toString());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                log("  " + characteristic.getUuid() + " props=" + characteristic.getProperties());
            }
        }
    }

    private static boolean canConnect(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private static void state(String address, boolean connected) {
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onState(address, connected);
        }
    }

    private static void ready(String address) {
        TRIES.remove(address);
        NEXT.remove(address);
        if (app != null) {
            StickerArrived.show(app, address);
        }
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onReady(address);
        }
        MAIN.postDelayed(() -> {
            afterAttempt();
            connectMissing();
        }, 1200);
    }

    private static void phase(String address, int which) {
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onPhase(address, which);
        }
    }

    private static void failOrRetry(String address, int reason, int status) {
        int tries = TRIES.containsKey(address) ? TRIES.get(address) : 0;
        if (tries < CONNECT_TRIES && app != null && (reason == FAIL_TIMEOUT || status == 133 || status == 8)) {
            TRIES.put(address, tries + 1);
            AppLog.w(TAG, "再连一次 " + address + " try=" + (tries + 1) + " status=" + status);
            log("retry " + (tries + 1) + " " + address);
            phase(address, PHASE_CONNECTING);
            MAIN.postDelayed(StickerHub::connectMissing, 800);
            return;
        }
        fail(address, reason, status);
    }

    private static void cancelBond(String address) {
        BONDING.remove(address);
        Runnable waiting = BOND_TIMEOUT.remove(address);
        if (waiting != null) {
            MAIN.removeCallbacks(waiting);
        }
    }

    private static void fail(String address, int reason, int status) {
        NEXT.put(address, android.os.SystemClock.elapsedRealtime() + 60000L);
        if ((reason == FAIL_TIMEOUT || reason == FAIL_GATT) && shouldHold(address)) {
            yield = address;
            MAIN.postDelayed(StickerHub::connectMissing, StickerRecover.AGAIN_MS);
        }
        AppLog.w(TAG, "智能贴失败 " + address + " reason=" + reason + " status=" + status);
        log("fail " + reason + " status=" + status);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onFail(address, reason, status);
        }
        afterAttempt();
    }

    private static void log(String line) {
        AppLog.d(TAG, line);
        for (Watch watch : new ArrayList<>(WATCHES)) {
            watch.onLog(line);
        }
    }
}
