package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.widget.TextViewCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual TextView sizing, row reuse and scrolling; never invokes About/Quit actions. */
@RunWith(AndroidJUnit4.class)
public class SettingsHeaderTypographyAndroidTest {
    private Context target() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private void main(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private Context context(float scale, int night) {
        Configuration c = new Configuration(target().getResources().getConfiguration());
        c.fontScale = scale; c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        c.setLocale(java.util.Locale.ENGLISH);
        return new ContextThemeWrapper(target().createConfigurationContext(c), R.style.Theme_Cam);
    }
    private void measure(View view, Context c, int widthDp) {
        int width = Math.round(widthDp * c.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    @Test public void reusedHeaderRestoresUniformFontWithoutAutoshrink() {
        main(() -> {
            for (float scale : new float[]{1f, 1.3f}) for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO,
                    Configuration.UI_MODE_NIGHT_YES}) {
                Context c = context(scale, night);
                View view = LayoutInflater.from(c).inflate(R.layout.pref_header_row, null);
                PreferenceViewHolder holder = PreferenceViewHolder.createInstanceForTests(view);
                TextView title = view.findViewById(android.R.id.title);
                String[] labels = {"360° camera", "Floating button", "Developer options", "Check for updates",
                        "About & credits", "Quit app", "Recording & photos", "About & credits"};
                for (String label : labels) {
                    // A recycled title must not retain a smaller size or autosizing from another binding.
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(title, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
                    SettingsHeadersFragment.HeaderRow row = new SettingsHeadersFragment.HeaderRow(c,
                            !label.startsWith("About") && !label.startsWith("Quit"), 0);
                    row.setTitle(label); row.setSelectedRow(label.startsWith("Floating")); row.onBindViewHolder(holder);
                    measure(view, c, 240);
                    assertEquals(label, c.getResources().getDimension(R.dimen.text_body), title.getTextSize(), .1f);
                    assertEquals(TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE, TextViewCompat.getAutoSizeTextType(title));
                    assertTrue(label, title.getLineCount() <= 2);
                    assertEquals(label, title.length(), title.getLayout().getLineEnd(title.getLineCount() - 1));
                    assertEquals(label, 0, title.getLayout().getEllipsisCount(title.getLineCount() - 1));
                    assertTrue(title.getBottom() <= view.getHeight());
                }
            }
        });
    }
    private SettingsHeadersFragment headers(TypographyTestHostActivity activity) {
        SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.fragment_container);
        return (SettingsHeadersFragment) shell.getChildFragmentManager().findFragmentById(R.id.settings_headers);
    }
    private void checkVisible(SettingsHeadersFragment headers) {
        RecyclerView list = headers.getListView(); PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
        for (int i = 0; i < list.getChildCount(); i++) {
            View view = list.getChildAt(i); int position = list.getChildAdapterPosition(view);
            if (position == RecyclerView.NO_POSITION) continue;
            TextView title = view.findViewById(android.R.id.title);
            assertEquals(adapter.getItem(position).getKey(), view.getResources().getDimension(R.dimen.text_body),
                    title.getTextSize(), .1f);
        }
    }
    @Test public void sidebarKeepsAboutAndQuitSizeAfterScrollingInBothThemes() {
        Context context = target(); boolean oldDeveloper = DeveloperMode.isUnlocked();
        int oldNight = AppCompatDelegate.getDefaultNightMode();
        context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().putBoolean("first_launch", false)
                .putBoolean("language_chosen", true).putBoolean("rail_side_chosen", true).commit();
        DeveloperMode.unlock(context, "6651");
        try {
            for (int mode : new int[]{AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES}) {
                main(() -> AppCompatDelegate.setDefaultNightMode(mode));
                try (ActivityScenario<TypographyTestHostActivity> scenario = ActivityScenario.launch(TypographyTestHostActivity.class)) {
                    scenario.onActivity(activity -> {
                        SettingsShellFragment shell = new SettingsShellFragment();
                        activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
                        activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, shell).commitNow();
                        shell.getChildFragmentManager().executePendingTransactions(); shell.showSection("screen_floating");
                        shell.getChildFragmentManager().executePendingTransactions();
                    });
                    for (boolean end : new boolean[]{true, false, true}) {
                        scenario.onActivity(activity -> {
                            RecyclerView list = headers(activity).getListView();
                            list.scrollToPosition(end ? list.getAdapter().getItemCount() - 1 : 0);
                        });
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(200);
                        scenario.onActivity(activity -> checkVisible(headers(activity)));
                    }
                    scenario.onActivity(activity -> {
                        SettingsHeadersFragment h = headers(activity); RecyclerView list = h.getListView();
                        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
                        for (String key : new String[]{"pref_about", "pref_exit"}) {
                            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(adapter.getPreferenceAdapterPosition(key));
                            assertNotNull(key, holder); assertTrue(holder.itemView.getHeight() > 0);
                        }
                    });
                    android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),
                            "settings-headers-" + mode + ".png"))) {
                        assertNotNull(screenshot); screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    } catch (java.io.IOException e) { throw new AssertionError(e); }
                    finally { if (screenshot != null) screenshot.recycle(); }
                }
            }
        } finally {
            if (oldDeveloper) DeveloperMode.unlock(context, "6651"); else DeveloperMode.lock(context); main(() -> AppCompatDelegate.setDefaultNightMode(oldNight));
        }
    }
}
