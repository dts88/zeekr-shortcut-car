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
 *       防车机服务重启后回调断了。</li>
 *   <li>浮点传感器（车速、转角、深度、温度……）不订阅 —— 变一点就推，订了只是噪声 —— 按 {@link #FLOAT_POLL_MS} 读。</li>
 *   <li><b>传感器监听疑似注销不掉</b>（注销再注册，回调会变成两条三条），所以传感器只在进程里注册一次、永远留着，
 *       停了就不理它的回调；功能号的监听每次停都注销。车辆对象整个进程只建一次（{@code Car.create} 要一秒）。</li>
 * </ul>
 *
 * <p>信号表在 {@link Signal}；读数进 {@link Readings}，信息条那份快照由 {@link VehicleStateMapper} 推出来。</p>
 *
 * <p><b>找黑屏信号，只记录</b>（2026-10-12）：{@link DarkScreenProbes} 那几个信号表里没有的号跟着一起订、对账时一起读
 * （它们的可用状态回调也记），连同信号表里的点火、挡位、哨兵、主驾门 / 座椅 / 安全带，每变一次进黑匣子一行（{@link #probeLog}）。
 * 不进读数、不进信息条、不改任何判断，出了错也只写日志；来源停了就不记。</p>
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
    /** 车辆信息：主驾在哪一边（ICarInfo.INT_INFO_DRIVER_SIDE），读到 0x00100302 = 右、0x00100301 = 左。 */
    private static final int INFO_DRIVER_SIDE = 0x00100300;
    private static final int DRIVER_SIDE_LEFT = 0x00100301;
    private static final int DRIVER_SIDE_RIGHT = 0x00100302;

    /** 车辆对象整个进程只建一次。 */
    private static volatile Object cachedCar;
    /** 传感器监听：进程里只注册一次、永不注销（注销不掉）；回调转给此刻活着的那个来源。 */
    private static final Object SENSOR_LOCK = new Object();
    private static Object sensorProxy;
    private static final Set<Integer> sensorTypesRegistered = new HashSet<>();
    private static volatile EcarxSource active;

    private final Telemetry telemetry;
    private final VehicleStateMapper mapper = new VehicleStateMapper();
    private final Map<Signal, Object> values = new EnumMap<>(Signal.class);
    /** 读法 + 号码 + 区域 → 用它的信号（同一个号码可能几条信号都用）。 */
    private final Map<Long, List<Signal>> byKey = new HashMap<>();
    private HandlerThread thread;
    private volatile Handler handler;
    /** 连接经过的细节：只进日志和黑匣子（{@link Telemetry#sourceReported}）。 */
    private volatile String status = "not started";
    /** 连接的结果：页面上的状态行只显示这个。 */
    private volatile Telemetry.CarLink link = Telemetry.CarLink.CONNECTING;
    private volatile boolean stopped;
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

    /**
     * 找黑屏信号的那几项（{@link DarkScreenProbes}）：上一个值和限量。只在自己的线程上碰。
     * 一次处理（一个回调、一遍对账）里变了的攒在 {@link #probeChanges}，处理完写成一行（{@link #writeProbeChanges}）。
     */
    private final ChangeTracker probeLog =
            new ChangeTracker(DarkScreenProbes.MAX_LINES_PER_WINDOW, DarkScreenProbes.WINDOW_MS);
    private final List<String> probeChanges = new ArrayList<>();
    /** 起点那一行记过了：之前的（注册后读的第一遍）是起点，不算变化。 */
    private boolean probesStarted;
    /** 记录出过错、日志写过了（只写一次，{@link #probeFailed}）。 */
    private boolean probeFailureLogged;

    private final Runnable floatPoll = this::pollFloats;
    private final Runnable reconcile = this::reconcileDiscrete;
    private final Runnable publish = this::publishNow;

    EcarxSource(Telemetry telemetry) {
        this.telemetry = telemetry;
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
        handler = null;
        thread = null;
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
    }

    Telemetry.CarLink link() {
        return link;
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
                    status = "Car.create returned null";
                    report(Telemetry.CarLink.UNAVAILABLE);
                    return;
                }
                cachedCar = car;
            }
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
                status = "no readable managers (function=" + (function != null) + ", sensor=" + (sensor != null) + ")";
                report(Telemetry.CarLink.UNAVAILABLE);
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
            readAll();
            status = String.format(Locale.US,
                    "connected in %d ms; function=%s zoned=%s sensorEvent=%s sensorValue=%s; driver %s; %s",
                    SystemClock.elapsedRealtime() - start, getFunctionValue != null, getFunctionValueZoned != null,
                    getSensorEvent != null, getSensorLatestValue != null, side, subscribed);
            report(Telemetry.CarLink.CONNECTED);
            Handler h = handler;
            if (h != null && !stopped) {
                h.postDelayed(floatPoll, FLOAT_POLL_MS);
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

    /** 结果给页面（{@link #link}），经过（{@link #status}，含异常原文）只进日志和黑匣子。 */
    private void report(Telemetry.CarLink result) {
        link = result;
        AppLog.i(TAG, status);
        telemetry.sourceReported("ecarx", status);
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
        writeProbeChanges(false);
    }

    private void onSensor(Signal.Kind kind, int type, Object value) {
        List<Signal> list = byKey.get(key(kind, type, 0));
        if (list != null) {
            for (Signal s : list) {
                apply(s, value);
            }
        }
        writeProbeChanges(false);
    }

    /** 归一、比对、有变化才记；发布合并在一个小窗口里。 */
    private void apply(Signal s, Object raw) {
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

    private void schedulePublish(long delayMs) {
        Handler h = handler;
        if (h == null || stopped) {
            return;
        }
        if (delayMs > 0) {
            h.postDelayed(publish, delayMs);
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
        telemetry.edit(b -> mapper.apply(b, forBar, now, driverOnRight));
        telemetry.publishReadings(snapshot);
        if (!firstRoundReported) {
            firstRoundReported = true;
            com.kooo.evcam.blackbox.BlackBox.note("行驶信息 ecarx 第一轮读数：" + snapshot.describe());
        }
    }

    /** 每一项读一遍：起点。 */
    private void readAll() {
        for (Signal s : Signal.values()) {
            apply(s, read(s));
        }
        schedulePublish(0);
        startProbes();
    }

    private void reconcileDiscrete() {
        if (stopped) {
            return;
        }
        for (Signal s : Signal.values()) {
            if (!s.isFloat()) {
                apply(s, read(s));
            }
        }
        reconcileProbes();
        Handler h = handler;
        if (h != null) {
            h.postDelayed(reconcile, RECONCILE_MS);
        }
    }

    private void pollFloats() {
        if (stopped) {
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

    /** 那几个号的可用状态变了（回调 {@code onSupportedFunctionStatusChanged}）：和值分开，一项「名字.status」。 */
    private void onFunctionStatus(int id, int zone, Object status) {
        try {
            int probe = DarkScreenProbes.indexOf(id);
            if (probe >= 0) {
                probeChanged(DarkScreenProbes.name(probe, zone) + DarkScreenProbes.STATUS, DarkScreenProbes.status(status));
            }
        } catch (RuntimeException e) {
            probeFailed("status", e);
        }
        writeProbeChanges(false);
    }

    /** 对账那一遍里（信号表那几项已经在 {@link #apply} 里攒上了）：读那几个号、写成一行、补记压下过的。 */
    private void reconcileProbes() {
        try {
            readProbes();
            writeProbeChanges(true);
            noteProbeOverflow();
        } catch (RuntimeException e) {
            probeFailed("reconcile", e);
        }
    }

    /**
     * 对账时那几个号也直接读一遍：回调漏了、车机服务重启后回调断了，靠它补上（那一行带「对账时读到」）。
     * 读不到的跳过，不记成「?」：不然读不了的车上它会和回调来回跳。
     */
    private void readProbes() {
        for (int i = 0; i < DarkScreenProbes.IDS.length; i++) {
            Object raw = readFunction(DarkScreenProbes.IDS[i]);
            if (raw != null) {
                probeChanged(DarkScreenProbes.NAMES[i], DarkScreenProbes.raw(raw));
            }
        }
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
     * 对账读到的标出来：回调没来，实际变化最多可能早一个对账间隔（{@link #RECONCILE_MS}）。
     */
    private void writeProbeChanges(boolean reconciled) {
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
                    + (reconciled ? "（对账时读到，实际可能早到 " + (RECONCILE_MS / 1000) + " 秒）" : ""));
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
