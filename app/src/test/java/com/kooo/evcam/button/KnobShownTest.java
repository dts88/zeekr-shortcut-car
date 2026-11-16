/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.CarAccess;
import com.kooo.evcam.telemetry.CarNames;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class KnobShownTest {

    private static final int HEAT = 0x1000;
    private static final int CIRCULATION = 0x4000;
    private static final int DRIVER = 1;
    private static final int ALL = 0x80000000;

    private final Map<String, Integer> values = new HashMap<>();

    private CarMoves.Car car() {
        CarAccess access = new CarAccess() {
            @Override
            public Integer read(int id) {
                return values.get(String.valueOf(id));
            }

            @Override
            public Integer read(int id, int zone) {
                return values.get(id + "@" + zone);
            }

            @Override
            public boolean write(int id, int value) {
                return false;
            }

            @Override
            public boolean write(int id, int zone, int value) {
                return false;
            }

            @Override
            public Float readFloat(int id, int zone) {
                return null;
            }

            @Override
            public boolean writeFloat(int id, int zone, float value) {
                return false;
            }

            @Override
            public Integer config(int n) {
                return null;
            }

            @Override
            public String status(int id, int zone) {
                return "active";
            }

            @Override
            public Float sensor(int type) {
                return null;
            }
        };
        CarMoves.Names names = new CarMoves.Names() {
            @Override
            public Integer function(String iface, String field) {
                String key = iface + "." + field;
                if (key.equals("IHvac.HVAC_FUNC_SEAT_HEATING")) {
                    return HEAT;
                }
                return key.equals("IHvac.HVAC_FUNC_CIRCULATION") ? Integer.valueOf(CIRCULATION) : null;
            }

            @Override
            public int zone(String name) {
                return CarNames.DRIVER.equals(name) ? DRIVER : ALL;
            }
        };
        return new CarMoves.Car(access, names, ms -> { }, null, null);
    }

    @Test
    public void shownValuesUseTheLabels() {
        Context app = RuntimeEnvironment.getApplication();
        values.put(HEAT + "@" + DRIVER, HEAT + 2);
        assertEquals(app.getString(R.string.knob_v_level, 2), KnobTurn.DRIVER_SEAT_HEAT.dial.shown(car(), app, 0));
        values.put(CIRCULATION + "@" + ALL, CIRCULATION + 1);
        assertEquals(app.getString(R.string.knob_v_circ_inside), KnobTurn.CIRCULATION.dial.shown(car(), app, 0));
        values.put(CIRCULATION + "@" + ALL, 99);
        assertNull(KnobTurn.CIRCULATION.dial.shown(car(), app, 0));
        assertEquals(app.getString(R.string.knob_v_windows_vent), KnobTurn.WINDOWS_VENT.dial.shown(car(), app, 1));
        assertEquals(app.getString(R.string.knob_v_windows_close), KnobTurn.WINDOWS.dial.shown(car(), app, -1));
    }
}
