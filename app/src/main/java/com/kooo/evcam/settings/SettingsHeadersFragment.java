/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.profile.QualityPreset;

/**
 * 左栏：设置的分区列表。
 *
 * <p>先整份载入 {@code preferences.xml}，读出顶层每一个分区的 key 和名字，再换成左栏自己的行
 * （{@link HeaderRow}）。这样分区名和顺序仍然只在那个 XML 里声明一次 ——
 * 另写一份导航列表的话，加了新分区却忘了同步，左栏就会少一项。</p>
 *
 * <p>换成自己的行，是为了三件默认的行做不到的事：每一项一个图标；<b>当前在哪个分区</b>看得出来（选中那一项垫一块灰）；
 * 分区名下面一行<b>这一类现在的状态</b>（4.0，ui-spec §十三「左栏的状态」）—— 不用点进去就知道哪个开着、U 盘还剩多少。</p>
 *
 * <p>左栏只有分区（4.0，项目所有者 2026-11-12）：以前顶上的「返回主界面」、底下的「退出应用」去掉了（左上角就是返回，退出在抽屉里），
 * 「关于与致谢」是「更新与关于」里的一行。开发者选项没解锁时整块拿掉，和右栏的判断保持一致。</p>
 */
public class SettingsHeadersFragment extends PreferenceFragmentCompat {

    /** 上一次建这份列表时，开发者选项是不是解锁着的。 */
    private boolean builtUnlocked;
    /** 右栏正在显示的分区。 */
    private String selectedKey;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        if (getParentFragment() instanceof SettingsShellFragment) {
            selectedKey = ((SettingsShellFragment) getParentFragment()).currentSection();
        }
        build();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PreferenceRows.styleList(this, 0, 12);
    }

    /**
     * 解锁开发者模式是在「关于本应用」里做的 —— <b>那是另一个 Activity</b>。
     *
     * <p>从那儿回来时，这份列表还是解锁之前建的，「开发者选项」不会出现；
     * 原先要退出设置再进来一次才看得见。这里发现状态变了就重建一次。</p>
     *
     * <p>状态那一行也在这时重写：录制、超级镜子、U 盘都可能在别处变过。</p>
     */
    @Override
    public void onResume() {
        super.onResume();
        if (builtUnlocked != DeveloperMode.isUnlocked()) {
            build();
        } else {
            refreshStatus();
        }
    }

    /** 右栏换了分区：左栏跟着把选中块挪过去。 */
    void markSelected(String key) {
        selectedKey = key;
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof HeaderRow) {
                ((HeaderRow) preference).setSelectedRow(key != null && key.equals(preference.getKey()));
            }
        }
    }

    /** 某个分区叫什么。标题区要用它，分区名只在 preferences.xml 里声明一次。 */
    CharSequence titleOf(String key) {
        Preference row = key == null || getPreferenceScreen() == null ? null : getPreferenceScreen().findPreference(key);
        return row == null ? null : row.getTitle();
    }

    /** 某个分区在左栏里是第几行（换分区时内容往上还是往下走看它）；没有这个分区返回 -1。 */
    int orderOf(String key) {
        Preference row = key == null || getPreferenceScreen() == null ? null : getPreferenceScreen().findPreference(key);
        return row instanceof HeaderRow ? ((HeaderRow) row).order : -1;
    }

    /** 每个分区名下面那一行状态照现在的设置重写。 */
    void refreshStatus() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || getContext() == null) {
            return;
        }
        AppConfig config = new AppConfig(requireContext());
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference row = screen.getPreference(i);
            if (row instanceof HeaderRow) {
                row.setSummary(statusOf(row.getKey(), config));
            }
        }
    }

    private void build() {
        builtUnlocked = DeveloperMode.isUnlocked();
        Context context = requireContext();

        // 分区的名字和顺序只从这里来
        setPreferencesFromResource(R.xml.preferences, null);
        PreferenceScreen source = getPreferenceScreen();

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        int order = 0;
        for (int i = 0; i < source.getPreferenceCount(); i++) {
            Preference child = source.getPreference(i);
            String key = child.getKey();
            if (key == null || !(child instanceof PreferenceGroup)) {
                continue;
            }
            if (SettingsShellFragment.DEVELOPER_SECTION.equals(key) && !builtUnlocked) {
                continue;
            }
            HeaderRow row = new HeaderRow(context, order++);
            row.setKey(key);
            row.setTitle(child.getTitle());
            row.setPersistent(false);
            row.setIconSpaceReserved(true);
            int icon = iconFor(key);
            if (icon != 0) {
                row.setIcon(icon);
            }
            row.setSelectedRow(key.equals(selectedKey));
            row.setOnPreferenceClickListener(p -> {
                if (getParentFragment() instanceof SettingsShellFragment) {
                    ((SettingsShellFragment) getParentFragment()).showSection(key, row.order);
                }
                return true;
            });
            screen.addPreference(row);
        }
        setPreferenceScreen(screen);
        refreshStatus();
        // 行建好了，标题区才问得到分区名
        if (getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment()).refreshTitle();
        }
    }

    /**
     * 这一类现在的样子，一行说完（ui-spec §十三）。只读已有的状态：设置里存的值、相机管家说在不在录、存储的快照 ——
     * 不为它去碰盘、不为它读车辆信号。没什么可写的（通用、开发者选项）返回 null，那一行不占地方。
     */
    @Nullable
    private CharSequence statusOf(String key, AppConfig config) {
        Context context = requireContext();
        switch (key) {
            case "screen_recording":
                return getString(isRecording(context) ? R.string.header_recording_on : R.string.header_recording_off,
                        qualityName(context));
            case "screen_storage": {
                com.kooo.evcam.storage.StorageState.Snapshot state = com.kooo.evcam.storage.StorageState.current();
                if (!state.known) {
                    return null;  // 还没探测过，先不写
                }
                return state.root != null
                        ? getString(R.string.header_storage_free, StorageHelper.formatSize(state.freeBytes))
                        : getString(R.string.status_storage_none);
            }
            case "screen_rearview":
                return config.isRearViewEnabled()
                        ? nameOf(SettingsRegistry.REARVIEW_VIEWS, config.getRearViewViews().key) : getString(R.string.opt_off);
            case "screen_side_view":
                return config.isSideViewEnabled()
                        ? nameOf(SettingsRegistry.SIDE_VIEW_MODE, config.getSideViewMode()) : getString(R.string.opt_off);
            case "screen_floating": {
                int on = (config.isRecordingFloatingEnabled() ? 1 : 0) + (config.isSmartButtonsEnabled() ? 1 : 0);
                return on == 0 ? getString(R.string.header_none_enabled) : getString(R.string.header_enabled_count, on);
            }
            case "screen_update":
                return com.kooo.evcam.update.UpdateFlow.currentVersion(context);
            default:
                return null;
        }
    }

    /** 录像开没开着：问相机管家（录制键、悬浮按钮、通知问的是同一处，ui-spec §八）。问不到按没在录写。 */
    private static boolean isRecording(Context context) {
        try {
            return com.kooo.evcam.camera.CameraKeeper.get(context).recordingWanted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 现在这份配置选的录制画质；有哪一路单独调过写「自定义」。 */
    private String qualityName(Context context) {
        Profile profile = new ProfileStore(context).current();
        for (com.kooo.evcam.profile.CameraProfile camera : profile.cameras) {
            if (camera.enabled && !profile.quality.matches(camera.record)) {
                return getString(R.string.editor_camera_tuned);
            }
        }
        return getString(profile.quality == QualityPreset.SAVE_SPACE ? R.string.editor_quality_space
                : profile.quality == QualityPreset.SHARPEST ? R.string.editor_quality_sharp
                : R.string.editor_quality_balanced);
    }

    /** 某个选项在界面上叫什么（和右边那一行的分段同一个名字）。 */
    private String nameOf(SettingSpec spec, String value) {
        int index = spec.indexOf(spec.sanitize(value));
        if (index < 0) {
            return "";
        }
        int res = spec.nameResIds()[index];
        return res != 0 ? getString(res) : spec.displayNames()[index];
    }

    private static int iconFor(String key) {
        switch (key) {
            case "screen_recording":
                return R.drawable.ic_video;
            case "screen_storage":
                return R.drawable.ic_storage;
            case "screen_rearview":
                return R.drawable.ic_rearview;
            case "screen_side_view":
                return R.drawable.ic_side_view;
            case "screen_floating":
                return R.drawable.ic_floating;
            case "screen_general":
                return R.drawable.ic_settings;
            case "screen_developer":
                return R.drawable.ic_developer;
            case "screen_update":
                return R.drawable.ic_info;
            default:
                return 0;
        }
    }

    /** 左栏的一行：记得自己排第几、现在是不是选中的那一项。 */
    static final class HeaderRow extends Preference {

        final int order;
        private boolean selected;

        HeaderRow(Context context, int order) {
            super(context);
            this.order = order;
            setLayoutResource(R.layout.pref_header_row);
        }

        void setSelectedRow(boolean value) {
            if (value != selected) {
                selected = value;
                notifyChanged();
            }
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            // 激活态会传给所有子控件：底色、图标、字一起变
            holder.itemView.setActivated(selected);
        }
    }
}
