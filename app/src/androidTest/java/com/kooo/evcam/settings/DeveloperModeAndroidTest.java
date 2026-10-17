package com.kooo.evcam.settings;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import androidx.preference.SwitchPreferenceCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;

import com.kooo.evcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/** Disposable emulator: real System toggle, two-pane refresh, persistence and feature gating. */
@RunWith(AndroidJUnit4.class)
public class DeveloperModeAndroidTest {
    private void main(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }

    @Test public void systemSwitchRefreshesHeadersPersistsAndDisablesSavedDeveloperSettings() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String permission : new String[]{Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(target.getPackageName(), permission);
        }
        SharedPreferences prefs = target.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("first_launch", false).putBoolean("language_chosen", true)
                .putBoolean("rail_side_chosen", true).commit();
        boolean oldMode = DeveloperMode.isUnlocked();
        boolean oldRaw = prefs.getBoolean("raw_frame_dump", false);
        DeveloperMode.setEnabled(target, false);
        try (ActivityScenario<SettingsTestHostActivity> scenario = ActivityScenario.launch(new android.content.Intent(target, SettingsTestHostActivity.class))) {
            AtomicReference<SettingsShellFragment> ref = new AtomicReference<>();
            scenario.onActivity(activity -> {
                SettingsShellFragment shell = new SettingsShellFragment();
                ref.set(shell);
                activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, shell).commitNow();
                shell.getChildFragmentManager().executePendingTransactions();
                shell.showSection("screen_system");
                shell.getChildFragmentManager().executePendingTransactions();
                assertFalse(toggle(shell).isChecked());
                assertFalse(hasDeveloperHeader(shell));
            });
            onView(withText(target.getString(R.string.set_developer_mode_title)))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            capture("zeekr-system-developer.png");
            onView(withText(target.getString(R.string.set_developer_mode_title))).perform(click());
            main(() -> {
                assertTrue(DeveloperMode.isUnlocked());
                assertTrue(hasDeveloperHeader(ref.get()));
                assertEquals("screen_system", ref.get().currentSection());
                assertTrue(target.getSharedPreferences("developer_mode", Context.MODE_PRIVATE)
                        .getBoolean("unlocked", false));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                ref.set(shell);
                shell.getChildFragmentManager().executePendingTransactions();
                assertTrue(toggle(shell).isChecked());
                assertTrue(hasDeveloperHeader(shell));
                prefs.edit().putBoolean("raw_frame_dump", true).commit();
                assertTrue(new AppConfig(target).isRawFrameDumpEnabled());
            });
            main(() -> {
                SettingsHeadersFragment headers = (SettingsHeadersFragment) ref.get()
                        .getChildFragmentManager().findFragmentById(R.id.settings_headers);
                androidx.preference.PreferenceGroupAdapter adapter =
                        (androidx.preference.PreferenceGroupAdapter) headers.getListView().getAdapter();
                headers.getListView().scrollToPosition(adapter.getPreferenceAdapterPosition("screen_system"));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> {
                SettingsHeadersFragment headers = (SettingsHeadersFragment) ref.get()
                        .getChildFragmentManager().findFragmentById(R.id.settings_headers);
                androidx.preference.PreferenceGroupAdapter adapter =
                        (androidx.preference.PreferenceGroupAdapter) headers.getListView().getAdapter();
                androidx.recyclerview.widget.RecyclerView.ViewHolder row = headers.getListView()
                        .findViewHolderForAdapterPosition(adapter.getPreferenceAdapterPosition("screen_system"));
                assertNotNull(row);
                assertTrue("Restored System category is highlighted", row.itemView.isActivated());
            });
            onView(withText(target.getString(R.string.set_developer_mode_title))).perform(click());
            main(() -> {
                assertFalse(DeveloperMode.isUnlocked());
                assertFalse(hasDeveloperHeader(ref.get()));
                assertEquals("screen_system", ref.get().currentSection());
                assertFalse(new AppConfig(target).isRawFrameDumpEnabled());
                assertTrue("Saved developer values survive disabling", prefs.getBoolean("raw_frame_dump", false));
            });
        } finally {
            prefs.edit().putBoolean("raw_frame_dump", oldRaw).commit();
            DeveloperMode.setEnabled(target, oldMode);
        }
    }

    private void capture(String name) {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(150, 2000);
            android.os.SystemClock.sleep(350); // Let the compositor present the committed layout.
            try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("screencap -p /sdcard/Download/" + name);
                 java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                while (in.read() != -1) {}
            }
        } catch (Exception e) { throw new AssertionError("Emulator settings capture failed", e); }
    }

    private SwitchPreferenceCompat toggle(SettingsShellFragment shell) {
        SettingsPreferenceFragment detail = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        return detail.findPreference("pref_developer_mode");
    }

    private boolean hasDeveloperHeader(SettingsShellFragment shell) {
        SettingsHeadersFragment headers = (SettingsHeadersFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_headers);
        return headers.findPreference("screen_developer") != null;
    }
}
