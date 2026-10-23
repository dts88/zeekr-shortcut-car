package com.kooo.evcam.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import com.kooo.evcam.profile.ProfileMigration;
import com.kooo.evcam.profile.ProfileStore;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.zeekr.DiagnosticsActivity;
import com.kooo.evcam.zeekr.RearViewMirrorService;
import com.kooo.evcam.zeekr.SideViewService;

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
    /**
     * 「录制声音」打开时要录音权限（项目所有者 2026-10-20：开关打开时才要，不在启动时要）：系统弹窗的结果回到这里。
     * 只能在建这个界面时登记（registerForActivityResult 的规矩），所以放在字段上。
     */
    private final androidx.activity.result.ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    this::onMicPermission);
    /** 绑定这一页时开发者选项开没开；回到这一页时变了就重绑（见 {@link #onResume}）。 */
    private boolean boundUnlocked;
    /** 外置卷的取值前缀，后面接它在探测结果里的下标。 */
    private static final String EXTERNAL_PREFIX = "external:";
    /**
     * 设置里选的是 U 盘、眼下却一个都没探测到时那一项的取值。故意不带 {@link #EXTERNAL_PREFIX}：
     * 它后面没有下标，不能当成某个卷去解析；选它什么都不改。
     */
    private static final String EXTERNAL_MISSING = "external-missing";
    private List<StorageHelper.VolumeInfo> storageVolumes;
    /** 选择框标题下面那一行此刻的状态，挂在这一行的 extras 上（存储位置：录像保存在哪，{@link #updateSavedIn}）。 */
    private static final String EXTRA_DIALOG_STATUS = "cam_dialog_status";
    /** 超级镜子「切换方式」两个选项的值（{@link #bindSwitchMode}）。 */
    private static final String SWITCH_SWIPE = "swipe";
    private static final String SWITCH_TAP = "tap";

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

    /**
     * 嵌在分区里的 PreferenceScreen 是下一层页面（盲区画面），只从它那一行（开关加下一层）进：
     * 在上一层不列成一行 —— androidx 本来会把它画成一行，点了却没处接。
     */
    private static void hideSubPages(androidx.preference.PreferenceGroup group) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof androidx.preference.PreferenceScreen) {
                child.setVisible(false);
            } else if (child instanceof androidx.preference.PreferenceGroup) {
                hideSubPages((androidx.preference.PreferenceGroup) child);
            }
        }
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        String section = rootKey;
        if (section == null && getArguments() != null) {
            section = getArguments().getString(ARG_SECTION);
        }
        setPreferencesFromResource(R.xml.preferences, section);
        hideSubPages(getPreferenceScreen());
        if (getContext() == null) {
            return;
        }
        appConfig = new AppConfig(getContext());
        boundUnlocked = DeveloperMode.isUnlocked();

        bindRecording();
        bindStorage();
        bindRearView();
        bindSideView();
        bindFloating();
        bindInterface();
        bindSystem();
        // 开发者选项分区：没开时不接线。左栏已经把整块拿掉了；万一是直接跳进来的，点了也不起作用 ——
        // 里面的设置这时本来就按没存过算（AppConfig.DEVELOPER_KEYS）
        if (boundUnlocked) {
            bindAdvanced();
            bindDeveloper();
        }
        bindUpdate();

        // 行样式在交给列表之前套上（车机系统式：卡片行、开关在前、值在后）
        PreferenceRows.apply(getPreferenceScreen());
    }

    @Override
    public void onViewCreated(@androidx.annotation.NonNull android.view.View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PreferenceRows.styleList(this, 28, 16);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 停在开发者选项这一页时把它关了（「关于与致谢」里关的）：这一页不能再留着，换回默认分区
        if (!DeveloperMode.isUnlocked() && getArguments() != null
                && SettingsShellFragment.DEVELOPER_SECTION.equals(getArguments().getString(ARG_SECTION))
                && getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment()).showSection(SettingsShellFragment.DEFAULT_SECTION);
            return;
        }
        // 开发者选项在「关于与致谢」（另一个 Activity）里开关：回来时变了就按新的状态整页重绑 ——
        // 中转写入能不能开、内置存储那一项怎么写、开发者分区接不接线都跟着它
        if (appConfig != null && boundUnlocked != DeveloperMode.isUnlocked()) {
            onCreatePreferences(null, null);
            return;
        }
        // 权限、存储用量这些可能在别处被改过，回到这个界面时重新读一次
        updateSavedIn();
        refreshRearViewWindow();
        refreshSideViewTarget();
        refreshDriveSoundValue();
    }

    @Override
    public void onStop() {
        super.onStop();
        // 窗口编辑框是一层全屏悬浮窗：设置页退到后台时关掉、不存，不压在别的应用上面
        com.kooo.evcam.zeekr.RearViewWindowEditor.cancel();
    }

    // ------------------------------------------------------------------ 录制

    private void bindRecording() {
        bindEnum("pref_preview_layout", SettingsRegistry.PREVIEW_LAYOUT, appConfig.getPreviewSourceMode(), value -> {
            appConfig.setPreviewSourceMode(value); toast(getString(R.string.msg_restart_required));
        });
        onClick("pref_camera_sources", pref -> openFragment(new CameraSourcesFragment(), R.string.camera_sources_title));
        bindCarModel();

        // 相机与视频流的参数（每一路的三条流、每一格的摆法）都在下面这个
        // 「编辑视频流配置」里。同一件事只留一个入口：两个入口意味着
        // 迟早会出现「这边写着 30、那边写着 15」。
        onClick("pref_profile_editor",
                pref -> openFragment(new ProfileEditorFragment(), R.string.set_profile_editor_title));

        bindSwitch("pref_auto_record", appConfig.isAutoStartRecording(),
                value -> appConfig.setAutoStartRecording(value));

        // 拍照使用 JPEG 输出：锁成开，只有开发者选项开着时能改（关着时主界面在后台拍不到画面，见 AppConfig）。
        // 没开时开着、灰着、点不动，下面写「需开启开发者选项」（同「中转写入」）—— 读到的值本来就按开算（DEVELOPER_KEYS）
        bindSwitch("pref_photo_via_jpeg", appConfig.isPhotoViaJpegEnabled(),
                enabled -> {
                    appConfig.setPhotoViaJpegEnabled(enabled);
                    toast(getString(R.string.msg_restart_required));
                });
        SwitchPreferenceCompat jpeg = findPreference("pref_photo_via_jpeg");
        if (jpeg != null && !boundUnlocked) {
            jpeg.setEnabled(false);
            jpeg.setSummary(getString(R.string.msg_dev_only));
        }


        // 时间水印：时间和录制规格同开同关（项目所有者 2026-10-23），录像上的规格那一行跟着它（MultiCameraManager.planFor）
        bindSwitch("pref_watermark", appConfig.isTimestampWatermarkEnabled(),
                value -> appConfig.setTimestampWatermarkEnabled(value));

        bindLicensePlate();

        // 行驶信息条（试验项目）：开关加下一层（ui-spec §三）—— 点这一行进「系统信息」勾显示哪几项（系统页那个入口照旧），
        // 行尾写勾了几项。经纬度和 GPS 车速要定位权限，已经有了（比如车机预先授予）才用，
        // 不弹权限框（项目所有者 2026-10-06）；没有就不显示，见 LocationSource
        bindSwitch("pref_info_bar", appConfig.isInfoBarEnabled(),
                value -> appConfig.setInfoBarEnabled(value));
        onClick("pref_info_bar", pref ->
                openFragment(new VehicleInfoFragment(), R.string.set_vehicle_info_title));
        SwitchNavPreference infoBar = findPreference("pref_info_bar");
        if (infoBar != null) {
            infoBar.setValue(getString(R.string.set_info_bar_value, appConfig.getInfoBarItems().size()));
        }
    }

    /**
     * 视频流配置。
     *
     * <p>「极氪7X（环视 + 前后座舱）」0.48 起对所有人开放：座舱那两路的旋转、镜像、
     * 画面适配都做完并在车上验证过了，它不再是半成品。</p>
     *
     * <p>只有这两项（{@link SettingsRegistry#CAR_MODEL}）；「自定义」1.44.0 删了。</p>
     */
    private void bindCarModel() {
        ListPreference pref = findPreference("pref_car_model");
        if (pref == null) {
            return;
        }
        String current = SettingsRegistry.CAR_MODEL.sanitize(appConfig.getCarModel());
        String[] allValues = SettingsRegistry.CAR_MODEL.values();
        String[] allNames = localizedNames(SettingsRegistry.CAR_MODEL);

        pref.setPersistent(false);
        pref.setEntries(allNames);
        pref.setEntryValues(allValues);
        pref.setValue(current);
        pref.setSummary(pref.getEntry());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            appConfig.setCarModel(value);
            // 「切换视频流配置」的含义就是「切换到另一份配置」。车型这个字段另外还用来分开存相机映射
            // （ZeekrMultiPlan.Stored），换了它相机管线重建。
            new ProfileStore(requireContext())
                    .select(ProfileMigration.presetIdFor(value));
            pref.setValue(value);
            pref.setSummary(pref.getEntry());
            toast(getString(R.string.msg_stream_changed, pref.getEntry()));
            return false;
        });
    }

    // ------------------------------------------------------------------ 存储

    private void bindStorage() {
        bindStorageLocation();

        // 中转写入是「先写内置再搬走」，本质上就是往内置存储规律性地写，
        // 所以和内置存储同一个门槛：开发者选项关着时置灰，值也按关算（AppConfig 那道门），写着关就是关
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

        bindStorageLimits();
        bindFootageLock();
        updateSavedIn();
    }

    /**
     * 锁定影像（2026-10-23 合成一行，开关加附加项）。打开直接生效；关掉时如果锁着东西，先说清楚代价（项目所有者 2026-10-03）：
     * 关掉后它们不再受保护，空间不够时和别的录像一样从旧到新删，删掉的找不回来。行尾写锁了多少（{@link #updateFootageLocked}）。
     *
     * <p>开着时下面一排「自动锁定」：闪远光（项目所有者 2026-10-04），默认关 —— 自动锁的也占空间，锁满了就停录；
     * 锁定影像关着时不起作用（{@code AutoLock.flashEnabled} 也看它）。鸣笛：车上还读不到喇叭信号，按钮摆着、
     * 置灰按不动（项目所有者 2026-10-23），没有触发它的代码；读得到了再放开。</p>
     */
    private void bindFootageLock() {
        SwitchChipsPreference pref = findPreference("pref_footage_lock");
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(appConfig.isFootageLockEnabled());
        pref.setOptionsLabel(getString(R.string.set_footage_lock_auto));
        pref.addOption(getString(R.string.set_footage_lock_flash_chip), appConfig.isFlashLockEnabled(), on -> {
            appConfig.setFlashLockEnabled(on);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: pref_footage_lock_flash → " + (on ? "开" : "关"));
            com.kooo.evcam.storage.AutoLock.get().settingsChanged(getContext());
        });
        pref.addUnavailableOption(getString(R.string.set_footage_lock_horn_chip));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue)) {
                applyFootageLock(pref, true);
                return true;
            }
            // 先不拨：量完锁了多少再决定要不要问
            final Context context = getContext() != null ? getContext().getApplicationContext() : null;
            if (context == null) {
                return false;
            }
            new Thread(() -> {
                com.kooo.evcam.storage.FootageLocks.Usage videos =
                        com.kooo.evcam.storage.FootageLocks.usage(StorageHelper.getVideoDir(context));
                com.kooo.evcam.storage.FootageLocks.Usage photos =
                        com.kooo.evcam.storage.FootageLocks.usage(StorageHelper.getPhotoDir(context));
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> confirmFootageLockOff(pref,
                        videos.files + photos.files, videos.bytes + photos.bytes));
            }, "footage-lock-off").start();
            return false;
        });
        updateFootageLocked();
    }

    private void confirmFootageLockOff(SwitchPreferenceCompat pref, int files, long bytes) {
        if (files == 0 || getContext() == null) {
            applyFootageLock(pref, false);
            return;
        }
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_footage_lock_off_title)
                .setMessage(getResources().getQuantityString(R.plurals.dlg_footage_lock_off_msg,
                        files, files, StorageHelper.formatSize(bytes)))
                .setPositiveButton(R.string.dlg_footage_lock_off_ok, (dialog, which) -> applyFootageLock(pref, false))
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void applyFootageLock(SwitchPreferenceCompat pref, boolean on) {
        appConfig.setFootageLockEnabled(on);
        pref.setChecked(on);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: pref_footage_lock → " + (on ? "开" : "关"));
        // 闪远光自动锁定跟着它：正在录的话马上开始 / 不再看信号
        com.kooo.evcam.storage.AutoLock.get().settingsChanged(getContext());
        updateFootageLocked();
    }

    /** 锁定影像那一行的行尾：锁了几个文件（录像和照片一起数）、多大；开关关着时注明不受保护。 */
    private void updateFootageLocked() {
        SwitchChipsPreference pref = findPreference("pref_footage_lock");
        if (pref == null || getContext() == null) {
            return;
        }
        pref.setValue(getString(R.string.info_reading));
        final Context context = getContext().getApplicationContext();
        final boolean on = appConfig.isFootageLockEnabled();
        new Thread(() -> {
            com.kooo.evcam.storage.FootageLocks.Usage videos =
                    com.kooo.evcam.storage.FootageLocks.usage(StorageHelper.getVideoDir(context));
            com.kooo.evcam.storage.FootageLocks.Usage photos =
                    com.kooo.evcam.storage.FootageLocks.usage(StorageHelper.getPhotoDir(context));
            if (!isAdded()) {
                return;
            }
            int files = videos.files + photos.files;
            String size = StorageHelper.formatSize(videos.bytes + photos.bytes);
            final String result = files == 0 ? getString(R.string.set_footage_locked_none)
                    : on ? getString(R.string.set_footage_locked_value, files, size)
                    : getString(R.string.set_footage_locked_value_unprotected, files, size);
            requireActivity().runOnUiThread(() -> pref.setValue(result));
        }, "footage-locked").start();
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
        // 主题显式传：Material 对话框 + 这个主题，是实车上唯一显示得出按钮的组合
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_internal_title)
                .setMessage(R.string.dlg_internal_msg)
                .setPositiveButton(R.string.dlg_internal_ok, (dialog, which) ->
                        applyStorageLocation(pref, AppConfig.STORAGE_INTERNAL))
                .setNegativeButton(R.string.action_cancel, null));
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
        // 选的是 U 盘、盘却不在：单列一项照实写。不列的话，选中项只能落到下面的
        // 「内置存储」上 —— 看着像设置被改过，其实一直是 U 盘
        String current = currentStorageValue(volumes);
        if (EXTERNAL_MISSING.equals(current)) {
            labels.add(getString(R.string.storage_external_missing));
            values.add(EXTERNAL_MISSING);
        }
        // 内置存储照样列出来 —— 藏起来只会让人以为软件没这个能力。
        // 但标明它要开发者选项，选中时也会被拦下。
        labels.add(getString(StorageHelper.isInternalStorageAllowed()
                ? R.string.opt_internal_storage : R.string.opt_internal_storage_locked));
        values.add(AppConfig.STORAGE_INTERNAL);

        pref.setEntries(labels.toArray(new String[0]));
        pref.setEntryValues(values.toArray(new String[0]));
        // 当前值一定在列表里：某个卷、「U 盘（未检测到）」，或者一直列着的内置存储
        pref.setValue(current);
        pref.setSummary(pref.getEntry());

        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = String.valueOf(newValue);
            if (EXTERNAL_MISSING.equals(value)) {
                // 没有卷可钉，也不该借这一下改成别的：原样不动
                return false;
            }
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
        updateSavedIn();
    }

    /**
     * 当前生效的是哪一项。外置时要对上具体哪个卷，不能笼统算「外置」；
     * 选了外置却一个卷都没有时是 {@link #EXTERNAL_MISSING}，不能算成内置。
     * 开发者选项关着时，存着的内置存储按 U 盘算（{@code AppConfig.getStorageLocation}），这里写的也是 U 盘。
     */
    private String currentStorageValue(List<StorageHelper.VolumeInfo> volumes) {
        if (!appConfig.isUsingExternalSdCard()) {
            return AppConfig.STORAGE_INTERNAL;
        }
        if (volumes.isEmpty()) {
            return EXTERNAL_MISSING;
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
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_internal_locked_title)
                .setMessage(R.string.dlg_internal_locked_msg)
                .setPositiveButton(R.string.action_got_it, null));
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
        // 盘的探测结果有 5 秒缓存：不清的话，回到主界面时状态条还写着换之前那个盘的余量
        StorageHelper.clearCache();
        com.kooo.evcam.storage.StorageState.refresh(pref.getContext(), "storage-setting");
        pref.setValue(value);
        pref.setSummary(pref.getEntry());
        toast(getString(R.string.msg_storage_changed, pref.getEntry()));
        updateSavedIn();
    }

    /**
     * 存储上限（2026-10-23 合成一行）：行尾写录像、照片两个上限，点开一个框里填两项（GB），清理的规则写在框里。
     * 上限用 GB 存；留空或 0 表示不限制。
     */
    private void bindStorageLimits() {
        Preference pref = findPreference("pref_storage_limits");
        if (pref == null) {
            return;
        }
        PreferenceRows.markValue(pref);
        showStorageLimits(pref);
        pref.setOnPreferenceClickListener(preference -> {
            editStorageLimits(pref);
            return true;
        });
    }

    private void showStorageLimits(Preference pref) {
        pref.setSummary(getString(R.string.set_storage_limits_value,
                describeLimit(appConfig.getVideoStorageLimitGb()), describeLimit(appConfig.getPhotoStorageLimitGb())));
    }

    /** 一个框里两个数字输入框：录像、照片（GB）。有一个填的不是数字就都不存，提示一句。 */
    private void editStorageLimits(Preference pref) {
        Context context = requireContext();
        android.widget.EditText video = limitInput(context, appConfig.getVideoStorageLimitGb());
        android.widget.EditText photo = limitInput(context, appConfig.getPhotoStorageLimitGb());
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(limitRow(context, R.string.set_storage_limits_video, video));
        box.addView(limitRow(context, R.string.set_storage_limits_photo, photo));
        android.widget.TextView note = com.kooo.evcam.ui.CamDialogs.note(context,
                getString(R.string.set_storage_limits_message));
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);

        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_storage_limits_title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    Integer videoGb = parseLimit(video);
                    Integer photoGb = parseLimit(photo);
                    if (videoGb == null || photoGb == null) {
                        toast(getString(R.string.msg_enter_number));
                        return;
                    }
                    appConfig.setVideoStorageLimitGb(videoGb);
                    appConfig.setPhotoStorageLimitGb(photoGb);
                    showStorageLimits(pref);
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static android.widget.EditText limitInput(Context context, int current) {
        android.widget.EditText input = com.kooo.evcam.ui.CamDialogs.input(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(current > 0 ? String.valueOf(current) : "");
        input.setSelectAllOnFocus(true);
        return input;
    }

    /** 一行：名称（录像 / 照片）、输入框、单位 GB。 */
    private static android.view.View limitRow(Context context, int labelRes, android.widget.EditText input) {
        float density = context.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(6 * density), 0, Math.round(6 * density));
        android.widget.TextView label = new android.widget.TextView(context);
        label.setTextAppearance(R.style.TextAppearance_Cam_Row);
        label.setText(labelRes);
        label.setMinWidth(Math.round(96 * density));
        row.addView(label);
        row.addView(input, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView unit = new android.widget.TextView(context);
        unit.setTextAppearance(R.style.TextAppearance_Cam_Row);
        unit.setText("GB");
        unit.setPadding(Math.round(12 * density), 0, 0, 0);
        row.addView(unit);
        return row;
    }

    /** 留空是 0（不限制）；不是数字是 null。 */
    private static Integer parseLimit(android.widget.EditText input) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 熄屏录制（开发者选项，2026-10-23 和时长上限合成一行，开关加下一层）：行尾写最长多久；点这一行填小时数。
     * 小时数可带小数（24、30 都行），存成分钟（{@link #saveWakeHours}）。
     */
    private void bindScreenOffRecording() {
        SwitchNavPreference pref = findPreference("pref_screen_off_recording");
        if (pref == null) {
            return;
        }
        bindSwitch("pref_screen_off_recording", appConfig.isScreenOffRecordingEnabled(),
                value -> appConfig.setScreenOffRecordingEnabled(value));
        showWakeHours(pref);
        pref.setOnPreferenceClickListener(preference -> {
            showTextDialog("pref_screen_off_wake_hours", getString(R.string.set_screen_off_wake_title), null,
                    hoursText(appConfig.getScreenOffWakeMinutes()), text -> {
                        if (saveWakeHours(text)) {
                            showWakeHours(pref);
                        }
                    });
            return true;
        });
    }

    private void showWakeHours(SwitchNavPreference pref) {
        pref.setValue(getString(R.string.set_screen_off_wake_value, hoursText(appConfig.getScreenOffWakeMinutes())));
    }

    /**
     * 填的小时数收不收：填错、留空、填 0 或负数（折成分钟四舍五入为 0 的也算）都不收，提示「请输入大于 0 的数字」；
     * 大到分钟数存不下的，按能存的最大值收。收下了返回 true。
     */
    private boolean saveWakeHours(String text) {
        int minutes;
        try {
            double hours = Double.parseDouble(text.trim());
            // 先夹在 int 范围里再收窄：直接强转，太大的正数会绕成负数被当成「填了负数」，
            // 太小的负数反倒绕成正数被收下
            minutes = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(hours * 60)));
        } catch (NumberFormatException e) {
            minutes = 0;
        }
        if (minutes <= 0) {
            toast(getString(R.string.msg_enter_positive_number));
            return false;
        }
        appConfig.setScreenOffWakeMinutes(minutes);
        return true;
    }

    /** 分钟数写成小时：整点写「2」，不整写「1.5」。 */
    private static String hoursText(int minutes) {
        if (minutes % 60 == 0) {
            return String.valueOf(minutes / 60);
        }
        return String.format(java.util.Locale.US, "%.1f", minutes / 60.0);
    }

    /**
     * 车牌号水印（开关加下一层，ui-spec §三，项目所有者 2026-10-23）：开关管录像和照片上显不显示车牌号；
     * 点这一行改车牌号，行尾写车牌号（没填写「未填写」）。开着就一定有车牌号：还没填时打开，先弹输入框、填了才算打开；
     * 改成空的，开关跟着关。
     *
     * <p>输入的东西一律先过 {@link LicensePlate#sanitize}：小写转大写，
     * 空格连字符之类去掉，超过十位截断。清洗结果写在行尾 ——
     * 录进画面的就是这一串，不能让人以为自己敲的原样进去了。</p>
     */
    private void bindLicensePlate() {
        SwitchNavPreference pref = findPreference("pref_license_plate");
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(appConfig.isLicensePlateEnabled());
        showLicensePlate(pref);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = Boolean.TRUE.equals(newValue);
            if (on && appConfig.getLicensePlateRaw().isEmpty()) {
                editLicensePlate(pref, true);
                return false;
            }
            showPlateStamp(on);
            return true;
        });
        pref.setOnPreferenceClickListener(preference -> {
            editLicensePlate(pref, false);
            return true;
        });
    }

    /**
     * 改车牌号（输入框，提示写着规则）。
     *
     * @param turnOn 是打开开关时车牌号还空着弹的：填了就打开
     */
    private void editLicensePlate(SwitchNavPreference pref, boolean turnOn) {
        showTextDialog("pref_license_plate", getString(R.string.set_plate_title), getString(R.string.set_plate_hint),
                appConfig.getLicensePlateRaw(), raw -> {
                    String clean = LicensePlate.sanitize(raw);
                    appConfig.setLicensePlate(clean);
                    if (!clean.equals(raw.trim()) && !clean.isEmpty()) {
                        toast(getString(R.string.msg_plate_cleaned, clean));
                    }
                    boolean on = !clean.isEmpty() && (turnOn || pref.isChecked());
                    if (on != pref.isChecked()) {
                        showPlateStamp(on);
                        pref.setChecked(on);
                    }
                    showLicensePlate(pref);
                });
    }

    /** 车牌号水印开 / 关，记黑匣子（和别的开关一样）。 */
    private void showPlateStamp(boolean on) {
        appConfig.setLicensePlateEnabled(on);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: pref_license_plate → " + (on ? "开" : "关"));
    }

    /** 行尾写当前车牌号；没填写「未填写」。 */
    private void showLicensePlate(SwitchNavPreference pref) {
        String plate = appConfig.getLicensePlateRaw();
        pref.setValue(plate.isEmpty() ? getString(R.string.set_plate_empty) : plate);
    }

    private String describeLimit(int gigabytes) {
        return gigabytes > 0 ? gigabytes + " GB" : getString(R.string.info_unlimited);
    }

    /**
     * 选盘的框里标题下面那一行（2026-10-23 从一行只读信息挪来，选哪个盘之前正好要知道）：录像保存在哪个目录 ——
     * 在录写录制器实际写的目录，没在录写下一次会写的目录；此刻录不了（没有 U 盘、开发者选项没开）写「未检测到 U 盘」
     * （{@link StorageHelper#savedVideoDir}）。推荐 USB-A 口那一句是说明，写在 XML 的 dialogMessage 里，列在选项下面。
     */
    private void updateSavedIn() {
        ListPreference pref = findPreference("pref_storage_location");
        if (pref == null || getContext() == null) {
            return;
        }
        final Context context = getContext().getApplicationContext();
        final boolean recording = com.kooo.evcam.recording.RecordingCoordinator.get(requireContext()).isRecording();
        final String noDrive = getString(R.string.set_storage_usage_no_drive);
        final String failed = getString(R.string.info_read_failed);
        new Thread(() -> {
            String where;
            try {
                java.io.File dir = StorageHelper.savedVideoDir(context, recording);
                where = dir != null ? dir.getAbsolutePath() : noDrive;
            } catch (Throwable t) {
                where = failed;
            }
            final String found = where;
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    if (isAdded()) {
                        pref.getExtras().putCharSequence(EXTRA_DIALOG_STATUS,
                                getString(R.string.set_storage_saved_in, found));
                    }
                });
            }
        }, "storage-saved-in").start();
    }

    // ------------------------------------------------------------------ 超级镜子

    /**
     * 超级镜子（2026-10-23 整理，ui-spec §十一）：总开关一行带两个附加项（方向标签、点按切换）；鱼眼校正和视野角度合成一行
     * （开关加下一层，{@link #bindFisheye}）；窗口的宽、高和重置合成一行（值加一个按钮，{@link #bindRearViewWindow}）。
     */
    private void bindRearView() {
        // 这个开关原先没查悬浮窗权限：没授权时它会拨上去、提示语还讲了手势怎么用，
        // 而服务在 onStartCommand 里就 stopSelf() 走了，屏幕上什么都没有。
        bindOverlaySwitch("pref_rearview", appConfig.isRearViewEnabled(),
                OverlayCoordinator::setRearViewEnabled, on -> {
                    if (on) {
                        toast(getString(R.string.msg_rearview_on));
                        com.kooo.evcam.ui.RearViewGuide.showOnce(getActivity());
                    }
                });
        SwitchChipsPreference mirror = findPreference("pref_rearview");
        if (mirror != null) {
            // 第 0 项（也是唯一一项）方向标签：重置之后照这个下标改回来，见 resetRearView
            mirror.addOption(getString(R.string.set_rearview_camera_label_title),
                    appConfig.isRearViewCameraLabelEnabled(), on -> {
                        appConfig.setRearViewCameraLabelEnabled(on);
                        com.kooo.evcam.blackbox.BlackBox.noteImportant(
                                "开关变更: pref_rearview_camera_label → " + (on ? "开" : "关"));
                        RearViewMirrorService.refreshCameraLabel();
                    });
        }
        bindSwitchMode();

        // 不跟着开关走：还没打开后视镜之前，也该能先看看它怎么用
        onClick("pref_rearview_guide",
                pref -> com.kooo.evcam.ui.RearViewGuide.show(getActivity()));

        // 切换范围（项目所有者 2026-10-19，取代「只显示前后视」开关）
        bindSegmented("pref_rearview_views", SettingsRegistry.REARVIEW_VIEWS,
                appConfig.getRearViewViews().key, value -> {
                    appConfig.setRearViewViews(com.kooo.evcam.zeekr.MirrorViews.fromKey(value));
                    applyRearViewViews();
                });

        bindFisheye();
        bindRearViewWindow();
    }

    /**
     * 切换方式：滑动切换 / 点按切换，二选一，默认滑动切换（项目所有者 2026-10-23）。存的还是原来那个开关
     * （{@code AppConfig.isRearViewButtonMode}，开 = 点按切换）—— 点按切换时横划不换路，两样本来就只能用一样；
     * 所以这里不走 {@link SettingsRegistry}（那里的设置存的是选项的值），两个选项直接写在这。
     * 选到点按切换时提示一句怎么点。
     */
    private void bindSwitchMode() {
        SegmentedPreference pref = findPreference("pref_rearview_switch_mode");
        if (pref == null) {
            return;
        }
        pref.setOptions(new CharSequence[]{
                getString(R.string.opt_rearview_switch_swipe), getString(R.string.opt_rearview_switch_tap)},
                new String[]{SWITCH_SWIPE, SWITCH_TAP});
        pref.setValue(appConfig.isRearViewButtonMode() ? SWITCH_TAP : SWITCH_SWIPE);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean tap = SWITCH_TAP.equals(newValue);
            appConfig.setRearViewButtonMode(tap);
            com.kooo.evcam.blackbox.BlackBox.noteImportant(
                    "设置变更: pref_rearview_switch_mode → " + (tap ? "点按切换" : "滑动切换"));
            if (getContext() == null) {
                return true;
            }
            if (tap) {
                Toast.makeText(getContext(), R.string.set_rearview_button_mode_summary, Toast.LENGTH_LONG).show();
            }
            if (appConfig.isRearViewEnabled()) {
                RearViewMirrorService.applyButtonMode(getContext());
            }
            return true;
        });
    }

    /** 鱼眼校正（开关加下一层）：行首的开关管校正开不开；行尾写视野角度，点这一行在框里拖（{@link #editFov}）。 */
    private void bindFisheye() {
        SwitchNavPreference pref = findPreference("pref_rearview_fisheye");
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(appConfig.isRearViewFisheyeCorrection());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = Boolean.TRUE.equals(newValue);
            appConfig.setRearViewFisheyeCorrection(on);
            com.kooo.evcam.blackbox.BlackBox.noteImportant(
                    "开关变更: pref_rearview_fisheye → " + (on ? "开" : "关"));
            pushCorrection();
            return true;
        });
        showFov(pref);
        pref.setOnPreferenceClickListener(preference -> {
            editFov(pref);
            return true;
        });
    }

    private void showFov(SwitchNavPreference pref) {
        pref.setValue(getString(R.string.set_rearview_fisheye_value, Math.round(appConfig.getRearViewFov())));
    }

    /**
     * 视野角度：框里一根拖动条，拖的时候窗口当场跟着变（看得见效果），「保存」才算数 ——
     * 取消、点框外、返回键都回到原来的角度（{@link SliderDialog}）。说明写在拖动条下面。
     */
    private void editFov(SwitchNavPreference pref) {
        SliderDialog.Slider fov = new SliderDialog.Slider(null,
                (int) FisheyeProjectionBounds.MIN, (int) FisheyeProjectionBounds.MAX,
                Math.round(appConfig.getRearViewFov()), "°", getString(R.string.set_rearview_fov_summary),
                true, true, value -> {
                    appConfig.setRearViewFov(value);
                    pushCorrection();
                });
        SliderDialog.show(requireContext(), getString(R.string.set_rearview_fov_title),
                java.util.Collections.singletonList(fov), () -> showFov(pref));
    }

    /**
     * 窗口（值加一个按钮）：行尾写宽和高；点这一行在整个屏幕上调（{@link com.kooo.evcam.zeekr.RearViewWindowEditor}，
     * 只存宽和高）；行尾「重置」就是原来的「重置窗口与画面」，先问一句 —— 重置的不止窗口，说明里列着。
     */
    private void bindRearViewWindow() {
        ValueActionPreference pref = findPreference("pref_rearview_window");
        if (pref == null) {
            return;
        }
        showRearViewWindow(pref);
        pref.setOnPreferenceClickListener(preference -> {
            if (getContext() != null) {
                com.kooo.evcam.zeekr.RearViewWindowEditor.show(requireContext(), appConfig, (width, height) -> {
                    if (isAdded()) {
                        showRearViewWindow(pref);
                    }
                });
            }
            return true;
        });
        pref.setAction(getString(R.string.action_reset), this::confirmRearViewReset);
    }

    /** 宽和高照超级镜子窗口自己用的那份屏幕尺寸夹（应用的，不是这个界面的），写出来的就是窗口实际的大小。 */
    private void showRearViewWindow(ValueActionPreference pref) {
        android.util.DisplayMetrics metrics =
                requireContext().getApplicationContext().getResources().getDisplayMetrics();
        pref.setValue(getString(R.string.set_rearview_window_value,
                appConfig.getRearViewWidth(metrics.widthPixels), appConfig.getRearViewHeight(metrics.heightPixels)));
    }

    /** 重置窗口与画面：重置的不止窗口，所以先问一句；主按钮照「恢复初值」用红色（CamDialogs）。 */
    private void confirmRearViewReset() {
        if (getContext() == null) {
            return;
        }
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(
                requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_rearview_reset_title)
                .setMessage(R.string.set_rearview_reset_summary)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> resetRearView())
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 恢复默认之后，这一页上被它改到的几行都照新的值显示（以前鱼眼校正、视野角度两行不跟着变）。 */
    private void resetRearView() {
        appConfig.resetRearViewLayout();
        SwitchChipsPreference mirror = findPreference("pref_rearview");
        if (mirror != null) {
            mirror.setOptionChecked(0, appConfig.isRearViewCameraLabelEnabled());
        }
        SegmentedPreference views = findPreference("pref_rearview_views");
        if (views != null) {
            views.setValue(appConfig.getRearViewViews().key);
        }
        SwitchNavPreference fisheye = findPreference("pref_rearview_fisheye");
        if (fisheye != null) {
            fisheye.setChecked(appConfig.isRearViewFisheyeCorrection());
            showFov(fisheye);
        }
        refreshRearViewWindow();
        applyRearViewViews();
        if (getContext() != null && appConfig.isRearViewEnabled()) {
            // 重开一次让新的默认值生效
            RearViewMirrorService.stop(getContext());
            RearViewMirrorService.start(getContext());
        }
        toast(getString(R.string.msg_rearview_reset));
    }

    /**
     * 窗口那一行的宽和高：超级镜子是个悬浮窗，<b>可以在设置页开着的时候被捏大捏小</b>，
     * 回到这一页时（{@code onResume}）重读一次。
     */
    private void refreshRearViewWindow() {
        ValueActionPreference pref = findPreference("pref_rearview_window");
        if (pref != null && getContext() != null) {
            showRearViewWindow(pref);
        }
    }

    /**
     * 切换范围变了（选了、或者重置了）。「后视 + 后座舱」要管线里有后座舱这一路：已经有管线时问一次
     * （{@link com.kooo.evcam.camera.CameraManagerHolder#getOrInit}）—— 换到这一档而管线里还没有后座舱时，管线当场放掉重建，
     * 主界面、超级镜子照「放掉重建了」接上新的那一份（在录的话那一段到头、自动接回）；换出这一档不重建。再叫醒调度（超级镜子要不要
     * 后座舱是它每一轮读的事实），正在显示的窗口换环、接上或撤回后座舱的画布。
     */
    private void applyRearViewViews() {
        android.content.Context context = getContext();
        if (context == null) {
            return;
        }
        com.kooo.evcam.camera.CameraManagerHolder holder = com.kooo.evcam.camera.CameraManagerHolder.getInstance();
        if (holder.getCameraManager() != null) {
            holder.getOrInit(context);
        }
        com.kooo.evcam.camera.ChannelScheduler.wakeAll();
        if (appConfig.isRearViewEnabled()) {
            RearViewMirrorService.applyLaneMode(context);
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

    // ------------------------------------------------------------------ 盲区画面

    /**
     * 超级镜子分区里的一行：单独的窗口、单独的开关，不挂在超级镜子的开关下面（项目所有者 2026-10-16）。
     * 2026-10-23 起是开关加下一层：行尾写显示位置，点这一行进「盲区画面」页（preferences.xml 里嵌着的 screen_side_view），
     * 显示位置、最低车速和 HUD 画面一组（2026-10-21）都在那一页。
     */
    private void bindSideView() {
        bindOverlaySwitch("pref_side_view", appConfig.isSideViewEnabled(),
                OverlayCoordinator::setSideViewEnabled, null);
        SwitchNavPreference row = findPreference("pref_side_view");
        if (row != null) {
            showSideViewTarget(row);
            row.setOnPreferenceClickListener(preference -> {
                openFragment(SettingsPreferenceFragment.forSection("screen_side_view"), R.string.set_side_view_title);
                return true;
            });
        }
        bindSegmented("pref_side_view_target", SettingsRegistry.SIDE_VIEW_TARGET,
                appConfig.getSideViewTarget(), value -> {
                    appConfig.setSideViewTarget(value);
                    syncSideViewRows();
                    SideViewService.applyConfig();
                });
        bindSlider("pref_side_view_min_speed", 0, AppConfig.SIDE_VIEW_MAX_MIN_SPEED,
                appConfig.getSideViewMinSpeed(), " km/h", value -> {
                    appConfig.setSideViewMinSpeed(value);
                    SideViewService.applyConfig();
                });

        bindSwitch("pref_hud_mirror", appConfig.isHudMirrored(), value -> {
            appConfig.setHudMirrored(value);
            SideViewService.applyConfig();
        });
        bindSlider("pref_hud_height", AppConfig.HUD_MIN_HEIGHT, AppConfig.HUD_MAX_HEIGHT, appConfig.getHudHeight(), "%",
                value -> {
                    appConfig.setHudHeight(value);
                    SideViewService.applyConfig();
                });
        bindSlider("pref_hud_shape", AppConfig.HUD_MIN_SHAPE, AppConfig.HUD_MAX_SHAPE, appConfig.getHudShape(), "%",
                value -> {
                    appConfig.setHudShape(value);
                    SideViewService.applyConfig();
                });
        bindSlider("pref_hud_edge", 0, 100, appConfig.getHudEdge(), "%", value -> {
            appConfig.setHudEdge(value);
            SideViewService.applyConfig();
        });
        bindSlider("pref_hud_vertical", 0, 100, appConfig.getHudVertical(), "%", value -> {
            appConfig.setHudVertical(value);
            SideViewService.applyConfig();
        });
        bindSlider("pref_hud_opacity", AppConfig.HUD_MIN_OPACITY, 100, appConfig.getHudOpacity(), "%", value -> {
            appConfig.setHudOpacity(value);
            SideViewService.applyConfig();
        });
        bindSlider("pref_hud_contrast", AppConfig.HUD_MIN_CONTRAST, AppConfig.HUD_MAX_CONTRAST,
                appConfig.getHudContrast(), "%", value -> {
                    appConfig.setHudContrast(value);
                    SideViewService.applyConfig();
                });
        bindSlider("pref_hud_contrast_night", AppConfig.HUD_MIN_CONTRAST, AppConfig.HUD_MAX_CONTRAST,
                appConfig.getHudContrastNight(), "%", value -> {
                    appConfig.setHudContrastNight(value);
                    SideViewService.applyConfig();
                });
        bindSlider("pref_hud_night", AppConfig.HUD_MIN_NIGHT, 100, appConfig.getHudNight(), "%", value -> {
            appConfig.setHudNight(value);
            SideViewService.applyConfig();
        });
        bindSlider("pref_hud_fade", 0, AppConfig.HUD_MAX_FADE, appConfig.getHudFade(), "%", value -> {
            appConfig.setHudFade(value);
            SideViewService.applyConfig();
        });
        syncSideViewRows();
    }

    /** 盲区画面那一行的行尾：显示位置。 */
    private void showSideViewTarget(SwitchNavPreference row) {
        row.setValue(nameOf(SettingsRegistry.SIDE_VIEW_TARGET, appConfig.getSideViewTarget()));
    }

    /** 某个选项在界面上叫什么（有翻译用翻译，{@link #localizedNames}）；不认得的值照默认那一项。 */
    private String nameOf(SettingSpec spec, String value) {
        int index = spec.indexOf(spec.sanitize(value));
        return index >= 0 ? localizedNames(spec)[index] : "";
    }

    /** 从「盲区画面」页回来时显示位置可能改过。 */
    private void refreshSideViewTarget() {
        SwitchNavPreference row = findPreference("pref_side_view");
        if (row != null && getContext() != null) {
            showSideViewTarget(row);
        }
    }

    /**
     * 显示位置决定哪些可用：只有中控屏时 HUD 画面那一组不可用，只有 HUD 时最低车速不可用（它只管中控屏）。
     * 这一页和盲区画面的开关不在同一页，开关关着时这些照样能先调好。
     */
    private void syncSideViewRows() {
        String target = appConfig.getSideViewTarget();
        Preference hud = findPreference("cat_side_view_hud");
        if (hud != null) {
            hud.setEnabled(!AppConfig.SIDE_VIEW_TARGET_CENTER.equals(target));
        }
        Preference minSpeed = findPreference("pref_side_view_min_speed");
        if (minSpeed != null) {
            minSpeed.setEnabled(!AppConfig.SIDE_VIEW_TARGET_HUD.equals(target));
        }
    }

    // ------------------------------------------------------------------ 悬浮窗

    /**
     * 悬浮按钮（2026-10-23 整理，ui-spec §十一）：总开关一行带「显示录制时长」「锁定位置」两个附加项；点击、长按各一行；
     * 按钮大小、透明度、时长文字大小合成一行「外观」（值加一个按钮：点这一行在框里拖、按钮跟着变，行尾「重置」）。
     */
    private void bindFloating() {
        // 按钮显示出来时自己问协调器在不在录，这里不用再推状态
        bindOverlaySwitch("pref_recording_floating", appConfig.isRecordingFloatingEnabled(),
                OverlayCoordinator::setRecordButtonEnabled, null);
        SwitchChipsPreference floating = findPreference("pref_recording_floating");
        if (floating != null) {
            floating.addOption(getString(R.string.set_floating_duration_title),
                    appConfig.isFloatingDurationVisible(), on -> {
                        appConfig.setFloatingDurationVisible(on);
                        com.kooo.evcam.blackbox.BlackBox.noteImportant(
                                "开关变更: pref_floating_duration → " + (on ? "开" : "关"));
                        pushFloatingStyle();
                    });
            floating.addOption(getString(R.string.set_floating_lock_title),
                    appConfig.isFloatingPositionLocked(), on -> {
                        appConfig.setFloatingPositionLocked(on);
                        com.kooo.evcam.blackbox.BlackBox.noteImportant(
                                "开关变更: pref_floating_lock → " + (on ? "开" : "关"));
                    });
        }

        bindFloatingAction("pref_floating_tap", appConfig.getFloatingTapAction(),
                value -> appConfig.setFloatingTapAction(value));
        bindFloatingAction("pref_floating_long_press", appConfig.getFloatingLongPressAction(),
                value -> appConfig.setFloatingLongPressAction(value));

        ValueActionPreference appearance = findPreference("pref_floating_appearance");
        if (appearance != null) {
            showFloatingAppearance(appearance);
            appearance.setOnPreferenceClickListener(preference -> {
                editFloatingAppearance(appearance);
                return true;
            });
            appearance.setAction(getString(R.string.action_reset), () -> confirmFloatingReset(appearance));
        }
    }

    /** 外观那一行的行尾：按钮大小和透明度。 */
    private void showFloatingAppearance(ValueActionPreference pref) {
        pref.setValue(getString(R.string.set_floating_appearance_value,
                appConfig.getRecordingFloatingButtonSizeDp(), appConfig.getFloatingWindowAlpha()));
    }

    /**
     * 外观：一个框里三根拖动条（{@link SliderDialog}），松手时按钮当场跟着变，「保存」才算数。
     * 按钮大小整档上移 50%（原来 32–100）：最小的也要比原来的最小大一半。时长文字大小随「显示录制时长」，没开时灰着。
     */
    private void editFloatingAppearance(ValueActionPreference pref) {
        java.util.List<SliderDialog.Slider> sliders = java.util.Arrays.asList(
                new SliderDialog.Slider(getString(R.string.set_button_size_title), 48, 150,
                        appConfig.getRecordingFloatingButtonSizeDp(), " dp", null, true, false, value -> {
                            appConfig.setRecordingFloatingButtonSizeDp(value);
                            pushFloatingStyle();
                        }),
                new SliderDialog.Slider(getString(R.string.set_floating_alpha_title), 20, 100,
                        appConfig.getFloatingWindowAlpha(), "%", null, true, false, value -> {
                            appConfig.setFloatingWindowAlpha(value);
                            pushFloatingStyle();
                        }),
                new SliderDialog.Slider(getString(R.string.set_button_text_size_title), 8, 24,
                        appConfig.getRecordingFloatingTimeTextSizeSp(), " sp", null,
                        appConfig.isFloatingDurationVisible(), false, value -> {
                            appConfig.setRecordingFloatingTimeTextSizeSp(value);
                            pushFloatingStyle();
                        }));
        SliderDialog.show(requireContext(), getString(R.string.set_group_appearance), sliders,
                () -> showFloatingAppearance(pref));
    }

    /** 重置悬浮按钮：位置也回去（不止这一行），所以先问一句；主按钮照「恢复初值」用红色（CamDialogs）。 */
    private void confirmFloatingReset(ValueActionPreference pref) {
        if (getContext() == null) {
            return;
        }
        com.kooo.evcam.ui.CamDialogs.showDestructive(new MaterialAlertDialogBuilder(
                requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_floating_reset_title)
                .setMessage(R.string.set_reset_floating_summary)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    // 以前清的是旧「主屏悬浮窗」那几个键，合并后的按钮根本不读它们 ——
                    // 点了提示「已重置」，按钮纹丝不动。现在清按钮自己的位置、大小、字号，
                    // 并让它当场挪回默认位置（右上角）
                    appConfig.resetRecordingFloatingLayout();
                    sendToRecordingFloating(RecordingFloatingService.ACTION_RESET_POSITION, null);
                    showFloatingAppearance(pref);
                    toast(getString(R.string.msg_floating_reset));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * 单击 / 长按能选哪几件事。
     *
     * <p>选项文字在 strings.xml，存下去的是 {@link FloatingAction} 里那几个 key，
     * 两边靠同一个数组下标对上 —— 所以这里必须一起写，不能一边加一边忘。</p>
     */
    private void bindFloatingAction(String key, String current, StringSetter onPick) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return;
        }
        ListPreference list = (ListPreference) pref;
        FloatingAction[] actions = FloatingAction.values();
        String[] values = new String[actions.length];
        String[] labels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) {
            values[i] = actions[i].key;
            labels[i] = getString(labelOf(actions[i]));
        }
        list.setEntries(labels);
        list.setEntryValues(values);
        list.setValue(FloatingAction.fromKey(current).key);
        list.setSummaryProvider(p -> ((ListPreference) p).getEntry());
        list.setOnPreferenceChangeListener((p, value) -> {
            onPick.set(String.valueOf(value));
            return true;
        });
    }

    /**
     * 每个动作在界面上叫什么。
     *
     * <p>两个数组从 {@link FloatingAction#values()} 一起长出来，所以数量不会对不上；
     * 新增一个动作时，忘了在这里加分支的后果是它显示成「打开主界面」——
     * 不会崩，但会说谎，所以新增时两处一起改。</p>
     */
    private static int labelOf(FloatingAction action) {
        switch (action) {
            case TOGGLE_RECORDING:
                return R.string.floating_action_toggle_recording;
            case TAKE_PHOTO:
                return R.string.floating_action_take_photo;
            case TOGGLE_MIRROR:
                return R.string.floating_action_toggle_mirror;
            case LOCK_FOOTAGE:
                return R.string.floating_action_lock_footage;
            case TOGGLE_DRIVE_SOUND:
                return R.string.floating_action_toggle_drive_sound;
            case OPEN_APP:
            default:
                return R.string.floating_action_open_app;
        }
    }


    /**
     * 外观改了，让按钮就地重读一遍配置。
     *
     * <p>以前这里发的是 {@code ACTION_UPDATE_SIZE} 加两个 extra，而服务那边
     * 是用<b>广播</b>接收器在等这个 action —— 这边却是 {@code startService}。
     * 两条路对不上，所以两个滑块一直没有任何反应。现在服务在 onStartCommand
     * 里认这条指令，参数它自己读，不用带。</p>
     */
    private void pushFloatingStyle() {
        sendToRecordingFloating(RecordingFloatingService.ACTION_UPDATE_STYLE, null);
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

    // ------------------------------------------------------------------ 界面

    private void bindInterface() {
        bindLanguage();
        // 回到主界面时生效（MainActivity.showRecordingInterface 会按它重新摆一次）。
        // 在这里选过，首次启动就不必再问
        bindSegmented("pref_rail_side", SettingsRegistry.ACTION_RAIL_SIDE,
                appConfig.getActionRailSide(), value -> {
                    appConfig.setActionRailSide(value);
                    appConfig.setRailSideChosen();
                });
        // 录制状态显示是主界面画面角落那块，不是悬浮按钮的事
        bindSwitch("pref_recording_stats", appConfig.isRecordingStatsEnabled(),
                value -> appConfig.setRecordingStatsEnabled(value));

        bindSwitch("pref_reduce_motion", appConfig.isReduceMotionWhileRecording(),
                value -> appConfig.setReduceMotionWhileRecording(value));

        bindInterfaceFisheye();
    }

    /**
     * 鱼眼校正（界面，2026-10-23 校正方式和校正视野合成一行）：管主界面预览、照片回放、录像回放里那个鱼眼按钮怎么校正
     * （开关在那个按钮上，这里只管「怎么校正」）。行尾写方式和视野；点这一行，框里选方式、拖视野（{@link #editInterfaceFisheye}）。
     */
    private void bindInterfaceFisheye() {
        ValueActionPreference pref = findPreference("pref_fisheye");
        if (pref == null) {
            return;
        }
        showInterfaceFisheye(pref);
        pref.setOnPreferenceClickListener(preference -> {
            editInterfaceFisheye(pref);
            return true;
        });
    }

    /** 行尾：方式和视野。视野照这一种方式的上限夹过再写 —— 写出来的就是实际生效的。 */
    private void showInterfaceFisheye(ValueActionPreference pref) {
        String projection = SettingsRegistry.FISHEYE_PROJECTION.sanitize(appConfig.getFisheyeProjection());
        int fov = Math.round(com.kooo.evcam.zeekr.FisheyeProjection.clampFov(appConfig.getFisheyeFov(), projection));
        pref.setValue(getString(R.string.set_fisheye_value,
                nameOf(SettingsRegistry.FISHEYE_PROJECTION, projection), fov));
    }

    /**
     * 框里上面选方式（单选列表），下面拖视野（{@link SliderDialog#block}）。两种投影的视野上限不同（直线 140°，另外两种 180°）：
     * 换方式时拖动条跟着换、拖到的值夹进新的范围 —— 以前是换投影时滑块要重新绑一次，否则界面上还能拖到 180°，实际生效的却是夹过的 140°。
     * 「保存」才存；说明写在拖动条下面。主界面这时候看不见，没什么好当场预览的。
     */
    private void editInterfaceFisheye(ValueActionPreference pref) {
        Context context = requireContext();
        SettingSpec spec = SettingsRegistry.FISHEYE_PROJECTION;
        String[] values = spec.values();
        final String[] projection = {spec.sanitize(appConfig.getFisheyeProjection())};
        final SliderDialog.Slider[] fov = {fisheyeSlider(projection[0], Math.round(appConfig.getFisheyeFov()))};
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(context);
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(SliderDialog.block(context, fov[0]));
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_rearview_fisheye_title)
                // 自己放进去的视图排在列表下面（AlertDialog 本来的结构）
                .setSingleChoiceItems(localizedNames(spec), Math.max(0, spec.indexOf(projection[0])),
                        (dialog, which) -> {
                            projection[0] = values[which];
                            fov[0] = fisheyeSlider(projection[0], fov[0].value());
                            holder.removeAllViews();
                            holder.addView(SliderDialog.block(context, fov[0]));
                        })
                .setView(holder)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    appConfig.setFisheyeProjection(projection[0]);
                    appConfig.setFisheyeFov(fov[0].value());
                    showInterfaceFisheye(pref);
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 视野那根拖动条：范围照这一种方式（原来的值夹进去）；拖的时候不做事，「保存」时一起存。 */
    private SliderDialog.Slider fisheyeSlider(String projection, int fov) {
        return new SliderDialog.Slider(getString(R.string.set_fisheye_fov_title),
                (int) FisheyeProjectionBounds.MIN, (int) com.kooo.evcam.zeekr.FisheyeProjection.maxFovFor(projection),
                fov, "°", getString(R.string.set_fisheye_fov_summary), true, false, value -> {
                });
    }

    /**
     * 系统：诊断信息、导出 / 导入设置、系统信息、开机自启动（含保活）、熄屏持续录制。
     * 开发者的「熄屏录制（阻止休眠）」不在这里，在开发者选项分区（{@link #bindAdvanced}）。
     */
    private void bindSystem() {
        // 诊断信息放在系统里：它是给所有人导出报告用的
        onClick("pref_diagnostics", pref ->
                startActivity(new Intent(getContext(), DiagnosticsActivity.class)));
        // 导出、导入设置（项目所有者 2026-10-19）：和诊断报告同一个文件夹；导入后应用退出，重新打开时照新设置起来
        onClick("pref_settings_export", pref -> SettingsBackupFlow.export(requireActivity()));
        onClick("pref_settings_import", pref -> SettingsBackupFlow.startImport(requireActivity()));
        Preference importRow = findPreference("pref_settings_import");
        if (importRow != null) {
            importRow.setSummary(getString(R.string.set_settings_import_summary,
                    SettingsBackup.currentMajor(requireContext())));
        }
        // 系统信息（试验性）：车机能读到的车辆信号。只在这一页开着时多读浮点和定位，离开就放掉（离散信号熄屏判断一直在读）
        onClick("pref_vehicle_info", pref ->
                openFragment(new VehicleInfoFragment(), R.string.set_vehicle_info_title));

        // 开机自启动接手全部保活手段（规格 §3）：关了就把定时任务也取消，别等它下次到点再自己退出
        bindSwitch("pref_auto_start", appConfig.isAutoStartOnBoot(),
                value -> {
                    appConfig.setAutoStartOnBoot(value);
                    if (value) {
                        com.kooo.evcam.KeepAliveManager.startKeepAliveWork(requireContext());
                    } else {
                        com.kooo.evcam.KeepAliveManager.stopKeepAliveWork(requireContext());
                    }
                });
        // 熄屏持续录制录到什么时候看哨兵模式（熄屏 v3：开着一直录环视，没开录到车机要睡）：行尾写着哨兵模式此刻开没开（行样式在 apply 之前标上）
        androidx.preference.Preference keepRecording = findPreference("pref_screen_off_keep_recording");
        if (keepRecording != null) {
            PreferenceRows.markSentryStatus(keepRecording);
        }
        bindSwitch("pref_screen_off_keep_recording", appConfig.isScreenOffKeepRecording(),
                value -> appConfig.setScreenOffKeepRecording(value));
    }

    // ------------------------------------------------------------------ 原「高级」，现在在开发者选项里

    private void bindAdvanced() {
        bindEnum("pref_recording_mode", SettingsRegistry.RECORDING_MODE,
                appConfig.getRecordingMode(), value -> appConfig.setRecordingMode(value),
                this::showRecordingModeSummary);

        // 熄屏录制 = 熄屏持续录制 + 唤醒锁（规格 §3.1）。整块只在开发者选项里出现，不用再单独锁
        bindScreenOffRecording();
        bindSwitch("pref_force_h264", appConfig.isForceH264Encoding(),
                value -> appConfig.setForceH264Encoding(value));
        bindFootageAudio();
        bindDriveSound();

        // 画面调节：打开调节窗口（MainActivity 管着它，退出设置也还在），同时开启画面调节、套上存着的参数。
        // 窗口开没开成由 MainActivity 说：没有悬浮窗权限、相机还没好时它自己提示
        onClick("pref_image_adjust", pref -> {
            if (getActivity() instanceof MainActivity) {
                MainActivity main = (MainActivity) getActivity();
                appConfig.setImageAdjustEnabled(true);
                main.setImageAdjustEnabled(true);
                main.showImageAdjustFloatingWindow();
            }
        });

        // 行尾「重置」（2026-10-23 原来单独一行「恢复画面调节默认值」）：只动这一行管的参数，不用先问
        ValueActionPreference adjust = findPreference("pref_image_adjust");
        if (adjust != null) {
            adjust.setAction(getString(R.string.action_reset), () -> {
                if (getActivity() instanceof MainActivity) {
                    com.kooo.evcam.camera.ImageAdjustManager manager =
                            ((MainActivity) getActivity()).getImageAdjustManager();
                    if (manager != null) {
                        manager.resetToDefault();
                        toast(getString(R.string.msg_adjust_reset));
                    }
                }
            });
        }

        onClick("pref_camera_mapping", pref -> openFragment(new CameraSourcesFragment(), R.string.camera_sources_title));
        onClick("pref_camera_sources", pref -> openFragment(new CameraSourcesFragment(), R.string.camera_sources_title));
        updateCameraMappingSummary();

    }

    /** 盲区画面标定那一行的行尾：三个角度。 */
    private void showSideViewCalibration(ValueActionPreference pref) {
        pref.setValue(getString(R.string.dev_side_view_calibration_value,
                appConfig.getSideViewBack(), appConfig.getSideViewUp(), appConfig.getSideViewFov()));
    }

    /** 三根拖动条一个框（{@link SliderDialog}）：松手时盲区画面当场跟着转，「保存」才算数。每一根的说明写在它下面。 */
    private void editSideViewCalibration(ValueActionPreference pref) {
        int maxBack = Math.round(com.kooo.evcam.zeekr.SideViewProjection.MAX_YAW_DEGREES);
        int maxUp = Math.round(com.kooo.evcam.zeekr.SideViewProjection.MAX_PITCH_DEGREES);
        java.util.List<SliderDialog.Slider> sliders = java.util.Arrays.asList(
                new SliderDialog.Slider(getString(R.string.dev_side_view_back_title), -maxBack, maxBack,
                        appConfig.getSideViewBack(), "°", getString(R.string.dev_side_view_back_summary),
                        true, false, value -> {
                            appConfig.setSideViewBack(value);
                            SideViewService.applyAim();
                        }),
                new SliderDialog.Slider(getString(R.string.dev_side_view_up_title), -maxUp, maxUp,
                        appConfig.getSideViewUp(), "°", getString(R.string.dev_side_view_up_summary),
                        true, false, value -> {
                            appConfig.setSideViewUp(value);
                            SideViewService.applyAim();
                        }),
                new SliderDialog.Slider(getString(R.string.dev_side_view_fov_title),
                        Math.round(com.kooo.evcam.zeekr.SideViewProjection.MIN_FOV_DEGREES),
                        Math.round(com.kooo.evcam.zeekr.SideViewProjection.MAX_FOV_DEGREES),
                        appConfig.getSideViewFov(), "°", getString(R.string.dev_side_view_fov_summary),
                        true, false, value -> {
                            appConfig.setSideViewFov(value);
                            SideViewService.applyAim();
                        }));
        SliderDialog.show(requireContext(), getString(R.string.dev_side_view_calibration_title), sliders,
                () -> showSideViewCalibration(pref));
    }

    /** 摘要除了选中项，还写明这一项现在不起作用：录制固定走 MediaCodec。 */
    private void showRecordingModeSummary() {
        ListPreference pref = findPreference("pref_recording_mode");
        if (pref != null && getContext() != null) {
            pref.setSummary(getString(R.string.set_recording_mode_summary, pref.getEntry()));
        }
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
     * 开发者选项里的工具。只在开发者选项开着时接线（见 {@link #onCreatePreferences}）。
     *
     * <p>没开时整个分区从左栏上移除，而不是置灰 —— 置灰等于告诉别人
     * 「这里有东西但你用不了」，而这些本来就不该出现在普通用户的设置里。</p>
     */
    private void bindDeveloper() {
        // 这里原来先找一个 key 为 cat_developer 的分类，找不到就整个返回 ——
        // 而 0.21.0 把分区改成嵌套 PreferenceScreen 之后，这个 key 就不存在了。
        // 于是下面四个入口一个都没接上，点了毫无反应，也不报错。
        onClick("pref_permissions",
                pref -> openFragment(new PermissionsPreferenceFragment(), R.string.dev_permissions_title));

        // 应用水印（2026-10-23 从录制页挪来）：应用名与版本（填了车牌号跟在后面）每一路 MediaCodec 录像、每一张照片都盖，
        // 和「时间水印」开不开无关（录像见 EglSurfaceEncoder.drawBrandOverlay，照片见 SingleCamera.addWatermark；
        // MediaRecorder 模式什么角标都画不了），这里只是把这件事摆出来：开着、灰着、点不动。
        // 给一个能关的开关，等于承诺一件代码里并不打算允许的事。
        SwitchPreferenceCompat brand = findPreference("pref_watermark_brand");
        if (brand != null) {
            brand.setPersistent(false);
            brand.setChecked(true);
            brand.setEnabled(false);
        }

        // 车辆状态面板（2026-10-23 从界面页挪来）：主界面那个控件自己听这个开关
        bindSwitch("pref_vehicle_status", appConfig.isVehicleStatusEnabled(),
                value -> appConfig.setVehicleStatusEnabled(value));

        bindSwitch("pref_raw_frame_dump", appConfig.isRawFrameDumpEnabled(),
                appConfig::setRawFrameDumpEnabled);

        // 鱼眼校正换成 GPU 逐像素算。只换算法，开不开还是看屏幕上那个鱼眼按钮
        bindSwitch("pref_gpu_fisheye_preview", appConfig.isGpuFisheyePreview(),
                appConfig::setGpuFisheyePreview);
        bindSwitch("pref_gpu_fisheye_video", appConfig.isGpuFisheyeVideo(),
                appConfig::setGpuFisheyeVideo);

        // 别的程序占用 / 放开相机时记嫌疑应用；要使用情况访问，在权限设置里授予
        bindSwitch("pref_camera_holder_suspects", appConfig.isCameraHolderSuspectsEnabled(),
                appConfig::setCameraHolderSuspectsEnabled);

        // 盲区画面标定（2026-10-23 三根拖动条合成一行）：每一路画面的朝向是猜的（SideViewProjection），上车标定之后定默认值
        ValueActionPreference calibration = findPreference("pref_side_view_calibration");
        if (calibration != null) {
            showSideViewCalibration(calibration);
            calibration.setOnPreferenceClickListener(preference -> {
                editSideViewCalibration(calibration);
                return true;
            });
        }

        onClick("pref_repair_mp4", pref -> {
            if (getActivity() == null) {
                return;
            }
            // 录制中不能修：正在录的那一段也没有索引，和断电留下的半截文件长得一样
            boolean recording = com.kooo.evcam.recording.RecordingCoordinator.get(requireContext()).isRecording();
            com.kooo.evcam.repair.Mp4RepairFlow.start(getActivity(), recording);
        });

        // 归档：把录像、照片、日志从录像盘搬到另一个盘（项目所有者用 Type-C 固态盘统一管理）
        onClick("pref_archive", pref -> {
            if (getActivity() == null) {
                return;
            }
            boolean recording = com.kooo.evcam.recording.RecordingCoordinator.get(requireContext()).isRecording();
            com.kooo.evcam.repair.ArchiveFlow.start(getActivity(), recording);
        });

    }

    /**
     * 带按钮的设置对话框自己弹。
     *
     * <h3>为什么不用 androidx 自带的</h3>
     *
     * <p>androidx 的偏好对话框自己建一个<b>框架</b> AlertDialog，主题靠
     * {@code alertDialogTheme} 从 Activity 主题里解析。「车牌号没有确认键」
     * 「存储上限没有保存键」说的都是这一件事。</p>
     *
     * <p>0.36.4 往主题里补属性、0.38.0 把主题直接传进构造函数，都只是换个方式继续用
     * 框架对话框，所以在实车上都没修好 —— 那一栏按钮长什么样由车机 ROM 说了算。
     * 真正的差别是<b>哪一种对话框</b>：同期用 Material 对话框的那几个（相机映射、
     * 设备名）一直是好的。这里也统一到那条路。</p>
     *
     * <p>下拉框（ListPreference）也在此列。androidx 那个选中即关闭、没有确认键，
     * 点错一项就直接生效，没有反悔的机会。</p>
     */
    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof ListPreference) {
            showChoiceDialog((ListPreference) preference);
            return;
        }
        super.onDisplayPreferenceDialog(preference);
    }

    /**
     * 单选：视频流配置、存储位置、语言……（ListPreference 那几行）。选中之后还要按确定才算数。
     * 标题下面可以有一行此刻的状态（{@link #EXTRA_DIALOG_STATUS}，存储位置写录像保存在哪）；
     * 说明（XML 里的 dialogMessage）是次要的，写在选项下面（ui-spec §三）。
     */
    private void showChoiceDialog(ListPreference pref) {
        CharSequence[] entries = pref.getEntries();
        CharSequence[] values = pref.getEntryValues();
        if (entries == null || values == null || entries.length != values.length) {
            super.onDisplayPreferenceDialog(pref);
            return;
        }
        String[] keys = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            keys[i] = values[i].toString();
        }
        CharSequence title = pref.getDialogTitle() != null
                ? pref.getDialogTitle() : pref.getTitle();
        showChoice(title, pref.getExtras().getCharSequence(EXTRA_DIALOG_STATUS), pref.getDialogMessage(),
                entries, keys, pref.getValue(), value -> {
                    if (pref.callChangeListener(value)) {
                        pref.setValue(value);
                    }
                });
    }

    /** 单选框本身：上面那几行和「开关加下一层」的下一层（录制声音的麦克风来源）共用。 */
    private void showChoice(CharSequence title, CharSequence status, CharSequence note,
                            CharSequence[] entries, String[] values, String current, StringSetter onPick) {
        int index = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                index = i;
                break;
            }
        }
        final int[] picked = {index};
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                requireContext(), R.style.Theme_Cam_MaterialAlertDialog);
        if (status == null) {
            builder.setTitle(title);
        } else {
            builder.setCustomTitle(titleWithStatus(title, status));
        }
        // 自己放进去的视图排在列表下面（AlertDialog 本来的结构：列表占正文的位置，视图在它和按钮之间）
        if (note != null) {
            int pad = (int) (24 * getResources().getDisplayMetrics().density);
            android.widget.TextView view = com.kooo.evcam.ui.CamDialogs.note(requireContext(), note);
            view.setPadding(pad, pad / 2, pad, pad / 2);
            builder.setView(view);
        }
        com.kooo.evcam.ui.CamDialogs.show(builder
                .setSingleChoiceItems(entries, picked[0], (d, which) -> picked[0] = which)
                .setPositiveButton(R.string.action_ok, (d, w) -> {
                    if (picked[0] < 0 || picked[0] >= values.length) {
                        return;
                    }
                    onPick.set(values[picked[0]]);
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 对话框标题加标题下面一行此刻的状态（单选框的标题栏只有一行字，所以自己排）。 */
    private android.view.View titleWithStatus(CharSequence title, CharSequence status) {
        Context context = requireContext();
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        android.widget.TextView heading = new android.widget.TextView(context);
        heading.setTextAppearance(R.style.TextAppearance_Cam_Title);
        heading.setText(title);
        box.addView(heading);
        android.widget.TextView line = com.kooo.evcam.ui.CamDialogs.note(context, status);
        line.setPadding(0, pad / 2, 0, 0);
        box.addView(line);
        return box;
    }

    /** 单行文本输入框：「开关加下一层」的下一层（车牌号、熄屏录制的时长上限）用它。键盘按 {@code key} 配（{@link #configureInput}）。 */
    private void showTextDialog(String key, CharSequence title, CharSequence message, String current,
                                StringSetter onSave) {
        final android.widget.EditText input =
                com.kooo.evcam.ui.CamDialogs.input(requireContext());
        input.setText(current);
        input.setSelectAllOnFocus(true);
        configureInput(key, input);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        if (message != null) {
            android.widget.TextView note = com.kooo.evcam.ui.CamDialogs.note(requireContext(), message);
            note.setPadding(0, pad / 2, 0, 0);
            box.addView(note);
        }

        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save,
                        (dialog, which) -> onSave.set(input.getText().toString()))
                .setNegativeButton(R.string.action_cancel, null));
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
        if ("pref_screen_off_wake_hours".equals(key)) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
    }

    
    // ------------------------------------------------------------------ 关于

    // ------------------------------------------------------------------ 检查更新

    /**
     * 检查更新：点那一行就查；另有一个「接收 Beta 版」开关。
     *
     * <p>当前版本写在那一行的说明里 —— 点之前就知道本机装的是哪个，
     * 查完提示「已是最新」时也对得上。</p>
     */
    private void bindUpdate() {
        androidx.preference.Preference check = findPreference("pref_check_update");
        if (check != null && getContext() != null) {
            check.setSummary(getString(R.string.set_check_update_summary,
                    com.kooo.evcam.update.UpdateFlow.currentVersion(getContext())));
        }
        onClick("pref_check_update",
                pref -> com.kooo.evcam.update.UpdateFlow.start(getActivity()));
        bindSwitch("pref_update_beta", appConfig.isUpdateBetaEnabled(),
                value -> appConfig.setUpdateBetaEnabled(value));
        // 随「接收 Beta 版」（XML 里的 dependency）：Beta 关着时它不可用，也不算数（UpdateFlow）
        bindSwitch("pref_update_alpha", appConfig.isUpdateAlphaEnabled(),
                value -> appConfig.setUpdateAlphaEnabled(value));
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

    /**
     * 录制声音（开发者选项，试验）：打开时没有录音权限就先问系统，给了才打开。开关显示的是会不会真录（开着、又有权限）——
     * 在系统设置里收回了权限，这里就显示关着。改了之后前台服务重走一遍（带上 / 去掉麦克风类型）；录法从下一次开录起。
     */
    private void bindFootageAudio() {
        SwitchNavPreference pref = findPreference("pref_footage_audio");
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(appConfig.isFootageAudioEnabled() && hasMicPermission());
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = Boolean.TRUE.equals(newValue);
            if (on && !hasMicPermission()) {
                micPermission.launch(android.Manifest.permission.RECORD_AUDIO);
                return false;  // 等系统弹窗的结果（onMicPermission）
            }
            setFootageAudio(on);
            return true;
        });
        // 麦克风来源（2026-10-23 起在这一行的下一层）：行尾写来源，点这一行选
        showFootageAudioSource(pref);
        pref.setOnPreferenceClickListener(preference -> {
            SettingSpec spec = SettingsRegistry.FOOTAGE_AUDIO_SOURCE;
            showChoice(getString(R.string.dev_footage_audio_source_title), null, null, localizedNames(spec),
                    spec.values(), spec.sanitize(appConfig.getFootageAudioSource()), value -> {
                        appConfig.setFootageAudioSource(value);
                        showFootageAudioSource(pref);
                    });
            return true;
        });
    }

    /** 录制声音那一行的行尾：来源的短名字（框里的长名字带着说明）。 */
    private void showFootageAudioSource(SwitchNavPreference pref) {
        boolean call = com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_CALL.equals(
                SettingsRegistry.FOOTAGE_AUDIO_SOURCE.sanitize(appConfig.getFootageAudioSource()));
        pref.setValue(getString(call ? R.string.dev_footage_audio_source_call_short
                : R.string.dev_footage_audio_source_raw_short));
    }

    /**
     * 模拟声浪（开发者选项，试验）：开关、音量、转速变化方式，改了马上生效（{@code sound.DriveSound}）。
     * 2026-10-23 起开关加下一层：行尾写音量和转速变化方式，点这一行进「模拟声浪」页（preferences.xml 里嵌着的 screen_drive_sound）。
     */
    private void bindDriveSound() {
        bindSwitch("pref_drive_sound", appConfig.isDriveSoundEnabled(), value -> {
            appConfig.setDriveSoundEnabled(value);
            refreshDriveSound();
        });
        SwitchNavPreference row = findPreference("pref_drive_sound");
        if (row != null) {
            showDriveSound(row);
            row.setOnPreferenceClickListener(preference -> {
                openFragment(SettingsPreferenceFragment.forSection("screen_drive_sound"), R.string.dev_drive_sound_group);
                return true;
            });
        }
        bindEnum("pref_drive_sound_volume", SettingsRegistry.DRIVE_SOUND_VOLUME, appConfig.getDriveSoundVolume(),
                value -> {
                    appConfig.setDriveSoundVolume(value);
                    refreshDriveSound();
                });
        bindEnum("pref_drive_sound_rpm", SettingsRegistry.DRIVE_SOUND_RPM, appConfig.getDriveSoundRpm(),
                value -> {
                    appConfig.setDriveSoundRpm(value);
                    refreshDriveSound();
                });
    }

    /** 模拟声浪那一行的行尾：音量和转速变化方式。 */
    private void showDriveSound(SwitchNavPreference row) {
        row.setValue(getString(R.string.dev_drive_sound_value,
                nameOf(SettingsRegistry.DRIVE_SOUND_VOLUME, appConfig.getDriveSoundVolume()),
                nameOf(SettingsRegistry.DRIVE_SOUND_RPM, appConfig.getDriveSoundRpm())));
    }

    /** 从「模拟声浪」页回来时音量、转速变化方式可能改过。 */
    private void refreshDriveSoundValue() {
        SwitchNavPreference row = findPreference("pref_drive_sound");
        if (row != null && getContext() != null) {
            showDriveSound(row);
        }
    }

    private void refreshDriveSound() {
        Context context = getContext();
        if (context != null) {
            com.kooo.evcam.sound.DriveSound.refresh(context);
        }
    }

    /** 系统弹窗问完录音权限：给了就打开「录制声音」；没给就照旧关着，说一句。 */
    private void onMicPermission(boolean granted) {
        Context context = getContext();
        if (context == null || appConfig == null) {
            return;
        }
        if (!granted) {
            Toast.makeText(context, R.string.msg_footage_audio_no_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        setFootageAudio(true);
        SwitchPreferenceCompat pref = findPreference("pref_footage_audio");
        if (pref != null) {
            pref.setChecked(true);
        }
    }

    private void setFootageAudio(boolean on) {
        appConfig.setFootageAudioEnabled(on);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: pref_footage_audio → " + (on ? "开" : "关"));
        Context context = getContext();
        if (context != null) {
            // 前台服务按此刻的开关重新起一次前台：带不带麦克风类型
            com.kooo.evcam.CameraForegroundService.start(context.getApplicationContext());
        }
    }

    private boolean hasMicPermission() {
        Context context = getContext();
        return context != null && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void bindSwitch(String key, boolean current, BoolSetter setter) {
        SwitchPreferenceCompat pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setPersistent(false);
        pref.setChecked(current);
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean on = Boolean.TRUE.equals(newValue);
            setter.set(on);
            // 开关什么时候被改过，黑匣子里要看得到 —— 否则一段时间线对不上当时的设置
            com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: " + key + " → " + (on ? "开" : "关"));
            return true;
        });
    }

    /** 枚举：选项与显示名都来自 {@link SettingsRegistry}，声明一遍就够。 */
    /**
     * 选项只有两三个的设置：摆在行里点一次。
     *
     * <p>和 {@link #bindEnum} 的区别只在长相；取值、校验、写回都还是走
     * {@link SettingSpec}，所以两者可以随时互换。</p>
     */
    private void bindSegmented(String key, SettingSpec spec, String current,
                               StringSetter setter) {
        SegmentedPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setOptions(localizedNames(spec), spec.values());
        pref.setValue(spec.sanitize(current));
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            setter.set(String.valueOf(newValue));
            return true;
        });
    }

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
     */
    private String[] localizedNames(SettingSpec spec) {
        String[] names = spec.displayNames();
        int[] res = spec.nameResIds();
        for (int i = 0; i < names.length; i++) {
            if (res[i] != 0) {
                names[i] = getString(res[i]);
            }
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
    /** @param titleRes 这个二级界面叫什么；标题区显示它，返回时自动退回上一层的名字 */
    private void openFragment(androidx.fragment.app.Fragment fragment, int titleRes) {
        if (getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment())
                    .openDetail(fragment, getString(titleRes));
            return;
        }
        if (getActivity() == null) {
            return;
        }
        getActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, fragment.getClass().getName())
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
