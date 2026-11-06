/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 功能号、区域按常量名取（{@link CarNames}）：这里没有车机系统的接口类，看找的次序和没有时的退路。 */
public class CarNamesTest {

    @Test
    public void interfacesAreLookedUpInTheKnownPackagesFirst() {
        String[] candidates = CarNames.candidates("IHvac");
        assertEquals("com.ecarx.xui.adaptapi.car.hvac.IHvac", candidates[0]);
        assertEquals("com.ecarx.xui.adaptapi.car.vehicle.IHvac", candidates[1]);
        assertEquals(CarNames.PACKAGES.length, candidates.length);
        assertEquals("com.ecarx.xui.adaptapi.car.IHvac", candidates[candidates.length - 1]);
    }

    /** 车机上没有这个接口、这个常量：没有这个功能。 */
    @Test
    public void aMissingConstantIsNull() {
        assertNull(CarNames.function("IHvac", "HVAC_FUNC_SEAT_HEATING"));
        assertNull(CarNames.function("INoSuchInterface", "NO_SUCH_FIELD"));
    }

    /** 车上没有区域那个类时照 ECARX 自己的那一份。 */
    @Test
    public void zonesFallBackToTheKnownValues() {
        assertEquals(0x80000000, CarNames.zone(CarNames.ALL));
        assertEquals(0x1, CarNames.zone(CarNames.DRIVER));
        assertEquals(0x4, CarNames.zone(CarNames.PASSENGER));
        assertEquals(0x8, CarNames.zone(CarNames.ROW_1));
        assertEquals(0x10, CarNames.zone(CarNames.ROW_2_LEFT));
        assertEquals(0x40, CarNames.zone(CarNames.ROW_2_RIGHT));
        assertEquals(0x80, CarNames.zone(CarNames.ROW_2));
        assertEquals(0x2, CarNames.zone(CarNames.ROW_1_CENTER));
        assertEquals(0, CarNames.zone("NO_SUCH_ZONE"));
    }
}
