package com.kooo.evcam.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.update.VersionName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 设置的导出与导入（项目所有者 2026-10-19）：重装应用后一次恢复全部设置。
 *
 * <h3>导出什么</h3>
 *
 * <p>两份存储整份：设置（{@link AppConfig#PREF_NAME}，去掉运行状态，{@link AppConfig#isRuntimeState}）
 * 和视频流配置（{@link ProfileStore#FILE}）。开发者选项解没解锁、用户退出的标记、黑匣子的记号、录像意图存在别的文件里，
 * 不是设置，不导出。每一项带类型（布尔、整数、长整数、小数、文字、文字集合），导回去和原来一样。
 * 诊断报告里也带这一份（{@code DiagnosticsJson} 的 settings 字段），不截断。</p>
 *
 * <h3>放在哪</h3>
 *
 * <p>和诊断报告同一个目录（{@link StorageHelper#getExportDir}：跟着存储位置，通常是 U 盘上的 EVCam_Log），
 * 文件名 zeekr_settings_时间.json。导入也只在这个目录里找。</p>
 *
 * <h3>版本</h3>
 *
 * <p>只导入同一个大版本（版本号第一段）导出、格式号相同的文件：大版本升级后配置可以失效，不做兼容（项目所有者 2026-10-19）。
 * 同一个大版本里，旧文件里改过名的键由 AppConfig 各自的迁移接上；不认识的键写进去没人读，无害。</p>
 *
 * <h3>导入</h3>
 *
 * <p>设置里的键（运行状态之外）全部换成文件里的，视频流配置整份换掉，同步落盘；随后应用退出（{@link SettingsBackupFlow}），
 * 重新打开时一切照新设置起来 —— 界面上的值就是生效的值，不用一项一项去通知正在跑的服务。整份先读完、读对了才写，
 * 读到一半出错一项都不写。</p>
 */
public final class SettingsBackup {

    private static final String TAG = "SettingsBackup";

    /** 文件里标明「这是设置文件」。 */
    static final String KIND = "zeekr_shortcut_settings";
    /** 文件格式。结构变了就加一：格式不同的不导入（和大版本不同一样，不做兼容）。 */
    static final int FORMAT = 1;
    static final String FILE_PREFIX = "zeekr_settings_";
    static final String FILE_SUFFIX = ".json";
    /** 文件名里的时间，和诊断报告的写法一样：按名字排就是按时间排。 */
    private static final String FILE_TIME = "yyyyMMdd_HHmmss";
    /** 设置文件最大多大：比这大的不是我们导出的，不读。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    /** 一项设置的类型（SharedPreferences 能存的那几种）。 */
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_STRING_SET = "string_set";

    /** 导出、导入的两份存储。 */
    private static final List<String> STORES = Collections.unmodifiableList(
            Arrays.asList(AppConfig.PREF_NAME, ProfileStore.FILE));

    private SettingsBackup() {
    }

    // ================================================================= 导出

    /**
     * 此刻的全部设置（导出、诊断报告用）。
     *
     * @throws JSONException 组装失败（不会发生：值都是 SharedPreferences 里的基本类型）
     */
    public static JSONObject snapshot(Context context) throws JSONException {
        Context app = context.getApplicationContext();
        JSONObject root = new JSONObject();
        root.put("kind", KIND);
        root.put("format", FORMAT);
        root.put("app_version", appVersion(app));
        root.put("exported_at", System.currentTimeMillis());
        JSONObject stores = new JSONObject();
        for (String store : STORES) {
            stores.put(store, encode(store, prefs(app, store).getAll()));
        }
        root.put("stores", stores);
        return root;
    }

    /**
     * 导出到诊断报告的那个目录。写文件是 I/O，调用方在后台线程上调。
     *
     * @return 写出的文件
     */
    public static File export(Context context) throws IOException, JSONException {
        File dir = folder(context);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            throw new IOException("cannot create " + dir);
        }
        File out = new File(dir, fileName(System.currentTimeMillis()));
        byte[] bytes = snapshot(context).toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream stream = new FileOutputStream(out)) {
            stream.write(bytes);
            stream.flush();
        }
        AppLog.i(TAG, "设置已导出: " + out.getAbsolutePath() + "（" + bytes.length + " 字节）");
        com.kooo.evcam.blackbox.BlackBox.note("导出设置: " + out.getName());
        return out;
    }

    /** 导出、导入用的目录：诊断报告存的那个（跟着存储位置）。 */
    public static File folder(Context context) {
        return StorageHelper.getExportDir(context);
    }

    // ================================================================= 导入

    /** 读出来的一份设置文件：能不能导入、为什么。 */
    public static final class Backup {

        public enum Status {
            /** 能导入。 */
            OK,
            /** 别的大版本、或者别的格式导出的：不导入。 */
            OTHER_VERSION,
            /** 读不了：不是设置文件、坏了、太大。 */
            UNREADABLE
        }

        public final File file;
        public final Status status;
        /** 导出它的应用版本；读不出来是空字符串。 */
        public final String appVersion;
        /** 导出的时刻（墙钟毫秒）；读不出来是 0。 */
        public final long exportedAt;
        /** 解析好、待写回的值（只有 OK 才有）：存储名 → 键 → 值。 */
        final Map<String, Map<String, Object>> values;

        Backup(File file, Status status, String appVersion, long exportedAt,
               Map<String, Map<String, Object>> values) {
            this.file = file;
            this.status = status;
            this.appVersion = appVersion;
            this.exportedAt = exportedAt;
            this.values = values;
        }
    }

    /** 目录里的设置文件，新的在前（文件名里的时间）。读目录是 I/O：后台线程。 */
    public static List<File> list(Context context) {
        File dir = folder(context);
        File[] found = dir == null ? null : dir.listFiles((d, name) -> isSettingsFile(name));
        List<File> files = new ArrayList<>();
        if (found != null) {
            for (File file : found) {
                if (file.isFile()) {
                    files.add(file);
                }
            }
        }
        Collections.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /** 读一个文件，查种类、格式和大版本，把值整份解析好。读不了不抛：状态里说。I/O：后台线程。 */
    public static Backup read(Context context, File file) {
        String version = "";
        long exportedAt = 0L;
        try {
            if (file.length() > MAX_FILE_BYTES) {
                throw new IOException("too large: " + file.length());
            }
            JSONObject root = new JSONObject(readText(file));
            if (!KIND.equals(root.optString("kind"))) {
                throw new JSONException("not a settings file");
            }
            version = root.optString("app_version", "");
            exportedAt = root.optLong("exported_at", 0L);
            if (!compatible(root.optInt("format", -1), version, appVersion(context))) {
                return new Backup(file, Backup.Status.OTHER_VERSION, version, exportedAt, null);
            }
            JSONObject stores = root.getJSONObject("stores");
            Map<String, Map<String, Object>> values = new TreeMap<>();
            for (String store : STORES) {
                values.put(store, decode(store, stores.getJSONObject(store)));
            }
            return new Backup(file, Backup.Status.OK, version, exportedAt, values);
        } catch (Exception e) {
            AppLog.w(TAG, "读不了设置文件 " + file.getName() + ": " + e);
            return new Backup(file, Backup.Status.UNREADABLE, version, exportedAt, null);
        }
    }

    /**
     * 写回去：设置里运行状态之外的键全部换成文件里的，视频流配置整份换掉。同步落盘 —— 之后应用马上退出，异步写会丢。
     * 写文件是 I/O：后台线程。
     *
     * @return 两份都写成了
     */
    public static boolean apply(Context context, Backup backup) {
        if (backup == null || backup.status != Backup.Status.OK || backup.values == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        boolean ok = true;
        int count = 0;
        for (String store : STORES) {
            SharedPreferences prefs = prefs(app, store);
            SharedPreferences.Editor editor = prefs.edit();
            boolean settingsOnly = AppConfig.PREF_NAME.equals(store);
            for (String key : prefs.getAll().keySet()) {
                if (!settingsOnly || !AppConfig.isRuntimeState(key)) {
                    editor.remove(key);
                }
            }
            Map<String, Object> values = backup.values.get(store);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                put(editor, entry.getKey(), entry.getValue());
                count++;
            }
            ok &= editor.commit();
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("导入设置: " + backup.file.getName() + "（版本 "
                + backup.appVersion + "，" + count + " 项）" + (ok ? "" : "，写入失败"));
        return ok;
    }

    /** 当前应用的大版本（版本号第一段）；读不出来是 -1。 */
    public static int currentMajor(Context context) {
        return VersionName.major(appVersion(context));
    }

    // ================================================================= 纯函数（测试直接调）

    /** 文件格式相同、大版本相同才导入。认不出版本号的一律不导入。 */
    static boolean compatible(int format, String fileVersion, String appVersion) {
        int major = VersionName.major(fileVersion);
        return format == FORMAT && major >= 0 && major == VersionName.major(appVersion);
    }

    static boolean isSettingsFile(String name) {
        return name != null && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
    }

    static String fileName(long wallMs) {
        return FILE_PREFIX + new SimpleDateFormat(FILE_TIME, Locale.US).format(new Date(wallMs)) + FILE_SUFFIX;
    }

    /** 这一项要不要导出、导入：设置里的运行状态不要，视频流配置全要。 */
    static boolean carries(String store, String key) {
        return !AppConfig.PREF_NAME.equals(store) || !AppConfig.isRuntimeState(key);
    }

    // ================================================================= 编码

    private static JSONObject encode(String store, Map<String, ?> all) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : new TreeMap<>(all).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String type = typeOf(value);
            if (type == null || !carries(store, key)) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("type", type);
            if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object element : new TreeSet<>(stringSet(value))) {
                    array.put(element);
                }
                item.put("value", array);
            } else if (value instanceof Float) {
                // 按 Float 自己的写法存（0.1 而不是 0.10000000149011612），读回来再转成 float 一模一样
                item.put("value", Double.parseDouble(value.toString()));
            } else {
                item.put("value", value);
            }
            out.put(key, item);
        }
        return out;
    }

    private static Map<String, Object> decode(String store, JSONObject in) throws JSONException {
        Map<String, Object> out = new TreeMap<>();
        Iterator<String> keys = in.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!carries(store, key)) {
                continue;
            }
            JSONObject item = in.getJSONObject(key);
            String type = item.getString("type");
            Object value;
            switch (type) {
                case TYPE_BOOLEAN:
                    value = item.getBoolean("value");
                    break;
                case TYPE_INT:
                    value = item.getInt("value");
                    break;
                case TYPE_LONG:
                    value = item.getLong("value");
                    break;
                case TYPE_FLOAT:
                    value = (float) item.getDouble("value");
                    break;
                case TYPE_STRING:
                    value = item.getString("value");
                    break;
                case TYPE_STRING_SET:
                    JSONArray array = item.getJSONArray("value");
                    Set<String> set = new HashSet<>();
                    for (int i = 0; i < array.length(); i++) {
                        set.add(array.getString(i));
                    }
                    value = set;
                    break;
                default:
                    throw new JSONException("unknown type " + type + " for " + key);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String typeOf(Object value) {
        if (value instanceof Boolean) {
            return TYPE_BOOLEAN;
        }
        if (value instanceof Integer) {
            return TYPE_INT;
        }
        if (value instanceof Long) {
            return TYPE_LONG;
        }
        if (value instanceof Float) {
            return TYPE_FLOAT;
        }
        if (value instanceof String) {
            return TYPE_STRING;
        }
        if (value instanceof Set) {
            return TYPE_STRING_SET;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stringSet(Object value) {
        return (Set<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    // ================================================================= 杂项

    private static SharedPreferences prefs(Context app, String store) {
        return app.getSharedPreferences(store, Context.MODE_PRIVATE);
    }

    private static String appVersion(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name != null ? name : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
