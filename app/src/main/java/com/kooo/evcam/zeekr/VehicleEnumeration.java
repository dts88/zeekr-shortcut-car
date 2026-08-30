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
 * 而是<b>把每一类可读的来源整片列出来</b> —— 先看清有什么，再决定往哪挖。
 * 报告存下来慢慢比对，比在车上临时试要有效得多。</p>
 *
 * <h3>板块</h3>
 *
 * <ul>
 *   <li><b>A</b> Settings 三张表：车厂常把状态塞在这里，而且不需要权限</li>
 *   <li><b>B</b> 系统服务全清单：主报告 4.2 只列了名字带 car/ecarx 的，
 *       这里不过滤 —— 名字不带这些字的一样可能是</li>
 *   <li><b>C</b> 车辆相关应用及其导出组件：能查的 provider、能收的广播</li>
 *   <li><b>D</b> 系统属性的命名空间地图：哪个前缀下有多少项，指出该往哪看</li>
 * </ul>
 */
public final class VehicleEnumeration {

    private static final String TAG = "VehicleEnumeration";

    /** 值太长的截断，免得报告被一两条撑爆。 */
    private static final int MAX_VALUE_LEN = 120;

    /** 每个板块最多列多少条。 */
    private static final int MAX_ROWS = 400;

    /** 判断「名字像不像车辆相关」用的关键词。 */
    private static final String[] VEHICLE_HINTS = {
            "car", "vehicle", "gear", "door", "speed", "drive", "reverse",
            "ecarx", "zeekr", "geely", "hvac", "seat", "light", "turn",
            "signal", "brake", "park", "adas", "avm", "dvr", "acc",
    };

    private VehicleEnumeration() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("\n## 9. 车辆信号分板块穷举\n\n");
        sb.append("标准 android.car 那条路已确认走不通（见 4.5）。这一节不针对某个信号，\n");
        sb.append("而是把每一类可读的来源整片列出来 —— 先看清有什么，再决定往哪挖。\n\n");
        sb.append("**看的时候**：先找名字里带挡位/车门/车速的项，\n");
        sb.append("再用「① 拍快照 / ② 对比变化」验证它会不会真的跟着动作变。\n");
        sb.append("名字只是线索，会不会动才是证据。\n");

        settingsTables(sb, context);
        allSystemServices(sb);
        vehicleRelatedPackages(sb, context);
        propertyNamespaces(sb);
    }

    // ------------------------------------------------------------------
    // 板块 A：Settings 三张表
    // ------------------------------------------------------------------

    private static void settingsTables(StringBuilder sb, Context context) {
        sb.append("\n### 9.A Settings 三张表\n\n");
        sb.append("不需要权限就能读，而且车厂常把状态放这里。\n");
        for (String table : new String[]{"global", "secure", "system"}) {
            dumpSettingsTable(sb, context, table);
        }
    }

    private static void dumpSettingsTable(StringBuilder sb, Context context, String table) {
        sb.append("\n**").append(table).append("**\n\n");
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(Uri.parse("content://settings/" + table),
                    null, null, null, null);
            if (cursor == null) {
                sb.append("- 查询返回 null（这张表读不到）\n");
                return;
            }
            int nameColumn = cursor.getColumnIndex("name");
            int valueColumn = cursor.getColumnIndex("value");
            if (nameColumn < 0) {
                sb.append("- 没有 name 列，实际列：")
                        .append(Arrays.toString(cursor.getColumnNames())).append('\n');
                return;
            }
            List<String> hits = new ArrayList<>();
            List<String> others = new ArrayList<>();
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                String value = valueColumn >= 0 ? cursor.getString(valueColumn) : "?";
                String row = "- " + name + " = " + truncate(value);
                if (looksVehicleRelated(name)) {
                    hits.add(row);
                } else {
                    others.add(row);
                }
            }
            sb.append("共 ").append(hits.size() + others.size()).append(" 项，")
                    .append("其中名字像车辆相关的 ").append(hits.size()).append(" 项。\n\n");
            if (!hits.isEmpty()) {
                sb.append("像车辆相关的：\n");
                appendRows(sb, hits);
                sb.append('\n');
            }
            sb.append("其余：\n");
            appendRows(sb, others);
        } catch (Throwable t) {
            sb.append("- 读取失败: ").append(t).append('\n');
            AppLog.w(TAG, "读 settings/" + table + " 失败: " + t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // 板块 B：系统服务全清单
    // ------------------------------------------------------------------

    private static void allSystemServices(StringBuilder sb) {
        sb.append("\n### 9.B 系统服务全清单（不过滤）\n\n");
        sb.append("主报告 4.2 只列了名字带 car/ecarx/vehicle 的。\n");
        sb.append("这里全列 —— 名字不带这些字的服务一样可能管着车辆状态。\n\n");
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            String[] services = (String[]) serviceManager
                    .getMethod("listServices").invoke(null);
            if (services == null) {
                sb.append("- listServices 返回 null\n");
                return;
            }
            List<String> hits = new ArrayList<>();
            List<String> others = new ArrayList<>();
            List<String> sorted = new ArrayList<>(Arrays.asList(services));
            Collections.sort(sorted);
            for (String name : sorted) {
                if (looksVehicleRelated(name)) {
                    hits.add("- " + name);
                } else {
                    others.add("- " + name);
                }
            }
            sb.append("共 ").append(sorted.size()).append(" 个服务，")
                    .append("名字像车辆相关的 ").append(hits.size()).append(" 个。\n\n");
            if (!hits.isEmpty()) {
                sb.append("像车辆相关的：\n");
                appendRows(sb, hits);
                sb.append('\n');
            }
            sb.append("其余：\n");
            appendRows(sb, others);
        } catch (Throwable t) {
            sb.append("- 枚举失败: ").append(t).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // 板块 C：车辆相关应用与导出组件
    // ------------------------------------------------------------------

    private static void vehicleRelatedPackages(StringBuilder sb, Context context) {
        sb.append("\n### 9.C 车辆相关应用及其导出组件\n\n");
        sb.append("导出的 provider 也许能直接查，导出的 receiver 说明有对应的广播可以收。\n");
        try {
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(
                    PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS);
            int shown = 0;
            for (PackageInfo info : packages) {
                if (!looksVehicleRelated(info.packageName)) {
                    continue;
                }
                shown++;
                sb.append("\n**").append(info.packageName).append("**");
                if (info.applicationInfo != null
                        && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    sb.append("（系统应用）");
                }
                sb.append('\n');
                appendProviders(sb, info);
                appendReceivers(sb, info);
            }
            if (shown == 0) {
                sb.append("\n- 没有匹配到名字像车辆相关的应用\n");
            } else {
                sb.append("\n共 ").append(shown).append(" 个。\n");
            }
        } catch (Throwable t) {
            sb.append("- 枚举失败: ").append(t).append('\n');
        }
    }

    private static void appendProviders(StringBuilder sb, PackageInfo info) {
        if (info.providers == null) {
            return;
        }
        for (ProviderInfo provider : info.providers) {
            if (!provider.exported) {
                continue;
            }
            sb.append("  - provider ").append(provider.authority);
            if (provider.readPermission != null) {
                sb.append("（需权限 ").append(provider.readPermission).append("）");
            } else {
                sb.append("（无读权限要求）");
            }
            sb.append('\n');
        }
    }

    private static void appendReceivers(StringBuilder sb, PackageInfo info) {
        if (info.receivers == null) {
            return;
        }
        for (ActivityInfo receiver : info.receivers) {
            if (!receiver.exported) {
                continue;
            }
            sb.append("  - receiver ").append(receiver.name).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // 板块 D：系统属性命名空间地图
    // ------------------------------------------------------------------

    private static void propertyNamespaces(StringBuilder sb) {
        sb.append("\n### 9.D 系统属性命名空间地图\n\n");
        sb.append("按前两段前缀分组，看哪一片下面东西多 —— 指出该往哪看，\n");
        sb.append("而不是一次把上千行倒出来。\n\n");
        Map<String, String> all = VehicleSignalProbe.captureProperties();
        if (all.isEmpty()) {
            sb.append("- 读不到系统属性\n");
            return;
        }
        Map<String, Integer> counts = new TreeMap<>();
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String key = entry.getKey();
            String prefix = prefixOf(key);
            Integer previous = counts.get(prefix);
            counts.put(prefix, previous == null ? 1 : previous + 1);
            if (looksVehicleRelated(key)) {
                hits.add("- " + key + " = " + truncate(entry.getValue()));
            }
        }
        Collections.sort(hits);
        sb.append("属性总数 ").append(all.size()).append("，命名空间 ")
                .append(counts.size()).append(" 个。\n\n");
        sb.append("名字像车辆相关的 ").append(hits.size()).append(" 项：\n");
        appendRows(sb, hits);
        sb.append("\n各命名空间的项数：\n");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("：")
                    .append(entry.getValue()).append('\n');
        }
    }

    private static String prefixOf(String key) {
        int first = key.indexOf('.');
        if (first < 0) {
            return key;
        }
        int second = key.indexOf('.', first + 1);
        return second < 0 ? key.substring(0, first) : key.substring(0, second);
    }

    // ------------------------------------------------------------------

    /**
     * 名字看着像不像车辆相关。
     *
     * <p>只用来<b>排序和分组</b>，不用来过滤掉任何东西 —— 每个板块都把
     * 「不像的」也一并列出来了。关键词命中的排在前面便于看，
     * 但真正的信号完全可能藏在一个名字毫不相干的键里，
     * 所以这个判断错了也不会让人漏掉什么。</p>
     */
    static boolean looksVehicleRelated(String name) {
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

    private static String truncate(String value) {
        if (value == null) {
            return "(null)";
        }
        return value.length() <= MAX_VALUE_LEN
                ? value : value.substring(0, MAX_VALUE_LEN) + "…（截断）";
    }

    private static void appendRows(StringBuilder sb, List<String> rows) {
        int limit = Math.min(rows.size(), MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            sb.append(rows.get(i)).append('\n');
        }
        if (rows.size() > limit) {
            sb.append("- …还有 ").append(rows.size() - limit).append(" 条未列出\n");
        }
    }
}
