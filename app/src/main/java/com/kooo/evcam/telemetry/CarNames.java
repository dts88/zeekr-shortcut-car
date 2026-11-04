package com.kooo.evcam.telemetry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 车辆接口的功能号、区域按系统里的常量名取，不写死（2026-11-05）：车机系统自带的 ECARX 接口类在 BOOTCLASSPATH 上
 * （{@link EcarxSource}），常量名和号码的对应照 zeekr-shortcut-lab 的功能表（{@code docs/tables/catalog.tsv}）。
 * 这台车机上没有这个常量的，就是没有这个功能。
 *
 * <p>接口在 {@code com.ecarx.xui.adaptapi.car} 下面的几个包里（Lab 遍历时能加载的那些：{@code hvac}、{@code vehicle} ……）；
 * 有的接口 Lab 只从 jar 里读到过名字、没记包名（比如 {@code ICharging}），所以按 {@link #PACKAGES} 的次序挨个试。
 * 找到的、找不到的都记着，同一个常量只找一次。</p>
 */
public final class CarNames {

    static final String BASE = "com.ecarx.xui.adaptapi.car.";
    /** 接口可能在的包，按次序试。 */
    static final String[] PACKAGES = {"hvac.", "vehicle.", "base.", "charging.", "hev.", "energy.", ""};
    /** 区域常量的类（不在 {@code car} 包下）。 */
    static final String ZONE_CLASS = "com.ecarx.xui.adaptapi.vehicle.VehicleZone";

    /** 全车。 */
    public static final String ALL = "ALL";
    /** 第一排左：ECARX 里是主驾（右舵车也是，按主驾 / 副驾算，不按左右；Lab 信号手册「区域」）。 */
    public static final String DRIVER = "ROW_1_LEFT";
    /** 第一排右：副驾。 */
    public static final String PASSENGER = "ROW_1_RIGHT";
    public static final String ROW_1_CENTER = "ROW_1_CENTER";
    public static final String ROW_1 = "ROW_1_ALL";
    public static final String ROW_2_LEFT = "ROW_2_LEFT";
    public static final String ROW_2_RIGHT = "ROW_2_RIGHT";
    public static final String ROW_2 = "ROW_2_ALL";

    /** 车上没有区域那个类时用的值：ECARX 自己的应用里打包的那一份（Lab {@code EcarxSweep}）。 */
    private static final Map<String, Integer> FALLBACK_ZONES = new HashMap<>();

    static {
        FALLBACK_ZONES.put(DRIVER, 0x1);
        FALLBACK_ZONES.put(ROW_1_CENTER, 0x2);
        FALLBACK_ZONES.put(PASSENGER, 0x4);
        FALLBACK_ZONES.put(ROW_1, 0x8);
        FALLBACK_ZONES.put(ROW_2_LEFT, 0x10);
        FALLBACK_ZONES.put(ROW_2_RIGHT, 0x40);
        FALLBACK_ZONES.put(ROW_2, 0x80);
        FALLBACK_ZONES.put(ALL, 0x80000000);
    }

    /** 找过的：「接口.常量」→ 号；找不到的存 null。 */
    private static final Map<String, Integer> FOUND = new HashMap<>();

    private CarNames() {
    }

    /** 一个功能号：接口名（「IHvac」）和常量名；这台车机上没有是 null。 */
    public static synchronized Integer function(String iface, String field) {
        String key = iface + "." + field;
        if (FOUND.containsKey(key)) {
            return FOUND.get(key);
        }
        Integer value = null;
        for (String candidate : candidates(iface)) {
            value = constant(candidate, field);
            if (value != null) {
                break;
            }
        }
        FOUND.put(key, value);
        return value;
    }

    /** 一个区域；车上有区域那个类就照它，没有照 {@link #FALLBACK_ZONES}。 */
    public static synchronized int zone(String name) {
        String key = "VehicleZone." + name;
        if (!FOUND.containsKey(key)) {
            FOUND.put(key, constant(ZONE_CLASS, name));
        }
        Integer value = FOUND.get(key);
        if (value != null) {
            return value;
        }
        Integer fallback = FALLBACK_ZONES.get(name);
        return fallback == null ? 0 : fallback;
    }

    /** 接口可能的全名，按 {@link #PACKAGES} 的次序（纯逻辑，{@code CarNamesTest}）。 */
    static String[] candidates(String iface) {
        String[] out = new String[PACKAGES.length];
        for (int i = 0; i < PACKAGES.length; i++) {
            out[i] = BASE + PACKAGES[i] + iface;
        }
        return out;
    }

    /** 一个类上的静态 int 常量；没有这个类、没有这个字段、不是 int 都是 null。 */
    private static Integer constant(String className, String field) {
        try {
            Field f = Class.forName(className).getField(field);
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                return null;
            }
            return f.getInt(null);
        } catch (Throwable e) {
            return null;
        }
    }
}
