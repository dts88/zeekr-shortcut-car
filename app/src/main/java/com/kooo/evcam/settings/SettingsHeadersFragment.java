package com.kooo.evcam.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/**
 * 左栏：设置的分区列表。
 *
 * <p>直接加载完整的 {@code preferences.xml}，然后<b>把每个分区里的内容全部移除</b>，
 * 只留分区自己作为一行。这样分区名和顺序仍然只在那个 XML 里声明一次 ——
 * 另写一份导航列表的话，加了新分区却忘了同步，左栏就会少一项。</p>
 *
 * <p>开发者选项没解锁时整块拿掉，和右栏的判断保持一致。</p>
 */
public class SettingsHeadersFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences, null);
        PreferenceScreen root = getPreferenceScreen();

        for (int i = root.getPreferenceCount() - 1; i >= 0; i--) {
            Preference child = root.getPreference(i);
            if (!(child instanceof PreferenceGroup)) {
                // 「返回录制界面」这类不属于任何分区的条目，留在最上面
                continue;
            }
            PreferenceGroup group = (PreferenceGroup) child;
            if ("screen_developer".equals(group.getKey()) && !DeveloperMode.isUnlocked()) {
                root.removePreference(group);
                continue;
            }
            // 只留标题这一行，内容交给右栏
            group.removeAll();
            group.setSummary(null);
        }

        wireClicks(root);
    }

    private void wireClicks(PreferenceScreen root) {
        Preference back = findPreference("pref_back_to_recording");
        if (back != null) {
            back.setOnPreferenceClickListener(preference -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToRecordingInterface();
                }
                return true;
            });
        }

        for (int i = 0; i < root.getPreferenceCount(); i++) {
            Preference child = root.getPreference(i);
            if (!(child instanceof PreferenceGroup) || child.getKey() == null) {
                continue;
            }
            final String key = child.getKey();
            child.setOnPreferenceClickListener(preference -> {
                if (getParentFragment() instanceof SettingsShellFragment) {
                    ((SettingsShellFragment) getParentFragment()).showSection(key);
                }
                return true;
            });
        }
    }
}
