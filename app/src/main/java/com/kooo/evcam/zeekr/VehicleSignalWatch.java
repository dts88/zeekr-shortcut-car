package com.kooo.evcam.zeekr;

import android.content.Context;

import com.kooo.evcam.AppLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 盯着点火状态这一类车辆信号，变一次记一次。
 *
 * <h3>为什么要盯</h3>
 *
 * <p>断电时正在录的那一段没有索引，是因为 MP4 的索引要等 {@code stop()} 才写。
 * 要在断电前把它关干净，得先知道「快断电了」。{@link ShutdownProbe} 在等关机广播，
 * 那条路给不给信号是车机说了算；这一条是另一个方向：<b>主动读点火状态</b>。</p>
 *
 * <p>2026-08-29 实测的权限级别决定了这条路值得一试：转向灯和车门是
 * {@code signature|privileged}（无解），但<b>点火状态、档位、手刹属于
 * {@code CAR_POWERTRAIN}，是 normal 级</b> —— 申请一次就该给。</p>
 *
 * <p>监听比广播还多一个好处：{@code registerCallback} 是<b>值一变就回调</b>，
 * 不用等系统广播，也不用轮询。</p>
 *
 * <h3>全反射，且只读</h3>
 *
 * <p>{@code android.car} 不在普通 SDK 里，编译期没有这些类，所以从连接到回调全走反射，
 * 回调接口用 {@link Proxy} 动态实现。任何一步失败都如实记下原因，不抛出去。</p>
 *
 * <p>{@link VehicleSignalProbe} 里有一份看起来重复的连接代码 —— 那一份是<b>给报告用的</b>，
 * 每一步都要写一行「成功 / 失败，原因是什么」。这里要的是连上之后长期挂着，
 * 两者的输出形态不同，合并只会让两边都别扭。</p>
 */
public final class VehicleSignalWatch {

    private static final String TAG = "VehicleSignalWatch";

    /**
     * 盯哪几个。都在 {@code CAR_POWERTRAIN} 名下 —— 这一组是 normal 级，拿得到。
     *
     * <p>转向灯、车门不在这里：它们是 {@code signature|privileged}，
     * 第三方应用没有任何办法拿到，注册了也只会收到错误回调。</p>
     */
    private static final String[] WATCHED = {
            "IGNITION_STATE",       // 点火状态 —— 最想要的那个
            "GEAR_SELECTION",       // 档位：挂 P 也是「要走了」的信号
            "PARKING_BRAKE_ON",     // 手刹
    };

    /** 值一变就回调。0 是 {@code SENSOR_RATE_ONCHANGE}。 */
    private static final float RATE_ON_CHANGE = 0f;

    /** 留最近这么多条变化，诊断报告里显示。 */
    private static final int MAX_EVENTS = 40;

    private static final Object LOCK = new Object();
    private static final List<String> EVENTS = new ArrayList<>();
    /** 属性 id → 我们认得的名字，回调里只给 id。 */
    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();
    /** 每一路最后一次读到的值。 */
    private static final Map<String, String> LAST = new LinkedHashMap<>();

    private static volatile Context appContext;
    private static volatile boolean started;
    private static volatile String status = "还没启动";
    /**
     * 这两个留着<b>只为强引用</b>：注册出去之后，车辆服务那边多半只持弱引用，
     * 我们这边一旦回收，回调就悄悄不来了 —— 而那是最难查的一种「没反应」。
     */
    private static Object propertyManager;
    private static Object callbackProxy;

    private VehicleSignalWatch() {
    }

    // ================================================================= 启停

    /**
     * 连上车辆属性服务并挂上监听。重复调用无害。
     *
     * <p>不检查权限就直接注册 —— 「检查说没有」和「实际读不到」在这台车机上未必一致，
     * 而不一致正是要找的东西。失败了如实记下来。</p>
     */
    public static void start(Context context) {
        if (started || context == null) {
            return;
        }
        started = true;
        appContext = context.getApplicationContext();
        try {
            Object manager = connect(appContext);
            if (manager == null) {
                return;
            }
            propertyManager = manager;
            registerAll(manager);
            readAllNow(manager);
        } catch (Throwable t) {
            status = "启动失败: " + t;
            AppLog.w(TAG, "车辆信号监听启动失败: " + t);
        }
    }

    /** 连接车辆属性服务，拿到 CarPropertyManager。 */
    private static Object connect(Context context) throws Exception {
        Class<?> carClass;
        try {
            carClass = Class.forName("android.car.Car");
        } catch (ClassNotFoundException e) {
            status = "这台车机没有 android.car";
            AppLog.i(TAG, status);
            return null;
        }
        Method createCar = null;
        for (Method m : carClass.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("createCar".equals(m.getName()) && params.length == 1
                    && params[0] == Context.class) {
                createCar = m;
                break;
            }
        }
        if (createCar == null) {
            status = "没有 createCar(Context)";
            return null;
        }
        Object car = createCar.invoke(null, context);
        if (car == null) {
            status = "createCar 返回 null";
            AppLog.w(TAG, status);
            return null;
        }
        Method getCarManager = carClass.getMethod("getCarManager", String.class);
        Object manager = getCarManager.invoke(car, "property");
        if (manager == null) {
            status = "getCarManager(\"property\") 返回 null";
            AppLog.w(TAG, status);
            return null;
        }
        return manager;
    }

    // ================================================================= 注册监听

    private static void registerAll(Object manager) {
        Class<?> callbackClass;
        try {
            callbackClass = Class.forName(
                    "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
        } catch (ClassNotFoundException e) {
            status = "没有 CarPropertyEventCallback，监听这条路不通";
            AppLog.w(TAG, status);
            return;
        }
        callbackProxy = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass}, new Callback());

        Method register = null;
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("registerCallback".equals(m.getName()) && params.length == 3
                    && params[0].isAssignableFrom(callbackClass)
                    && params[1] == int.class && params[2] == float.class) {
                register = m;
                break;
            }
        }
        if (register == null) {
            status = "没有 registerCallback(callback, int, float)";
            AppLog.w(TAG, status);
            return;
        }

        int ok = 0;
        StringBuilder failures = new StringBuilder();
        for (String name : WATCHED) {
            Integer propId = propertyId(name);
            if (propId == null) {
                failures.append(name).append("=无此常量 ");
                continue;
            }
            NAMES.put(propId, name);
            try {
                Object result = register.invoke(manager, callbackProxy, propId, RATE_ON_CHANGE);
                boolean accepted = !(result instanceof Boolean) || (Boolean) result;
                if (accepted) {
                    ok++;
                    AppLog.i(TAG, "已挂上监听: " + name);
                } else {
                    failures.append(name).append("=被拒 ");
                }
            } catch (Throwable t) {
                // 这里最可能是 SecurityException —— 记下来，这正是要的答案
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                failures.append(name).append('=').append(cause.getClass().getSimpleName())
                        .append(' ');
                AppLog.w(TAG, "挂监听失败 " + name + ": " + cause);
            }
        }
        status = "已挂上 " + ok + "/" + WATCHED.length
                + (failures.length() > 0 ? "，失败: " + failures.toString().trim() : "");
        AppLog.i(TAG, status);
    }

    /** 挂上之后先主动读一次，免得在「一直没变」的情况下什么都看不到。 */
    private static void readAllNow(Object manager) {
        Method getProperty = null;
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("getProperty".equals(m.getName()) && params.length == 2
                    && params[0] == int.class && params[1] == int.class) {
                getProperty = m;
                break;
            }
        }
        if (getProperty == null) {
            return;
        }
        for (Map.Entry<Integer, String> entry : NAMES.entrySet()) {
            try {
                Object value = getProperty.invoke(manager, entry.getKey(), 0);
                String text = describeValue(value);
                LAST.put(entry.getValue(), text);
                record(entry.getValue(), text, "开机读到");
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                LAST.put(entry.getValue(), "读不到: " + cause.getClass().getSimpleName());
                AppLog.w(TAG, "读不到 " + entry.getValue() + ": " + cause);
            }
        }
    }

    private static Integer propertyId(String name) {
        try {
            Class<?> ids = Class.forName("android.car.VehiclePropertyIds");
            return ids.getField(name).getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= 回调

    /** 车辆属性的回调接口只有两个方法，这里用动态代理顶上。 */
    private static final class Callback implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            try {
                if ("onChangeEvent".equals(name) && args != null && args.length > 0) {
                    onChange(args[0]);
                } else if ("onErrorEvent".equals(name) && args != null && args.length >= 1) {
                    AppLog.w(TAG, "车辆属性回调报错: " + java.util.Arrays.toString(args));
                    record("(错误回调)", java.util.Arrays.toString(args), "错误");
                }
            } catch (Throwable t) {
                AppLog.w(TAG, "处理回调失败: " + t);
            }
            // Object 那三个方法也会走到这里
            if ("toString".equals(name)) {
                return "VehicleSignalWatch$Callback";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            return null;
        }
    }

    private static void onChange(Object carPropertyValue) throws Exception {
        if (carPropertyValue == null) {
            return;
        }
        Class<?> cls = carPropertyValue.getClass();
        int propId = (Integer) cls.getMethod("getPropertyId").invoke(carPropertyValue);
        Object value = cls.getMethod("getValue").invoke(carPropertyValue);
        String name = NAMES.containsKey(propId) ? NAMES.get(propId) : ("属性#" + propId);
        String text = describeValue(value);
        LAST.put(name, text);
        record(name, text, "变化");
        AppLog.i(TAG, "车辆信号变化: " + name + " = " + text);

        // 点火状态的变化和关机广播摆在同一条时间线上，好对照。
        // 按「要紧」记：如果它真是「快断电了」的信号，那条记录必须已经落盘
        if ("IGNITION_STATE".equals(name) && appContext != null) {
            ShutdownProbe.note(appContext, "IGNITION_STATE=" + text, true);
        }
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[]) {
            return java.util.Arrays.deepToString((Object[]) value);
        }
        return String.valueOf(value);
    }

    private static void record(String name, String value, String why) {
        synchronized (LOCK) {
            EVENTS.add(String.format(Locale.US, "%s  %s = %s  (%s)",
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                            .format(new java.util.Date()),
                    name, value, why));
            while (EVENTS.size() > MAX_EVENTS) {
                EVENTS.remove(0);
            }
        }
    }

    // ================================================================= 报告

    /** 诊断报告里的那一节。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(status).append('\n');
        if (!LAST.isEmpty()) {
            sb.append("当前值:\n");
            for (Map.Entry<String, String> entry : LAST.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(" = ")
                        .append(entry.getValue()).append('\n');
            }
        }
        synchronized (LOCK) {
            if (EVENTS.isEmpty()) {
                sb.append("还没收到过任何变化。\n");
                sb.append("如果熄过一次火再开机也依然是这一行，说明监听这条路也拿不到点火状态。\n");
            } else {
                sb.append("收到过的变化（新的在下面）:\n");
                for (String event : EVENTS) {
                    sb.append("  ").append(event).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
