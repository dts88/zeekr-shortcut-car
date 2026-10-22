package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.blackbox.ChangeTracker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 车辆信号这一路：车机系统自带的 ECARX 车辆接口（{@code com.ecarx.xui.adaptapi}），全反射，只读。
 *
 * <h3>为什么是它、怎么读（zeekr-shortcut-lab 在 7X 上、在 App Lab 容器里实测）</h3>
 *
 * <p>{@code ecarx.adaptapi.impl.jar} 在 BOOTCLASSPATH 上，任何应用都能 {@code Car.create(context)}，不要任何车辆权限；
 * 安卓标准的 {@code android.car} 在容器里结构性拿不到，极氪自家的 {@code com.zeekr.coreservice} 回「对当前应用不可用」。</p>
 *
 * <p><b>订阅，不轮询</b>（Lab 0.9.0：回调比轮询早 50–165 ms，189 次变化轮询漏 80 次、回调漏 1 次，只订阅的 CPU 和基线分不出来）：</p>
 * <ul>
 *   <li>离散信号（功能号、传感器事件）：{@code registerFunctionValueWatcher(int[], 监听)} 一次挂全部功能号，
 *       {@code registerListener(监听, 类型)} 逐个挂传感器；只认带值的三个回调
 *       {@code onFunctionValueChanged(id, zone, value)} / {@code onSensorEventChanged} / {@code onSensorValueChanged}。
 *       回调在车的 binder 线程上来，转到自己的线程处理。</li>
 *   <li>注册后立刻把每一项读一遍当起点（注册时车不推当前值）；之后每 {@link #RECONCILE_MS} 再读一遍对账，
 *       防车机服务重启后回调断了。对账只有一条节拍：当场要的那一遍（{@link #reconcileNow}：安卓刚亮、有人在时）先撤掉排着的那一次，
 *       读完再排一次。</li>
 *   <li>浮点传感器（车速、转角、深度、温度……）不订阅 —— 变一点就推，订了只是噪声 —— 按 {@link #FLOAT_POLL_MS} 读，
 *       而且只在有完整用户时读（{@link #floats}，{@link TelemetryUsers}）：熄屏判断那一层只要离散信号。</li>
 *   <li>例外（项目所有者 2026-10-21、22）：快读里登记了车速、油门深度、刹车深度（模拟声浪），就在第一次快读时另外挂上这三个的推送
 *       （{@link #pushes}，只这三个），带速率：踏板最快档、车速次快档（{@link #pushRate}；不带速率时车机约 217 ms 才推一次，
 *       快读读到的值还比推送晚约 100 ms）。推来的值就在车的线程上交给 {@link Telemetry#pushed}，不排队、不进普通读数、不发布。
 *       传感器监听注销不掉，挂上就一直在；没人快读时回调里直接丢掉。快读照旧，兜底、看活着没有。</li>
 *   <li><b>传感器监听疑似注销不掉</b>（注销再注册，回调会变成两条三条），所以传感器在一个车辆对象上只注册一次、永远留着，
 *       停了就不理它的回调；功能号的监听每次停都注销。车辆对象建一次一直用（{@code Car.create} 要一秒），
 *       连不上（{@link Telemetry.CarLink#FAILED}）时丢掉，下次再连重建、传感器在新的上面重新挂（{@link #forgetCar}）。</li>
 * </ul>
 *
 * <p>信号表在 {@link Signal}；读数进 {@link Readings}，信息条那份快照由 {@link VehicleStateMapper} 推出来。</p>
 *
 * <p><b>连接的结果</b>（{@link Telemetry.CarLink}）：没有那个类、没有 {@code Car.create} 是这台车机没有（不再试）；
 * 别的连不上（出错、{@code Car.create} 给了 null、接口里没有能读的、连上那一遍一个值都读不到），以及连上之后连着
 * {@link #DEAD_PASSES} 遍对账一个值都读不到（车机服务多半没了），都算连不上：这个来源就此不再读，
 * {@link Telemetry} 过一会儿换一个新的再连。占位值（255）算读到了：车回了话，只是这一项没数据。</p>
 *
 * <p><b>找黑屏信号，只记录</b>（2026-10-12）：{@link DarkScreenProbes} 那几个信号表里没有的号跟着一起订、对账时一起读
 * （它们的可用状态回调也记），连同信号表里的屏幕电源事件、点火、挡位、哨兵、主驾门 / 座椅 / 安全带，每变一次进黑匣子一行（{@link #probeLog}）。
 * 那几个号不进读数、不进信息条、不改任何判断，出了错也只写日志；来源停了就不记。</p>
 */
final class EcarxSource {

    private static final String TAG = "EcarxSource";

    static final String CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car";

    /** 浮点传感器多久读一次。 */
    static final long FLOAT_POLL_MS = 200L;
    /** 离散信号多久对一次账（回调在的时候这一遍几乎读不到变化）。 */
    static final long RECONCILE_MS = 3000L;
    /** 一次变化引起的发布合并在这么长的窗口里。 */
    private static final long PUBLISH_COALESCE_MS = 40L;
    /** 连着这么多遍对账一个值都读不到，就算断了（{@link #stillAnswering}）：约 6 秒，一次偶尔的读失败不算。 */
    static final int DEAD_PASSES = 2;
    /** 两遍对账之间深睡过多久算「睡过」（{@link #emptyPassesAfter}）：两个钟各读一次有一两毫秒的出入，不算。 */
    static final long SLEPT_BETWEEN_MS = 1_000L;
    /** 车辆信息：主驾在哪一边（ICarInfo.INT_INFO_DRIVER_SIDE），读到 0x00100302 = 右、0x00100301 = 左。 */
    private static final int INFO_DRIVER_SIDE = 0x00100300;
    private static final int DRIVER_SIDE_LEFT = 0x00100301;
    private static final int DRIVER_SIDE_RIGHT = 0x00100302;

    /** 车辆对象：建一次一直用；连不上的那一个丢掉（{@link #forgetCar}），下次再连重建。 */
    private static volatile Object cachedCar;
    /** 传感器监听：一个车辆对象上只注册一次、永不注销（注销不掉）；回调转给此刻活着的那个来源。车辆对象丢掉时清（{@link #forgetCar}）。 */
    private static final Object SENSOR_LOCK = new Object();
    private static Object sensorProxy;
    private static final Set<Integer> sensorTypesRegistered = new HashSet<>();
    /**
     * 油门深度另挂的一个监听（试验，2026-10-22：想办法更快拿到新值）：用 {@code RATE_DR}（多半是导航航位推算用的速率），
     * 和主监听分开计数，看它是不是推得更快；推来的值照样用。进程里一个、挂上就一直在（注销不掉），{@link #forgetCar} 时重挂。
     */
    private static Object throttleDrProxy;
    private static boolean throttleDrRegistered;
    private static volatile EcarxSource active;

    private final Telemetry telemetry;
    private final VehicleStateMapper mapper = new VehicleStateMapper();
    private final Map<Signal, Object> values = new EnumMap<>(Signal.class);
    /** 读法 + 号码 + 区域 → 用它的信号（同一个号码可能几条信号都用）。 */
    private final Map<Long, List<Signal>> byKey = new HashMap<>();
    private HandlerThread thread;
    private volatile Handler handler;
    /**
     * 快读自己的线程（项目所有者 2026-10-22：这几个编码轮询再快一些，挑中间拖延迟的地方）：以前和对账挤在一个线程上，
     * 每 3 秒一遍的全量对账要读几十个号，那时快读排不上（实车最长空了 93 ms）；也不再「读完再等一个间隔」，按固定的节拍读。
     */
    private HandlerThread fastThread;
    private volatile Handler fastHandler;
    /** 下一次快读的时刻（{@code uptimeMillis}）。只在快读线程上碰。 */
    private long nextFastAt;
    /** 连接经过的细节：只进日志和黑匣子（{@link Telemetry#carReported}），诊断报告也读它（{@link #status()}）。 */
    private volatile String status = "not started";
    /** 连接的结果：页面上的状态行、熄屏判断看这个。 */
    private volatile Telemetry.CarLink link = Telemetry.CarLink.CONNECTING;
    private volatile boolean stopped;
    /** 连上了、还在读（没断）：回调、对账、浮点只在这时算数。只在自己的线程上改；快读线程也看。 */
    private volatile boolean connected;
    /** 读不读浮点传感器（有完整用户）。构造时给，之后只在自己的线程上碰（{@link #floats}）。 */
    private boolean floatsOn;
    /** 连着几遍对账一个值都没读到（两遍之间车机深睡过的不算连着，{@link #emptyPassesAfter}）。 */
    private int emptyPasses;
    /** 上一遍对账时车机一共深睡过多久（elapsedRealtime − uptimeMillis）：这一遍和它比，涨了就是两遍之间睡过。 */
    private long sleptAtLastPass;
    /** 这个来源用的车辆对象（{@link #cachedCar} 里拿的、或者刚建的）；还没拿到是 null。只在自己的线程上碰。 */
    private Object car;
    /** 等着「当场对一次账」读完的回调（{@link #reconcileNow}）：停的时候还没轮到的也交给主线程，一个不丢。 */
    private final List<Runnable> waiting = new ArrayList<>();
    private long readingsVersion;
    private boolean publishPending;
    private boolean firstRoundReported;
    private boolean driverOnRight = true;
    /** 取勾选用（{@link InfoBar#selection}）。 */
    private volatile Context appContext;

    private Object function;
    private Object sensor;
    private Method getFunctionValue;
    private Method getFunctionValueZoned;
    private Method getSensorEvent;
    private Method getSensorLatestValue;
    private Object functionWatcher;
    private int[] functionIds = new int[0];
    /** 这个来源试过挂推送了（成不成都只试一次）。只在自己的线程上碰。 */
    private boolean pushTried;
    /** 推送挂得怎么样（诊断报告用）：没试过是空的。 */
    private volatile String pushStatus = "";

    /**
     * 找黑屏信号的那几项（{@link DarkScreenProbes}）：上一个值和限量。只在自己的线程上碰。
     * 一次处理（一个回调、一遍对账）里变了的攒在 {@link #probeChanges}，处理完写成一行（{@link #writeProbeChanges}）。
     */
    private final ChangeTracker probeLog =
            new ChangeTracker(DarkScreenProbes.MAX_LINES_PER_WINDOW, DarkScreenProbes.WINDOW_MS);
    private final List<String> probeChanges = new ArrayList<>();
    /** 照原值记的那几项（{@link DarkScreenProbes#RAW_WATCHED}）最近读到的原值：起点那一行照它写，不为起点另读一遍。只在自己的线程上碰。 */
    private final Map<Signal, String> rawProbes = new EnumMap<>(Signal.class);
    /** 起点那一行记过了：之前的（注册后读的第一遍）是起点，不算变化。 */
    private boolean probesStarted;
    /** 记录出过错、日志写过了（只写一次，{@link #probeFailed}）。 */
    private boolean probeFailureLogged;

    /**
     * 快读的读法（{@link FastReads}）：读哪几个浮点、多久一次；没人快读是 null。还没起线程时由 {@link Telemetry} 直接写，
     * 之后只在自己的线程上改（{@link #setFast}）。
     */
    private volatile FastReads.Plan fastPlan;
    private long fastVersion;

    private final Runnable floatPoll = this::pollFloats;
    private final Runnable fastPoll = this::pollFast;
    private final Runnable reconcile = this::reconcileDiscrete;
    /** 合并窗口到点的那一次发布；当场对账（{@link #reconcileNow}）要立刻发布，撤的是它。 */
    private final Runnable publish = this::publishNow;
    /** 显示里有保持的信号到点再算的那一次（{@link #apply}）：和 {@link #publish} 分开，立刻发布时不把它撤掉。 */
    private final Runnable holdPublish = this::publishNow;

    /** 这一遍读是谁触发的：黑匣子那一行据此标出来（{@link #writeProbeChanges}）。 */
    private enum Pass {
        /** 一个回调 */
        CALLBACK,
        /**
         * 每 {@link #RECONCILE_MS} 的对账；有人在时提前读的那一遍（{@link #reconcileNow}，不是刚醒）也是它 ——
         * 节拍一直在走，读到的变化同样最多早一个对账间隔
         */
        RECONCILE,
        /** 安卓刚亮时要的那一遍（{@link #reconcileNow}，刚醒）：车机睡着时变的，什么时候变的不知道 */
        WAKE
    }

    /**
     * @param floats 读不读浮点传感器（有完整用户）；之后由 {@link #floats} 改
     */
    EcarxSource(Telemetry telemetry, boolean floats) {
        this.telemetry = telemetry;
        this.floatsOn = floats;
        // 再连时换的新来源接着旧的版本号往上数（快照的版本号只往上走）
        this.readingsVersion = telemetry.readings().version;
        for (Signal s : Signal.values()) {
            long key = key(s.kind, s.id, s.zone);
            List<Signal> list = byKey.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byKey.put(key, list);
            }
            list.add(s);
        }
    }

    private static long key(Signal.Kind kind, int id, int zone) {
        // 读法 2 位 | 号码 32 位 | 区域 30 位（后备箱的区域是 0x20000000）
        return ((long) kind.ordinal() << 62) | ((id & 0xFFFFFFFFL) << 30) | (zone & 0x3FFFFFFFL);
    }

    void start(Context context) {
        final Context app = context.getApplicationContext();
        appContext = app;
        thread = new HandlerThread("Telemetry-Ecarx");
        thread.start();
        fastThread = new HandlerThread("Telemetry-Ecarx-Fast", android.os.Process.THREAD_PRIORITY_DISPLAY);
        fastThread.start();
        fastHandler = new Handler(fastThread.getLooper());
        handler = new Handler(thread.getLooper());
        handler.post(() -> connect(app));
    }

    void stop() {
        stopped = true;
        if (active == this) {
            active = null;
        }
        Handler h = handler;
        HandlerThread t = thread;
        Handler fh = fastHandler;
        HandlerThread ft = fastThread;
        handler = null;
        thread = null;
        fastHandler = null;
        fastThread = null;
        if (fh != null) {
            fh.removeCallbacksAndMessages(null);
        }
        if (ft != null) {
            ft.quitSafely();
        }
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            // 功能号的监听每次都注销；传感器的留着（注销不掉，见类说明）
            final Object watcher = functionWatcher;
            functionWatcher = null;
            if (watcher != null) {
                h.post(() -> unregisterFunctions(watcher));
            }
        }
        if (t != null) {
            t.quitSafely();
        }
        status = "stopped";
        // 还没轮到的「当场对一次账」：不读了，回调照样交给主线程（叫的人在等它）
        List<Runnable> left;
        synchronized (waiting) {
            left = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (Runnable r : left) {
            telemetry.toMain(r);
        }
    }

    Telemetry.CarLink link() {
        return link;
    }

    /** 连接经过的细节（英文）：日志、黑匣子、诊断报告用。 */
    String status() {
        return status;
    }

    /**
     * 读不读浮点传感器（{@link Telemetry} 在第一个完整用户来、最后一个走时叫）。在自己的线程上改：
     * 开 —— 连着的话马上读一遍，之后按节拍读；关 —— 停节拍、表里的浮点值清掉、位置和 GPS 车速跟着清，再发布一次。
     * 不发空快照：离散的读数照旧。
     */
    void floats(boolean on) {
        Handler h = handler;
        if (h != null && !stopped) {
            h.post(() -> setFloats(on));
        }
    }

    private void setFloats(boolean on) {
        Handler h = handler;
        if (stopped || h == null || floatsOn == on) {
            return;
        }
        floatsOn = on;
        h.removeCallbacks(floatPoll);
        if (on) {
            if (connected) {
                pollFloats();
            }
            return;
        }
        values.keySet().removeIf(Signal::isFloat);
        telemetry.floatsCleared(this);
        schedulePublish(0);
    }

    /**
     * 快读的读法变了（{@link Telemetry#acquireFast} / {@link Telemetry#releaseFast}；新来源起之前也给一次）。
     * 还没起线程的直接记下，连上后照它读；起了的在自己的线程上换：停旧节拍，有读法而且连着就马上读一遍、按新间隔接着读，
     * 没人快读了就发一份空的。
     */
    void fast(FastReads.Plan plan) {
        Handler h = fastHandler;
        if (h == null) {
            fastPlan = plan;
            return;
        }
        if (!stopped) {
            h.post(() -> setFast(plan));
        }
    }

    /** 快读线程上：换读法。停旧节拍；有读法而且连着就马上读一遍、按新间隔接着读；没人快读了就发一份空的。 */
    private void setFast(FastReads.Plan plan) {
        Handler h = fastHandler;
        if (stopped || h == null) {
            return;
        }
        fastPlan = plan;
        h.removeCallbacks(fastPoll);
        if (plan == null) {
            telemetry.publishFast(this, FastReadings.empty());
            return;
        }
        if (connected) {
            nextFastAt = SystemClock.uptimeMillis();
            pollFast();
        }
    }

    /** 快读一遍：只读读法里那几个浮点，解出来放进一份新快照交给 {@link Telemetry}（不进读数表、不发布），再排下一次。 */
    private void pollFast() {
        FastReads.Plan plan = fastPlan;
        if (stopped || !connected || plan == null) {
            return;
        }
        if (!pushTried) {
            listenForPushes(plan);
        }
        Map<Signal, Float> read = new EnumMap<>(Signal.class);
        for (Signal s : plan.signals) {
            Object decoded = s.decode(read(s));
            if (decoded instanceof Float) {
                read.put(s, (Float) decoded);
            }
        }
        telemetry.publishFast(this, new FastReadings(read, SystemClock.elapsedRealtime(), ++fastVersion));
        Handler h = fastHandler;
        if (h != null) {
            // 固定节拍：下一次按上一次排的时刻往后数，不是读完再等一整个间隔；落后太多就从现在接着排
            long now = SystemClock.uptimeMillis();
            nextFastAt = Math.max(nextFastAt + plan.intervalMs, now + 1);
            h.postAtTime(fastPoll, nextFastAt);
        }
    }

    /**
     * 当场对一次账（{@link Telemetry#reconcileNow}）：交给自己的线程，读完把 {@code onDone} 交给主线程。
     *
     * @param afterWake 安卓刚亮（车机睡过，黑匣子那一行标「醒来时读到」）；不是的（有人在、提前读）和对账一样标
     * @return 交出去了；停了的是 false，叫的人自己把 {@code onDone} 交给主线程
     */
    boolean reconcileNow(boolean afterWake, Runnable onDone) {
        Handler h = handler;
        if (h == null || stopped) {
            return false;
        }
        synchronized (waiting) {
            waiting.add(onDone);
        }
        final Pass pass = afterWake ? Pass.WAKE : Pass.RECONCILE;
        if (!h.post(() -> {
            readNow(pass);
            deliver(onDone);
        })) {
            // 线程刚好退了：不读了，回调照样交出去
            deliver(onDone);
        }
        return true;
    }

    /** 回调交给主线程，一次：{@link #stop} 那边先交了的就不再交。 */
    private void deliver(Runnable onDone) {
        boolean mine;
        synchronized (waiting) {
            mine = waiting.remove(onDone);
        }
        if (mine) {
            telemetry.toMain(onDone);
        }
    }

    /**
     * 当场要的那一遍：撤掉排着的对账，离散信号和那几个号读一遍，有变化立刻发布（撤掉合并窗口那一次，当场发），
     * 再排下一次对账 —— 对账的节拍始终只有一条。发布先把监听者那一批交给主线程，回调（{@link #deliver}）排在它后面。
     */
    private void readNow(Pass pass) {
        Handler h = handler;
        if (h == null || stopped || !connected) {
            return;
        }
        h.removeCallbacks(reconcile);
        boolean alive = stillAnswering(readPass(pass));
        if (publishPending) {
            h.removeCallbacks(publish);
            publishNow();
        }
        if (alive) {
            h.postDelayed(reconcile, RECONCILE_MS);
        }
    }

    // ================================================================= 连接（在自己的线程上）

    private void connect(Context context) {
        long start = SystemClock.elapsedRealtime();
        try {
            Class<?> carClass = Class.forName(CAR_CLASS);
            Object car = cachedCar;
            if (car == null) {
                Method create = match(carClass.getMethods(), "create", Context.class);
                if (create == null) {
                    status = "no Car.create(Context)";
                    report(Telemetry.CarLink.UNAVAILABLE);
                    return;
                }
                car = create.invoke(null, context);
                if (car == null) {
                    // 开机时车机服务还没起来也会这样：算连不上，过一会儿再试（不当成这台车没有）
                    status = "Car.create returned null";
                    report(Telemetry.CarLink.FAILED);
                    return;
                }
                cachedCar = car;
            }
            this.car = car;
            function = call(car, "getICarFunction");
            sensor = call(car, "getSensorManager");
            if (function != null) {
                getFunctionValue = findMethod(function, "getFunctionValue", int.class);
                getFunctionValueZoned = findMethod(function, "getFunctionValue", int.class, int.class);
            }
            if (sensor != null) {
                getSensorEvent = findMethod(sensor, "getSensorEvent", int.class);
                getSensorLatestValue = findMethod(sensor, "getSensorLatestValue", int.class);
            }
            if (getFunctionValue == null && getSensorEvent == null) {
                // 同上：开机时可能只是还没好，算连不上、过一会儿再试
                status = "no readable managers (function=" + (function != null) + ", sensor=" + (sensor != null) + ")";
                report(Telemetry.CarLink.FAILED);
                return;
            }
            String side = readDriverSide(car);
            String subscribed = subscribe();
            if (stopped) {
                unregisterFunctions(functionWatcher);
                functionWatcher = null;
                return;
            }
            active = this;
            connected = true;
            if (!readAll()) {
                // 方法都找到了，读却一个值都回不来（车机服务没起来、或者已经没了）：不算连上，过一会儿换新的再连。
                // 起点、第一轮读数那两行也不写 —— 不然每次再连都多两行全是「?」的。细节不带耗时：
                // 每次再连都一样的结果，黑匣子只记一次（Telemetry.carReported 按细节比）
                connected = false;
                status = "managers found but no read answered";
                report(Telemetry.CarLink.FAILED);
                return;
            }
            sleptAtLastPass = sleptMs();
            status = String.format(Locale.US,
                    "connected in %d ms; function=%s zoned=%s sensorEvent=%s sensorValue=%s; driver %s; %s",
                    SystemClock.elapsedRealtime() - start, getFunctionValue != null, getFunctionValueZoned != null,
                    getSensorEvent != null, getSensorLatestValue != null, side, subscribed);
            report(Telemetry.CarLink.CONNECTED);
            Handler h = handler;
            if (h != null && !stopped) {
                if (floatsOn) {
                    h.postDelayed(floatPoll, FLOAT_POLL_MS);
                }
                Handler fh = fastHandler;
                if (fastPlan != null && fh != null) {
                    fh.post(() -> {
                        nextFastAt = SystemClock.uptimeMillis();
                        pollFast();
                    });
                }
                h.postDelayed(reconcile, RECONCILE_MS);
            }
        } catch (ClassNotFoundException e) {
            status = "no " + CAR_CLASS + " on this head unit";
            report(Telemetry.CarLink.UNAVAILABLE);
        } catch (Throwable t) {
            status = "connect failed: " + describe(t);
            report(Telemetry.CarLink.FAILED);
        }
    }

    /**
     * 结果给页面和熄屏判断（{@link #link}），经过（{@link #status}，含异常原文）跟着进日志和黑匣子（{@link Telemetry#carReported}）。
     * 连不上的，手上的车辆对象不再用（{@link #forgetCar}）。
     */
    private void report(Telemetry.CarLink result) {
        link = result;
        if (result == Telemetry.CarLink.FAILED) {
            forgetCar(car);
        }
        telemetry.carReported(this, result, status);
    }

    /**
     * 这个车辆对象不再用：拿着它连不上（接口拿不到、找到了却一个值都读不到、出错），或者连上之后读的路断了（{@link #lose}）——
     * 车机服务多半没了或者重启过，旧的对象连着的是死的那一个，拿它再连多少次都一样（审查 rev3 B4：以前整个进程只建一次，
     * 车机服务重启一次，这一趟停车就一直只看安卓）。下一次再连重新 {@code Car.create}；传感器的监听也在新的那一个上重新挂
     * （挂在旧的上的回调不会再来；万一旧的还活着、回调来两份，{@link #apply} 比对后只算一次变化）。
     */
    static void forgetCar(Object car) {
        if (car == null) {
            return;
        }
        synchronized (SENSOR_LOCK) {
            if (cachedCar != car) {
                return;
            }
            cachedCar = null;
            sensorTypesRegistered.clear();
            throttleDrRegistered = false;
        }
        AppLog.i(TAG, "car object dropped: the next connect runs Car.create again");
    }

    /** 主驾在哪一边：车辆信息 INT_INFO_DRIVER_SIDE。读不到按右舵（这台车）。 */
    private String readDriverSide(Object car) {
        try {
            Object info = call(car, "getCarInfoManager");
            Method get = info == null ? null : findMethod(info, "getCarInfoInt", int.class);
            if (get == null) {
                return "right (no car info)";
            }
            Object v = get.invoke(info, INFO_DRIVER_SIDE);
            int code = v instanceof Number ? ((Number) v).intValue() : 0;
            if (code == DRIVER_SIDE_RIGHT) {
                driverOnRight = true;
                return "right";
            }
            if (code == DRIVER_SIDE_LEFT) {
                driverOnRight = false;
                return "left";
            }
            return "right (info read 0x" + Integer.toHexString(code) + ")";
        } catch (Throwable t) {
            return "right (info failed: " + describe(t) + ")";
        }
    }

    // ================================================================= 订阅

    /** 挂监听；结果汇成一句话进状态。 */
    private String subscribe() {
        StringBuilder out = new StringBuilder();
        if (function != null) {
            out.append(subscribeFunctions());
        }
        if (sensor != null) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(subscribeSensors());
        }
        return out.toString();
    }

    /** 功能号：一次挂全部；这台车不认数组就逐个挂。 */
    private String subscribeFunctions() {
        Method byArray = findMethodWithInterface(function, "registerFunctionValueWatcher", int[].class);
        Method byOne = findMethodWithInterface(function, "registerFunctionValueWatcher", int.class);
        Method any = byArray != null ? byArray : byOne;
        if (any == null) {
            return "functions: no registerFunctionValueWatcher";
        }
        Class<?> type = any.getParameterTypes()[1];
        Object proxy = Proxy.newProxyInstance(EcarxSource.class.getClassLoader(), new Class<?>[]{type}, new FunctionCallback());
        Set<Integer> ids = new LinkedHashSet<>();
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.FUNCTION || s.kind == Signal.Kind.FUNCTION_ZONE) {
                ids.add(s.id);
            }
        }
        // 2026-10-12 只加记录：找黑屏信号的那几个号一起订上；它们不在信号表里，回调到了只进黑匣子（onFunctionValue）
        for (int id : DarkScreenProbes.IDS) {
            ids.add(id);
        }
        functionIds = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            functionIds[i++] = id;
        }
        if (byArray != null && invokeOk(byArray, function, functionIds, proxy)) {
            functionWatcher = proxy;
            return "functions: watching " + functionIds.length;
        }
        if (byOne != null) {
            int done = 0;
            for (int id : functionIds) {
                if (invokeOk(byOne, function, id, proxy)) {
                    done++;
                }
            }
            if (done > 0) {
                functionWatcher = proxy;
            }
            return "functions: per-id " + done + "/" + functionIds.length;
        }
        return "functions: refused";
    }

    /** 能推送、快读里登记了就另外挂上推送的浮点：车速、油门深度、刹车深度（模拟声浪要的那几个，别的不订）。 */
    static boolean pushes(Signal s) {
        return s == Signal.SPEED || s == Signal.THROTTLE_DEPTH || s == Signal.BRAKE_DEPTH;
    }

    /** 推来的传感器类型 → 信号；不是那三个是 null。 */
    private static Signal pushedSignal(int type) {
        if (type == Signal.THROTTLE_DEPTH.id) {
            return Signal.THROTTLE_DEPTH;
        }
        if (type == Signal.BRAKE_DEPTH.id) {
            return Signal.BRAKE_DEPTH;
        }
        return type == Signal.SPEED.id ? Signal.SPEED : null;
    }

    /**
     * 订阅的速率（{@code ISensor.RATE_*}，0 最快 … 5 最慢，Lab findings「传感器组」那一段）：油门、刹车最快档（跟脚），车速次快档（车速变得慢，
     * 推太勤只是噪声）。接口上读得到常量就用它的值，读不到按 0、1。
     */
    private int pushRate(Signal s) {
        return s == Signal.SPEED ? rateConstant("RATE_FAST", 1) : rateConstant("RATE_FASTEST", 0);
    }

    private int rateConstant(String name, int fallback) {
        for (Class<?> type : publicInterfaces(sensor)) {
            try {
                java.lang.reflect.Field f = type.getField(name);
                if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                    return f.getInt(null);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 这个接口上没有这个常量
            }
        }
        return fallback;
    }

    /**
     * 快读里有能推送的浮点：挂上它的传感器监听。一个车辆对象上只挂一次、永远留着（注销不掉）；挂不上不再试（这个来源）。
     * 订阅传感器事件时（{@link #subscribeSensors}）还没有快读，所以放在第一次快读这里。
     */
    private void listenForPushes(FastReads.Plan plan) {
        pushTried = true;
        StringBuilder status = new StringBuilder();
        for (Signal s : plan.signals) {
            if (!pushes(s)) {
                continue;
            }
            String result;
            synchronized (SENSOR_LOCK) {
                if (sensorTypesRegistered.contains(s.id)) {
                    result = "listening (registered before)";
                } else if (sensor == null || sensorProxy == null) {
                    result = "no sensor manager";
                } else {
                    // 带速率的那个优先；没有再用不带速率的（车机按它的默认速率推）
                    Method withRate = findMethodWithInterfaceThen(sensor, "registerListener", int.class, int.class);
                    Method plain = findMethodWithInterfaceFirst(sensor, "registerListener", int.class);
                    int rate = pushRate(s);
                    if (withRate != null && invokeOk(withRate, sensor, sensorProxy, s.id, rate)) {
                        sensorTypesRegistered.add(s.id);
                        result = "listening at rate " + rate;
                    } else if (plain != null && invokeOk(plain, sensor, sensorProxy, s.id)) {
                        sensorTypesRegistered.add(s.id);
                        result = "listening at the default rate" + (withRate == null ? " (no rate parameter)" : " (rate refused)");
                    } else {
                        result = withRate == null && plain == null ? "no registerListener" : "refused";
                    }
                }
            }
            status.append(status.length() == 0 ? "" : "; ").append(s.name()).append(' ').append(result);
        }
        if (plan.signals.contains(Signal.THROTTLE_DEPTH)) {
            status.append("; ").append(listenThrottleDr());
        }
        status.append("; rates ").append(rateConstants());
        pushStatus = status.toString();
        AppLog.i(TAG, "push: " + pushStatus);
    }

    /** 油门深度再挂一个 {@code RATE_DR} 的监听（见 {@link #throttleDrProxy}）；返回结果（诊断）。 */
    private String listenThrottleDr() {
        int dr = rateConstant("RATE_DR", -1);
        if (dr < 0) {
            return "THROTTLE_DEPTH at RATE_DR: no such rate";
        }
        synchronized (SENSOR_LOCK) {
            if (throttleDrRegistered) {
                return "THROTTLE_DEPTH also at RATE_DR " + dr + " (registered before)";
            }
            Method withRate = sensor == null ? null : findMethodWithInterfaceThen(sensor, "registerListener", int.class, int.class);
            if (withRate == null) {
                return "THROTTLE_DEPTH at RATE_DR: no rate parameter";
            }
            if (throttleDrProxy == null) {
                throttleDrProxy = Proxy.newProxyInstance(EcarxSource.class.getClassLoader(),
                        new Class<?>[]{withRate.getParameterTypes()[0]}, new ThrottleDrCallback());
            }
            if (invokeOk(withRate, sensor, throttleDrProxy, Signal.THROTTLE_DEPTH.id, dr)) {
                throttleDrRegistered = true;
                return "THROTTLE_DEPTH also at RATE_DR " + dr;
            }
            return "THROTTLE_DEPTH at RATE_DR " + dr + " refused";
        }
    }

    /** 接口上所有的速率常量（{@code RATE_*}，诊断用：还有哪几档能试）。 */
    private String rateConstants() {
        StringBuilder sb = new StringBuilder();
        for (Class<?> type : publicInterfaces(sensor)) {
            for (java.lang.reflect.Field f : type.getFields()) {
                if (f.getName().startsWith("RATE_") && f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                    try {
                        sb.append(sb.length() == 0 ? "" : " ").append(f.getName()).append('=').append(f.getInt(null));
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // 读不到就不列
                    }
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** 车的线程上：{@code RATE_DR} 那个监听推来的油门深度。 */
    private void onThrottleDrPush(Object raw, long atMs) {
        if (stopped) {
            return;
        }
        Object decoded = Signal.THROTTLE_DEPTH.decode(raw);
        if (decoded instanceof Float) {
            telemetry.publishThrottleDrPush(this, (Float) decoded, atMs);
        }
    }

    /** 车的线程上：推来的快读浮点直接交给 {@link Telemetry}（它只收此刻的来源、有人快读它的）。 */
    private void onPush(Signal s, Object raw, long atMs) {
        if (stopped) {
            return;
        }
        Object decoded = s.decode(raw);
        if (decoded instanceof Float) {
            telemetry.publishPush(this, s, (Float) decoded, atMs);
        }
    }

    /** 推送挂得怎么样（诊断报告用）。 */
    String pushStatus() {
        return pushStatus;
    }

    /** 传感器事件：进程里每个类型只注册一次。 */
    private String subscribeSensors() {
        synchronized (SENSOR_LOCK) {
            Method register = findMethodWithInterfaceFirst(sensor, "registerListener", int.class);
            if (register == null) {
                return "sensors: no registerListener";
            }
            Class<?> type = register.getParameterTypes()[0];
            if (sensorProxy == null) {
                sensorProxy = Proxy.newProxyInstance(EcarxSource.class.getClassLoader(), new Class<?>[]{type}, new SensorCallback());
            }
            int added = 0;
            int refused = 0;
            for (Signal s : Signal.values()) {
                if (s.kind != Signal.Kind.SENSOR_EVENT || sensorTypesRegistered.contains(s.id)) {
                    continue;
                }
                if (invokeOk(register, sensor, sensorProxy, s.id)) {
                    sensorTypesRegistered.add(s.id);
                    added++;
                } else {
                    refused++;
                }
            }
            String line = "sensors: " + sensorTypesRegistered.size() + " listening";
            if (added > 0 || refused > 0) {
                line += " (+" + added + (refused > 0 ? ", " + refused + " refused" : "") + ")";
            }
            return line;
        }
    }

    private void unregisterFunctions(Object watcher) {
        if (watcher == null || function == null) {
            return;
        }
        try {
            Method one = findMethodWithInterface(function, "unregisterFunctionValueWatcher");
            if (one != null) {
                one.invoke(function, watcher);
                return;
            }
            Method withIds = findMethodWithInterface(function, "unregisterFunctionValueWatcher", int[].class);
            if (withIds != null) {
                withIds.invoke(function, functionIds, watcher);
                return;
            }
            Method perId = findMethodWithInterface(function, "unregisterFunctionValueWatcher", int.class);
            if (perId != null) {
                for (int id : functionIds) {
                    perId.invoke(function, id, watcher);
                }
            }
        } catch (Throwable e) {
            AppLog.w(TAG, "unregisterFunctionValueWatcher failed: " + describe(e));
        }
    }

    /** 功能号的回调：只认带值的 onFunctionValueChanged(id, zone, value)。 */
    private final class FunctionCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("onFunctionValueChanged".equals(method.getName()) && args != null && args.length == 3
                    && args[0] instanceof Integer && args[1] instanceof Integer && args[2] instanceof Number) {
                final int id = (Integer) args[0];
                final int zone = (Integer) args[1];
                final Object value = args[2];
                Handler h = handler;
                if (h != null && !stopped) {
                    h.post(() -> onFunctionValue(id, zone, value));
                }
                return null;
            }
            // 2026-10-12 只加记录：找黑屏信号那几个号的「可用状态」也记（Lab §2.39：熄屏 / 上下电前后屏幕电源事件 0 → 1 → 0，
            // 同一刻电源开关的可用状态 active ↔ notactive）。别的号的不管；返回值照旧（下面的 proxyDefault）
            if ("onSupportedFunctionStatusChanged".equals(method.getName()) && args != null && args.length >= 2
                    && args[0] instanceof Integer && DarkScreenProbes.indexOf((Integer) args[0]) >= 0) {
                final int id = (Integer) args[0];
                final int zone = args.length >= 3 && args[1] instanceof Integer ? (Integer) args[1] : 0;
                final Object status = args[args.length - 1];
                Handler h = handler;
                if (h != null && !stopped) {
                    h.post(() -> onFunctionStatus(id, zone, status));
                }
            }
            return proxyDefault(proxy, method, args, "EcarxSource.FunctionCallback");
        }
    }

    /** 油门深度 {@code RATE_DR} 那个监听的回调：转给此刻活着的来源，就在车的线程上。 */
    private static final class ThrottleDrCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            EcarxSource target = active;
            if (target != null && "onSensorValueChanged".equals(method.getName()) && args != null && args.length == 2
                    && args[0] instanceof Integer && (Integer) args[0] == Signal.THROTTLE_DEPTH.id && args[1] instanceof Number) {
                target.onThrottleDrPush(args[1], SystemClock.elapsedRealtime());
                return null;
            }
            return proxyDefault(proxy, method, args, "EcarxSource.ThrottleDrCallback");
        }
    }

    /** 传感器的回调（进程级的一个代理）：转给此刻活着的来源。 */
    private static final class SensorCallback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            EcarxSource target = active;
            if (target != null && args != null && args.length == 2 && args[0] instanceof Integer
                    && args[1] instanceof Number) {
                String name = method.getName();
                Signal.Kind kind = "onSensorEventChanged".equals(name) ? Signal.Kind.SENSOR_EVENT
                        : "onSensorValueChanged".equals(name) ? Signal.Kind.SENSOR_VALUE : null;
                Signal pushed = kind == Signal.Kind.SENSOR_VALUE ? pushedSignal((Integer) args[0]) : null;
                if (pushed != null) {
                    // 推来的车速、油门、刹车：要的就是快 —— 就在这个线程上交出去，不排队、不进普通读数
                    target.onPush(pushed, args[1], SystemClock.elapsedRealtime());
                    return null;
                }
                if (kind != null) {
                    final int type = (Integer) args[0];
                    final Object value = args[1];
                    Handler h = target.handler;
                    if (h != null && !target.stopped) {
                        h.post(() -> target.onSensor(kind, type, value));
                    }
                    return null;
                }
            }
            return proxyDefault(proxy, method, args, "EcarxSource.SensorCallback");
        }
    }

    /** 代理上其余的方法：Object 的三个照常，别的回个零值。 */
    private static Object proxyDefault(Object proxy, Method method, Object[] args, String label) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return label;
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        Class<?> r = method.getReturnType();
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class || r == short.class || r == byte.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == char.class) {
            return '\0';
        }
        return null;
    }

    // ================================================================= 读数进表（都在自己的线程上）

    private void onFunctionValue(int id, int zone, Object value) {
        if (!connected) {
            // 断了（连着几遍读不到）：这个来源不再算数，等 Telemetry 换新的
            return;
        }
        // 不带区域的信号：回调里的 zone 不管是什么都收；带区域的按区域对
        List<Signal> global = byKey.get(key(Signal.Kind.FUNCTION, id, 0));
        if (global != null) {
            for (Signal s : global) {
                apply(s, value);
            }
        }
        List<Signal> zoned = byKey.get(key(Signal.Kind.FUNCTION_ZONE, id, zone));
        if (zoned != null) {
            for (Signal s : zoned) {
                apply(s, value);
            }
        }
        probeFunction(id, zone, value);
        writeProbeChanges(Pass.CALLBACK);
    }

    private void onSensor(Signal.Kind kind, int type, Object value) {
        if (!connected) {
            return;
        }
        List<Signal> list = byKey.get(key(kind, type, 0));
        if (list != null) {
            for (Signal s : list) {
                apply(s, value);
            }
        }
        writeProbeChanges(Pass.CALLBACK);
    }

    /** 归一、比对、有变化才记；发布合并在一个小窗口里。没有完整用户时浮点的一概不收（回调来的也不收）。 */
    private void apply(Signal s, Object raw) {
        if (s.isFloat() && !floatsOn) {
            return;
        }
        probeRaw(s, raw);
        Object decoded = s.decode(raw);
        Object before = values.get(s);
        boolean changed = decoded == null ? before != null : !decoded.equals(before);
        if (!changed) {
            return;
        }
        if (decoded == null) {
            values.remove(s);
        } else {
            values.put(s, decoded);
        }
        // 上一次真变了是什么时候（开座舱那一行、相机步骤统计读：点火、主驾门、屏幕电源事件多久以前变过，2.11.4）
        SignalAges.changed(s, before, decoded, SystemClock.elapsedRealtime());
        probeSignal(s, decoded);
        schedulePublish(0);
        long hold = VehicleStateMapper.republishAfterMs(s);
        if (hold > 0) {
            // 显示里有保持的信号（转向灯闪烁、闪远光）到点要再算一次，否则要等下一次变化才灭。
            // 映射看到这次变化是在合并发布之后（最多晚 PUBLISH_COALESCE_MS），重算也跟着往后让这么多
            schedulePublish(hold + PUBLISH_COALESCE_MS + 50);
        }
    }

    /** 勾选或开发者模式变了：读数不变也重新映射一次，信息条那份快照按新的勾选滤。 */
    void republish() {
        Handler h = handler;
        if (h != null && !stopped) {
            h.post(() -> schedulePublish(0));
        }
    }

    /** 排一次发布。没连着（还在连、连不上、断了）的来源不发布：它没有读数，断的那一刻 {@link #lose} 自己发最后一份。 */
    private void schedulePublish(long delayMs) {
        Handler h = handler;
        if (h == null || stopped || !connected) {
            return;
        }
        if (delayMs > 0) {
            h.postDelayed(holdPublish, delayMs);
            return;
        }
        if (!publishPending) {
            publishPending = true;
            h.postDelayed(publish, PUBLISH_COALESCE_MS);
        }
    }

    private void publishNow() {
        publishPending = false;
        if (stopped) {
            return;
        }
        final Readings snapshot = new Readings(values, ++readingsVersion);
        final long now = SystemClock.uptimeMillis();
        if (snapshot.number(Signal.SPEED) != null) {
            telemetry.noteCarSpeed();
        }
        // 不能用的信号先滤掉再映射，信息条上不出现猜的东西；开发者勾了的没验证信号才放进来（InfoBar）
        Context app = appContext;
        final Readings forBar = app == null ? snapshot.usableOnly()
                : snapshot.usableOr(InfoBar.selection(app));
        telemetry.edit(this, b -> mapper.apply(b, forBar, now, driverOnRight));
        telemetry.publishReadings(this, snapshot);
        if (!firstRoundReported) {
            firstRoundReported = true;
            com.kooo.evcam.blackbox.BlackBox.note("行驶信息 ecarx 第一轮读数：" + snapshot.describe());
        }
    }

    /**
     * 每一项读一遍：起点（浮点的只在读浮点时读）。
     *
     * @return 读到了至少一个值（占位值也算）；一个都没读到时不发布、不记起点
     */
    private boolean readAll() {
        int answered = 0;
        for (Signal s : Signal.values()) {
            if (!s.isFloat() || floatsOn) {
                Object raw = read(s);
                if (raw != null) {
                    answered++;
                }
                apply(s, raw);
            }
        }
        if (answered == 0) {
            return false;
        }
        schedulePublish(0);
        startProbes();
        return true;
    }

    /** 每 {@link #RECONCILE_MS} 一次的对账；断了就不再排。 */
    private void reconcileDiscrete() {
        if (stopped || !connected) {
            return;
        }
        if (!stillAnswering(readPass(Pass.RECONCILE))) {
            return;
        }
        Handler h = handler;
        if (h != null) {
            h.postDelayed(reconcile, RECONCILE_MS);
        }
    }

    /**
     * 离散信号和那几个号直接读一遍（对账、当场要的那一遍都是这一遍），变了的照常记；那一行黑匣子按 {@code pass} 标出来。
     *
     * @return 读到了至少一个值（占位值 255 也算读到：车回话了）；一个都没读到说明读的路断了
     */
    private boolean readPass(Pass pass) {
        int answered = 0;
        for (Signal s : Signal.values()) {
            if (!s.isFloat()) {
                Object raw = read(s);
                if (raw != null) {
                    answered++;
                }
                apply(s, raw);
            }
        }
        answered += reconcileProbes(pass);
        return answered > 0;
    }

    /**
     * 这一遍读到了没有：读到了清零；连着 {@link #DEAD_PASSES} 遍都没读到算断了（{@link #lose}）。两遍之间车机深睡过的不算连着
     * （{@link #emptyPassesAfter}）。
     */
    private boolean stillAnswering(boolean answered) {
        long slept = sleptMs();
        boolean sleptBetween = slept - sleptAtLastPass >= SLEPT_BETWEEN_MS;
        sleptAtLastPass = slept;
        emptyPasses = emptyPassesAfter(emptyPasses, answered, sleptBetween);
        if (emptyPasses < DEAD_PASSES) {
            return true;
        }
        lose();
        return false;
    }

    /**
     * 这一遍之后「连着几遍一个值都没读到」：读到了清零；没读到加一 —— 上一遍和这一遍之间车机深睡过的，从这一遍重新数：
     * 车机下电时那一遍读不到、醒来头一遍也读不到（车机服务刚起来），两遍隔着一觉，不是读的路断了；以前它俩凑成「连着两遍」，
     * 醒来车辆接口就算断了、只看安卓（审查 rev3 F1）。纯函数，测试直接调。
     *
     * @param sleptBetween 上一遍和这一遍之间车机深睡过（{@link #SLEPT_BETWEEN_MS} 以上）
     */
    static int emptyPassesAfter(int before, boolean answered, boolean sleptBetween) {
        if (answered) {
            return 0;
        }
        return (sleptBetween ? 0 : before) + 1;
    }

    /** 开机以来车机一共深睡过多久（毫秒）。 */
    private static long sleptMs() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    /**
     * 断了：连着几遍一个值都读不到，车机服务多半没了（重启后回调也断了）。这个来源不再读：停节拍、不收回调，
     * 剩下的值清掉、发布（读数全是「不知道」），报连不上 —— {@link Telemetry} 过一会儿换一个新来源重新连、重新订。
     */
    private void lose() {
        connected = false;
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(reconcile);
            h.removeCallbacks(floatPoll);
            h.removeCallbacks(publish);
            h.removeCallbacks(holdPublish);
        }
        Handler fh = fastHandler;
        if (fh != null) {
            fh.removeCallbacks(fastPoll);
        }
        values.clear();
        telemetry.publishFast(this, FastReadings.empty());
        publishNow();
        status = "lost: " + DEAD_PASSES + " read passes in a row returned nothing";
        report(Telemetry.CarLink.FAILED);
    }

    private void pollFloats() {
        if (stopped || !connected || !floatsOn) {
            return;
        }
        for (Signal s : Signal.values()) {
            if (s.isFloat()) {
                apply(s, read(s));
            }
        }
        Handler h = handler;
        if (h != null) {
            h.postDelayed(floatPoll, FLOAT_POLL_MS);
        }
    }

    /** 直接读一次；读不到返回 null。 */
    private Object read(Signal s) {
        try {
            switch (s.kind) {
                case FUNCTION:
                    return getFunctionValue == null ? null : getFunctionValue.invoke(function, s.id);
                case FUNCTION_ZONE:
                    return getFunctionValueZoned == null ? null : getFunctionValueZoned.invoke(function, s.id, s.zone);
                case SENSOR_EVENT:
                    return getSensorEvent == null ? null : getSensorEvent.invoke(sensor, s.id);
                case SENSOR_VALUE:
                    return getSensorLatestValue == null ? null : getSensorLatestValue.invoke(sensor, s.id);
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= 找黑屏信号：只记录（2026-10-12，都在自己的线程上）
    //
    // 从原来的流程里调进来的几处（startProbes、probeFunction、probeSignal、writeProbeChanges、reconcileProbes、
    // onFunctionStatus）各自兜住异常、只写一次日志（probeFailed）：记录出了错，连接、对账的重排、回调线程都不能跟着出事。

    /** 注册后读的第一遍当起点：记一行每一项此刻的值，之后只记变化。 */
    private void startProbes() {
        try {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < DarkScreenProbes.IDS.length; i++) {
                String v = DarkScreenProbes.raw(readFunction(DarkScreenProbes.IDS[i]));
                probeLog.seed(DarkScreenProbes.NAMES[i], v);
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(DarkScreenProbes.NAMES[i]).append('=').append(v);
            }
            for (Signal s : DarkScreenProbes.WATCHED) {
                String v = DarkScreenProbes.text(s, values.get(s));
                probeLog.seed(DarkScreenProbes.key(s), v);
                line.append(' ').append(DarkScreenProbes.key(s)).append('=').append(v);
            }
            for (Signal s : DarkScreenProbes.RAW_WATCHED) {
                String v = rawProbes.containsKey(s) ? rawProbes.get(s) : "?";
                probeLog.seed(DarkScreenProbes.key(s), v);
                line.append(' ').append(DarkScreenProbes.key(s)).append('=').append(v);
            }
            probesStarted = true;
            BlackBox.note("车辆信号起点：" + line);
        } catch (RuntimeException e) {
            probeFailed("start", e);
        }
    }

    /** 功能号的回调：是要记的号就记它的原值（区域不是 0 的单独一项，{@link DarkScreenProbes#name}）。 */
    private void probeFunction(int id, int zone, Object value) {
        try {
            int probe = DarkScreenProbes.indexOf(id);
            if (probe >= 0) {
                probeChanged(DarkScreenProbes.name(probe, zone), DarkScreenProbes.raw(value));
            }
        } catch (RuntimeException e) {
            probeFailed("function", e);
        }
    }

    /** 信号表里的一项变了（{@link #apply}）：是跟着记的那几项就记它解出来的值。 */
    private void probeSignal(Signal s, Object decoded) {
        try {
            if (DarkScreenProbes.watches(s)) {
                probeChanged(DarkScreenProbes.key(s), DarkScreenProbes.text(s, decoded));
            }
        } catch (RuntimeException e) {
            probeFailed("signal", e);
        }
    }

    /**
     * 信号表里照原值记的那几项（{@link DarkScreenProbes#RAW_WATCHED}，2.11.5）读到一个值（{@link #apply}：回调、对账都走它）：记它的原值，
     * 变了（没超量）进这一次处理的那一行。起点之前读到的只留着给起点那一行。读不到的跳过（和 {@link #readProbes} 一样：
     * 不然读不了的那几遍会和回调来回跳）。
     */
    private void probeRaw(Signal s, Object raw) {
        try {
            if (raw != null && DarkScreenProbes.watchesRaw(s)) {
                String v = DarkScreenProbes.raw(raw);
                rawProbes.put(s, v);
                probeChanged(DarkScreenProbes.key(s), v);
            }
        } catch (RuntimeException e) {
            probeFailed("raw", e);
        }
    }

    /** 那几个号的可用状态变了（回调 {@code onSupportedFunctionStatusChanged}）：和值分开，一项「名字.status」。 */
    private void onFunctionStatus(int id, int zone, Object status) {
        if (!connected) {
            return;
        }
        try {
            int probe = DarkScreenProbes.indexOf(id);
            if (probe >= 0) {
                probeChanged(DarkScreenProbes.name(probe, zone) + DarkScreenProbes.STATUS, DarkScreenProbes.status(status));
            }
        } catch (RuntimeException e) {
            probeFailed("status", e);
        }
        writeProbeChanges(Pass.CALLBACK);
    }

    /**
     * 对账、当场要的那一遍里（信号表那几项已经在 {@link #apply} 里攒上了）：读那几个号、写成一行、补记压下过的。
     *
     * @return 那几个号读到了几个（记录出了错算 0，只影响「这一遍读到了没有」的计数）
     */
    private int reconcileProbes(Pass pass) {
        try {
            int answered = readProbes();
            writeProbeChanges(pass);
            noteProbeOverflow();
            return answered;
        } catch (RuntimeException e) {
            probeFailed("reconcile", e);
            return 0;
        }
    }

    /**
     * 对账时那几个号也直接读一遍：回调漏了、车机服务重启后回调断了，靠它补上（那一行带「对账时读到」）。
     * 读不到的跳过，不记成「?」：不然读不了的车上它会和回调来回跳。
     *
     * @return 读到了几个
     */
    private int readProbes() {
        int answered = 0;
        for (int i = 0; i < DarkScreenProbes.IDS.length; i++) {
            Object raw = readFunction(DarkScreenProbes.IDS[i]);
            if (raw != null) {
                answered++;
                probeChanged(DarkScreenProbes.NAMES[i], DarkScreenProbes.raw(raw));
            }
        }
        return answered;
    }

    /** 一项读到一个值：变了（没超量）就攒进这一次处理的那一行。起点之前读到的不算变化。 */
    private void probeChanged(String name, String value) {
        if (!probesStarted) {
            return;
        }
        String change = probeLog.change(name, value, SystemClock.elapsedRealtime());
        if (change != null) {
            probeChanges.add(name + " " + change);
        }
    }

    /**
     * 这一次处理（一个回调、一遍对账）里变了的写成一行：「车辆信号：screen_power_event 0 → 1；gear P → D」。
     * 对账读到的标出来：回调没来，实际变化最多可能早一个对账间隔（{@link #RECONCILE_MS}；有人在时提前读的那一遍也一样）。
     * 安卓刚亮那一遍（{@link #reconcileNow}，刚醒）读到的标「醒来时读到」：车机睡着时变的，什么时候变的不知道。
     */
    private void writeProbeChanges(Pass pass) {
        if (probeChanges.isEmpty()) {
            return;
        }
        try {
            StringBuilder line = new StringBuilder();
            for (String change : probeChanges) {
                if (line.length() > 0) {
                    line.append('；');
                }
                line.append(change);
            }
            probeChanges.clear();
            BlackBox.noteImportant("车辆信号：" + line
                    + (pass == Pass.RECONCILE ? "（对账时读到，实际可能早到 " + (RECONCILE_MS / 1000) + " 秒）"
                    : pass == Pass.WAKE ? "（醒来时读到）" : ""));
        } catch (RuntimeException e) {
            probeChanges.clear();
            probeFailed("write", e);
        }
    }

    /**
     * 超量压下过的项，窗口过了补一行：最后停在了哪个值（{@link ChangeTracker#drain}）。
     * 这一行写下的时刻不是变化的时刻，单独一行、标「限量后补记」。
     */
    private void noteProbeOverflow() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, String> item : probeLog.drain(SystemClock.elapsedRealtime()).entrySet()) {
            if (line.length() > 0) {
                line.append('；');
            }
            line.append(item.getKey()).append(' ').append(item.getValue());
        }
        if (line.length() > 0) {
            BlackBox.note("车辆信号（限量后补记，时刻不是变化的时刻）：" + line);
        }
    }

    /** 不在信号表里的功能号直接读一次；读不到返回 null。 */
    private Object readFunction(int id) {
        try {
            return getFunctionValue == null ? null : getFunctionValue.invoke(function, id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记录出了错：只写一次日志，别的什么都不动（这次是只加记录，不能因为它改了原来的流程）。 */
    private void probeFailed(String where, RuntimeException e) {
        if (!probeFailureLogged) {
            probeFailureLogged = true;
            AppLog.w(TAG, "dark-screen probe " + where + " failed (logged once): " + describe(e));
        }
    }

    // ================================================================= 反射小工具

    private static Object call(Object target, String name) {
        Method m = findMethod(target, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            AppLog.w(TAG, name + " failed: " + describe(t));
            return null;
        }
    }

    /** 调一下算不算成功：没抛异常，而且（返回 boolean 的话）返回 true。 */
    private static boolean invokeOk(Method m, Object target, Object... args) {
        try {
            Object r = m.invoke(target, args);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            AppLog.w(TAG, m.getName() + " failed: " + describe(t));
            return false;
        }
    }

    /**
     * 找公开方法：先在对象实现的<b>公开接口</b>上找（实现类 {@code com.zeekrlife.adaptapi.car.impl.*}
     * 不是公开的，拿它的 Method 去调会被访问检查拦下），找不到再退回类本身。
     */
    static Method findMethod(Object target, String name, Class<?>... params) {
        for (Class<?> type : publicInterfaces(target)) {
            Method m = match(type.getMethods(), name, params);
            if (m != null) {
                return m;
            }
        }
        Method m = match(target.getClass().getMethods(), name, params);
        if (m != null) {
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // 设不上就算了，调的时候再看
            }
        }
        return m;
    }

    /** 找「(first, 某个接口)」形状的方法：注册 / 注销监听的那种，接口类型从签名上取。 */
    private static Method findMethodWithInterface(Object target, String name, Class<?> first) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 2 && p[0] == first && p[1].isInterface()) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找「(某个接口)」形状的方法。 */
    private static Method findMethodWithInterface(Object target, String name) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 1 && p[0].isInterface()) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找「(某个接口, rest...)」形状的方法：带速率的 registerListener(listener, type, rate)。 */
    private static Method findMethodWithInterfaceThen(Object target, String name, Class<?>... rest) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals(name) || p.length != rest.length + 1 || !p[0].isInterface()) {
                    continue;
                }
                boolean same = true;
                for (int i = 0; i < rest.length; i++) {
                    same &= p[i + 1] == rest[i];
                }
                if (same) {
                    return m;
                }
            }
        }
        return null;
    }

    /** 找「(某个接口, second)」形状的方法：传感器的 registerListener(listener, type)。 */
    private static Method findMethodWithInterfaceFirst(Object target, String name, Class<?> second) {
        for (Class<?> type : publicInterfaces(target)) {
            for (Method m : type.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 2 && p[0].isInterface() && p[1] == second) {
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Class<?>> publicInterfaces(Object target) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            collectInterfaces(c.getInterfaces(), types);
        }
        List<Class<?>> pub = new ArrayList<>();
        for (Class<?> t : types) {
            if (Modifier.isPublic(t.getModifiers())) {
                pub.add(t);
            }
        }
        return pub;
    }

    private static void collectInterfaces(Class<?>[] interfaces, List<Class<?>> into) {
        for (Class<?> i : interfaces) {
            if (!into.contains(i)) {
                into.add(i);
                collectInterfaces(i.getInterfaces(), into);
            }
        }
    }

    private static Method match(Method[] methods, String name, Class<?>... params) {
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length != params.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < types.length; i++) {
                if (types[i] != params[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return m;
            }
        }
        return null;
    }

    /** 反射那一层剥掉，露出真正的原因。 */
    private static String describe(Throwable e) {
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
