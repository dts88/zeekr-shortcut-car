package com.kooo.evcam.zeekr;

import android.content.Context;
import android.content.pm.PackageManager;

import com.kooo.evcam.AppLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 深度探测「这台车机到底能不能读到转向灯 / 车门 / 车速」。
 *
 * <p>之前只按类名探测了几个猜出来的 CarSignalManager，全都没找到就下了结论 ——
 * 那是错的。EVCam 真正的读法根本不是找那个类名，而是：</p>
 *
 * <pre>
 *   ServiceManager.getService("ecarxcar_service")
 *     -> ecarx.car.IECarXCar$Stub.asInterface(binder)
 *     -> ecarx.car.ECarXCar.createCar(context, iECarXCar)
 *     -> car.getCarManager("car_signal", iECarXCar)
 *     -> getIndcrSts() / getDoorDrvrSts() / ...
 * </pre>
 *
 * <p>ECARX 正是做极氪与吉利车机的那家（EVCC 的全称就是 Ecarx Vehicle Control
 * Console），所以同一条路在极氪上很可能是通的，只是需要实测。</p>
 *
 * <p>本类<b>只读不写</b>：全部走反射并吞掉异常，读到什么报什么，读不到就如实说明失败原因，
 * 绝不因为一条路不通就断言"读不到信号"。</p>
 */
public final class VehicleSignalProbe {

    private static final String TAG = "VehicleSignalProbe";

    /** EVCam 使用的 ECARX 车辆服务名。 */
    private static final String ECARX_SERVICE = "ecarxcar_service";

    /** ECARX 转向灯 / 车门读取方法，取自 EVCam 的实现。 */
    private static final String[] SIGNAL_METHODS = {
            "getIndcrSts",        // 转向灯
            "getDoorDrvrSts",     // 主驾门
            "getDoorPassSts",     // 副驾门
            "getDoorLeReSts",     // 左后门
            "getDoorRiReSts",     // 右后门
    };

    /** 读车辆属性需要的权限，按诊断报告里出现的顺序。 */
    public static final String[] CAR_PERMISSIONS = {
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CONTROL_CAR_DOORS",
            "android.car.permission.CAR_EXTERIOR_LIGHTS",
            "android.car.permission.CAR_ENERGY",
    };

    /** 标准 Android Automotive 里几个我们关心的属性。 */
    private static final String[][] CAR_PROPERTIES = {
            {"TURN_SIGNAL_STATE", "转向灯"},
            {"DOOR_POS", "车门位置"},
            {"PERF_VEHICLE_SPEED", "车速"},
            {"GEAR_SELECTION", "档位"},
            {"PARKING_BRAKE_ON", "手刹"},
            {"IGNITION_STATE", "点火状态"},
    };

    private VehicleSignalProbe() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        AppLog.i(TAG, "开始车辆信号深度探测");
        sb.append("## 4. 车辆信号（深度探测）").append('\n');
        sb.append("目标：确认转向灯 / 车门 / 车速能否读到。只读，不改任何设置。").append('\n');
        sb.append('\n');

        probeSystemProperties(sb);
        probeSystemServices(sb);
        probeEcarxCarService(sb, context);
        probeEcarxAdaptApi(sb, context);
        probeAndroidCar(sb, context);
        probeLogcatSignals(sb);

        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 0. 系统属性 —— 容器伪装了 Build，但通常没伪装 SystemProperties
    // ------------------------------------------------------------------

    private static void probeSystemProperties(StringBuilder sb) {
        sb.append("### 4.1 系统属性（识别真实平台）").append('\n');
        sb.append("App Lab 把我们跑在虚拟化容器里，android.os.Build 被伪装成了 Pixel 3a。").append('\n');
        sb.append("SystemProperties 通常不被伪装，能看出这台车机真正是谁家的平台 ——").append('\n');
        sb.append("这决定了该找哪家的车辆 SDK，而不是继续靠猜。").append('\n');

        String[] keys = {
                "ro.product.brand", "ro.product.manufacturer", "ro.product.model",
                "ro.product.name", "ro.product.device", "ro.board.platform",
                "ro.build.version.release", "ro.build.display.id",
                "ro.ecarx.version", "ro.ecarx.product", "ro.zeekr.version",
                "persist.sys.ecarx.region", "ro.vendor.ecarx.platform",
        };
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            for (String key : keys) {
                Object value = get.invoke(null, key, "");
                String text = String.valueOf(value);
                sb.append("     ").append(key).append(" = ")
                        .append(text.isEmpty() ? "(空)" : text).append('\n');
            }
        } catch (Throwable t) {
            sb.append("!! 读取失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 1. 系统服务清单 —— 先看车机到底注册了哪些 binder 服务
    // ------------------------------------------------------------------

    private static void probeSystemServices(StringBuilder sb) {
        sb.append("### 4.2 系统服务清单（含 car/ecarx/vehicle 字样的）").append('\n');
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method listServices = sm.getMethod("listServices");
            Object result = listServices.invoke(null);
            if (!(result instanceof String[])) {
                sb.append("listServices 返回了意外类型").append('\n').append('\n');
                return;
            }
            String[] services = (String[]) result;
            sb.append("服务总数: ").append(services.length).append('\n');
            List<String> interesting = new ArrayList<>();
            for (String name : services) {
                String lower = name.toLowerCase(Locale.US);
                if (lower.contains("car") || lower.contains("ecarx")
                        || lower.contains("vehicle") || lower.contains("signal")
                        || lower.contains("vhal") || lower.contains("hvac")) {
                    interesting.add(name);
                }
            }
            if (interesting.isEmpty()) {
                sb.append(">> 没有匹配的服务").append('\n');
            } else {
                sb.append(">> 相关服务 (").append(interesting.size())
                        .append(")，附各自的 AIDL 接口名:").append('\n');
                // 接口描述符能直接说出这个 binder 背后是哪个 AIDL —— 比服务名本身
                // 更有指向性，是下一步该去找什么类的线索。
                Method getService = sm.getMethod("getService", String.class);
                for (String name : interesting) {
                    sb.append("     ").append(name);
                    try {
                        Object binder = getService.invoke(null, name);
                        if (binder == null) {
                            sb.append("  (取不到 binder)");
                        } else {
                            Method descriptor = binder.getClass()
                                    .getMethod("getInterfaceDescriptor");
                            sb.append("  -> ").append(descriptor.invoke(binder));
                        }
                    } catch (Throwable t) {
                        sb.append("  (接口名读取失败)");
                    }
                    sb.append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("!! 枚举失败: ").append(t).append('\n');
            sb.append("   （应用可能没有权限调用 ServiceManager）").append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 2. ECARX ecarxcar_service —— EVCam 实际使用的那条路
    // ------------------------------------------------------------------

    private static void probeEcarxCarService(StringBuilder sb, Context context) {
        sb.append("### 4.3 ECARX ecarxcar_service（EVCam 使用的路径）").append('\n');

        Object binder = null;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            binder = getService.invoke(null, ECARX_SERVICE);
            sb.append("binder \"").append(ECARX_SERVICE).append("\": ")
                    .append(binder != null ? "存在" : "不存在").append('\n');
        } catch (Throwable t) {
            sb.append("!! 获取 binder 失败: ").append(t).append('\n');
        }

        boolean stubOk = classExists(sb, "ecarx.car.IECarXCar");
        classExists(sb, "ecarx.car.ECarXCar");

        if (binder == null || !stubOk) {
            sb.append(">> 这条路不可用（binder 或类缺失）").append('\n').append('\n');
            return;
        }

        try {
            Class<?> stubClass = Class.forName("ecarx.car.IECarXCar$Stub");
            Method asInterface = stubClass.getMethod("asInterface",
                    Class.forName("android.os.IBinder"));
            Object eCarXCar = asInterface.invoke(null, binder);
            sb.append("asInterface: ").append(eCarXCar != null ? "成功" : "返回 null").append('\n');
            if (eCarXCar == null) {
                sb.append('\n');
                return;
            }

            Class<?> carClass = Class.forName("ecarx.car.ECarXCar");
            Class<?> iface = Class.forName("ecarx.car.IECarXCar");
            Method createCar = carClass.getMethod("createCar", Context.class, iface);
            Object car = createCar.invoke(null, context, eCarXCar);
            sb.append("createCar: ").append(car != null ? "成功" : "返回 null").append('\n');
            if (car == null) {
                sb.append('\n');
                return;
            }

            // 用声明类而不是运行时类查方法：createCar 若返回非 public 的子类，
            // 在运行时类上拿到的 Method 会在 invoke 时因访问权限失败 ——
            // 那在报告里看起来就像「这条路不通」，实际上是通的。
            Method getCarManager = carClass.getMethod("getCarManager", String.class, iface);
            Object signalManager = getCarManager.invoke(car, "car_signal", eCarXCar);
            sb.append("getCarManager(\"car_signal\"): ")
                    .append(signalManager != null ? "成功" : "返回 null").append('\n');
            if (signalManager == null) {
                sb.append('\n');
                return;
            }

            sb.append(">> 信号读取结果（转动方向盘 / 开关车门后重新采集可对比变化）:").append('\n');
            for (String name : SIGNAL_METHODS) {
                readAndReport(sb, signalManager, name);
            }

            // 把这个 manager 上所有 getter 都列出来，可能有我们还不知道的信号
            sb.append(">> 该 manager 的全部无参 getter:").append('\n');
            listNoArgGetters(sb, signalManager);

        } catch (Throwable t) {
            sb.append("!! 调用链失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 3. ECARX adaptapi CarSensor —— EVCam 里出现过的另一条路
    // ------------------------------------------------------------------

    private static void probeEcarxAdaptApi(StringBuilder sb, Context context) {
        sb.append("### 4.4 ECARX adaptapi CarSensor").append('\n');
        final String cls = "com.ecarx.xui.adaptapi.car.sensor.CarSensor";
        if (!classExists(sb, cls)) {
            sb.append(">> 不可用").append('\n').append('\n');
            return;
        }
        try {
            Class<?> sensorClass = Class.forName(cls);
            Object sensor = null;
            // 只认「静态、单参、参数收得下 Context」的 create ——
            // 只按名字和参数个数匹配的话，可能会把某个 create(int) 拿来传 Context。
            for (Method m : sensorClass.getMethods()) {
                if (!"create".equals(m.getName())
                        || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(Context.class)) {
                    sensor = m.invoke(null, context);
                    break;
                }
            }
            sb.append("create(context): ").append(sensor != null ? "成功" : "失败或无此方法").append('\n');
            if (sensor != null) {
                sb.append(">> 全部无参 getter:").append('\n');
                listNoArgGetters(sb, sensor);
            }
        } catch (Throwable t) {
            sb.append("!! 调用失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 4. 标准 Android Automotive Car API
    // ------------------------------------------------------------------

    private static void probeAndroidCar(StringBuilder sb, Context context) {
        sb.append("### 4.5 标准 android.car").append('\n');
        if (!classExists(sb, "android.car.Car")) {
            sb.append(">> 不可用").append('\n').append('\n');
            return;
        }

        // 车辆相关权限的授予情况 —— 读属性需要这些
        // 分别报告「我们声明了没有」和「系统给了没有」——
        // checkSelfPermission 对这两种情况都返回 DENIED，只看它分不清是哪种。
        java.util.Set<String> declared = new java.util.HashSet<>();
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                java.util.Collections.addAll(declared, info.requestedPermissions);
            }
        } catch (Throwable t) {
            sb.append("     （读取本应用声明的权限失败: ").append(t).append("）").append('\n');
        }

        sb.append("权限状态（声明 / 授予 / 保护级别）:").append('\n');
        boolean anyRuntimeGrantable = false;
        for (String perm : CAR_PERMISSIONS) {
            boolean granted = false;
            try {
                granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
                // 权限名在本平台可能不存在
            }
            String protection = describeProtection(context, perm);
            if (protection.startsWith("dangerous")) {
                anyRuntimeGrantable = true;
            }
            sb.append("     ")
                    .append(declared.contains(perm) ? "[已声明] " : "[未声明] ")
                    .append(granted ? "[已授予] " : "[未授予] ")
                    .append(perm)
                    .append("  级别=").append(protection).append('\n');
        }
        sb.append('\n');
        sb.append("     保护级别决定这件事有没有戏：").append('\n');
        sb.append("       dangerous            —— 运行时可申请，值得弹窗试一次").append('\n');
        sb.append("       signature/privileged —— 需与平台同签名或预置到 priv-app，").append('\n');
        sb.append("                               App Lab 里的第三方应用拿不到").append('\n');
        sb.append("       未定义               —— 本平台根本没有这个权限").append('\n');
        if (anyRuntimeGrantable) {
            sb.append("     >> 有 dangerous 级别的权限，用诊断页的「申请车辆权限」按钮试一次，")
                    .append('\n');
            sb.append("        然后重新采集本报告对比。").append('\n');
        } else {
            sb.append("     >> 没有任何一个是 dangerous 级别，运行时申请不会有帮助。")
                    .append('\n');
        }

        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Object car = null;
            // createCar(Context) 是较新的同步版本
            for (Method m : carClass.getMethods()) {
                if ("createCar".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == Context.class) {
                    car = m.invoke(null, context);
                    break;
                }
            }
            sb.append("Car.createCar(context): ")
                    .append(car != null ? "成功" : "失败或无此重载").append('\n');
            if (car == null) {
                sb.append('\n');
                return;
            }

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            Object propertyManager = getCarManager.invoke(car, "property");
            sb.append("getCarManager(\"property\"): ")
                    .append(propertyManager != null ? "成功" : "返回 null").append('\n');
            if (propertyManager == null) {
                sb.append('\n');
                return;
            }

            // 列出该应用实际能拿到的属性
            try {
                Method getConfigs = propertyManager.getClass()
                        .getMethod("getPropertyList");
                Object configs = getConfigs.invoke(propertyManager);
                if (configs instanceof List) {
                    List<?> list = (List<?>) configs;
                    sb.append(">> 可访问的属性数量: ").append(list.size()).append('\n');
                    int shown = 0;
                    for (Object cfg : list) {
                        if (shown++ >= 40) {
                            sb.append("     ...（其余略）").append('\n');
                            break;
                        }
                        sb.append("     ").append(cfg).append('\n');
                    }
                }
            } catch (Throwable t) {
                sb.append("   属性清单读取失败: ").append(t).append('\n');
            }

            // 尝试直接读我们关心的几个
            sb.append(">> 关心的属性读取结果:").append('\n');
            for (String[] entry : CAR_PROPERTIES) {
                readCarProperty(sb, propertyManager, entry[0], entry[1]);
            }

        } catch (Throwable t) {
            sb.append("!! 调用失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 读一个车辆属性。
     *
     * <p>优先用不带类型的 {@code getProperty(int, int)} —— 它返回
     * {@code CarPropertyValue}，对什么类型的属性都适用。带类型的三参重载要求传对
     * 具体类（车速是 Float、手刹是 Boolean、转向灯是 Integer），传错会以一个与
     * 「能不能读到」无关的理由失败，反而掩盖真正的答案。</p>
     */
    private static void readCarProperty(StringBuilder sb, Object propertyManager,
                                        String propName, String label) {
        sb.append("     ").append(label).append(" (").append(propName).append("): ");
        int propId;
        try {
            Class<?> ids = Class.forName("android.car.VehiclePropertyIds");
            propId = ids.getField(propName).getInt(null);
        } catch (Throwable t) {
            sb.append("本平台无此属性常量").append('\n');
            return;
        }

        Method untyped = null;
        for (Method m : propertyManager.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if ("getProperty".equals(m.getName()) && params.length == 2
                    && params[0] == int.class && params[1] == int.class) {
                untyped = m;
                break;
            }
        }
        if (untyped == null) {
            sb.append("无 getProperty(int,int) 方法").append('\n');
            return;
        }

        // areaId 0 是全局属性的区域号；车门这类分区属性用 0 读不到，
        // 那种情况下要看上面的属性清单里列出的 areaIds。
        try {
            Object value = untyped.invoke(propertyManager, propId, 0);
            sb.append(value).append('\n');
        } catch (Throwable t) {
            String reason = t.getCause() != null ? String.valueOf(t.getCause()) : String.valueOf(t);
            sb.append("读取失败 ").append(reason).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // 5. logcat —— EVCam 的第三条路
    // ------------------------------------------------------------------

    private static void probeLogcatSignals(StringBuilder sb) {
        sb.append("### 4.6 logcat 信号线索").append('\n');
        sb.append("EVCam 匹配的是 \"data1 = <数字>\" 和 \"front turn signal:\"，").append('\n');
        sb.append("这里放宽范围，看看极氪日志里有没有可用的信号事件。").append('\n');

        String[] needles = {
                "data1 =", "turn signal", "turnsignal", "indcr", "IndcrSts",
                "door", "Door", "gear", "Gear", "speed",
        };
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-v", "brief", "-t", "3000"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> hits = new ArrayList<>();
            String line;
            int scanned = 0;
            while ((line = reader.readLine()) != null && hits.size() < 60) {
                scanned++;
                for (String needle : needles) {
                    if (line.contains(needle)) {
                        hits.add(line.length() > 200 ? line.substring(0, 200) + "…" : line);
                        break;
                    }
                }
            }
            reader.close();
            sb.append("扫描 ").append(scanned).append(" 行，命中 ").append(hits.size())
                    .append(" 行").append('\n');
            if (hits.isEmpty()) {
                sb.append(">> 没有命中。若此刻没有打转向灯/开车门，属正常；").append('\n');
                sb.append("   请打几次转向灯、开关一次车门后再采集一次。").append('\n');
            } else {
                for (String hit : hits) {
                    sb.append("     ").append(hit).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("!! logcat 读取失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 读权限的保护级别。
     *
     * <p>这是「能不能拿到」的决定性依据，比凭印象背 AOSP 可靠 —— 各 Android 版本和
     * 各家 OEM 的定义并不一致，只有这台车机自己的答案算数。</p>
     */
    private static String describeProtection(Context context, String permission) {
        try {
            android.content.pm.PermissionInfo info =
                    context.getPackageManager().getPermissionInfo(permission, 0);
            int base;
            int flags;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                base = info.getProtection();
                flags = info.getProtectionFlags();
            } else {
                base = info.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE;
                flags = info.protectionLevel & ~android.content.pm.PermissionInfo.PROTECTION_MASK_BASE;
            }
            StringBuilder out = new StringBuilder();
            switch (base) {
                case android.content.pm.PermissionInfo.PROTECTION_NORMAL:
                    out.append("normal");
                    break;
                case android.content.pm.PermissionInfo.PROTECTION_DANGEROUS:
                    out.append("dangerous");
                    break;
                case android.content.pm.PermissionInfo.PROTECTION_SIGNATURE:
                    out.append("signature");
                    break;
                default:
                    out.append("其他(").append(base).append(")");
                    break;
            }
            if ((flags & android.content.pm.PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
                out.append("|privileged");
            }
            return out.toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "未定义";
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    /**
     * 全部尚未授予的车辆权限（不看保护级别）。
     *
     * <p>「申请权限时车机弹不弹框」本身就是要观察的实验，所以别替系统先筛掉
     * 一部分。dangerous 的会弹框，signature/privileged 的系统会当场静默拒绝 ——
     * 两种结果都由用户在车上直接看到，比我们凭级别猜更可靠。</p>
     *
     * @return 尚未授予的车辆权限；全部已授予时返回空数组
     */
    public static String[] ungrantedCarPermissions(Context context) {
        List<String> pending = new ArrayList<>();
        for (String perm : CAR_PERMISSIONS) {
            try {
                if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    pending.add(perm);
                }
            } catch (Throwable ignored) {
                // 权限名在本平台可能不存在，跳过
            }
        }
        return pending.toArray(new String[0]);
    }

    private static boolean classExists(StringBuilder sb, String name) {
        try {
            Class.forName(name);
            sb.append("类 ").append(name).append(": 存在").append('\n');
            return true;
        } catch (Throwable t) {
            sb.append("类 ").append(name).append(": 不存在").append('\n');
            return false;
        }
    }

    private static void readAndReport(StringBuilder sb, Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object value = m.invoke(target);
            sb.append("     ").append(methodName).append("() = ")
                    .append(abbreviate(value)).append('\n');
        } catch (NoSuchMethodException e) {
            sb.append("     ").append(methodName).append("(): 无此方法").append('\n');
        } catch (Throwable t) {
            String reason = t.getCause() != null ? String.valueOf(t.getCause()) : String.valueOf(t);
            sb.append("     ").append(methodName).append("(): 调用失败 ").append(reason).append('\n');
        }
    }

    /** 返回值可能是个很大的对象，截断后再写进报告。 */
    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    /**
     * 列出对象上所有无参 getter 及其返回值。
     *
     * <p>比逐个猜方法名可靠得多 —— 极氪的 manager 上可能有我们还不知道的信号。</p>
     */
    private static void listNoArgGetters(StringBuilder sb, Object target) {
        Method[] methods = target.getClass().getMethods();
        Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
        int count = 0;
        for (Method m : methods) {
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("get") && !name.startsWith("is")) {
                continue;
            }
            if ("getClass".equals(name)) {
                continue;
            }
            if (count++ >= 60) {
                sb.append("     ...（其余略）").append('\n');
                break;
            }
            try {
                Object value = m.invoke(target);
                sb.append("     ").append(name).append("() = ")
                        .append(abbreviate(value)).append('\n');
            } catch (Throwable t) {
                sb.append("     ").append(name).append("(): 调用失败").append('\n');
            }
        }
        if (count == 0) {
            sb.append("     （没有无参 getter）").append('\n');
        }
    }
}
