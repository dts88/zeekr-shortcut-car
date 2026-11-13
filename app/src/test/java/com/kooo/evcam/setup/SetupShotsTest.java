/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceFragmentCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/** 初始设置的七步画成图片（{@link Shots}），顺带看住：第五、六步摆的是设置里的行；跳过以后不再走。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SetupShotsTest {

    private static final String[] NAMES = {"language", "before", "side", "drive", "record", "features", "done"};

    @Test
    public void everyStepByDay() {
        shoot(Shots.DAY, "day");
    }

    @Test
    public void everyStepByNight() {
        shoot(Shots.NIGHT, "night");
    }

    @Test
    public void stepsFiveAndSixShowTheSettingsRows() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SetupFragment setup = show(activity);

        setup.jumpTo(SetupFragment.RECORD);
        Shots.settle();
        PreferenceFragmentCompat rows = rows(setup);
        for (String key : new String[]{"pref_auto_record", "pref_screen_off_keep_recording", "pref_footage_audio",
                "pref_car_model", "pref_record_quality"}) {
            assertNotNull("第五步少了设置里的 " + key, rows.findPreference(key));
        }
        assertEquals("第五步只摆那五行", 5, rows.getPreferenceScreen().getPreferenceCount());

        setup.jumpTo(SetupFragment.FEATURES);
        Shots.settle();
        rows = rows(setup);
        for (String key : new String[]{"pref_rearview", "pref_side_view", "pref_recording_floating"}) {
            assertNotNull("第六步少了设置里的 " + key, rows.findPreference(key));
        }
        assertEquals("第六步只摆那三行", 3, rows.getPreferenceScreen().getPreferenceCount());

        setup.jumpTo(SetupFragment.DONE);
        Shots.settle();
        assertEquals("别的步不留着设置的行", null,
                setup.getChildFragmentManager().findFragmentById(R.id.setup_rows));
    }

    @Test
    public void skippingMarksSetupDone() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        assertTrue("新装的要走初始设置", config.needsSetup());
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SetupFragment setup = show(activity);

        View skip = setup.requireView().findViewById(R.id.setup_skip);
        skip.performClick();
        Shots.settle();

        assertFalse("跳过以后不再走", config.needsSetup());
    }

    private void shoot(String qualifiers, String tag) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SetupFragment setup = show(activity);
        for (int step = 0; step < SetupFragment.STEPS; step++) {
            setup.jumpTo(step);
            File file = Shots.save(activity, "setup-" + (step + 1) + "-" + NAMES[step] + "-" + tag);
            assertTrue(file + " 是空的", file.length() > 10_000);
        }
    }

    private static SetupFragment show(ShotActivity activity) {
        SetupFragment setup = new SetupFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, setup).commitNow();
        Shots.settle();
        return setup;
    }

    private static PreferenceFragmentCompat rows(SetupFragment setup) {
        Fragment rows = setup.getChildFragmentManager().findFragmentById(R.id.setup_rows);
        assertNotNull("这一步没有摆设置的行", rows);
        return (PreferenceFragmentCompat) rows;
    }
}
