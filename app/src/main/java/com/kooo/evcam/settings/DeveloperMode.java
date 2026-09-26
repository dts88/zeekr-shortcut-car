package com.kooo.evcam.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 开发者选项的解锁状态。
 *
 * <h3>打开之后一直开着（项目拥有者 2026-09-26 定）</h3>
 *
 * <p>重启、更新都不会关。只有三种情况会关：在「关于」里同一个位置关掉；清除应用数据；
 * 卸载重装。以前刻意不保存，装一次新版就悄悄关了 —— 开着「息屏录制」的人每次更新都失效一次，
 * 界面上还看不出来。</p>
 *
 * <p>存在单独的一份配置里（{@code developer_mode}），而且不进系统备份（见 backup_rules、
 * data_extraction_rules）—— 否则卸载重装之后，系统可能把它恢复回来。</p>
 *
 * <p>{@link #isUnlocked()} 不带参数，各处直接问；进程里第一次建 {@code AppConfig}
 * 或 Application 启动时会调 {@link #init} 把存着的值读进来。</p>
 */
public final class DeveloperMode {

    private static final String PASSWORD = "6651";
    private static final String PREFS = "developer_mode";
    private static final String KEY_UNLOCKED = "unlocked";

    private static volatile boolean unlocked;
    private static volatile boolean loaded;

    private DeveloperMode() {
    }

    /** 把存着的值读进来。只读一次，后面再调直接返回。 */
    public static void init(Context context) {
        if (loaded || context == null) {
            return;
        }
        unlocked = prefs(context).getBoolean(KEY_UNLOCKED, false);
        loaded = true;
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    /**
     * 尝试解锁；对了就存下来。
     *
     * @return 密码对不对
     */
    public static boolean unlock(Context context, String password) {
        if (!isPassword(password)) {
            return false;
        }
        set(context, true);
        return true;
    }

    /** 密码对不对。单独拿出来，测试不用碰存储。 */
    static boolean isPassword(String password) {
        return PASSWORD.equals(password);
    }

    /** 关掉，并存下来。 */
    public static void lock(Context context) {
        set(context, false);
    }

    private static void set(Context context, boolean value) {
        unlocked = value;
        loaded = true;
        prefs(context).edit().putBoolean(KEY_UNLOCKED, value).apply();
        com.kooo.evcam.blackbox.BlackBox.noteImportant(value ? "开发者模式：打开" : "开发者模式：关闭");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
