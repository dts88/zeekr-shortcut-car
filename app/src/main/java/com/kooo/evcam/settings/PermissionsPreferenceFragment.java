package com.kooo.evcam.settings;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;

/**
 * 权限设置。
 *
 * <h3>为什么整个界面在代码里搭</h3>
 *
 * <p>每一项的<b>状态是算出来的</b>（查一次系统），而不是存下来的设置值，
 * 所以没有 XML 可写 —— 写死一份 XML 再逐个 findPreference 去改标题和摘要，
 * 等于把同一件事声明两遍。这里直接按检查结果生成条目。</p>
 *
 * <p>回到这个界面时重新查一遍：授权是去系统设置里点的，
 * 回来时状态多半已经变了，不刷新就会显示过期的结果。</p>
 *
 * <h3>ADB / 白名单那几个工具不在这里</h3>
 *
 * <p>它们要往屏幕上滚动输出日志，那是控制台不是设置项。
 * 那几项留在原来的界面里，从开发者选项进。</p>
 */
public class PermissionsPreferenceFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        build(screen, context);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 授权是在系统设置里点的，回来时要重新查
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null && getContext() != null) {
            screen.removeAll();
            build(screen, getContext());
        }
    }

    private void build(PreferenceScreen screen, Context context) {
        PreferenceCategory basic = category(screen, context, "基础权限");
        add(basic, context, "相机权限", "录制和拍照都要用",
                hasPermission(context, Manifest.permission.CAMERA), this::openAppSettings);
        add(basic, context, "麦克风权限", "录像带声音时要用",
                hasPermission(context, Manifest.permission.RECORD_AUDIO), this::openAppSettings);
        add(basic, context, "存储权限", "保存和读取录像、照片",
                hasStoragePermission(context), this::openAppSettings);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(basic, context, "通知权限", "显示录制状态通知",
                    hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
                    this::openAppSettings);
        }

        PreferenceCategory advanced = category(screen, context, "高级权限");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(advanced, context, "所有文件访问", "访问 U 盘上的公共目录",
                    Environment.isExternalStorageManager(), this::requestAllFiles);
        }
        add(advanced, context, "悬浮窗权限", "超级后视镜、悬浮窗、录制按钮都要用",
                WakeUpHelper.hasOverlayPermission(context), this::requestOverlay);
        add(advanced, context, "无障碍服务", "用于保活，避免后台被系统回收",
                isAccessibilityEnabled(context), this::openAccessibilitySettings);
        add(advanced, context, "使用情况访问", "判断应用是否在前台",
                hasUsageStats(context), this::openUsageStatsSettings);
        add(advanced, context, "忽略电池优化", "避免系统在后台掐掉录制",
                isIgnoringBatteryOptimizations(context), this::requestIgnoreBattery);
    }

    // ------------------------------------------------------------------ 构造条目

    private PreferenceCategory category(PreferenceScreen screen, Context context, String title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    /**
     * 加一条权限。
     *
     * <p>已授权的那条<b>不可点</b>：点了也只是又跳一次系统设置，
     * 给一个什么都不会发生的按钮不如不给。</p>
     */
    private void add(PreferenceCategory parent, Context context, String title,
                     String why, boolean granted, Runnable onGrant) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setSummary(granted ? "已授权 ✓" : "未授权 · " + why);
        preference.setSelectable(!granted);
        if (!granted) {
            preference.setOnPreferenceClickListener(p -> {
                onGrant.run();
                return true;
            });
        }
        parent.addPreference(preference);
    }

    // ------------------------------------------------------------------ 检查

    private boolean hasPermission(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
                    || hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private boolean isAccessibilityEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasUsageStats(Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 跳转

    private void openAppSettings() {
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + requireContext().getPackageName())));
    }

    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launch(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
        }
    }

    private void requestOverlay() {
        if (getContext() != null) {
            WakeUpHelper.requestOverlayPermission(getContext());
        }
    }

    private void openAccessibilitySettings() {
        launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openUsageStatsSettings() {
        launch(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    private void requestIgnoreBattery() {
        launch(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    /** 车机上未必装着对应的系统设置页，跳不过去就说一声，别崩。 */
    private void launch(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            AppLog.w("PermissionsPreference", "打不开系统设置页: " + t);
            android.widget.Toast.makeText(getContext(),
                    "这台车机上打不开对应的系统设置页", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
