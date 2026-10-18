package com.kooo.evcam.settings;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.View;
import androidx.preference.ListPreference;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.update.UpdateChannel;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/** Disposable emulator: real SharedPreferences migration and native choice/confirmation behavior. */
@RunWith(AndroidJUnit4.class)
public class UpdateChannelsAndroidTest {
    private Context target() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private SharedPreferences prefs() { return target().getSharedPreferences("app_config", Context.MODE_PRIVATE); }
    private static final String[] KEYS = {"update_channel", "update_include_beta", "first_launch",
            "language_chosen", "rail_side_chosen"};

    private Map<String, Object> backup() {
        Map<String, Object> values = new HashMap<>();
        for (String key : KEYS) values.put(key, prefs().getAll().get(key));
        return values;
    }
    private void restore(Map<String, Object> values) {
        SharedPreferences.Editor edit = prefs().edit();
        for (String key : KEYS) {
            Object value = values.get(key);
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else edit.remove(key);
        }
        assertTrue(edit.commit());
    }

    @Test public void migrationPreservesLegacyChoicesAndNeverEnablesAlpha() {
        Map<String, Object> old = backup();
        try {
            prefs().edit().remove("update_channel").remove("update_include_beta")
                    .putBoolean("first_launch", true).putBoolean("language_chosen", false).commit();
            AppConfig config = new AppConfig(target());
            assertEquals(UpdateChannel.BETA, config.getUpdateChannel());
            prefs().edit().putBoolean("first_launch", false).putBoolean("language_chosen", true).commit();
            assertEquals(UpdateChannel.BETA, new AppConfig(target()).getUpdateChannel());

            prefs().edit().remove("update_channel").putBoolean("update_include_beta", false).commit();
            assertEquals(UpdateChannel.STABLE, new AppConfig(target()).getUpdateChannel());
            prefs().edit().remove("update_channel").putBoolean("update_include_beta", true).commit();
            assertEquals(UpdateChannel.BETA, new AppConfig(target()).getUpdateChannel());
            // Established installs that never touched the old default-on switch stay on Beta.
            prefs().edit().remove("update_channel").remove("update_include_beta").commit();
            assertEquals(UpdateChannel.BETA, new AppConfig(target()).getUpdateChannel());

            config.setUpdateChannel(UpdateChannel.ALPHA);
            prefs().edit().putBoolean("update_include_beta", false).commit();
            assertEquals(UpdateChannel.ALPHA, new AppConfig(target()).getUpdateChannel());
            prefs().edit().putString("update_channel", "nightly").commit();
            assertEquals(UpdateChannel.STABLE, new AppConfig(target()).getUpdateChannel());
        } finally { restore(old); }
    }

    @Test public void pickerRequiresConfirmationAndSurvivesActivityRecreation() throws Exception {
        Map<String, Object> old = backup();
        for (String permission : new String[]{Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(target().getPackageName(), permission);
        }
        prefs().edit().remove("update_channel").putBoolean("update_include_beta", true)
                .putBoolean("first_launch", false).putBoolean("language_chosen", true)
                .putBoolean("rail_side_chosen", true).commit();
        try (ActivityScenario<SettingsTestHostActivity> scenario = ActivityScenario.launch(SettingsTestHostActivity.class)) {
            scenario.onActivity(activity -> {
                SettingsShellFragment shell = new SettingsShellFragment();
                activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, shell).commitNow();
                shell.getChildFragmentManager().executePendingTransactions();
                shell.showSection("screen_update");
                shell.getChildFragmentManager().executePendingTransactions();
                assertEquals("beta", choice(shell).getValue());
                assertEquals(activity.getString(R.string.update_channel_beta), choice(shell).getSummary());
            });
            capture("update-channel-row.png");
            onView(withText(R.string.set_update_channel_title)).perform(click());
            onView(withText(R.string.update_channel_alpha_option)).perform(click());
            capture("update-channel-options.png");
            onView(withText(R.string.action_cancel)).perform(click());
            assertEquals(UpdateChannel.BETA, new AppConfig(target()).getUpdateChannel());

            onView(withText(R.string.set_update_channel_title)).perform(click());
            onView(withText(R.string.update_channel_alpha_option)).perform(click());
            onView(withText(R.string.action_ok)).perform(click());
            assertEquals(UpdateChannel.ALPHA, new AppConfig(target()).getUpdateChannel());
            scenario.recreate();
            scenario.onActivity(activity -> {
                SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container);
                shell.getChildFragmentManager().executePendingTransactions();
                assertEquals("alpha", choice(shell).getValue());
                assertEquals(activity.getString(R.string.update_channel_alpha), choice(shell).getSummary());
            });
            capture("update-channel-alpha.png");
            onView(withText(R.string.set_update_channel_title)).perform(click());
            onView(withText(R.string.update_channel_stable_option)).perform(click());
            onView(withText(R.string.action_ok)).perform(click());
            assertEquals(UpdateChannel.STABLE, new AppConfig(target()).getUpdateChannel());
        } finally { restore(old); }
    }

    private ListPreference choice(SettingsShellFragment shell) {
        SettingsPreferenceFragment detail = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        return detail.findPreference("pref_update_channel");
    }
    private void capture(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(200);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(target().getExternalFilesDir(null), name))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }
}
