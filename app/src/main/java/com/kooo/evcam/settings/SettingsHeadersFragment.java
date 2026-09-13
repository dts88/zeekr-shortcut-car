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

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.update.UpdateFlow;

/**
 * 左栏：设置的分区列表。
 *
 * <p>先整份载入 {@code preferences.xml}，读出顶层每一项的 key 和名字，再换成左栏自己的行
 * （{@link HeaderRow}）。这样分区名和顺序仍然只在那个 XML 里声明一次 ——
 * 另写一份导航列表的话，加了新分区却忘了同步，左栏就会少一项。</p>
 *
 * <p>换成自己的行，是为了两件默认的行做不到的事：每一项一个图标，
 * 以及<b>当前在哪个分区</b>看得出来（选中那一项垫一块灰）。
 * 以前点完左栏，左栏本身没有任何变化，只能靠右栏的内容去猜。</p>
 *
 * <p>开发者选项没解锁时整块拿掉，和右栏的判断保持一致。</p>
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
     */
    @Override
    public void onResume() {
        super.onResume();
        if (builtUnlocked != DeveloperMode.isUnlocked()) {
            build();
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
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || key == null) {
            return null;
        }
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (key.equals(preference.getKey())) {
                return preference.getTitle();
            }
        }
        return null;
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
            if (key == null) {
                continue;
            }
            boolean section = child instanceof PreferenceGroup;
            if (section && "screen_developer".equals(key) && !builtUnlocked) {
                continue;
            }
            HeaderRow row = new HeaderRow(context, section, order++);
            row.setKey(key);
            row.setTitle(child.getTitle());
            row.setPersistent(false);
            row.setIconSpaceReserved(true);
            int icon = iconFor(key);
            if (icon != 0) {
                row.setIcon(icon);
            }
            row.setSelectedRow(key.equals(selectedKey));
            screen.addPreference(row);
        }
        setPreferenceScreen(screen);
        wireClicks(screen);
        // 行建好了，标题区才问得到分区名
        if (getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment()).refreshTitle();
        }
    }

    private void wireClicks(PreferenceScreen screen) {
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (!(preference instanceof HeaderRow)) {
                continue;
            }
            HeaderRow row = (HeaderRow) preference;
            final String key = row.getKey();
            row.setOnPreferenceClickListener(p -> {
                if ("pref_back_to_recording".equals(key)) {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).goToRecordingInterface();
                    }
                } else if ("pref_check_update".equals(key)) {
                    // 顶层的动作条目：它不属于任何分区，右栏按 key 取子树时取不到它
                    UpdateFlow.start(getActivity());
                } else if ("pref_exit".equals(key)) {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).exitApp();
                    }
                } else if (row.section && getParentFragment() instanceof SettingsShellFragment) {
                    ((SettingsShellFragment) getParentFragment()).showSection(key, row.order);
                }
                return true;
            });
        }
    }

    private static int iconFor(String key) {
        switch (key) {
            case "pref_back_to_recording":
                return R.drawable.ic_back;
            case "screen_recording":
                return R.drawable.ic_video;
            case "screen_storage":
                return R.drawable.ic_storage;
            case "screen_rearview":
                return R.drawable.ic_rearview;
            case "screen_floating":
                return R.drawable.ic_floating;
            case "screen_interface":
                return R.drawable.ic_interface;
            case "screen_system":
                return R.drawable.ic_settings;
            case "screen_advanced":
                return R.drawable.ic_advanced;
            case "screen_developer":
                return R.drawable.ic_developer;
            case "pref_check_update":
                return R.drawable.ic_update;
            case "screen_about":
                return R.drawable.ic_info;
            case "pref_exit":
                return R.drawable.ic_power;
            default:
                return 0;
        }
    }

    /** 左栏的一行：记得自己是不是分区、排第几、现在是不是选中的那一项。 */
    static final class HeaderRow extends Preference {

        final boolean section;
        final int order;
        private boolean selected;

        HeaderRow(Context context, boolean section, int order) {
            super(context);
            this.section = section;
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
