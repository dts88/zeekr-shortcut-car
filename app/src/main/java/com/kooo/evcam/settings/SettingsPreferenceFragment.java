package com.kooo.evcam.settings;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.os.Bundle;
import android.text.InputType;
import com.kooo.evcam.profile.ProfileMigration;
import com.kooo.evcam.profile.ProfileStore;
import android.widget.TextView;
import android.widget.ScrollView;
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
import com.kooo.evcam.camera.EncodeSize;
import com.kooo.evcam.camera.TargetBitrate;
import com.kooo.evcam.zeekr.StreamLayoutTable;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CustomCameraConfigFragment;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.PermissionSettingsFragment;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.OverlayCoordinator;
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
    /** 外置卷的取值前缀，后面接它在探测结果里的下标。 */
    private static final String EXTERNAL_PREFIX = "external:";
    private List<StorageHelper.VolumeInfo> storageVolumes;

    private static final String ARG_SECTION = "section";

    /**
     * 只显示某一个分区。
     *
     * <p>分区在 {@code preferences.xml} 里是嵌套的 PreferenceScreen，
     * {@code setPreferencesFromResource} 的 rootKey 参数就是按 key 取子树用的 ——
     * 所以不需要把 XML 拆成八个文件。</p>
     */
    public static SettingsPreferenceFragment forSection(String screenKey) {
        SettingsPreferenceFragment fragment = new SettingsPreferenceFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, screenKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        String section = rootKey;
        if (section == null && getArguments() != null) {
            section = getArguments().getString(ARG_SECTION);
        }
        setPreferencesFromResource(R.xml.preferences, section);
        if (getContext() == null) {
            return;
        }
        appConfig = new AppConfig(getContext());

        bindRecording();
        bindStorage();
        bindRearView();
        bindFloating();
        bindSystem();
        bindAdvanced();
        bindDeveloper();
        bindAbout();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 权限、存储用量这些可能在别处被改过，回到这个界面时重新读一次
        updateStorageUsage();
        refreshRearViewSize();
    }

    // ------------------------------------------------------------------ 录制

    private void bindRecording() {
        bindCarModel();

        bindEnum("pref_record_layout", SettingsRegistry.RECORD_LAYOUT,
                appConfig.getRecordLayout(), value -> appConfig.setRecordLayout(value));

        bindEnum("pref_record_fps", SettingsRegistry.RECORD_FPS,
                appConfig.getRecordFps(), value -> appConfig.setRecordFps(value));
        probeMainStreamFps();

        bindSegmentDuration();

        bindRecordingCameras();

        bindResolution();

        bindEnum("pref_bitrate", SettingsRegistry.BITRATE_LEVEL,
                appConfig.getBitrateLevel(), value -> appConfig.setBitrateLevel(value),
                this::showTargetBitrate);

        bindSwitch("pref_license_plate_enabled", appConfig.isLicensePlateEnabled(),
                enabled -> {
                    appConfig.setLicensePlateEnabled(enabled);
                    showLicensePlate();
                });
        bindLicensePlate();

        // 应用名与版本是无条件盖上去的（见 MultiCameraManager.buildBrandLine），
        // 这里只是把这件事摆在界面上：开着、灰着、点不动。
        // 给一个能关的开关，等于承诺一件代码里并不打算允许的事。
        SwitchPreferenceCompat brand = findPreference("pref_watermark_brand");
        if (brand != null) {
            brand.setPersistent(false);
            brand.setChecked(true);
            brand.setEnabled(false);
        }

        bindSwitch("pref_watermark", appConfig.isTimestampWatermarkEnabled(),
                value -> appConfig.setTimestampWatermarkEnabled(value));

        bindSwitch("pref_watermark_spec", appConfig.isWatermarkSpecEnabled(),
                value -> appConfig.setWatermarkSpecEnabled(value));
    }

    /**
     * 视频流配置。
     *
     * <p>「环视+座舱3路」和「自定义」还没做完，平时不列出来 ——
     * 半成品混在正常选项里，选中之后出问题会让人以为是应用坏了。
     * 开发者选项打开时才出现。</p>
     *
     * <p>但<b>当前值一定保留</b>：万一已经停在某个隐藏选项上，把它从列表里抹掉
     * 会让下拉框显示空白，那才是真的没法收拾。</p>
     */
    private void bindCarModel() {
        ListPreference pref = findPreference("pref_car_model");
        if (pref == null) {
            return;
        }
        String current = SettingsRegistry.CAR_MODEL.sanitize(appConfig.getCarModel());
        String[] allValues = SettingsRegistry.CAR_MODEL.values();
        String[] allNames = localizedNames(SettingsRegistry.CAR_MODEL);

        List<String> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < allValues.length; i++) {
            boolean unfinished = !AppConfig.CAR_MODEL_ZEEKR_7X.equals(allValues[i]);
            if (!unfinished || DeveloperMode.isUnlocked() || allValues[i].equals(current)) {
                values.add(allValues[i]);
                names.add(allNames[i]);
            }
        }

        pref.setPersistent(false);
        pref.setEntries(names.toArray(new String[0]));
        pref.setEntryValues(values.toArray(new String[0]));
        pref.setValue(current);
        pref.setSummary(pref.getEntry());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            appConfig.setCarModel(value);
            // 「切换视频流配置」现在的含义就是「切换到另一份配置」——
            // 车型这个字段留着只是为了第一次翻译时有个输入。
            new ProfileStore(requireContext())
                    .select(ProfileMigration.presetIdFor(value));
            pref.setValue(value);
            pref.setSummary(pref.getEntry());
            toast(getString(R.string.msg_stream_changed, pref.getEntry()));
            return false;
        });
    }

    /**
     * 录制分辨率。
     *
     * <p>选项要问过相机才知道，所以是异步填的；填好之前先显示当前值，
     * 不留一个空白的下拉框。</p>
     *
     * <p>只列每一路都支持的尺寸，规则和理由见 {@link ResolutionOptions}。</p>
     */
    private void bindResolution() {
        ListPreference pref = findPreference("pref_resolution");
        if (pref == null || getContext() == null) {
            return;
        }
        pref.setPersistent(false);

        // 极氪 7X（单路合成流）下这一项是没有作用的：那一路的尺寸由合成流本身
        // 决定，代码里用 setPreferredSize 钉死，全局目标分辨率根本不会被读到。
        // 摆一个能选、选了又不生效的下拉框，比不给这个选项更糟。
        // 「环视 + 两路座舱」不同 —— 那两路座舱仍然按这里选的尺寸挑。
        if (appConfig.isZeekrCompositeModel() && !appConfig.isZeekrMultiModel()) {
            pref.setEnabled(false);
            pref.setSummary(getString(R.string.set_resolution_pinned));
            return;
        }

        pref.setSummary(appConfig.getTargetResolution());

        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            final List<String> options = ResolutionOptions.common(probeSupportedSizes(context));
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> populateResolution(pref, options));
        }, "resolution-probe").start();
    }

    private void populateResolution(ListPreference pref, List<String> options) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add(AppConfig.RESOLUTION_DEFAULT);
        labels.add(getString(R.string.opt_resolution_default));
        for (String option : options) {
            values.add(option);
            labels.add(option);
        }

        String current = appConfig.getTargetResolution();
        if (!values.contains(current)) {
            // 当前值必须留着，否则下拉框会显示空白
            values.add(current);
            labels.add(getString(R.string.opt_resolution_current, current));
        }

        pref.setEntries(labels.toArray(new String[0]));
        pref.setEntryValues(values.toArray(new String[0]));
        pref.setValue(current);
        pref.setSummary(pref.getEntry());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            appConfig.setTargetResolution(value);
            pref.setValue(value);
            pref.setSummary(pref.getEntry());
            toast(getString(R.string.msg_resolution_changed, pref.getEntry()));
            return false;
        });
    }

    /** 问每一路相机支持哪些尺寸。 */
    private List<List<int[]>> probeSupportedSizes(Context context) {
        List<List<int[]>> result = new ArrayList<>();
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return result;
            }
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }
                List<int[]> sizes = new ArrayList<>();
                Size[] supported = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                if (supported != null) {
                    for (Size size : supported) {
                        sizes.add(new int[]{size.getWidth(), size.getHeight()});
                    }
                }
                result.add(sizes);
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "探测相机分辨率失败: " + t);
        }
        return result;
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
            pref.setSummary(getString(R.string.info_no_stream_detected));
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
                toast(getString(R.string.msg_keep_one_camera));
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
        return sb.length() > 0 ? sb.toString() : getString(R.string.info_none_selected);
    }

    /** 分段时长存的是分钟数（int），不是枚举字符串，所以单独处理。 */
    private void bindSegmentDuration() {
        ListPreference pref = findPreference("pref_segment_duration");
        if (pref == null) {
            return;
        }
        String[] values = {"1", "3", "5", "10"};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = getString(R.string.opt_minutes, Integer.parseInt(values[i]));
        }
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
        bindStorageLocation();

        // 中转写入是「先写内置再搬走」，本质上就是往内置存储规律性地写，
        // 所以和内置存储同一个门槛
        SwitchPreferenceCompat relay = findPreference("pref_relay_write");
        if (relay != null) {
            relay.setPersistent(false);
            relay.setChecked(appConfig.isRelayWriteEnabled());
            if (!StorageHelper.isInternalStorageAllowed()) {
                relay.setEnabled(false);
                relay.setSummary(getString(R.string.msg_relay_dev_only));
            }
            relay.setOnPreferenceChangeListener((preference, newValue) -> {
                appConfig.setRelayWriteEnabled(Boolean.TRUE.equals(newValue));
                return true;
            });
        }

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
        // 必须带上 AlertDialogTheme：这个应用的主题下，不指定它的话
        // 按钮文字和背景同色，看着就像「弹出来了但没有确认键」
        new android.app.AlertDialog.Builder(getContext(), R.style.AlertDialogTheme)
                .setTitle(R.string.dlg_internal_title)
                .setMessage(R.string.dlg_internal_msg)
                .setPositiveButton(R.string.dlg_internal_ok, (dialog, which) ->
                        applyStorageLocation(pref, AppConfig.STORAGE_INTERNAL))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 存储位置：把<b>实际存在的卷</b>逐个列出来。
     *
     * <p>以前只有「U盘 / 内置」两项，插两个盘时没法指定是哪一个。
     * 这里按 {@link com.kooo.evcam.StorageHelper#listExternalVolumes} 的结果生成选项，
     * 每一项带上卷名和剩余/总容量 —— 要选到正确的那个盘，得先看得出它们的区别。</p>
     *
     * <p>选中某个外置卷时会把它的根目录钉到 {@code customSdCardPath}，
     * 否则检测逻辑永远落到第一个盘上。</p>
     */
    private void bindStorageLocation() {
        ListPreference pref = findPreference("pref_storage_location");
        if (pref == null || getContext() == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setSummary(getString(R.string.info_checking_storage));

        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            final List<StorageHelper.VolumeInfo> volumes =
                    StorageHelper.listExternalVolumes(context);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> populateStorageLocation(pref, volumes));
        }, "storage-volumes").start();
    }

    private void populateStorageLocation(ListPreference pref,
                                         List<StorageHelper.VolumeInfo> volumes) {
        storageVolumes = volumes;

        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < volumes.size(); i++) {
            labels.add(volumes.get(i).describe());
            // 用下标当取值：同一台车上插拔顺序会变，但选中的那一刻下标是确定的，
            // 真正被记住的是下面钉进 customSdCardPath 的根目录
            values.add(EXTERNAL_PREFIX + i);
        }
        // 内置存储照样列出来 —— 藏起来只会让人以为软件没这个能力。
        // 但标明它要开发者选项，选中时也会被拦下。
        labels.add(getString(StorageHelper.isInternalStorageAllowed()
                ? R.string.opt_internal_storage : R.string.opt_internal_storage_locked));
        values.add(AppConfig.STORAGE_INTERNAL);

        pref.setEntries(labels.toArray(new String[0]));
        pref.setEntryValues(values.toArray(new String[0]));
        pref.setValue(currentStorageValue(volumes));
        pref.setSummary(pref.getEntry() != null
                ? pref.getEntry() : getString(R.string.info_none_selected));

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            if (AppConfig.STORAGE_INTERNAL.equals(value)) {
                if (!StorageHelper.isInternalStorageAllowed()) {
                    explainInternalStorageIsGated();
                    return false;
                }
                confirmInternalStorage(pref);
            } else {
                applyStorageLocation(pref, value);
            }
            return false;
        });
        updateStorageUsage();
    }

    /** 当前生效的是哪一项。外置时要对上具体哪个卷，不能笼统算「外置」。 */
    private String currentStorageValue(List<StorageHelper.VolumeInfo> volumes) {
        if (!appConfig.isUsingExternalSdCard() || volumes.isEmpty()) {
            return AppConfig.STORAGE_INTERNAL;
        }
        String pinned = appConfig.getCustomSdCardPath();
        if (pinned != null && !pinned.isEmpty()) {
            for (int i = 0; i < volumes.size(); i++) {
                if (pinned.equals(volumes.get(i).root.getAbsolutePath())) {
                    return EXTERNAL_PREFIX + i;
                }
            }
        }
        return EXTERNAL_PREFIX + "0";
    }

    /** 说清楚为什么内置存储点不动，而不是让它默默没反应。 */
    private void explainInternalStorageIsGated() {
        if (getContext() == null) {
            return;
        }
        new android.app.AlertDialog.Builder(getContext(), R.style.AlertDialogTheme)
                .setTitle(R.string.dlg_internal_locked_title)
                .setMessage(R.string.dlg_internal_locked_msg)
                .setPositiveButton(R.string.action_got_it, null)
                .show();
    }

    private void applyStorageLocation(ListPreference pref, String value) {
        if (value.startsWith(EXTERNAL_PREFIX)) {
            int index = Integer.parseInt(value.substring(EXTERNAL_PREFIX.length()));
            if (storageVolumes != null && index < storageVolumes.size()) {
                appConfig.setCustomSdCardPath(
                        storageVolumes.get(index).root.getAbsolutePath());
            }
            appConfig.setStorageLocation(AppConfig.STORAGE_EXTERNAL_SD);
        } else {
            // 回到内置存储时清掉钉住的卷，否则下次选外置还会认着旧盘
            appConfig.setCustomSdCardPath(null);
            appConfig.setStorageLocation(AppConfig.STORAGE_INTERNAL);
        }
        pref.setValue(value);
        pref.setSummary(pref.getEntry());
        toast(getString(R.string.msg_storage_changed, pref.getEntry()));
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
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            int parsed = 0;
            String text = String.valueOf(newValue).trim();
            if (!text.isEmpty()) {
                try {
                    parsed = Math.max(0, Integer.parseInt(text));
                } catch (NumberFormatException e) {
                    toast(getString(R.string.msg_enter_number));
                    return false;
                }
            }
            setter.set(parsed);
            pref.setText(parsed > 0 ? String.valueOf(parsed) : "");
            pref.setSummary(describeLimit(parsed));
            return false;
        });
    }

    /**
     * 车牌号输入框。
     *
     * <p>输入的东西一律先过 {@link LicensePlate#sanitize}：小写转大写，
     * 空格连字符之类去掉，超过十位截断。清洗结果直接显示在下面 ——
     * 录进画面的就是这一串，不能让人以为自己敲的原样进去了。</p>
     */
    private void bindLicensePlate() {
        EditTextPreference pref = findPreference("pref_license_plate");
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setText(appConfig.getLicensePlateRaw());
        showLicensePlate();
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String raw = String.valueOf(newValue);
            String clean = LicensePlate.sanitize(raw);
            appConfig.setLicensePlate(clean);
            pref.setText(clean);
            showLicensePlate();
            if (!clean.equals(raw.trim()) && !clean.isEmpty()) {
                toast(getString(R.string.msg_plate_cleaned, clean));
            }
            return false;
        });
    }

    /** 摘要写当前车牌号；没填就说没填，并把规则写在后面。 */
    private void showLicensePlate() {
        EditTextPreference pref = findPreference("pref_license_plate");
        if (pref == null) {
            return;
        }
        String plate = appConfig.getLicensePlateRaw();
        pref.setSummary(plate.isEmpty()
                ? getString(R.string.set_plate_empty) + " · " + getString(R.string.set_plate_hint)
                : plate + " · " + getString(R.string.set_plate_hint));
    }

    private String describeLimit(int gigabytes) {
        return gigabytes > 0 ? gigabytes + " GB" : getString(R.string.info_unlimited);
    }

    private void updateStorageUsage() {
        Preference pref = findPreference("pref_storage_usage");
        if (pref == null || getContext() == null) {
            return;
        }
        pref.setSummary(getString(R.string.info_reading));
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            String desc;
            try {
                desc = StorageHelper.getCurrentStoragePathDesc(context);
            } catch (Throwable t) {
                desc = getString(R.string.info_read_failed);
            }
            final String result = desc;
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> pref.setSummary(result));
            }
        }, "storage-usage").start();
    }

    // ------------------------------------------------------------------ 超级后视镜

    private void bindRearView() {
        // 这个开关原先没查悬浮窗权限：没授权时它会拨上去、提示语还讲了手势怎么用，
        // 而服务在 onStartCommand 里就 stopSelf() 走了，屏幕上什么都没有。
        bindOverlaySwitch("pref_rearview", appConfig.isRearViewEnabled(),
                OverlayCoordinator::setRearViewEnabled, on -> {
                    if (on) {
                        toast(getString(R.string.msg_rearview_on));
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
            toast(getString(R.string.msg_rearview_reset));
        });
    }

    /**
     * 把「窗口宽度 / 高度」两根滑块拨到窗口当前的实际尺寸。
     *
     * <p>后视镜是个悬浮窗，<b>可以在设置页开着的时候被捏大捏小</b> ——
     * 滑块的值是在 {@code onCreatePreferences} 里读一次就定了的，
     * 不重读的话，界面上显示的宽高和眼前那个窗口对不上。</p>
     *
     * <p>放在 {@code onResume}：从别处回到这个界面时必然经过它。</p>
     */
    private void refreshRearViewSize() {
        if (getContext() == null) {
            return;
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        SeekBarPreference width = findPreference("pref_rearview_width");
        if (width != null) {
            width.setValue(appConfig.getRearViewWidth(screenWidth));
        }
        SeekBarPreference height = findPreference("pref_rearview_height");
        if (height != null) {
            height.setValue(appConfig.getRearViewHeight(screenHeight));
        }
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
        bindOverlaySwitch("pref_floating_window", appConfig.isFloatingWindowEnabled(),
                OverlayCoordinator::setPreviewWindowEnabled, on -> {
                    if (on && getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).broadcastCurrentRecordingState();
                    }
                });

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

        bindOverlaySwitch("pref_recording_floating", appConfig.isRecordingFloatingEnabled(),
                OverlayCoordinator::setRecordButtonEnabled, null);

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
            toast(getString(R.string.msg_floating_reset));
        });
    }

    // ------------------------------------------------------------------ 系统

    /**
     * 界面语言。
     *
     * <p>选完立刻生效：{@link Languages#apply} 走的是系统的「按应用设定语言」，
     * 由系统重新加载资源并重建界面 —— 不需要提示「重启后生效」，
     * 那种提示本身就意味着界面上显示的和实际生效的暂时不是一回事。</p>
     */
    private void bindLanguage() {
        bindEnum("pref_language", SettingsRegistry.LANGUAGE, appConfig.getLanguageMode(),
                value -> {
                    appConfig.setLanguageMode(value);
                    Languages.apply(value);
                });
    }

    private void bindSystem() {
        bindLanguage();

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

        onClick("pref_image_adjust", pref -> {
            if (getActivity() instanceof MainActivity) {
                appConfig.setImageAdjustEnabled(true);
                ((MainActivity) getActivity()).setImageAdjustEnabled(true);
                toast(getString(R.string.msg_adjust_opened));
            }
        });

        onClick("pref_image_adjust_reset", pref -> {
            if (getActivity() instanceof MainActivity) {
                com.kooo.evcam.camera.ImageAdjustManager manager =
                        ((MainActivity) getActivity()).getImageAdjustManager();
                if (manager != null) {
                    manager.resetToDefault();
                    toast(getString(R.string.msg_adjust_reset));
                }
            }
        });

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
            toast(file != null
                    ? getString(R.string.msg_logs_saved, file.getAbsolutePath())
                    : getString(R.string.msg_logs_failed));
        });
    }

    private void updateCameraMappingSummary() {
        Preference pref = findPreference("pref_camera_mapping");
        if (pref == null) {
            return;
        }
        pref.setSummary(getString(appConfig.hasCameraOverride()
                ? R.string.info_mapping_manual : R.string.info_mapping_auto));
    }

    // ------------------------------------------------------------------ 开发者选项

    /**
     * 开发者选项整块的显隐。
     *
     * <p>没解锁时把整个分类从界面上移除，而不是置灰 —— 置灰等于告诉别人
     * 「这里有东西但你用不了」，而这些本来就不该出现在普通用户的设置里。</p>
     */
    private void bindDeveloper() {
        // 这里原来先找一个 key 为 cat_developer 的分类，找不到就整个返回 ——
        // 而 0.21.0 把分区改成嵌套 PreferenceScreen 之后，这个 key 就不存在了。
        // 于是下面四个入口一个都没接上，点了毫无反应，也不报错。
        if (!DeveloperMode.isUnlocked()) {
            // 左栏已经把整块拿掉了；万一是直接跳进来的，这里也不接线
            return;
        }

        onClick("pref_permissions", pref -> openFragment(new PermissionsPreferenceFragment()));

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

        onClick("pref_permission_tools",
                pref -> openFragment(new PermissionSettingsFragment()));

        onClick("pref_blind_spot", pref -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showBlindSpotInterface();
            }
        });

        onClick("pref_supervision", pref -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).toggleSupervisionMode();
            }
        });

        // 「自定义」这档视频流配置是能选出来的，但配置它的界面一直没有入口 ——
        // 选了之后没有任何地方能配摄像头路数和映射
        onClick("pref_custom_config",
                pref -> openFragment(new CustomCameraConfigFragment()));

        bindSwitch("pref_preview_correction", appConfig.isPreviewCorrectionEnabled(), value -> {
            appConfig.setPreviewCorrectionEnabled(value);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshPreviewCorrection();
            }
        });

        onClick("pref_preview_correction_adjust", pref -> {
            if (!appConfig.isPreviewCorrectionEnabled()) {
                toast("请先打开「预览画面矫正」");
                return;
            }
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showPreviewCorrectionFloating();
            }
        });

        bindSwitch("pref_photo_via_jpeg", appConfig.isPhotoViaJpegEnabled(),
                enabled -> {
                    appConfig.setPhotoViaJpegEnabled(enabled);
                    toast(getString(R.string.msg_restart_required));
                });

        onClick("pref_profile_editor", pref -> startActivity(
                new Intent(getContext(), com.kooo.evcam.profile.ProfileEditorActivity.class)));

        onClick("pref_current_profile", pref -> showCurrentProfile());

        onClick("pref_preview_sampler", pref -> startActivity(
                new Intent(getContext(), com.kooo.evcam.zeekr.PreviewSamplerActivity.class)));

        onClick("pref_photo_test", pref -> startActivity(
                new Intent(getContext(), com.kooo.evcam.zeekr.PhotoCaptureTestActivity.class)));


        onClick("pref_preview_correction_reset", pref -> {
            appConfig.resetAllPreviewCorrection();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).refreshPreviewCorrection();
            }
            toast("预览矫正参数已重置");
        });
    }

    /**
     * 在「码率」那一项下面写出目标码率。
     *
     * <p>这个数不是一个固定值，它跟着分辨率和帧率一起变，所以只写等级名没有用。
     * 算它的是 {@link TargetBitrate}，和编码器用的是同一个函数 ——
     * 界面上写的就是实际配给编码器的那个数。</p>
     *
     * <p>录出来的实际码率会低于它：目标码率是上限，画面静止时编码器用不满。
     * 实际值印在录像角标上。</p>
     */
    private void showTargetBitrate() {
        ListPreference pref = findPreference("pref_bitrate");
        if (pref == null || getContext() == null) {
            return;
        }
        CharSequence level = pref.getEntry();
        EncodeSize encode = liveEncodeSize();
        if (encode == null || encode.width <= 0) {
            // 相机还没开起来就没有「真正在录的尺寸」，那就只写等级名，不编一个数
            pref.setSummary(level);
            return;
        }
        int fps = appConfig.getNominalFrameRate(hardwareMaxFps());
        // 编码器优先走 H.265，只有「强制 H.264」开着时才是 H.264
        boolean hevc = !appConfig.isForceH264Encoding();
        int bitrate = TargetBitrate.compute(appConfig.getEncoderQualityLevel(),
                encode.width, encode.height, fps, hevc);
        pref.setSummary(getString(R.string.set_bitrate_summary_target,
                level, TargetBitrate.format(bitrate),
                encode.toString(), fps, hevc ? "H.265" : "H.264"));
    }

    /**
     * 主视频流<b>真正编码</b>时的尺寸。
     *
     * <p>不能用「录制分辨率」那个设置：环视这一路根本不读它（尺寸由合成流决定），
     * 而且四宫格重排会把 1280×5140 拼成 2560×2560 —— 拿设置里的数去算码率，
     * 算出来的和实际配给编码器的不是一回事。</p>
     *
     * <p>所以直接问正在跑的那台相机，再套用录制链路同一个 {@link EncodeSize}。
     * 相机没开时返回 null。</p>
     */
    private EncodeSize liveEncodeSize() {
        if (!(getActivity() instanceof MainActivity)) {
            return null;
        }
        com.kooo.evcam.camera.MultiCameraManager manager =
                ((MainActivity) getActivity()).getCameraManager();
        if (manager == null) {
            return null;
        }
        com.kooo.evcam.camera.SingleCamera camera = manager.getCamera("front");
        if (camera == null || camera.getPreviewSize() == null) {
            return null;
        }
        Size source = camera.getPreviewSize();
        return EncodeSize.forSource(camera.getCameraId(), source.getWidth(), source.getHeight(),
                appConfig.isRecordGridLayout());
    }

    private static int hardwareMaxFps() {
        int declared = com.kooo.evcam.camera.CameraCapabilities.declaredMaxFps();
        return declared > 0 ? declared : AppConfig.RECORDER_MAX_FPS;
    }

    /**
     * 把主视频流声明的帧率写到「原始帧率」右边。
     *
     * <p>主视频流就是这套配置真正在录的那一路 —— 极氪 7X 上是环视合成流。
     * 「原始帧率」的含义是不限制、跟随视频流，那么这一路能给多少，
     * 就是这一档实际会得到多少，写出来才有参照。</p>
     *
     * <p>读不到就不写 —— 与其编一个数，不如什么都不写。</p>
     */
    private void probeMainStreamFps() {
        ListPreference pref = findPreference("pref_record_fps");
        if (pref == null || getContext() == null) {
            return;
        }
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            final int[] range = mainStreamFpsRange(context);
            if (!isAdded() || range == null) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                CharSequence[] entries = pref.getEntries();
                String[] values = pref.getEntryValues() == null
                        ? new String[0] : toStrings(pref.getEntryValues());
                for (int i = 0; i < values.length && i < entries.length; i++) {
                    if ("auto".equals(values[i])) {
                        entries[i] = getString(R.string.opt_fps_auto) + "（"
                                + getString(R.string.opt_fps_stream_detected, range[1]) + "）";
                    }
                }
                pref.setEntries(entries);
                pref.setSummary(pref.getEntry());
            });
        }, "main-stream-fps").start();
    }

    private static String[] toStrings(CharSequence[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = String.valueOf(values[i]);
        }
        return out;
    }

    /**
     * 主视频流声明的帧率区间 {@code {下限, 上限}}；读不到返回 null。
     *
     * <p>先找 EXTERNAL（环视合成流），找不到再退回第一台相机。</p>
     */
    private static int[] mainStreamFpsRange(Context context) {
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return null;
            }
            String[] ids = manager.getCameraIdList();
            String target = null;
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    target = id;
                    break;
                }
            }
            if (target == null && ids.length > 0) {
                target = ids[0];
            }
            if (target == null) {
                return null;
            }
            android.util.Range<Integer>[] ranges = manager.getCameraCharacteristics(target)
                    .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) {
                return null;
            }
            int low = Integer.MAX_VALUE;
            int high = 0;
            for (android.util.Range<Integer> range : ranges) {
                if (range == null || range.getLower() == null || range.getUpper() == null) {
                    continue;
                }
                low = Math.min(low, range.getLower());
                high = Math.max(high, range.getUpper());
            }
            return high > 0 ? new int[]{low, high} : null;
        } catch (Exception e) {
            AppLog.w("Settings", "读不到主视频流的帧率声明: " + e);
            return null;
        }
    }

    /**
     * 带按钮的设置对话框自己弹。
     *
     * <h3>为什么不用 androidx 自带的</h3>
     *
     * <p>androidx 的偏好对话框自己 {@code new AlertDialog.Builder(context)}，主题靠
     * {@code alertDialogTheme} 这个属性从 Activity 主题里解析。这个应用里那条路
     * <b>解析不出想要的结果</b> —— 按钮画出来是看不见的，能点，但没有形状。
     * 「车牌号输入框没有确认键」「存储上限没有保存键」都是这一件事。</p>
     *
     * <p>0.36.4 往主题里补 {@code alertDialogTheme} 是想从根上解决，结果只对
     * 代码里自己建的对话框有效。所以这里改成不依赖属性解析：把主题
     * <b>直接传进构造函数</b>，和这个应用里其他所有对话框一样 ——
     * 那条路是反复验证过能显示出按钮的。</p>
     *
     * <p>下拉框（ListPreference）不在此列：它选中即关闭，本来就没有按钮。</p>
     */
    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof EditTextPreference) {
            showTextDialog((EditTextPreference) preference);
            return;
        }
        if (preference instanceof MultiSelectListPreference) {
            showMultiSelectDialog((MultiSelectListPreference) preference);
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    /** 单行文本输入：车牌号、视频 / 图片存储上限。 */
    private void showTextDialog(EditTextPreference pref) {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setText(pref.getText());
        input.setSelectAllOnFocus(true);
        configureInput(pref.getKey(), input);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        new android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle(pref.getTitle())
                .setView(box)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String value = input.getText().toString();
                    if (pref.callChangeListener(value)) {
                        pref.setText(value);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 按 key 配键盘类型。
     *
     * <p>本来该问 preference 自己要那个 {@code OnBindEditTextListener}，
     * 但那个 getter 不是公开的。要输入什么这里本来就知道，写在一处反而更好找。</p>
     */
    private void configureInput(String key, android.widget.EditText input) {
        if ("pref_license_plate".equals(key)) {
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            input.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(LicensePlate.MAX_LENGTH)});
            return;
        }
        if ("pref_video_limit".equals(key) || "pref_photo_limit".equals(key)) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
    }

    /** 多选：参与录制的摄像头。 */
    private void showMultiSelectDialog(MultiSelectListPreference pref) {
        CharSequence[] entries = pref.getEntries();
        CharSequence[] values = pref.getEntryValues();
        if (entries == null || values == null) {
            super.onDisplayPreferenceDialog(pref);
            return;
        }
        final boolean[] checked = new boolean[values.length];
        Set<String> current = pref.getValues();
        for (int i = 0; i < values.length; i++) {
            checked[i] = current.contains(String.valueOf(values[i]));
        }

        new android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle(pref.getTitle())
                .setMultiChoiceItems(entries, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    Set<String> picked = new HashSet<>();
                    for (int i = 0; i < values.length; i++) {
                        if (checked[i]) {
                            picked.add(String.valueOf(values[i]));
                        }
                    }
                    if (pref.callChangeListener(picked)) {
                        pref.setValues(picked);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 把翻译出来的配置全文摆出来。
     *
     * <p>第 1 步只做翻译，不改取值路径。迷失一项的代价是某个设置悄悄回到
     * 默认值，得等到开车时才发现 —— 所以先让它能被逐行核对。</p>
     */
    private void showCurrentProfile() {
        if (getContext() == null) {
            return;
        }
        String text;
        try {
            text = new ProfileStore(getContext()).current().toString();
        } catch (Exception e) {
            text = "读不出来：" + e;
        }
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextSize(13f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(view);

        new android.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle(R.string.set_current_profile_title)
                .setView(scroll)
                .setPositiveButton(R.string.action_got_it, null)
                .show();
    }

    // ------------------------------------------------------------------ 关于

    private void bindAbout() {
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
    /**
     * 悬浮窗类开关。
     *
     * <p>没有悬浮窗权限时<b>不把开关拨上去</b>，而是去要权限 ——
     * 拨上去而窗口不出现，就是「界面显示的和实际生效的不是一回事」。
     * 三个开关原先各写各的，其中一个漏了这道检查。</p>
     *
     * @param toggle      真正去开 / 关的动作，返回 false 表示没开成
     * @param afterChange 开成了之后额外要做的事，可以为 null
     */
    private void bindOverlaySwitch(String key, boolean current,
                                   OverlayToggle toggle, BoolSetter afterChange) {
        SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (getContext() == null) {
                return false;
            }
            boolean on = Boolean.TRUE.equals(newValue);
            if (!toggle.apply(getContext(), on)) {
                toast(getString(R.string.msg_need_overlay));
                WakeUpHelper.requestOverlayPermission(getContext());
                return false;
            }
            if (afterChange != null) {
                afterChange.set(on);
            }
            return true;
        });
    }

    /** 开 / 关一个悬浮窗；返回是否真的按要求生效。 */
    private interface OverlayToggle {
        boolean apply(Context context, boolean enabled);
    }

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
        bindEnum(key, spec, current, setter, null);
    }

    /**
     * @param summary 自己接管摘要的写法；传 null 时摘要就是选中项的名字。
     *                <b>必须在 {@code setValue} 之后调用</b> —— 之前调的话
     *                {@code getEntry()} 拿到的还是上一个选项，而且紧接着会被
     *                默认的 setSummary 覆盖掉。「切换之后摘要消失」就是这么来的。
     */
    private void bindEnum(String key, SettingSpec spec, String current, StringSetter setter,
                          Runnable summary) {
        ListPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setEntries(localizedNames(spec));
        pref.setEntryValues(spec.values());
        pref.setValue(spec.sanitize(current));
        if (summary == null) {
            pref.setSummary(pref.getEntry());
        } else {
            summary.run();
        }
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            setter.set(value);
            pref.setValue(value);
            if (summary == null) {
                pref.setSummary(pref.getEntry());
            } else {
                summary.run();
            }
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

    /**
     * 枚举选项的显示名。
     *
     * <p>有字符串资源的用资源 —— 英文界面就是靠这个；没有的回落到
     * {@link SettingsRegistry} 里那份中文（分辨率、fps 这类本来也不需要翻译）。</p>
     *
     * <p>「原始帧率」那一项要带上实际会用的帧率，所以单独格式化。
     * 这个数必须是<b>真正会录的</b>那个，不能是写死的文字。</p>
     */
    private String[] localizedNames(SettingSpec spec) {
        String[] names = spec.displayNames();
        int[] res = spec.nameResIds();
        for (int i = 0; i < names.length; i++) {
            if (res[i] != 0) {
                names[i] = getString(res[i]);
            }
        }
        if (spec == SettingsRegistry.RECORD_FPS) {
            int auto = spec.indexOf("auto");
            if (auto >= 0) {
                names[auto] = getString(R.string.opt_fps_auto);
            }
            // 其余各档是上限而不是强制值 —— 标题里就该这么写，
            // 免得看到「30 fps」以为选了它就一定录得到 30
        }
        return names;
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

    /**
     * 打开一个自带界面的子项（权限设置这类）。
     *
     * <p>两栏布局下只换右栏，左栏的分区列表留着 —— 进了二级界面还看得见
     * 自己在设置的哪一块。外壳不在时（理论上不会）退回整屏替换。</p>
     */
    private void openFragment(androidx.fragment.app.Fragment fragment) {
        if (getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment()).openDetail(fragment);
            return;
        }
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
