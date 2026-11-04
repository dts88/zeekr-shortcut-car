package com.kooo.evcam.button;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/**
 * 跟一台灵动贴的一次连接：只在长按（约 3 秒、背光闪）时连（车主 2026-11-04，同「极+」）。连上以后订按键通知、查版本、读电量、查序列号，
 * 之后一直连着：灵动贴最后一下按完 15 秒自己断开（Lab findings §2.61、§2.93 车上又看到一次）。我们不主动断开，
 * 除了出错、蓝牙关了、车机要睡、退出、关了灵动贴、删了它、要连另一台。
 *
 * <p>顺序照「极+」（Lab findings §2.93）：连上 → 找服务 → 隔 {@link #SUBSCRIBE_DELAY_MS} 订 0x2A60 的通知（订上算连好，绑定在这一刻算成）
 * → 查版本 → 订电量通知、读一次电量 → 查序列号；两条查询之前各隔 {@link #QUERY_GAP_MS}。只写查询和蓝牙标准的订阅，不写别的。</p>
 *
 * <p>安卓的 GATT 同时只做一件事：一件做完（它的回调来了）再做下一件，回调要对得上是哪一种、而且这件真的开始了才算；
 * 排不上（方法回 false，多半是 GATT 还忙）隔 {@link #BUSY_RETRY_MS} 再排，{@link #BUSY_TRIES} 次为止；一件 {@link #OP_TIMEOUT_MS}
 * 没回就断开：安卓这时会把 GATT 卡 30 秒，后面的都排不上（Lab 3.5.3）。</p>
 *
 * <p>一次 {@link #CONNECT_TIMEOUT_MS} 没连上算没连上，最多连 {@link #MAX_ATTEMPTS} 次、两次之间至少隔 {@link #RETRY_GAP_MS}；
 * 连上 {@link #READY_TIMEOUT_MS} 还没订好按键通知也算没连上。</p>
 *
 * <p>只在灵动贴自己的线程上用（{@code SmartButtons} 的 bt 线程）：蓝牙回调在别的线程上来，一律转过去。</p>
 */
@SuppressWarnings("deprecation") // 车机是安卓 12，GATT 的读写只有旧的那一套
final class ButtonLink {

    static final long CONNECT_TIMEOUT_MS = 10_000L;
    static final long READY_TIMEOUT_MS = 8_000L;
    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_GAP_MS = 1_000L;
    static final long OP_TIMEOUT_MS = 3_000L;
    static final long BUSY_RETRY_MS = 200L;
    static final int BUSY_TRIES = 3;
    /** 连上、找到服务以后隔这么久再订按键通知（「极+」）。 */
    static final long SUBSCRIBE_DELAY_MS = 100L;
    /** 每条查询之前隔这么久（「极+」的写队列也是一条一条来）。 */
    static final long QUERY_GAP_MS = 150L;

    static final UUID NOTIFY = uuid16(0x2a60);
    static final UUID WRITE = uuid16(0x2a70);
    static final UUID BATTERY_LEVEL = uuid16(0x2a19);
    static final UUID CCCD = uuid16(0x2902);
    /** 订按键通知那一件的名字：它没成就断开再连。 */
    private static final String LABEL_SUBSCRIBE = "subscribe 0x2A60";

    /** 给 {@code SmartButtons} 的（都在 bt 线程上）。 */
    interface Listener {
        /** 第 {@code n} 次开始连（连之前）。 */
        void attempt(ButtonLink link, int n);

        /** 按键通知订上了：从这时起按键从通知来。 */
        void ready(ButtonLink link);

        /** 0x2A60 来了一条。 */
        void reply(ButtonLink link, SmartButtonAd.Reply reply, byte[] raw);

        /** 读到、推来一个电量（%）。 */
        void battery(ButtonLink link, int percent);

        /** 断了、没连上、关掉了：之后这个对象不再用。 */
        void closed(ButtonLink link, String why);
    }

    /** 排着的一件事。 */
    private static final class Op {
        static final int DESCRIPTOR = 0;
        static final int WRITE = 1;
        static final int READ = 2;

        final String label;
        final int type;
        final long delay;
        final Runnable run;
        /** 真的开始了（隔的那一会儿过了、调了安卓的方法）：没开始时来的回调不算它的。 */
        boolean started;
        int busyTries;

        Op(String label, int type, long delay, Runnable run) {
            this.label = label;
            this.type = type;
            this.delay = delay;
            this.run = run;
        }
    }

    final String address;
    /** 这是在绑新的（订上通知就算绑上）。 */
    final boolean binding;
    /** 连了几次。 */
    int attempts;
    /** 连上过（不管订没订好）：断开以后它记着的计数作废（{@code SmartButtons}）。 */
    boolean everConnected;
    /** 第二次起连之前先停着扫描（{@code SmartButtons#ensureScan}）：车上一边扫一边连失败过（Lab 2.14.3），看是不是这个原因。 */
    boolean pausesScan;

    private final Context app;
    private final Handler bt;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyCh;
    private BluetoothGattCharacteristic writeCh;
    private BluetoothGattCharacteristic batteryCh;
    private Op current;
    private Runnable opTimeout;
    private boolean connected;
    private boolean ready;
    private boolean closed;
    private boolean retryPending;
    private long lastAttemptAt;
    private long startedAt;

    ButtonLink(Context app, Handler bt, BluetoothAdapter adapter, String address, boolean binding, Listener listener) {
        this.app = app;
        this.bt = bt;
        this.adapter = adapter;
        this.address = address;
        this.binding = binding;
        this.listener = listener;
    }

    boolean isReady() {
        return ready && !closed;
    }

    boolean isClosed() {
        return closed;
    }

    /** 订好过按键通知（关掉以后也照实答）。 */
    boolean wasReady() {
        return ready;
    }

    /** 从开始连到现在多久（毫秒）。 */
    long ageMs() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    /** 开始连（第一次）。 */
    void start() {
        startedAt = SystemClock.elapsedRealtime();
        connect();
    }

    /** 断开、收尾（不再重试）。{@code why} 是给黑匣子的英文。 */
    void close(String why) {
        if (closed) {
            return;
        }
        closed = true;
        dropGatt();
        listener.closed(this, why);
    }

    // ================================================================= 连

    private void connect() {
        retryPending = false;
        if (closed) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            close("not connected after " + attempts + " attempts");
            return;
        }
        dropGatt();
        attempts++;
        lastAttemptAt = SystemClock.elapsedRealtime();
        listener.attempt(this, attempts);
        if (closed) {
            return;
        }
        BluetoothGatt g;
        try {
            g = adapter.getRemoteDevice(address).connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException e) {
            close("connectGatt: " + e);
            return;
        }
        if (g == null) {
            retryOrClose("connectGatt returned null");
            return;
        }
        gatt = g;
        bt.postDelayed(() -> {
            if (gatt == g && !connected && !closed) {
                retryOrClose("not connected in " + CONNECT_TIMEOUT_MS / 1000 + " s");
            }
        }, CONNECT_TIMEOUT_MS);
    }

    /** 没连上、或者连上了还没订好通知就出了事：隔一会儿再连，{@link #MAX_ATTEMPTS} 次为止。同一条链路连着报几回断开，只排一次。 */
    private void retryOrClose(String why) {
        if (closed || retryPending) {
            return;
        }
        if (ready || attempts >= MAX_ATTEMPTS) {
            close(why);
            return;
        }
        dropGatt();
        retryPending = true;
        long wait = Math.max(0, lastAttemptAt + RETRY_GAP_MS - SystemClock.elapsedRealtime());
        bt.postDelayed(this::connect, wait);
    }

    /** 这一条链路不要了（换下一次连、或者收尾）：排着的事、找到的特征都清掉。 */
    private void dropGatt() {
        if (opTimeout != null) {
            bt.removeCallbacks(opTimeout);
            opTimeout = null;
        }
        ops.clear();
        current = null;
        notifyCh = null;
        writeCh = null;
        batteryCh = null;
        connected = false;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (RuntimeException ignored) {
                // 关不了就算了
            }
            gatt = null;
        }
    }

    // ================================================================= 一件一件做

    private void enqueue(Op op) {
        ops.addLast(op);
        if (current == null) {
            next();
        }
    }

    private void next() {
        if (closed) {
            return;
        }
        current = ops.pollFirst();
        if (current == null) {
            return;
        }
        Op op = current;
        Runnable begin = () -> {
            if (current != op || closed) {
                return;
            }
            op.started = true;
            opTimeout = () -> {
                if (current == op) {
                    close(op.label + ": no answer in " + OP_TIMEOUT_MS / 1000 + " s");
                }
            };
            bt.postDelayed(opTimeout, OP_TIMEOUT_MS);
            op.run.run();
        };
        if (op.delay > 0) {
            bt.postDelayed(begin, op.delay);
        } else {
            begin.run();
        }
    }

    /** 现在这件是这一种、而且已经开始了：来的回调是它的。 */
    private boolean expecting(int type) {
        return current != null && current.started && current.type == type;
    }

    /** 这件做完了（回调来了、或者做不成），做下一件。 */
    private void done() {
        if (current == null) {
            return;
        }
        if (opTimeout != null) {
            bt.removeCallbacks(opTimeout);
            opTimeout = null;
        }
        next();
    }

    /** 安卓的方法回 false（多半是 GATT 还忙）：隔一会儿再排，{@link #BUSY_TRIES} 次还不行就算没做成。 */
    private void busy() {
        Op op = current;
        if (op == null) {
            return;
        }
        if (op.busyTries < BUSY_TRIES) {
            op.busyTries++;
            bt.postDelayed(() -> {
                if (current == op && !closed) {
                    op.run.run();
                }
            }, BUSY_RETRY_MS);
        } else {
            failed(op.label + ": not queued after " + (BUSY_TRIES + 1) + " tries");
        }
    }

    /** 这件没做成：订按键通知那一件没成就断开再连（没它连着也没用），别的跳过、做下一件。 */
    private void failed(String why) {
        Op op = current;
        if (op != null && op.type == Op.DESCRIPTOR && !ready && notifyCh != null && op.label.equals(LABEL_SUBSCRIBE)) {
            if (opTimeout != null) {
                bt.removeCallbacks(opTimeout);
                opTimeout = null;
            }
            current = null;
            retryOrClose(why);
            return;
        }
        done();
    }

    private void subscribe(BluetoothGattCharacteristic ch) {
        try {
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
            if (cccd == null) {
                failed("no 0x2902 on " + ch.getUuid());
                return;
            }
            if (!gatt.setCharacteristicNotification(ch, true)) {
                failed("setCharacteristicNotification refused");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(cccd)) {
                busy();
            }
        } catch (RuntimeException e) {
            failed("subscribe: " + e);
        }
    }

    private void write(byte[] frame) {
        try {
            int props = writeCh.getProperties();
            writeCh.setWriteType((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeCh.setValue(frame);
            if (!gatt.writeCharacteristic(writeCh)) {
                busy();
            }
        } catch (RuntimeException e) {
            failed("write: " + e);
        }
    }

    private void read(BluetoothGattCharacteristic ch) {
        try {
            if (!gatt.readCharacteristic(ch)) {
                busy();
            }
        } catch (RuntimeException e) {
            failed("read: " + e);
        }
    }

    // ================================================================= 连上以后的顺序

    private void onServices() {
        try {
            for (BluetoothGattService service : gatt.getServices()) {
                if (notifyCh == null) {
                    notifyCh = service.getCharacteristic(NOTIFY);
                }
                if (writeCh == null) {
                    writeCh = service.getCharacteristic(WRITE);
                }
                if (batteryCh == null) {
                    batteryCh = service.getCharacteristic(BATTERY_LEVEL);
                }
            }
        } catch (RuntimeException e) {
            retryOrClose("services: " + e);
            return;
        }
        if (notifyCh == null) {
            close("no 0x2A60: not a smart button");
            return;
        }
        BluetoothGattCharacteristic ch = notifyCh;
        enqueue(new Op(LABEL_SUBSCRIBE, Op.DESCRIPTOR, SUBSCRIBE_DELAY_MS, () -> subscribe(ch)));
    }

    /** 按键通知订上了：算连好了，接着查版本、读电量、查序列号（「极+」灵动贴的顺序）。 */
    private void onReady() {
        ready = true;
        listener.ready(this);
        if (closed) {
            return;
        }
        if (writeCh != null) {
            byte[] ask = SmartButtonAd.askVersion();
            enqueue(new Op("ask version", Op.WRITE, QUERY_GAP_MS, () -> write(ask)));
        }
        if (batteryCh != null) {
            BluetoothGattCharacteristic ch = batteryCh;
            enqueue(new Op("subscribe 0x2A19", Op.DESCRIPTOR, 0, () -> subscribe(ch)));
            enqueue(new Op("read 0x2A19", Op.READ, 0, () -> read(ch)));
        }
        if (writeCh != null) {
            byte[] ask = SmartButtonAd.askSerial();
            enqueue(new Op("ask serial", Op.WRITE, QUERY_GAP_MS, () -> write(ask)));
        }
    }

    // ================================================================= 回调（别的线程 → bt 线程）

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            bt.post(() -> {
                if (g != gatt || closed) {
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    everConnected = true;
                    bt.postDelayed(() -> {
                        if (gatt == g && !ready && !closed) {
                            retryOrClose("connected but not ready in " + READY_TIMEOUT_MS / 1000 + " s");
                        }
                    }, READY_TIMEOUT_MS);
                    try {
                        if (!g.discoverServices()) {
                            retryOrClose("discoverServices refused");
                        }
                    } catch (RuntimeException e) {
                        retryOrClose("discoverServices: " + e);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (ready) {
                        close(String.format(Locale.US, "disconnected (status %d)", status));
                    } else {
                        retryOrClose(String.format(Locale.US, "%s (status %d)",
                                connected ? "dropped before ready" : "not connected", status));
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            bt.post(() -> {
                if (g != gatt || closed) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retryOrClose("service discovery failed (status " + status + ")");
                } else {
                    onServices();
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            UUID ch = d.getCharacteristic().getUuid();
            bt.post(() -> {
                if (g != gatt || closed || !expecting(Op.DESCRIPTOR)) {
                    return; // 不是这一条链路的、或者晚到的
                }
                if (NOTIFY.equals(ch) && !ready) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        done();
                        onReady();
                    } else {
                        failed("0x2A60 not subscribed (status " + status + ")");
                    }
                } else {
                    done();
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            bt.post(() -> {
                if (g == gatt && !closed && expecting(Op.WRITE)) {
                    done();
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            read(g, c.getUuid(), c.getValue(), status);
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c,
                                         @NonNull byte[] value, int status) {
            read(g, c.getUuid(), value, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            changed(g, c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt g, @NonNull BluetoothGattCharacteristic c,
                                            @NonNull byte[] value) {
            changed(g, c.getUuid(), value);
        }
    };

    /** 安卓 13 起的新回调和老回调都接：哪个版本只会叫其中一个。 */
    private void read(BluetoothGatt g, UUID uuid, byte[] value, int status) {
        byte[] copy = value == null ? null : value.clone();
        bt.post(() -> {
            if (g != gatt || closed || !expecting(Op.READ)) {
                return;
            }
            done();
            if (BATTERY_LEVEL.equals(uuid) && status == BluetoothGatt.GATT_SUCCESS && copy != null && copy.length > 0) {
                listener.battery(this, copy[0] & 0xFF);
            }
        });
    }

    private void changed(BluetoothGatt g, UUID uuid, byte[] value) {
        byte[] copy = value == null ? null : value.clone();
        bt.post(() -> {
            if (g != gatt || closed) {
                return;
            }
            if (NOTIFY.equals(uuid)) {
                listener.reply(this, SmartButtonAd.parseNotification(copy), copy);
            } else if (BATTERY_LEVEL.equals(uuid) && copy != null && copy.length > 0) {
                listener.battery(this, copy[0] & 0xFF);
            }
        });
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", value));
    }
}
