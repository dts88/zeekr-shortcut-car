package com.kooo.evcam.settings;

import android.Manifest;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class AppThemeSettingsAndroidTest {
    private SettingsPreferenceFragment page(SettingsTestHostActivity activity) {
        SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        return (SettingsPreferenceFragment) shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
    }
    private float checkTypography(SettingsTestHostActivity activity) {
        SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        SettingsHeadersFragment headers = (SettingsHeadersFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_headers);
        RecyclerView list = headers.getListView();
        assertTrue(list.getChildCount() > 0);
        for (int i = 0; i < list.getChildCount(); i++) {
            TextView title = list.getChildAt(i).findViewById(android.R.id.title);
            assertEquals(activity.getResources().getDimension(R.dimen.text_body), title.getTextSize(), .51f);
        }
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) page(activity).getListView().getAdapter();
        RecyclerView.ViewHolder row = page(activity).getListView().findViewHolderForAdapterPosition(
                adapter.getPreferenceAdapterPosition("pref_theme"));
        assertNotNull(row);
        TextView title = row.itemView.findViewById(android.R.id.title);
        // XML textSize may round sp to a pixel; allow at most half a pixel for header inflation.
        assertEquals(activity.getResources().getDimensionPixelSize(R.dimen.text_body), title.getTextSize(), .1f);
        return title.getTextSize();
    }
    @Test public void actualPickerPersistsAcrossRecreationAndBothThemesKeepMenuSize() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppConfig config = new AppConfig(target);
        String original = config.getThemeMode(); int oldMode = AppCompatDelegate.getDefaultNightMode();
        for (String permission : new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(target.getPackageName(), permission);
        target.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().putBoolean("first_launch", false)
                .putBoolean("language_chosen", true).putBoolean("rail_side_chosen", true).commit();
        try (ActivityScenario<SettingsTestHostActivity> scenario = ActivityScenario.launch(SettingsTestHostActivity.class)) {
            scenario.onActivity(activity -> {
                SettingsShellFragment shell = new SettingsShellFragment();
                activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
                activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, shell).commitNow();
                shell.getChildFragmentManager().executePendingTransactions();
                shell.showSection("screen_interface"); shell.getChildFragmentManager().executePendingTransactions();
            });
            float[] lightSize = {0};
            onView(withText(R.string.set_theme_title)).perform(click());
            onView(withText(R.string.theme_dark)).perform(click());
            onView(withText(R.string.action_cancel)).perform(click());
            assertEquals(original, config.getThemeMode());
            for (String value : new String[]{AppTheme.LIGHT, AppTheme.DARK, AppTheme.SYSTEM}) {
                String previous = config.getThemeMode();
                onView(withText(R.string.set_theme_title)).perform(click());
                if (AppTheme.LIGHT.equals(value)) capture(target, "theme-options");
                int label = AppTheme.LIGHT.equals(value) ? R.string.theme_light
                        : AppTheme.DARK.equals(value) ? R.string.theme_dark : R.string.theme_system;
                onView(withText(label)).perform(click());
                assertEquals("Selecting before OK must not change the theme", previous, config.getThemeMode());
                onView(withText(R.string.action_ok)).perform(click());
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals(value, new AppConfig(activity).getThemeMode());
                    ListPreference theme = page(activity).findPreference("pref_theme");
                    assertEquals(value, theme.getValue()); assertEquals(activity.getString(label), theme.getSummary());
                    int expected = AppTheme.SYSTEM.equals(value)
                            ? target.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK
                            : AppTheme.DARK.equals(value) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
                    assertEquals(expected, activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
                    float size = checkTypography(activity);
                    if (AppTheme.LIGHT.equals(value)) lightSize[0] = size;
                    else assertEquals("Theme cannot change menu font size", lightSize[0], size, .1f);
                });
                capture(target, "theme-" + value);
            }
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals(AppTheme.SYSTEM,
                    ((ListPreference) page(activity).findPreference("pref_theme")).getValue()));
        } finally {
            config.setThemeMode(original);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                AppTheme.apply(original); AppCompatDelegate.setDefaultNightMode(oldMode);
            });
        }
    }
    private void capture(Context context, String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureTheme"))) return;
        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); android.os.SystemClock.sleep(300);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null), name + ".png"))) {
            assertNotNull(image); assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { if (image != null) image.recycle(); }
    }
}
