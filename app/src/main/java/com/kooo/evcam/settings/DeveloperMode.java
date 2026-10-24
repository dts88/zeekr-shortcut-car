package com.kooo.evcam.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 开发者选项的解锁状态。
 *
 * <h3>打开之后一直开着（项目拥有者 2026-09-26 定）</h3>
 *
 * <p>重启、更新都不会关。只有三种情况会关：在「系统」里关闭「启用开发者模式」；清除应用数据；
 * 卸载重装。以前刻意不保存，装一次新版就悄悄关了 —— 开着「息屏录制」的人每次更新都失效一次，
 * 界面上还看不出来。</p>
 *
 * <p>存在单独的一份配置里（{@code developer_mode}），而且不进系统备份（见 backup_rules、
 * data_extraction_rules）—— 否则卸载重装之后，系统可能把它恢复回来。</p>
 *
 * <p>{@link #isUnlocked()} 不带参数，各处直接问；进程里第一次建 {@code AppConfig}
 * 或 Application 启动时会调 {@link #init} 把存着的值读进来。</p>
 *
 * <h3>关着时，里面的功能停止生效（项目所有者 2026-10-05）</h3>
 *
 * <p>设置不用各自来问这里：归开发者选项管的设置列在 {@code AppConfig.DEVELOPER_KEYS}，
 * 关着时按没存过算（普通用户的值），存着的不动，再打开就回来。直接问 {@link #isUnlocked()} 的
 * 只剩不存设置的几件事：分区本身显不显示（{@code SettingsHeadersFragment}、{@code SettingsPreferenceFragment}）、
 * 录像、照片能不能存到内置存储（{@code StorageHelper.isInternalStorageAllowed}）、没验证的车辆信号算不算数
 * （{@code InfoBar.selectable}）。诊断信息里的「发送到手机」曾经也归这里，2.10.10 起对所有人开放
 * （项目所有者 2026-10-09：「不属于开发者的功能，都能用」）。</p>
 */
public final class DeveloperMode {

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

    /** System setting: persist the mode without a password, retaining existing state on upgrades. */
    public static void setEnabled(Context context, boolean value) {
        java.util.Objects.requireNonNull(context, "context");
        unlocked = value;
        loaded = true;
        prefs(context).edit().putBoolean(KEY_UNLOCKED, value).apply();
        com.kooo.evcam.blackbox.BlackBox.noteImportant(value ? "开发者模式：打开" : "开发者模式：关闭");
        // 没验证的信号算不算数跟着它走：信息条那份快照重新滤一次
        com.kooo.evcam.telemetry.Telemetry.get().selectionChanged();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
