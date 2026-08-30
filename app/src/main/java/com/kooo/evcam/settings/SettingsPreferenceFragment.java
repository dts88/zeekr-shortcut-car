package com.kooo.evcam.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.PermissionSettingsFragment;
import com.kooo.evcam.ResolutionSettingsFragment;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.zeekr.AboutActivity;
import com.kooo.evcam.zeekr.DiagnosticsActivity;
import com.kooo.evcam.zeekr.RearViewMirrorService;

/**
 * 设置界面。
 *
 * <h3>为什么换成 PreferenceScreen</h3>
 *
 * <p>之前是一千九百多行手写的 LinearLayout 卡片，加上两千八百行的接线代码。
 * 每加一个设置都要重复写一遍「卡片 + 标题 + 说明 + 控件 + 找 id + 读值 + 写值 +
 * 联动显隐」，而分类、摘要、启用依赖这些框架本来就提供。</p>
 *
 * <p>其中<b>启用依赖</b>尤其值得换：以前「息屏录制要先开启动自动录制」这类关系
 * 是手写显隐逻辑维持的，写漏一处就会出现「开关能点但不生效」。
 * 现在用 {@code android:dependency} 声明，框架负责置灰。</p>
 *
 * <h3>取值一律走 AppConfig</h3>
 *
 * <p>所有 Preference 都 {@code setPersistent(false)} —— 它们<b>不自己往
 * SharedPreferences 里写</b>，读写全部经过 {@link AppConfig}。</p>
 *
 * <p>这一点是刻意的。AppConfig 的 getter/setter 里带着夹取、默认值和联动，
 * 而且它是这些设置在整个应用里唯一的读取入口。如果让 Preference 自己持久化，
 * 就多了一条写入路径，key 稍有出入就会变成「设置看着改了、实际没生效」——
 * 这个项目在这类问题上已经栽过好几次。所以 XML 里的 key 只是标识符，不是存储键。</p>
 */
public class SettingsPreferenceFragment extends PreferenceFragmentCompat {

    private static final String TAG = "SettingsPreference";

    private AppConfig appConfig;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        if (getContext() == null) {
            return;
        }
        appConfig = new AppConfig(getContext());

        onClick("pref_back_to_recording", pref -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        bindRecording();
        bindStorage();
        bindRearView();
        bindFloating();
        bindSystem();
        bindAdvanced();
        bindAbout();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 权限、存储用量这些可能在别处被改过，回到这个界面时重新读一次
        updateStorageUsage();
    }

    // ------------------------------------------------------------------ 录制

    private void bindRecording() {
        bindEnum("pref_car_model", SettingsRegistry.CAR_MODEL,
                appConfig.getCarModel(), value -> {
                    appConfig.setCarModel(value);
                    toast("视频流配置已改为「"
                            + SettingsRegistry.CAR_MODEL.displayNameOf(value) + "」，重启应用后生效");
                });

        bindEnum("pref_record_layout", SettingsRegistry.RECORD_LAYOUT,
                appConfig.getRecordLayout(), value -> appConfig.setRecordLayout(value));

        bindEnum("pref_record_fps", SettingsRegistry.RECORD_FPS,
                appConfig.getRecordFps(), value -> appConfig.setRecordFps(value));

        bindSegmentDuration();

        bindRecordingCameras();

        onClick("pref_resolution", pref -> openFragment(new ResolutionSettingsFragment()));

        bindSwitch("pref_watermark", appConfig.isTimestampWatermarkEnabled(),
                value -> appConfig.setTimestampWatermarkEnabled(value));

        bindSwitch("pref_watermark_spec", appConfig.isWatermarkSpecEnabled(),
                value -> appConfig.setWatermarkSpecEnabled(value));
    }

    /**
     * 参与录制的视频流。
     *
     * <p>选项是按<b>这台车实际有几路</b>生成的，不是写死四个 —— 车型不同路数不同，
     * 列出不存在的那几路只会让人以为漏勾了什么。</p>
     *
     * <p>至少要留一路：全不勾等于关掉录制，而关录制有它自己的开关，
     * 不该从这里绕出去。</p>
     */
    private void bindRecordingCameras() {
        MultiSelectListPreference pref = findPreference("pref_recording_cameras");
        if (pref == null) {
            return;
        }
        int cameraCount = appConfig.getCameraCount();
        String[] slots = {"front", "back", "left", "right"};

        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < slots.length && i < cameraCount; i++) {
            values.add(slots[i]);
            labels.add(appConfig.getRecordingCameraDisplayName(slots[i], i + 1));
            if (appConfig.isRecordingCameraEnabled(slots[i])) {
                selected.add(slots[i]);
            }
        }
        if (values.isEmpty()) {
            pref.setEnabled(false);
            pref.setSummary("还没探测到视频流");
            return;
        }

        pref.setPersistent(false);
        pref.setEntries(labels.toArray(new String[0]));
        pref.setEntryValues(values.toArray(new String[0]));
        pref.setValues(selected);
        pref.setSummary(describeCameras(selected, values, labels));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            @SuppressWarnings("unchecked")
            Set<String> chosen = new HashSet<>((Set<String>) newValue);
            if (chosen.isEmpty()) {
                toast("至少要保留一路；不想录的话请关掉录制");
                return false;
            }
            for (String slot : values) {
                appConfig.setRecordingCameraEnabled(slot, chosen.contains(slot));
            }
            pref.setValues(chosen);
            pref.setSummary(describeCameras(chosen, values, labels));
            return false;
        });
    }

    private String describeCameras(Set<String> chosen, List<String> values, List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (chosen.contains(values.get(i))) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(labels.get(i));
            }
        }
        return sb.length() > 0 ? sb.toString() : "未选择";
    }

    /** 分段时长存的是分钟数（int），不是枚举字符串，所以单独处理。 */
    private void bindSegmentDuration() {
        ListPreference pref = findPreference("pref_segment_duration");
        if (pref == null) {
            return;
        }
        String[] values = {"1", "3", "5", "10"};
        String[] labels = {"1 分钟", "3 分钟", "5 分钟", "10 分钟"};
        pref.setPersistent(false);
        pref.setEntries(labels);
        pref.setEntryValues(values);
        pref.setValue(String.valueOf(appConfig.getSegmentDurationMinutes()));
        pref.setSummary(pref.getEntry());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int minutes = Integer.parseInt(String.valueOf(newValue));
            appConfig.setSegmentDurationMinutes(minutes);
            pref.setValue(String.valueOf(minutes));
            pref.setSummary(pref.getEntry());
            return false;   // 值已经自己设好了，不让框架再写一遍
        });
    }

    // ------------------------------------------------------------------ 存储

    private void bindStorage() {
        ListPreference storage = findPreference("pref_storage_location");
        if (storage != null) {
            storage.setPersistent(false);
            storage.setEntries(new String[]{"U 盘（推荐）", "内置存储"});
            storage.setEntryValues(new String[]{
                    AppConfig.STORAGE_EXTERNAL_SD, AppConfig.STORAGE_INTERNAL});
            storage.setValue(appConfig.getStorageLocation());
            storage.setSummary(storage.getEntry());
            storage.setOnPreferenceChangeListener((preference, newValue) -> {
                String value = String.valueOf(newValue);
                if (AppConfig.STORAGE_INTERNAL.equals(value)) {
                    confirmInternalStorage(storage);
                } else {
                    applyStorageLocation(storage, value);
                }
                return false;
            });
        }

        bindSwitch("pref_relay_write", appConfig.isRelayWriteEnabled(),
                value -> appConfig.setRelayWriteEnabled(value));

        bindGigabyteLimit("pref_video_limit", appConfig.getVideoStorageLimitGb(),
                value -> appConfig.setVideoStorageLimitGb(value));
        bindGigabyteLimit("pref_photo_limit", appConfig.getPhotoStorageLimitGb(),
                value -> appConfig.setPhotoStorageLimitGb(value));

        updateStorageUsage();
    }

    /**
     * 换成内置存储之前先把代价说清楚。
     *
     * <p>行车记录是<b>一直在写</b>的，而闪存的写入寿命有限，车机存储通常也换不了。
     * 所以必须明确确认才生效，取消则把下拉框拨回原来那一项。</p>
     */
    private void confirmInternalStorage(ListPreference pref) {
        if (getContext() == null) {
            return;
        }
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("确定用内置存储？")
                .setMessage("行车记录会持续不断地写入数据 —— 只要在录，就一直在写。\n\n"
                        + "闪存的写入寿命是有限的，长期把车机内置存储当作记录仪的落盘位置，"
                        + "会实打实地消耗它的寿命，而车机存储通常是不可更换的。\n\n"
                        + "建议改用 U 盘：坏了随时能换，也方便直接拔下来拷走。")
                .setPositiveButton("仍然使用内置存储", (dialog, which) ->
                        applyStorageLocation(pref, AppConfig.STORAGE_INTERNAL))
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyStorageLocation(ListPreference pref, String value) {
        appConfig.setStorageLocation(value);
        pref.setValue(value);
        pref.setSummary(pref.getEntry());
        toast("存储位置已切换为「" + pref.getEntry() + "」");
        updateStorageUsage();
    }

    /** 上限用 GB 存，界面上是个数字输入框；留空或 0 表示不限制。 */
    private void bindGigabyteLimit(String key, int current, IntSetter setter) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setText(current > 0 ? String.valueOf(current) : "");
        pref.setSummary(describeLimit(current));
        pref.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int parsed = 0;
            String text = String.valueOf(newValue).trim();
            if (!text.isEmpty()) {
                try {
                    parsed = Math.max(0, Integer.parseInt(text));
                } catch (NumberFormatException e) {
                    toast("请输入数字");
                    return false;
                }
            }
            setter.set(parsed);
            pref.setText(parsed > 0 ? String.valueOf(parsed) : "");
            pref.setSummary(describeLimit(parsed));
            return false;
        });
    }

    private String describeLimit(int gigabytes) {
        return gigabytes > 0 ? gigabytes + " GB" : "不限制";
    }

    private void updateStorageUsage() {
        Preference pref = findPreference("pref_storage_usage");
        if (pref == null || getContext() == null) {
            return;
        }
        pref.setSummary("正在读取...");
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            String desc;
            try {
                desc = com.kooo.evcam.StorageHelper.getCurrentStoragePathDesc(context);
            } catch (Throwable t) {
                desc = "读取失败";
            }
            final String result = desc;
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> pref.setSummary(result));
            }
        }, "storage-usage").start();
    }

    // ------------------------------------------------------------------ 超级后视镜

    private void bindRearView() {
        bindSwitch("pref_rearview", appConfig.isRearViewEnabled(), value -> {
            appConfig.setRearViewEnabled(value);
            if (getContext() == null) {
                return;
            }
            if (value) {
                RearViewMirrorService.start(getContext());
                toast("超级后视镜已开启：中间上下滑调取景、左右划换一路，两侧拖动窗口");
            } else {
                RearViewMirrorService.stop(getContext());
            }
        });

        bindSwitch("pref_rearview_front_rear", appConfig.isRearViewFrontRearOnly(), value -> {
            appConfig.setRearViewFrontRearOnly(value);
            if (getContext() != null && appConfig.isRearViewEnabled()) {
                RearViewMirrorService.applyLaneMode(getContext());
            }
        });

        bindSwitch("pref_rearview_fisheye", appConfig.isRearViewFisheyeCorrection(), value -> {
            appConfig.setRearViewFisheyeCorrection(value);
            pushCorrection();
        });

        bindSlider("pref_rearview_fov",
                (int) FisheyeProjectionBounds.MIN, (int) FisheyeProjectionBounds.MAX,
                Math.round(appConfig.getRearViewFov()), "°", value -> {
                    appConfig.setRearViewFov(value);
                    pushCorrection();
                });

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        bindSlider("pref_rearview_width", AppConfig.REARVIEW_MIN_SIZE, screenWidth,
                appConfig.getRearViewWidth(screenWidth), " px", value -> {
                    appConfig.setRearViewSize(value, appConfig.getRearViewHeight(screenHeight),
                            screenWidth, screenHeight);
                    pushSize();
                });

        bindSlider("pref_rearview_height", AppConfig.REARVIEW_MIN_SIZE, screenHeight,
                appConfig.getRearViewHeight(screenHeight), " px", value -> {
                    appConfig.setRearViewSize(appConfig.getRearViewWidth(screenWidth), value,
                            screenWidth, screenHeight);
                    pushSize();
                });

        onClick("pref_rearview_reset", pref -> {
            appConfig.resetRearViewLayout();
            if (getContext() != null && appConfig.isRearViewEnabled()) {
                // 重开一次让新的默认值生效
                RearViewMirrorService.stop(getContext());
                RearViewMirrorService.start(getContext());
            }
            toast("后视镜取景与位置已重置");
        });
    }

    private void pushCorrection() {
        if (getContext() != null && appConfig.isRearViewEnabled()) {
            RearViewMirrorService.applyCorrection(getContext());
        }
    }

    private void pushSize() {
        if (getContext() != null && appConfig.isRearViewEnabled()) {
            RearViewMirrorService.applySize(getContext());
        }
    }

    // ------------------------------------------------------------------ 悬浮窗

    private void bindFloating() {
        SwitchPreferenceCompat floating = findPreference("pref_floating_window");
        if (floating != null) {
            floating.setPersistent(false);
            floating.setChecked(appConfig.isFloatingWindowEnabled());
            floating.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                if (getContext() == null) {
                    return false;
                }
                // 没有悬浮窗权限就打不开，先去授权而不是把开关拨上去装作开了
                if (on && !WakeUpHelper.hasOverlayPermission(getContext())) {
                    toast("请先授权悬浮窗权限");
                    WakeUpHelper.requestOverlayPermission(getContext());
                    return false;
                }
                appConfig.setFloatingWindowEnabled(on);
                if (on) {
                    FloatingWindowService.start(getContext());
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).broadcastCurrentRecordingState();
                    }
                } else {
                    FloatingWindowService.stop(getContext());
                }
                return true;
            });
        }

        // 范围取自 AppConfig 自己的常量，不另编一套 —— 让人选一个随后又被夹掉的值，
        // 就又变成「界面显示的和实际生效的不是一回事」
        bindSlider("pref_floating_size",
                AppConfig.FLOATING_SIZE_TINY, AppConfig.FLOATING_SIZE_MAX,
                appConfig.getFloatingWindowSize(), " dp",
                value -> {
                    appConfig.setFloatingWindowSize(value);
                    pushFloatingWindow();
                });
        bindSlider("pref_floating_alpha", 20, 100, appConfig.getFloatingWindowAlpha(), "%",
                value -> {
                    appConfig.setFloatingWindowAlpha(value);
                    pushFloatingWindow();
                });

        SwitchPreferenceCompat recordingFloating = findPreference("pref_recording_floating");
        if (recordingFloating != null) {
            recordingFloating.setPersistent(false);
            recordingFloating.setChecked(appConfig.isRecordingFloatingEnabled());
            recordingFloating.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                if (getContext() == null) {
                    return false;
                }
                if (on && !WakeUpHelper.hasOverlayPermission(getContext())) {
                    toast("请先授权悬浮窗权限");
                    WakeUpHelper.requestOverlayPermission(getContext());
                    return false;
                }
                appConfig.setRecordingFloatingEnabled(on);
                sendToRecordingFloating(on
                        ? RecordingFloatingService.ACTION_SHOW
                        : RecordingFloatingService.ACTION_HIDE, null);
                return true;
            });
        }

        bindSlider("pref_button_size", 32, 100,
                appConfig.getRecordingFloatingButtonSizeDp(), " dp",
                value -> {
                    appConfig.setRecordingFloatingButtonSizeDp(value);
                    pushRecordingButtonSize();
                });
        bindSlider("pref_button_text_size", 8, 24,
                appConfig.getRecordingFloatingTimeTextSizeSp(), " sp",
                value -> {
                    appConfig.setRecordingFloatingTimeTextSizeSp(value);
                    pushRecordingButtonSize();
                });

        bindSwitch("pref_recording_stats", appConfig.isRecordingStatsEnabled(),
                value -> appConfig.setRecordingStatsEnabled(value));

        bindFloatingReset();

    }

    /** 大小或透明度改了，正在显示的悬浮窗要跟着变，否则得关掉再开才看得到。 */
    private void pushFloatingWindow() {
        if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
            FloatingWindowService.sendUpdateFloatingWindow(getContext());
        }
    }

    private void pushRecordingButtonSize() {
        Intent intent = new Intent();
        intent.putExtra(RecordingFloatingService.EXTRA_BUTTON_SIZE,
                appConfig.getRecordingFloatingButtonSizeDp());
        intent.putExtra(RecordingFloatingService.EXTRA_TEXT_SIZE,
                appConfig.getRecordingFloatingTimeTextSizeSp());
        sendToRecordingFloating(RecordingFloatingService.ACTION_UPDATE_SIZE, intent);
    }

    /**
     * 给录制悬浮按钮的服务发个指令。
     *
     * <p>放到后台线程：{@code startService} 会同步走到服务的 onStartCommand，
     * 悬浮窗那边要建视图，在主线程上做容易卡住。</p>
     */
    private void sendToRecordingFloating(String action, Intent extras) {
        if (getContext() == null) {
            return;
        }
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            try {
                Intent intent = new Intent(context, RecordingFloatingService.class);
                intent.setAction(action);
                if (extras != null) {
                    intent.putExtras(extras);
                }
                context.startService(intent);
            } catch (Exception e) {
                AppLog.e(TAG, "录制悬浮服务指令失败: " + action, e);
            }
        }, "recording-floating-cmd").start();
    }

    private void bindFloatingReset() {
        onClick("pref_reset_floating", pref -> {
            appConfig.resetFloatingWindowLayout();
            toast("悬浮窗布局已重置");
        });
    }

    // ------------------------------------------------------------------ 系统

    private void bindSystem() {
        bindSwitch("pref_auto_start", appConfig.isAutoStartOnBoot(),
                value -> appConfig.setAutoStartOnBoot(value));
        bindSwitch("pref_auto_record", appConfig.isAutoStartRecording(),
                value -> appConfig.setAutoStartRecording(value));
        bindSwitch("pref_screen_off_recording", appConfig.isScreenOffRecordingEnabled(),
                value -> appConfig.setScreenOffRecordingEnabled(value));
        bindSwitch("pref_keep_alive", appConfig.isKeepAliveEnabled(),
                value -> appConfig.setKeepAliveEnabled(value));
        bindSwitch("pref_prevent_sleep", appConfig.isPreventSleepEnabled(),
                value -> appConfig.setPreventSleepEnabled(value));

        onClick("pref_permissions", pref -> openFragment(new PermissionSettingsFragment()));
    }

    // ------------------------------------------------------------------ 高级

    private void bindAdvanced() {
        bindEnum("pref_recording_mode", SettingsRegistry.RECORDING_MODE,
                appConfig.getRecordingMode(), value -> appConfig.setRecordingMode(value));

        bindSwitch("pref_force_h264", appConfig.isForceH264Encoding(),
                value -> appConfig.setForceH264Encoding(value));

        bindSwitch("pref_decouple_preview", appConfig.isDecouplePreviewEnabled(),
                value -> appConfig.setDecouplePreviewEnabled(value));

        bindEnum("pref_preview_resolution", SettingsRegistry.PREVIEW_RESOLUTION,
                appConfig.getPreviewResolution(), value -> appConfig.setPreviewResolution(value));

        onClick("pref_camera_mapping", pref -> {
            if (getContext() != null) {
                SettingsDialogs.showCameraMappingDialog(
                        getContext(), appConfig, this::updateCameraMappingSummary);
            }
        });
        updateCameraMappingSummary();

        SwitchPreferenceCompat debug = findPreference("pref_debug");
        if (debug != null && getContext() != null) {
            debug.setPersistent(false);
            debug.setChecked(AppLog.isDebugToInfoEnabled(getContext()));
            debug.setOnPreferenceChangeListener((preference, newValue) -> {
                AppLog.setDebugToInfoEnabled(getContext(), Boolean.TRUE.equals(newValue));
                return true;
            });
        }

        onClick("pref_save_logs", pref -> {
            if (getContext() == null) {
                return;
            }
            java.io.File file = AppLog.saveLogsToFile(getContext());
            toast(file != null ? "日志已保存到: " + file.getAbsolutePath() : "保存日志失败");
        });

        onClick("pref_upload_logs", pref -> {
            if (getContext() == null) {
                return;
            }
            // 没设过设备名就先问一次，否则上传上去分不清是哪台车
            if (appConfig.hasDeviceNickname()) {
                SettingsDialogs.showUploadConfirmDialog(
                        getContext(), appConfig, appConfig.getDeviceNickname());
            } else {
                SettingsDialogs.showDeviceNicknameInputDialog(getContext(), appConfig);
            }
        });
    }

    private void updateCameraMappingSummary() {
        Preference pref = findPreference("pref_camera_mapping");
        if (pref == null) {
            return;
        }
        pref.setSummary(appConfig.hasCameraOverride()
                ? "已手动指定相机映射"
                : "自动分配。多路配置下若某一路不出画面，可在这里手动指定");
    }

    // ------------------------------------------------------------------ 关于

    private void bindAbout() {
        onClick("pref_usage_guide", pref -> {
            if (getContext() != null) {
                SettingsDialogs.showUsageGuideDialog(getContext(), appConfig);
            }
        });
        onClick("pref_diagnostics", pref ->
                startActivity(new Intent(getContext(), DiagnosticsActivity.class)));
        onClick("pref_about", pref ->
                startActivity(new Intent(getContext(), AboutActivity.class)));
    }

    // ------------------------------------------------------------------ 小工具

    private interface BoolSetter {
        void set(boolean value);
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface StringSetter {
        void set(String value);
    }

    private interface Action {
        void run(Preference preference);
    }

    /** 开关：不自己持久化，读写都走 AppConfig。 */
    private void bindSwitch(String key, boolean current, BoolSetter setter) {
        SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set(Boolean.TRUE.equals(newValue));
            return true;
        });
    }

    /** 枚举：选项与显示名都来自 {@link SettingsRegistry}，声明一遍就够。 */
    private void bindEnum(String key, SettingSpec spec, String current, StringSetter setter) {
        ListPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setEntries(spec.displayNames());
        pref.setEntryValues(spec.values());
        pref.setValue(spec.sanitize(current));
        pref.setSummary(pref.getEntry());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            setter.set(value);
            pref.setValue(value);
            pref.setSummary(pref.getEntry());
            return false;
        });
    }

    /**
     * 滑块。
     *
     * <p>只在<b>松手</b>时落盘（{@code updatesContinuously = false}）——
     * 拖动过程中每一格都写一次配置、再推给正在显示的悬浮窗，是没必要的开销。</p>
     */
    private void bindSlider(String key, int min, int max, int current,
                            String unit, IntSetter setter) {
        SeekBarPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setMin(min);
        pref.setMax(Math.max(min, max));
        pref.setValue(Math.max(min, Math.min(max, current)));
        pref.setUpdatesContinuously(false);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set((Integer) newValue);
            return true;
        });
    }

    private void onClick(String key, Action action) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setOnPreferenceClickListener(preference -> {
            action.run(preference);
            return true;
        });
    }

    /** 切到另一个 Fragment（分辨率、权限这些本来就有自己的界面）。 */
    private void openFragment(androidx.fragment.app.Fragment fragment) {
        if (getActivity() == null) {
            return;
        }
        getActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void toast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    /** 视野角度的取值范围，避免这里再写一遍数字。 */
    private static final class FisheyeProjectionBounds {
        static final float MIN = com.kooo.evcam.zeekr.FisheyeProjection.MIN_FOV_DEGREES;
        static final float MAX = com.kooo.evcam.zeekr.FisheyeProjection.MAX_FOV_DEGREES;
    }
}
