package com.kooo.evcam.zeekr;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 车辆信号的分板块穷举。
 *
 * <h3>这一节想解决什么</h3>
 *
 * <p>标准 {@code android.car} 那条路已经确认走不通（权限一个都不发，
 * 连 {@code normal} 级的也不发）。但那只排除了<b>一条</b>路，不等于信号读不到。</p>
 *
 * <p>问题是剩下的路不知道从哪下手。所以这里不猜、也不针对某个具体信号，
 * 而是<b>把每一类可读的来源整片列出来</b> —— 先看清有什么，再决定往哪挖。</p>
 *
 * <h3>取数与呈现是分开的</h3>
 *
 * <p>{@code readXxx()} 只负责取原始数据，{@link #appendTo} 负责写成给人看的文本，
 * {@link DiagnosticsJson} 负责写成给机器读的 JSON。两个出口共用同一份取数逻辑 ——
 * 否则迟早会出现「文本里有、JSON 里没有」这种对不上的情况。</p>
 *
 * <p>文本那份为了能在车机上翻，每块是有条数上限的；
 * <b>JSON 那份不设上限</b>，导出来慢慢分析用。</p>
 */
public final class VehicleEnumeration {

    private static final String TAG = "VehicleEnumeration";

    /** Settings 的三张表。 */
    public static final String[] SETTINGS_TABLES = {"global", "secure", "system"};

    /** 值太长的截断，只用于文本呈现；JSON 里保留原值。 */
    private static final int MAX_VALUE_LEN = 120;

    /** 文本呈现时每块最多列多少条。JSON 不受此限。 */
    private static final int MAX_ROWS = 200;

    /** 判断「名字像不像车辆相关」用的关键词。 */
    private static final String[] VEHICLE_HINTS = {
            "car", "vehicle", "gear", "door", "speed", "drive", "reverse",
            "ecarx", "zeekr", "geely", "hvac", "seat", "light", "turn",
            "signal", "brake", "park", "adas", "avm", "dvr", "acc",
    };

    private VehicleEnumeration() {
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    /**
     * 读 Settings 的某一张表。
     *
     * <p>不需要权限，而且车厂常把状态放这里 —— 是目前最值得先看的一块。</p>
     *
     * @return 键值对；读不到时返回空表，不返回 null
     */
    public static Map<String, String> readSettings(Context context, String table) {
        Map<String, String> values = new TreeMap<>();
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(Uri.parse("content://settings/" + table),
                    null, null, null, null);
            if (cursor == null) {
                return values;
            }
            int nameColumn = cursor.getColumnIndex("name");
            int valueColumn = cursor.getColumnIndex("value");
            if (nameColumn < 0) {
                return values;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if (name == null) {
                    continue;
                }
                values.put(name, valueColumn >= 0 ? cursor.getString(valueColumn) : null);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "读 settings/" + table + " 失败: " + t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return values;
    }

    /**
     * 系统服务全清单，不过滤。
     *
     * <p>主报告 4.2 只列了名字带 car/ecarx/vehicle 的 ——
     * 名字不带这些字的服务一样可能管着车辆状态。</p>
     */
    public static List<String> readServices() {
        List<String> names = new ArrayList<>();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            String[] services = (String[]) serviceManager
                    .getMethod("listServices").invoke(null);
            if (services != null) {
                names.addAll(Arrays.asList(services));
                Collections.sort(names);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "枚举系统服务失败: " + t);
        }
        return names;
    }

    /** 一个车辆相关应用及其可用的入口。 */
    public static final class PackageEntry {
        public final String packageName;
        public final boolean systemApp;
        /** 导出的 provider，形如 {@code authority}；可能可以直接查。 */
        public final List<String> exportedProviders = new ArrayList<>();
        /** 每个 provider 的读权限要求，与上一项一一对应；null 表示不要求。 */
        public final List<String> providerPermissions = new ArrayList<>();
        /** 导出的 receiver，说明存在对应的广播。 */
        public final List<String> exportedReceivers = new ArrayList<>();

        PackageEntry(String packageName, boolean systemApp) {
            this.packageName = packageName;
            this.systemApp = systemApp;
        }
    }

    /** 名字像车辆相关的已安装应用，连同它们导出的 provider 与 receiver。 */
    public static List<PackageEntry> readVehiclePackages(Context context) {
        List<PackageEntry> entries = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(
                    PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS);
            for (PackageInfo info : packages) {
                if (!looksVehicleRelated(info.packageName)) {
                    continue;
                }
                boolean system = info.applicationInfo != null
                        && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                PackageEntry entry = new PackageEntry(info.packageName, system);
                if (info.providers != null) {
                    for (ProviderInfo provider : info.providers) {
                        if (!provider.exported) {
                            continue;
                        }
                        entry.exportedProviders.add(provider.authority);
                        entry.providerPermissions.add(provider.readPermission);
                    }
                }
                if (info.receivers != null) {
                    for (ActivityInfo receiver : info.receivers) {
                        if (receiver.exported) {
                            entry.exportedReceivers.add(receiver.name);
                        }
                    }
                }
                entries.add(entry);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "枚举车辆相关应用失败: " + t);
        }
        return entries;
    }

    /** 系统属性按前两段前缀分组的项数。 */
    public static Map<String, Integer> namespaceCounts(Map<String, String> properties) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String key : properties.keySet()) {
            String prefix = prefixOf(key);
            Integer previous = counts.get(prefix);
            counts.put(prefix, previous == null ? 1 : previous + 1);
        }
        return counts;
    }

    /** 从一份键值表里挑出名字像车辆相关的，保持键的顺序。 */
    public static Map<String, String> vehicleLookingEntries(Map<String, String> source) {
        Map<String, String> hits = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (looksVehicleRelated(entry.getKey())) {
                hits.put(entry.getKey(), entry.getValue());
            }
        }
        return hits;
    }

    static String prefixOf(String key) {
        int first = key.indexOf('.');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf('.', first + 1);
        return second < 0 ? key.substring(0, first) : key.substring(0, second);
    }

    /**
     * 名字看着像不像车辆相关。
     *
     * <p>只用来<b>排序和分组</b>，不用来过滤掉任何东西 —— 每个板块都把
     * 「不像的」也一并列出来了。关键词命中的排在前面便于看，
     * 但真正的信号完全可能藏在一个名字毫不相干的键里，
     * 所以这个判断错了也不会让人漏掉什么。</p>
     */
    public static boolean looksVehicleRelated(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String hint : VEHICLE_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 文本呈现（给人在车机上翻的，有条数上限）
    // ------------------------------------------------------------------

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("\n## 9. 车辆信号分板块穷举\n\n");
        sb.append("标准 android.car 那条路已确认走不通（见 4.5）。这一节不针对某个信号，\n");
        sb.append("而是把每一类可读的来源整片列出来 —— 先看清有什么，再决定往哪挖。\n\n");
        sb.append("**看的时候**：先找名字里带挡位/车门/车速的项，\n");
        sb.append("再用「① 拍快照 / ② 对比变化」验证它会不会真的跟着动作变。\n");
        sb.append("名字只是线索，会不会动才是证据。\n\n");
        sb.append("> 这里每块最多列 ").append(MAX_ROWS).append(" 条，够在车上翻。\n");
        sb.append("> **完整数据在导出的 .json 里**，不设上限。\n");

        sb.append("\n### 9.A Settings 三张表\n\n");
        sb.append("不需要权限就能读，而且车厂常把状态放这里。\n");
        for (String table : SETTINGS_TABLES) {
            Map<String, String> values = readSettings(context, table);
            sb.append("\n**").append(table).append("**：共 ").append(values.size()).append(" 项");
            if (values.isEmpty()) {
                sb.append("（读不到）\n");
                continue;
            }
            Map<String, String> hits = vehicleLookingEntries(values);
            sb.append("，名字像车辆相关的 ").append(hits.size()).append(" 项\n\n");
            appendMap(sb, hits);
        }

        sb.append("\n### 9.B 系统服务全清单\n\n");
        List<String> services = readServices();
        List<String> serviceHits = new ArrayList<>();
        for (String name : services) {
            if (looksVehicleRelated(name)) {
                serviceHits.add(name);
            }
        }
        sb.append("共 ").append(services.size()).append(" 个服务，名字像车辆相关的 ")
                .append(serviceHits.size()).append(" 个：\n\n");
        appendList(sb, serviceHits);

        sb.append("\n### 9.C 车辆相关应用及其导出组件\n\n");
        sb.append("导出的 provider 也许能直接查，导出的 receiver 说明有对应的广播可以收。\n");
        List<PackageEntry> packages = readVehiclePackages(context);
        if (packages.isEmpty()) {
            sb.append("\n- 没有匹配到名字像车辆相关的应用\n");
        }
        for (PackageEntry entry : packages) {
            sb.append("\n**").append(entry.packageName).append("**");
            if (entry.systemApp) {
                sb.append("（系统应用）");
            }
            sb.append('\n');
            for (int i = 0; i < entry.exportedProviders.size(); i++) {
                sb.append("  - provider ").append(entry.exportedProviders.get(i));
                String permission = entry.providerPermissions.get(i);
                sb.append(permission != null ? "（需权限 " + permission + "）" : "（无读权限要求）");
                sb.append('\n');
            }
            for (String receiver : entry.exportedReceivers) {
                sb.append("  - receiver ").append(receiver).append('\n');
            }
        }

        sb.append("\n### 9.D 系统属性命名空间地图\n\n");
        Map<String, String> properties = VehicleSignalProbe.captureProperties();
        if (properties.isEmpty()) {
            sb.append("- 读不到系统属性\n");
            return;
        }
        Map<String, String> propertyHits = vehicleLookingEntries(properties);
        sb.append("属性总数 ").append(properties.size()).append("，名字像车辆相关的 ")
                .append(propertyHits.size()).append(" 项：\n\n");
        appendMap(sb, propertyHits);
        sb.append("\n各命名空间的项数：\n");
        for (Map.Entry<String, Integer> entry : namespaceCounts(properties).entrySet()) {
            sb.append("- ").append(entry.getKey()).append("：")
                    .append(entry.getValue()).append('\n');
        }
    }

    private static void appendMap(StringBuilder sb, Map<String, String> values) {
        int shown = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (shown++ >= MAX_ROWS) {
                sb.append("- …还有 ").append(values.size() - MAX_ROWS).append(" 条，见 .json\n");
                break;
            }
            sb.append("- ").append(entry.getKey()).append(" = ")
                    .append(truncate(entry.getValue())).append('\n');
        }
    }

    private static void appendList(StringBuilder sb, List<String> values) {
        int limit = Math.min(values.size(), MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            sb.append("- ").append(values.get(i)).append('\n');
        }
        if (values.size() > limit) {
            sb.append("- …还有 ").append(values.size() - limit).append(" 条，见 .json\n");
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "(null)";
        }
        return value.length() <= MAX_VALUE_LEN
                ? value : value.substring(0, MAX_VALUE_LEN) + "…（截断）";
    }
}
