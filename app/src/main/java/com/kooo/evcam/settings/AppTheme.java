package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import androidx.appcompat.app.AppCompatDelegate;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** App-local DayNight choice. Service-owned camera controls resolve the same palette without restarting cameras. */
public final class AppTheme {
    public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";
    private static final Set<View> controls = Collections.newSetFromMap(new WeakHashMap<>());
    private AppTheme() {}

    public static int modeFor(String value) {
        if (LIGHT.equals(value)) return AppCompatDelegate.MODE_NIGHT_NO;
        if (DARK.equals(value)) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    /** Call on the main thread, like AppCompatDelegate and View lifecycle callbacks. */
    public static void apply(String value) {
        AppCompatDelegate.setDefaultNightMode(modeFor(value));
        for (View view : new java.util.ArrayList<>(controls)) view.invalidate();
    }

    public static void observe(View view) { controls.add(view); }
    public static void forget(View view) { controls.remove(view); }

    public static int nightMode(Context context) {
        int mode = AppCompatDelegate.getDefaultNightMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) return Configuration.UI_MODE_NIGHT_NO;
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) return Configuration.UI_MODE_NIGHT_YES;
        return context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    }

    public static Context palette(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        int mode = nightMode(context);
        if ((configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == mode) return context;
        Configuration selected = new Configuration(configuration);
        selected.uiMode = (selected.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
        return context.createConfigurationContext(selected);
    }
}
