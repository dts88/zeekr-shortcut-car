package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 车辆信号的唯一入口：来源往这里写，用的人从这里读。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li>两份快照，都不可变、版本号只往上走：{@link Readings}（每个信号的读数，「系统信息」页看它）和
 *       {@link VehicleState}（信息条要的那几项，由 {@link VehicleStateMapper} 从读数推出来，再加定位）。</li>
 *   <li>来源各管一条路：{@link EcarxSource}（车机自带的 ECARX 接口，订阅），{@link LocationSource}（经纬度，
 *       车不给车速时用 GPS 车速）。再有新的路就是再加一个来源。</li>
 *   <li><b>谁要用就登记，分两层</b>（{@link TelemetryUsers}，2026-10-12 熄屏跟着物理屏幕）：
 *       <ul>
 *         <li>核心（{@link #keepCore}）：熄屏判断（{@code screen.ScreenState}）在进程一起来时登记，进程活着一直在。
 *             车辆接口的离散信号一直在读（订阅 + 每 {@link #RECONCILE_MS} 对账）；不读浮点传感器、不开定位。</li>
 *         <li>完整（{@link #acquire} / {@link #release}，按名字）：录像开着信息条、「系统信息」页、车辆状态面板、
 *             录像时开着闪远光自动锁定（{@code storage.AutoLock}）……另外读浮点传感器、开定位。最后一个完整用户走了，
 *             浮点和定位停掉，它们的值、位置、GPS 车速清掉 —— 下次不会把旧的车速、位置画进新录像；离散的照旧（核心还在用），
 *             不发空快照。</li>
 *       </ul></li>
 *   <li><b>快读</b>（{@link #acquireFast} / {@link #releaseFast}，按名字，2026-10-21 模拟声浪）：登记的那几个浮点按登记的间隔另读一份
 *       （{@link #fast}，{@link FastReadings}），不发布、不进主线程、不改上面两层的读法；搭在核心的车辆接口上，不开定位。</li>
 *   <li>车辆接口连不上（{@link CarLink#FAILED}：连的时候出错，或者连上之后整遍读不到）每 {@link #RETRY_MS} 换一个新来源再连，
 *       熄屏判断在安卓亮屏时也叫一次（{@link #retryLinkIfFailed}）；这台车机根本没有这套接口（{@link CarLink#UNAVAILABLE}）不再试。</li>
 *   <li>要「此刻」的读数（安卓刚亮屏，记着的可能是睡前的）：{@link #reconcileNow} 立刻读一遍、立刻发布，
 *       回调排在监听者那一批后面。</li>
 * </ul>
 *
 * <p>车辆接口的结果每变一次往黑匣子记一行「车辆信号连接：…」（连接经过的细节在括号里）；
 * 定位每开一次记一行「行驶信息来源 location: …」。</p>
 */
public final class Telemetry {

    private static final String TAG = "Telemetry";
    private static final Telemetry INSTANCE = new Telemetry();

    /** 离散信号多久对一次账：回调漏了，读数最多晚这么久；连着两遍一个值都读不到算断了（{@link CarLink#FAILED}）。 */
    public static final long RECONCILE_MS = EcarxSource.RECONCILE_MS;
    /** 车辆接口连不上，多久再连一次。 */
    public static final long RETRY_MS = 5 * 60_000L;

    /** 来源改快照的方式：在最新一份上改几项。 */
    public interface Edit {
        void apply(VehicleState.Builder builder);
    }

    /** 读数变了、或者车辆接口的结果（{@link #carLink}）变了，谁来看（在主线程上回调；结果变了的那一次读数可能没变）。 */
    public interface Listener {
        void onReadingsChanged(Readings readings);
    }

    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TelemetryUsers users = new TelemetryUsers();
    /** 谁要哪几个浮点读得更勤（{@link #acquireFast}）。 */
    private final FastReads fastReads = new FastReads();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile VehicleState state = VehicleState.empty();
    private volatile Readings readings = Readings.empty();
    /** 快读那几个浮点最近一次的值（{@link #fast}）；没人快读时是空的。 */
    private volatile FastReadings fastReadings = FastReadings.empty();
    /** 推来的油门深度（{@link #pushedThrottle}）；快读里有油门深度时才收（{@link #throttlePushWanted}）。 */
    private volatile PushedValue pushedThrottle = PushedValue.empty();
    private volatile boolean throttlePushWanted;
    /** 车速有没有从车辆接口来过：有的话定位那边的 GPS 车速就不写了（那个是推算的）。 */
    private volatile boolean carSpeedSeen;
    private Context app;
    /** 有人用（核心或完整）时不为 null；连不上再连时换成新的一个。 */
    private EcarxSource car;
    /** 有完整用户时不为 null。 */
    private LocationSource location;
    /** 上一次写进黑匣子的车辆接口结果（一次变化一行，{@link #carReported}）；还没写过是 null。 */
    private String linkNoted;
    private final Runnable retry = this::retryLinkIfFailed;

    private Telemetry() {
    }

    public static Telemetry get() {
        return INSTANCE;
    }

    /** 信息条的勾选或开发者模式变了（{@link InfoBar}）：读数不变也按新的勾选重新映射一次。 */
    public void selectionChanged() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        if (c != null) {
            c.republish();
        }
    }

    /**
     * 核心登记：进程活着就一直读车辆接口的离散信号（熄屏判断要的屏幕电源事件、点火、哨兵、挡位都在里面），
     * 不读浮点、不开定位，不注销。{@code screen.ScreenState.install} 叫；重复叫无害。
     */
    public void keepCore(Context context) {
        synchronized (lock) {
            app = context.getApplicationContext();
            if (users.keepCore() != TelemetryUsers.Change.START_CORE) {
                return;
            }
            startCar(false);
        }
        AppLog.i(TAG, "车辆信号开始收集（核心：只读离散信号）");
    }

    /**
     * 完整登记：我要用车辆信号，连浮点传感器和定位。第一个完整用户把浮点和定位开起来（核心在跑就不重连、快照不清）；
     * 重复登记无害。
     */
    public void acquire(Context context, String who) {
        synchronized (lock) {
            app = context.getApplicationContext();
            TelemetryUsers.Change change = users.acquire(who);
            if (change == TelemetryUsers.Change.START_FULL) {
                startCar(true);
            } else if (change == TelemetryUsers.Change.FLOATS_ON) {
                car.floats(true);
            } else {
                return;
            }
            location = new LocationSource(this);
            location.start(app);
        }
        AppLog.i(TAG, "车辆信号完整收集开始（" + who + "）：加上浮点传感器、定位");
    }

    /**
     * 完整注销：我不用了；没登记过也无害。最后一个完整用户走了：核心在的话只停浮点和定位、清它们的值（在车辆接口自己的线程上清，
     * 那份表只在那里碰），离散的照旧；核心不在就全停、快照清空。
     */
    public void release(String who) {
        EcarxSource stopped = null;
        LocationSource l;
        synchronized (lock) {
            TelemetryUsers.Change change = users.release(who);
            if (change != TelemetryUsers.Change.FLOATS_OFF && change != TelemetryUsers.Change.STOP) {
                return;
            }
            l = location;
            location = null;
            if (change == TelemetryUsers.Change.STOP) {
                stopped = car;
                car = null;
                linkNoted = null;
                carSpeedSeen = false;
                state = VehicleState.empty();
                readings = Readings.empty();
                fastReadings = FastReadings.empty();
                pushedThrottle = PushedValue.empty();
            } else {
                car.floats(false);
            }
        }
        if (l != null) {
            l.stop();
        }
        if (stopped != null) {
            main.removeCallbacks(retry);
            stopped.stop();
            AppLog.i(TAG, "车辆信号停止收集（" + who + " 是最后一个）");
            notifyListeners(Readings.empty());
        } else {
            AppLog.i(TAG, "车辆信号回到核心（" + who + " 是最后一个完整用户）：浮点传感器、定位停了");
        }
    }

    /**
     * 快读登记：这几个浮点信号我要读得比 {@link EcarxSource#FLOAT_POLL_MS} 勤（模拟声浪要 50 ms，跟得上脚），同一个名字再登记就是改。
     * 只收浮点（离散的本来就是订阅的）；读到的放进 {@link #fast} 那份快照，不发布、不进主线程、不走信息条那条路，
     * 其余的读法一样不动。快读搭在核心的车辆接口上（核心进程活着一直在），不开定位。
     */
    public void acquireFast(String who, java.util.Set<Signal> signals, long intervalMs) {
        EcarxSource c;
        FastReads.Plan plan;
        synchronized (lock) {
            plan = fastReads.acquire(who, signals, intervalMs);
            throttlePushWanted = plan != null && plan.signals.contains(Signal.THROTTLE_DEPTH);
            c = car;
        }
        if (c != null) {
            c.fast(plan);
        }
        AppLog.i(TAG, "快读登记（" + who + "）：" + plan);
    }

    /** 快读注销；没登记过也无害。最后一个走了，快读停掉、那份快照清空。 */
    public void releaseFast(String who) {
        EcarxSource c;
        FastReads.Plan plan;
        synchronized (lock) {
            plan = fastReads.release(who);
            c = car;
            throttlePushWanted = plan != null && plan.signals.contains(Signal.THROTTLE_DEPTH);
            if (plan == null) {
                fastReadings = FastReadings.empty();
            }
            if (!throttlePushWanted) {
                pushedThrottle = PushedValue.empty();
            }
        }
        if (c != null) {
            c.fast(plan);
        }
        AppLog.i(TAG, "快读注销（" + who + "）：" + (plan == null ? "没人快读了" : plan));
    }

    /** 快读那几个浮点最近一次读到的值，永远不为 null；没人快读、或者车辆接口没连着时是空的。哪个线程都能读。 */
    public FastReadings fast() {
        return fastReadings;
    }

    /**
     * 车机推来的油门深度最近一份（试验，项目所有者 2026-10-21：和 50 ms 快读并行，看推送快不快），永远不为 null；
     * 快读里没有油门深度、车机不推、或者车辆接口没连着时是空的。哪个线程都能读。
     */
    public PushedValue pushedThrottle() {
        return pushedThrottle;
    }

    /** 油门深度的推送挂得怎么样（诊断报告用）；没试过是空的。 */
    public String pushStatus() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        return c == null ? "" : c.pushStatus();
    }

    /** 车辆接口收到一次推送（车的线程上叫，不转线程）。不是当前来源的、快读里没有它的丢掉。 */
    void publishPush(EcarxSource source, Signal signal, float value, long atMs) {
        if (signal != Signal.THROTTLE_DEPTH || !throttlePushWanted) {
            return;
        }
        synchronized (lock) {
            if (source != car) {
                return;
            }
            pushedThrottle = new PushedValue(value, atMs, pushedThrottle.count + 1);
        }
    }

    /** 此刻登记着快读的名字（诊断报告用）。 */
    public List<String> fastUsers() {
        synchronized (lock) {
            return fastReads.names();
        }
    }

    /** 车辆接口快读了一遍。不是当前来源的丢掉。 */
    void publishFast(EcarxSource source, FastReadings snapshot) {
        synchronized (lock) {
            if (source != car) {
                return;
            }
            fastReadings = snapshot;
        }
    }

    /** 在 {@link #lock} 里：起一个新的车辆接口来源，快照从空的开始；快读照登记着的来。 */
    private void startCar(boolean floats) {
        carSpeedSeen = false;
        state = VehicleState.empty();
        readings = Readings.empty();
        fastReadings = FastReadings.empty();
        pushedThrottle = PushedValue.empty();
        car = new EcarxSource(this, floats);
        car.fast(fastReads.plan());
        car.start(app);
    }

    /**
     * 车辆接口此刻连不上（{@link CarLink#FAILED}）就换一个新来源再连一次；连着的、在连的、这台车没有的不动。
     * 每 {@link #RETRY_MS} 自己叫一次；熄屏判断在安卓亮屏、判那条边之前也叫（车机睡醒，车机服务可能刚起来）—— 它等连上后的
     * 第一份读数再判（{@code ScreenState}）。主线程上叫。
     */
    public void retryLinkIfFailed() {
        String last;
        synchronized (lock) {
            EcarxSource old = car;
            if (old == null || old.link() != CarLink.FAILED || app == null) {
                return;
            }
            last = old.status();
            // 排着的那一次再连撤掉 —— 在新来源起来之前撤：它连不上会在自己的线程上马上排下一次（carReported），
            // 放到起来之后再撤，就可能把那一次撤掉，从此没有定时再连（审查 rev3 F3）
            main.removeCallbacks(retry);
            // 旧的先停：它停的时候把传感器回调的去处（EcarxSource.active）清掉，新的连上后再接过去
            old.stop();
            fastReadings = FastReadings.empty();
            pushedThrottle = PushedValue.empty();
            car = new EcarxSource(this, users.full());
            car.fast(fastReads.plan());
            car.start(app);
        }
        AppLog.i(TAG, "车辆接口再连一次（上次：" + last + "）");
    }

    /**
     * 立刻把离散信号读一遍、有变化立刻发布（不等合并窗口），然后在主线程上叫 {@code onDone}：
     * 监听者那一批先进主线程队列，{@code onDone} 排在后面，到它时 {@link #readings()} 就是这一遍读到的。
     * 安卓刚亮屏时用（记着的读数可能是车机睡之前的），黑匣子里这一遍读到的变化标「醒来时读到」；有人在、车辆还说黑时
     * 熄屏判断也叫一遍（{@code ScreenState.refresh}），那一遍不是刚醒，和对账一样标（对账的节拍一直在走）。
     * 车辆接口没连着（没人用、在连、连不上、这台车没有）时不等，马上把 {@code onDone} 交给主线程。{@code onDone} 一定会被叫一次。
     *
     * @param afterWake 安卓刚亮（车机睡过）
     */
    public void reconcileNow(boolean afterWake, Runnable onDone) {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        if (c == null || c.link() != CarLink.CONNECTED || !c.reconcileNow(afterWake, onDone)) {
            main.post(onDone);
        }
    }

    /** 信息条要的快照，永远不为 null。 */
    public VehicleState latest() {
        return state;
    }

    /** 每个信号的读数，永远不为 null。 */
    public Readings readings() {
        return readings;
    }

    /** 核心登记过没有（诊断报告用）。 */
    public boolean coreKept() {
        synchronized (lock) {
            return users.core();
        }
    }

    /** 此刻的完整用户，登记的先后（诊断报告用）；空的 = 浮点传感器和定位没在读。 */
    public List<String> fullUsers() {
        synchronized (lock) {
            return users.names();
        }
    }

    /** 来源改几项。不是当前来源的（停了、再连时换掉了）写入丢掉 —— 停掉的定位晚到的一次位置也不会写回来。 */
    void edit(Object source, Edit edit) {
        synchronized (lock) {
            if (source != car && source != location) {
                return;
            }
            VehicleState.Builder b = state.edit();
            edit.apply(b);
            state = b.build();
        }
    }

    /** 车辆接口发布一份读数。不是当前来源的丢掉。 */
    void publishReadings(EcarxSource source, Readings snapshot) {
        synchronized (lock) {
            if (source != car) {
                return;
            }
            readings = snapshot;
        }
        notifyListeners(snapshot);
    }

    /**
     * 车辆接口把浮点的值清掉了（最后一个完整用户走了）：位置、GPS 车速跟着清，「车给过车速」也忘掉。
     * 在车辆接口的线程上、清完浮点之后、发布之前叫，下一份快照里就没有这几项。
     */
    void floatsCleared(EcarxSource source) {
        synchronized (lock) {
            if (source != car) {
                return;
            }
            carSpeedSeen = false;
            state = state.edit().position(null, null).speedKmh(null).build();
        }
    }

    /** 交给主线程（{@link #reconcileNow} 的回调）。 */
    void toMain(Runnable r) {
        main.post(r);
    }

    public void addListener(Listener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(Readings snapshot) {
        List<Listener> copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(listeners);
        }
        main.post(() -> {
            for (Listener l : copy) {
                try {
                    l.onReadingsChanged(snapshot);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "listener failed: " + e);
                }
            }
        });
    }


    void noteCarSpeed() {
        carSpeedSeen = true;
    }

    boolean hasCarSpeed() {
        return carSpeedSeen;
    }

    /**
     * 车辆接口的结果（{@link EcarxSource} 连上、连不上、断了）。黑匣子里一次变化一行「车辆信号连接：…」，
     * 连接经过的细节（耗时、方法探测、订阅、异常原文）放在括号里；同样的结果再报一次（每 {@link #RETRY_MS} 再连还是一样）不再写。
     * 连不上的排上下一次再连。页面上的状态行、熄屏判断都跟着换（通知一次监听者）。
     */
    void carReported(EcarxSource source, CarLink link, String detail) {
        String key = link == CarLink.CONNECTED ? link.name() : link.name() + " " + detail;
        String before;
        synchronized (lock) {
            if (source != car) {
                // 停了的、再连时换掉的来源晚到的结果不算
                return;
            }
            before = linkNoted;
            linkNoted = key;
        }
        AppLog.i(TAG, "ecarx " + link + ": " + detail);
        main.removeCallbacks(retry);
        if (link == CarLink.FAILED) {
            main.postDelayed(retry, RETRY_MS);
        }
        if (!key.equals(before)) {
            BlackBox.noteImportant("车辆信号连接：" + (link == CarLink.CONNECTED ? "接上了（" + detail + "）"
                    : link == CarLink.UNAVAILABLE ? "这台车机没有这套接口（" + detail + "），熄屏只看安卓"
                    : (CarLink.CONNECTED.name().equals(before) ? "断了（" : "连不上（") + detail + "），熄屏只看安卓；"
                    + (RETRY_MS / 60_000L) + " 分钟后、安卓亮屏时再试"));
        }
        notifyListeners(readings);
    }

    /** 定位报到：订阅上了什么、没订上什么。只进日志和黑匣子；页面上只显示 {@link #locationLink}。 */
    void locationReported(LocationSource source, String status) {
        synchronized (lock) {
            if (source != location) {
                return;
            }
        }
        AppLog.i(TAG, "location: " + status);
        BlackBox.note("行驶信息来源 location: " + status);
        // 页面上的状态行跟着换
        notifyListeners(readings);
    }

    /** 车辆接口这一路的结果（「系统信息」页的状态行、熄屏判断）。 */
    public enum CarLink {
        /** 还在连（{@code Car.create} 第一次要一秒左右；再连时也是它）：状态行先不写这一项，熄屏判断只看安卓 */
        CONNECTING,
        CONNECTED,
        /** 这台车机没有 ECARX 接口（没有那个类、没有 {@code Car.create}）：不再试 */
        UNAVAILABLE,
        /**
         * 有接口，但连不上（连的时候出错、{@code Car.create} 给了 null、接口里没有能读的、连上那一遍一个值都读不到），
         * 或者连上之后连着两遍对账一个值都读不到（车机服务多半没了）：每 {@link #RETRY_MS}、安卓亮屏时再连
         */
        FAILED
    }

    /** 定位这一路的结果（「系统信息」页的状态行）。 */
    public enum LocationLink {
        ON,
        NO_PERMISSION,
        /** 系统定位关着（没有一个提供者能订阅） */
        OFF
    }

    /** 车辆接口此刻的结果；没人用（核心还没登记）时 null。 */
    public CarLink carLink() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        return c == null ? null : c.link();
    }

    /** 车辆接口此刻连着、读数是活的（熄屏判断：不连着就只看安卓）。 */
    public boolean carLinkConnected() {
        return carLink() == CarLink.CONNECTED;
    }

    /** 车辆接口连接经过的细节（英文，诊断报告用）；没人用时 null。 */
    public String carDetail() {
        EcarxSource c;
        synchronized (lock) {
            c = car;
        }
        return c == null ? null : c.status();
    }

    /** 定位此刻的结果；没有完整用户（定位没开）时 null。 */
    public LocationLink locationLink() {
        LocationSource l;
        synchronized (lock) {
            l = location;
        }
        return l == null ? null : l.link();
    }
}
