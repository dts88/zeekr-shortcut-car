package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后视镜上那盏灯的来源（{@link MirrorLamps}，项目所有者 2026-11-03 用在盲区画面上）：车机设置服务（{@code setting_zeekr}）里
 * 盲区指示灯、开门预警左右这三个号。车辆接口（{@link EcarxSource}）的功能号里没有盲区指示灯，所以另走这一条路。只读，一个都不写。
 *
 * <h3>怎么连（Lab findings §2.68、§2.75、§2.78，车上验证过的事实）</h3>
 *
 * <ul>
 *   <li>安卓车辆库 {@code android.car.Car}，用<b>带生命周期监听</b>的那个 {@code createCar}：车辆服务重启时库自己重连、
 *       先后通知「断了」「好了」。不带监听的那几个，车辆服务一重启库就把整个进程杀掉（库的规矩），录着像也一样 ——
 *       所以不用；这台车机没有带监听的那个就不连。</li>
 *   <li>{@code getCarManager("setting_zeekr")} 拿管理器，它里面包着一个属性管理器（字段 {@code mMgr}）：列号、读值、订回调都走它，
 *       这个服务不查权限。号按管理器上的常量名取，不写死；这台车的清单里没有的不读。</li>
 *   <li>回调是整个服务的（车速这些也一直在推），库把它交到自己的线程上；不是这三个号的当场丢掉，不转线程。</li>
 *   <li>盲区指示灯一个号分区域：{@link #AREA_LEFT} 左、{@link #AREA_RIGHT} 右（项目所有者的车上对过，右舵；还有一个区域
 *       意思不知道，不用）。开门预警左右各一个号。</li>
 * </ul>
 *
 * <h3>读法</h3>
 *
 * <p>连上先读一遍，之后靠推（这几个号变了才推）。漏了「灭」会让画面一直挂着，所以有灯亮着时每 {@link #RECONCILE_MS}
 * 再读一遍亮着的那几盏；都灭着不读（漏了「亮」只是少显示一次）。车辆服务断了：全部算读不到，好了从头再来。
 * 每变一次往黑匣子记一行「后视镜灯：…」。</p>
 *
 * <p>只在有人要时开着（{@link Telemetry#acquireLamps}）。</p>
 */
final class MirrorLampSource {

    private static final String CAR_CLASS = "android.car.Car";
    private static final String LIFECYCLE_CLASS = "android.car.Car$CarServiceLifecycleListener";
    static final String SERVICE = "setting_zeekr";
    /** 管理器上的常量名：盲区指示灯（分左右区域）、开门预警左、右。 */
    static final String BLIND_SPOT = "ID_SETTING_FUNC_LCMA_INDCN_DATA";
    static final String DOOR_WARNING_LEFT = "ID_ZEEKR_DOOROPENWARNLEINDCN_DATA";
    static final String DOOR_WARNING_RIGHT = "ID_ZEEKR_DOOROPENWARNRIINDCN_DATA";
    /** 盲区指示灯的区域。 */
    static final int AREA_LEFT = 0x1;
    static final int AREA_RIGHT = 0x4;
    /** 有灯亮着时多久再读一遍亮着的那几盏（漏推了「灭」，画面最多多挂这么久）。 */
    static final long RECONCILE_MS = 2000L;

    /** 这一路的结果（黑匣子那一行照它写）。 */
    enum Link {
        CONNECTING,
        CONNECTED,
        /** 这台车机没有这条路（没有车辆库、没有带监听的连法、没有这个服务、清单里没有这几个号）：不再试。 */
        UNAVAILABLE,
        /** 有这条路但这一次没接上，或者车辆服务断了（库自己重连，好了再接）。 */
        FAILED
    }

    private final Telemetry telemetry;
    private final Context app;
    private volatile boolean stopped;
    private HandlerThread thread;
    private volatile Handler handler;
    private volatile Looper looper;

    // 下面这些只在自己的线程上碰（号是 volatile：库要是在别的线程上回调，先用号筛掉不是我们的）
    private Object car;
    private Object base;
    private Method getProperty;
    private Method unregister;
    private volatile int blindSpotId = -1;
    private volatile int doorLeftId = -1;
    private volatile int doorRightId = -1;
    private int doorLeftArea;
    private int doorRightArea;
    private boolean blindLeftListed;
    private boolean blindRightListed;
    private final Map<Integer, Class<?>> types = new HashMap<>();
    private MirrorLamps lamps = MirrorLamps.UNKNOWN;
    /** 读值失败记过一次（一个连接只记一次）。 */
    private boolean readFailureNoted;
    /** 推来的值的三个读法（第一次推来时按它的类找）。 */
    private volatile Method idGetter;
    private volatile Method areaGetter;
    private volatile Method valueGetter;

    private final Runnable reconcile = this::reconcile;

    MirrorLampSource(Telemetry telemetry, Context context) {
        this.telemetry = telemetry;
        this.app = context.getApplicationContext();
    }

    void start() {
        thread = new HandlerThread("Telemetry-MirrorLamps");
        thread.start();
        looper = thread.getLooper();
        Handler h = new Handler(looper);
        handler = h;
        h.post(this::connect);
    }

    /** 不读了：注销回调、断开车辆库、线程退出。哪个线程叫都行。 */
    void stop() {
        stopped = true;
        Handler h = handler;
        HandlerThread t = thread;
        handler = null;
        thread = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(() -> {
                dropBase();
                disconnect();
            });
        }
        if (t != null) {
            t.quitSafely();
        }
    }

    // ================================================================= 连接（在自己的线程上）

    private void connect() {
        if (stopped) {
            return;
        }
        Class<?> carClass;
        Method create;
        Class<?> lifecycle;
        try {
            carClass = Class.forName(CAR_CLASS, true, app.getClassLoader());
            lifecycle = Class.forName(LIFECYCLE_CLASS, true, carClass.getClassLoader());
            create = carClass.getMethod("createCar", Context.class, Handler.class, long.class, lifecycle);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            report(Link.UNAVAILABLE, "no createCar with a lifecycle listener (" + describe(e) + ")");
            return;
        } catch (Throwable t) {
            report(Link.FAILED, "car library failed: " + describe(t));
            return;
        }
        InvocationHandler lifecycleCalls = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onLifecycleChanged":
                    // 库在主线程上叫：交给自己的线程
                    boolean ready = args != null && args.length > 1 && Boolean.TRUE.equals(args[1]);
                    Handler h = handler;
                    if (h != null && !stopped) {
                        h.post(() -> onLifecycle(ready));
                    }
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "MirrorLampSource.lifecycle";
                default:
                    return null;
            }
        };
        try {
            Object listener = Proxy.newProxyInstance(lifecycle.getClassLoader(), new Class<?>[]{lifecycle}, lifecycleCalls);
            // 不等：车辆服务好了库在主线程上叫监听；回调交到这个线程
            car = create.invoke(null, app, handler, 0L, listener);
            if (car == null) {
                report(Link.FAILED, "createCar returned null");
            }
        } catch (Throwable t) {
            report(Link.FAILED, "createCar failed: " + describe(t));
        }
    }

    /** 车辆服务好了 / 断了（库叫的，转到这里）。 */
    private void onLifecycle(boolean ready) {
        if (stopped || car == null) {
            return;
        }
        // 重启前的管理器作废了：好了也从头接
        dropBase();
        if (!ready) {
            update(MirrorLamps.UNKNOWN);
            report(Link.FAILED, "car service disconnected; the car library reconnects by itself");
            return;
        }
        attach();
    }

    /** 拿设置服务的属性管理器、按名字取号、订回调、读一遍。 */
    private void attach() {
        try {
            Object manager = car.getClass().getMethod("getCarManager", String.class).invoke(car, SERVICE);
            if (manager == null) {
                report(Link.UNAVAILABLE, "no " + SERVICE + " manager");
                return;
            }
            Field field = field(manager.getClass(), "mMgr");
            if (field == null) {
                report(Link.UNAVAILABLE, "no mMgr in " + manager.getClass().getName());
                return;
            }
            field.setAccessible(true);
            Object b = field.get(manager);
            if (b == null) {
                report(Link.FAILED, "mMgr is null");
                return;
            }
            Map<Integer, Object> configs = configs(b);
            int blind = listed(configs, constant(manager.getClass(), BLIND_SPOT));
            int left = listed(configs, constant(manager.getClass(), DOOR_WARNING_LEFT));
            int right = listed(configs, constant(manager.getClass(), DOOR_WARNING_RIGHT));
            if (blind < 0 && left < 0 && right < 0) {
                report(Link.UNAVAILABLE, "none of the lamp properties is listed (" + configs.size() + " listed)");
                return;
            }
            int[] blindAreas = areas(configs.get(blind));
            blindLeftListed = blind >= 0 && contains(blindAreas, AREA_LEFT);
            blindRightListed = blind >= 0 && contains(blindAreas, AREA_RIGHT);
            doorLeftArea = firstArea(configs.get(left));
            doorRightArea = firstArea(configs.get(right));
            types.clear();
            types.put(blind, type(configs.get(blind)));
            types.put(left, type(configs.get(left)));
            types.put(right, type(configs.get(right)));
            getProperty = b.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            unregister = b.getClass().getMethod("unregisterCallback");
            Method register = oneArgument(b.getClass(), "registerCallback");
            if (register == null || !register.getParameterTypes()[0].isInterface()) {
                report(Link.UNAVAILABLE, "no registerCallback(listener) on " + b.getClass().getName());
                return;
            }
            Class<?> callbackType = register.getParameterTypes()[0];
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, events);
            blindSpotId = blind;
            doorLeftId = left;
            doorRightId = right;
            base = b;
            readFailureNoted = false;
            register.invoke(b, callback);
            update(readAll());
            report(Link.CONNECTED, "blind spot " + (blind < 0 ? "not listed" : (blindLeftListed ? "L" : "")
                    + (blindRightListed ? "R" : "")) + ", door warning " + (left < 0 ? "" : "L") + (right < 0 ? "" : "R")
                    + "; " + lamps);
        } catch (Throwable t) {
            dropBase();
            report(Link.FAILED, "attach failed: " + describe(t));
        }
    }

    /** 回调注销、号忘掉、对账停掉。 */
    private void dropBase() {
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(reconcile);
        }
        Object b = base;
        Method u = unregister;
        base = null;
        unregister = null;
        getProperty = null;
        blindSpotId = -1;
        doorLeftId = -1;
        doorRightId = -1;
        if (b != null && u != null) {
            try {
                u.invoke(b);
            } catch (Throwable ignored) {
                // 车辆服务已经没了：注销不掉也没关系
            }
        }
    }

    private void disconnect() {
        Object c = car;
        car = null;
        if (c == null) {
            return;
        }
        try {
            c.getClass().getMethod("disconnect").invoke(c);
        } catch (Throwable ignored) {
            // 断不开也没关系：线程马上退出
        }
    }

    // ================================================================= 读

    /** 推来的（库的线程上）：不是这三个号的当场丢掉；是的交到自己的线程上处理。 */
    private final InvocationHandler events = (proxy, method, args) -> {
        switch (method.getName()) {
            case "onChangeEvent":
                if (args != null && args.length == 1 && args[0] != null) {
                    onEvent(args[0]);
                }
                return null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && proxy == args[0];
            case "toString":
                return "MirrorLampSource.events";
            default:
                // onErrorEvent：只读的号，不管
                return null;
        }
    };

    private void onEvent(Object value) {
        int id = intOf(value, getter(value, 0));
        if (id < 0 || (id != blindSpotId && id != doorLeftId && id != doorRightId)) {
            return;
        }
        if (Looper.myLooper() == looper) {
            handle(value, id);
            return;
        }
        Handler h = handler;
        if (h != null && !stopped) {
            h.post(() -> handle(value, id));
        }
    }

    private void handle(Object value, int id) {
        if (stopped || base == null) {
            return;
        }
        MirrorLamps.Lamp lamp = lampOf(id, intOf(value, getter(value, 1)));
        if (lamp == null) {
            return;
        }
        update(lamps.with(lamp, MirrorLamps.on(call(value, getter(value, 2)))));
    }

    /** 哪个号哪个区域是哪一盏；盲区指示灯别的区域不用。 */
    private MirrorLamps.Lamp lampOf(int id, int area) {
        if (id == blindSpotId) {
            return area == AREA_LEFT ? MirrorLamps.Lamp.BLIND_SPOT_LEFT
                    : area == AREA_RIGHT ? MirrorLamps.Lamp.BLIND_SPOT_RIGHT : null;
        }
        if (id == doorLeftId) {
            return MirrorLamps.Lamp.DOOR_WARNING_LEFT;
        }
        return id == doorRightId ? MirrorLamps.Lamp.DOOR_WARNING_RIGHT : null;
    }

    /** 四盏都读一遍（连上时）。 */
    private MirrorLamps readAll() {
        MirrorLamps read = MirrorLamps.UNKNOWN;
        for (MirrorLamps.Lamp lamp : MirrorLamps.Lamp.values()) {
            read = read.with(lamp, read(lamp));
        }
        return read;
    }

    /** 亮着的那几盏再读一遍；还有亮着的就接着排下一次。 */
    private void reconcile() {
        if (stopped || base == null) {
            return;
        }
        MirrorLamps read = lamps;
        for (MirrorLamps.Lamp lamp : MirrorLamps.Lamp.values()) {
            if (Boolean.TRUE.equals(read.get(lamp))) {
                read = read.with(lamp, read(lamp));
            }
        }
        update(read);
        scheduleReconcile();
    }

    private void scheduleReconcile() {
        Handler h = handler;
        if (h == null || stopped) {
            return;
        }
        h.removeCallbacks(reconcile);
        if (base != null && lamps.anyOn()) {
            h.postDelayed(reconcile, RECONCILE_MS);
        }
    }

    /** 读一盏；这台车的清单里没有、读不到的是 null。 */
    private Boolean read(MirrorLamps.Lamp lamp) {
        int id;
        int area;
        switch (lamp) {
            case BLIND_SPOT_LEFT:
                id = blindLeftListed ? blindSpotId : -1;
                area = AREA_LEFT;
                break;
            case BLIND_SPOT_RIGHT:
                id = blindRightListed ? blindSpotId : -1;
                area = AREA_RIGHT;
                break;
            case DOOR_WARNING_LEFT:
                id = doorLeftId;
                area = doorLeftArea;
                break;
            default:
                id = doorRightId;
                area = doorRightArea;
                break;
        }
        Method get = getProperty;
        if (id < 0 || get == null || base == null) {
            return null;
        }
        try {
            Object value = get.invoke(base, types.get(id), id, area);
            return value == null ? null : MirrorLamps.on(call(value, getter(value, 2)));
        } catch (Throwable t) {
            if (!readFailureNoted) {
                readFailureNoted = true;
                BlackBox.note("后视镜灯：读不到 " + lamp + "（" + describe(t) + "），之后同样的不再记");
            }
            return null;
        }
    }

    /** 换成新的一份：变了才交给 {@link Telemetry}、记一行，再看要不要对账。 */
    private void update(MirrorLamps next) {
        if (next == lamps) {
            return;
        }
        boolean changed = !next.equals(lamps);
        lamps = next;
        if (changed) {
            BlackBox.note("后视镜灯：" + next);
            telemetry.publishLamps(this, next);
        }
        scheduleReconcile();
    }

    private void report(Link link, String detail) {
        telemetry.lampsReported(this, link, detail);
    }

    // ================================================================= 反射的小工具

    /**
     * 推来的值的三个读法：0 号、1 区域、2 值。第一次按它的类找，之后照用（推来的都是同一个类）。
     */
    private Method getter(Object value, int which) {
        Method m = which == 0 ? idGetter : which == 1 ? areaGetter : valueGetter;
        if (m != null && m.getDeclaringClass().isInstance(value)) {
            return m;
        }
        try {
            Class<?> c = value.getClass();
            m = c.getMethod(which == 0 ? "getPropertyId" : which == 1 ? "getAreaId" : "getValue");
        } catch (NoSuchMethodException e) {
            return null;
        }
        if (which == 0) {
            idGetter = m;
        } else if (which == 1) {
            areaGetter = m;
        } else {
            valueGetter = m;
        }
        return m;
    }

    private static Object call(Object target, Method m) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Object target, Method m) {
        Object v = call(target, m);
        return v instanceof Integer ? (Integer) v : -1;
    }

    /** 管理器上的常量（public static int）；没有是 -1。 */
    private static int constant(Class<?> manager, String name) {
        try {
            Field f = manager.getField(name);
            return f.getType() == int.class ? f.getInt(null) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 这台车的清单（{@code getPropertyList}）：号 → 配置。 */
    private static Map<Integer, Object> configs(Object b) throws Exception {
        Map<Integer, Object> out = new HashMap<>();
        Object list = b.getClass().getMethod("getPropertyList").invoke(b);
        if (list instanceof List) {
            for (Object config : (List<?>) list) {
                if (config == null) {
                    continue;
                }
                Object id = config.getClass().getMethod("getPropertyId").invoke(config);
                if (id instanceof Integer) {
                    out.put((Integer) id, config);
                }
            }
        }
        return out;
    }

    private static int listed(Map<Integer, Object> configs, int id) {
        return id >= 0 && configs.containsKey(id) ? id : -1;
    }

    private static int[] areas(Object config) {
        if (config == null) {
            return new int[0];
        }
        try {
            Object areas = config.getClass().getMethod("getAreaIds").invoke(config);
            return areas instanceof int[] ? (int[]) areas : new int[0];
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** 开门预警只有一个区域（项目所有者的车上是「全部」那一个）；清单里没写就用 0。 */
    private static int firstArea(Object config) {
        int[] areas = areas(config);
        return areas.length > 0 ? areas[0] : 0;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** 值的类型（这几个号是整数）；读不到按整数。 */
    private static Class<?> type(Object config) {
        if (config == null) {
            return Integer.class;
        }
        try {
            Object type = config.getClass().getMethod("getPropertyType").invoke(config);
            return type instanceof Class ? (Class<?>) type : Integer.class;
        } catch (Throwable t) {
            return Integer.class;
        }
    }

    /** 字段，往父类找。 */
    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 往上找
            }
        }
        return null;
    }

    private static Method oneArgument(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                return m;
            }
        }
        return null;
    }

    /** 反射包了一层的，取里面真正的异常。 */
    static String describe(Throwable t) {
        Throwable e = t;
        while (e instanceof InvocationTargetException && e.getCause() != null) {
            e = e.getCause();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
