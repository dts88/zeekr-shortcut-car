package com.kooo.evcam.settings;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
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
        PreferenceRows.apply(screen);
    }

    @Override
    public void onViewCreated(@androidx.annotation.NonNull android.view.View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 这一页以前漏了：分隔线和默认边距还在，和其他设置页不是一套
        PreferenceRows.styleList(this, 28, 16);
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
        PreferenceCategory basic = category(screen, context, R.string.dev_perm_group_basic);
        add(basic, context, R.string.dev_perm_camera, R.string.dev_perm_camera_why,
                hasPermission(context, Manifest.permission.CAMERA), this::openAppSettings);
        add(basic, context, R.string.dev_perm_microphone, R.string.dev_perm_microphone_why,
                hasPermission(context, Manifest.permission.RECORD_AUDIO), this::openAppSettings);
        add(basic, context, R.string.dev_perm_storage, R.string.dev_perm_storage_why,
                hasStoragePermission(context), this::openAppSettings);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(basic, context, R.string.dev_perm_notifications, R.string.dev_perm_notifications_why,
                    hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
                    this::openAppSettings);
        }
        // 行驶信息条的经纬度：LocationSource 认的就是精确定位这一条
        add(basic, context, R.string.dev_perm_location, R.string.dev_perm_location_why,
                hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION), this::openAppSettings);

        PreferenceCategory advanced = category(screen, context, R.string.dev_perm_group_advanced);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(advanced, context, R.string.dev_perm_all_files, R.string.dev_perm_all_files_why,
                    Environment.isExternalStorageManager(), this::requestAllFiles);
        }
        add(advanced, context, R.string.dev_perm_overlay, R.string.dev_perm_overlay_why,
                WakeUpHelper.hasOverlayPermission(context), this::requestOverlay);
        add(advanced, context, R.string.dev_perm_accessibility, R.string.dev_perm_accessibility_why,
                isAccessibilityEnabled(context), this::openAccessibilitySettings);
        // 开发者选项「记录可能占用摄像头的应用」要它；查法和那边是同一个
        add(advanced, context, R.string.dev_perm_usage_access, R.string.dev_perm_usage_access_why,
                com.kooo.evcam.camera.CameraHolderSuspects.hasUsageAccess(context),
                this::openUsageAccessSettings);
        add(advanced, context, R.string.dev_perm_battery, R.string.dev_perm_battery_why,
                isIgnoringBatteryOptimizations(context), this::requestIgnoreBattery);
    }

    // ------------------------------------------------------------------ 构造条目

    private PreferenceCategory category(PreferenceScreen screen, Context context, @StringRes int title) {
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
    private void add(PreferenceCategory parent, Context context, @StringRes int title,
                     @StringRes int why, boolean granted, Runnable onGrant) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setSummary(granted ? context.getString(R.string.dev_perm_granted)
                : context.getString(R.string.dev_perm_not_granted, context.getString(why)));
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

    /** 车机上未必有「使用情况访问」那一页：打不开就退到本应用的详情页。 */
    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            AppLog.w("PermissionsPreference", "打不开使用情况访问设置页，改开应用详情: " + t);
            openAppSettings();
        }
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
                    R.string.dev_perm_settings_unavailable, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
