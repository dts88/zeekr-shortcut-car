/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class AccessoryConfigTest {

    private static final String BUTTON = "00:11:22:33:44:55";
    private static final String KNOB = "66:77:88:99:AA:BB";

    private AppConfig config;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        config = new AppConfig(context);
        prefs = context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void eachKindKeepsItsOwnList() {
        config.addAccessory(Accessory.BUTTON, BUTTON);
        config.addAccessory(Accessory.KNOB, KNOB);
        assertEquals(Collections.singletonList(BUTTON), config.getAccessories(Accessory.BUTTON));
        assertEquals(Collections.singletonList(KNOB), config.getAccessories(Accessory.KNOB));
        assertEquals(BUTTON, prefs.getString("smart_buttons", ""));
        assertEquals(KNOB, prefs.getString("x_knobs", ""));
    }

    @Test
    public void oneSwitchIsOnUntilTurnedOff() {
        assertTrue(config.isAccessoriesEnabled());
        config.setAccessoriesEnabled(false);
        assertFalse(config.isAccessoriesEnabled());
        assertFalse(prefs.getBoolean("smart_buttons_enabled", true));
        assertFalse(prefs.contains("x_knobs_enabled"));
    }

    @Test
    public void aNewKnobTurnsTheVolumeUntilSomethingElseIsChosen() {
        config.addAccessory(Accessory.KNOB, KNOB);
        assertEquals(KnobTurn.VOLUME, config.getKnobTurn(KNOB));
        assertEquals("none", config.getAccessoryAction(Accessory.KNOB, KNOB, SmartButtonAd.SINGLE));
        config.setKnobTurn(KNOB, KnobTurn.SUSPENSION);
        assertEquals(KnobTurn.SUSPENSION, config.getKnobTurn(KNOB));
        prefs.edit().putString("x_knob_turn." + KNOB, "nonsense").commit();
        assertEquals(KnobTurn.NONE, config.getKnobTurn(KNOB));
    }

    @Test
    public void exclusiveModeIsOffAndShowsTheDefaultList() {
        config.addAccessory(Accessory.KNOB, KNOB);
        assertFalse(config.isKnobExclusive(KNOB));
        assertEquals(KnobTurn.home(), config.getKnobHome(KNOB));
        config.setKnobExclusive(KNOB, true);
        assertTrue(config.isKnobExclusive(KNOB));
        config.setKnobHome(KNOB, Arrays.asList(KnobTurn.FAN, KnobTurn.VOLUME));
        assertEquals(Arrays.asList(KnobTurn.VOLUME, KnobTurn.FAN), config.getKnobHome(KNOB));
        prefs.edit().putString("x_knob_home." + KNOB, "nonsense").commit();
        assertEquals(KnobTurn.home(), config.getKnobHome(KNOB));
    }

    @Test
    public void removingAKnobForgetsItsActionsAndName() {
        config.addAccessory(Accessory.KNOB, KNOB);
        config.setAccessoryAction(Accessory.KNOB, KNOB, SmartButtonAd.DOUBLE, "media_next");
        config.setKnobTurn(KNOB, KnobTurn.TEMPERATURE);
        config.setKnobExclusive(KNOB, true);
        config.setKnobHome(KNOB, Arrays.asList(KnobTurn.FAN));
        config.setAccessoryName(Accessory.KNOB, KNOB, "中控台");
        config.removeAccessory(Accessory.KNOB, KNOB);
        assertTrue(config.getAccessories(Accessory.KNOB).isEmpty());
        for (String key : prefs.getAll().keySet()) {
            assertFalse(key, key.startsWith("x_knob_action.") || key.startsWith("x_knob_name.")
                    || key.startsWith("x_knob_turn.") || key.startsWith("x_knob_exclusive.")
                    || key.startsWith("x_knob_home."));
        }
    }

    @Test
    public void theSameAddressIsNotListedTwice() {
        config.addAccessory(Accessory.KNOB, KNOB);
        config.addAccessory(Accessory.KNOB, KNOB);
        assertEquals(Arrays.asList(KNOB), config.getAccessories(Accessory.KNOB));
    }
}
