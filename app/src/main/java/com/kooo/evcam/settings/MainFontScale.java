package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;

/** Main-screen enlargement, established before AppCompat and view inflation. */
public final class MainFontScale {
    private MainFontScale() {}
    public static Context wrap(Context base) {
        Configuration local = new Configuration(base.getResources().getConfiguration());
        local.fontScale = 1.2f;
        return base.createConfigurationContext(local);
    }
}
