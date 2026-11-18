/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardHome;

/** 家的那一个点、那一段距离、那个保守的结论（{@link GuardHome}）。 */
public class GuardHomeTest {

    /** 悉尼歌剧院附近，一个真实存在的点。 */
    private static final double LAT = -33.856_159;
    private static final double LON = 151.215_297;

    @Test
    public void aStoredPointReadsBack() {
        GuardHome.Point point = GuardHome.parse(GuardHome.format(LAT, LON));
        assertNotNull(point);
        assertEquals(LAT, point.lat, 0.000_001);
        assertEquals(LON, point.lon, 0.000_001);
    }

    @Test
    public void nonsenseIsNotAPoint() {
        assertNull(GuardHome.parse(null));
        assertNull(GuardHome.parse(""));
        assertNull(GuardHome.parse("   "));
        assertNull(GuardHome.parse("home"));
        assertNull(GuardHome.parse("1,2,3"));
        assertNull(GuardHome.parse("-91,151"));
        assertNull(GuardHome.parse("-33,181"));
        assertNull(GuardHome.parse("NaN,151"));
        // 0,0 是「没定位」最常见的写法，不是几内亚湾
        assertNull(GuardHome.parse("0,0"));
    }

    @Test
    public void theRadiusIsTwoHundredMetres() {
        assertEquals(200.0, GuardHome.RADIUS_M, 0.001);
    }

    @Test
    public void distanceIsRightToWithinAGPSFix() {
        // 一个纬度大约 111.2 km
        assertEquals(111_195.0, GuardHome.distanceM(0, 0, 1, 0), 500.0);
        // 同一点是零
        assertEquals(0.0, GuardHome.distanceM(LAT, LON, LAT, LON), 0.001);
        // 0.0015 度纬度 ≈ 167 米：小于 200，算在家
        assertTrue(GuardHome.distanceM(LAT, LON, LAT + 0.0015, LON) < GuardHome.RADIUS_M);
        // 0.005 度 ≈ 556 米：在外面
        assertTrue(GuardHome.distanceM(LAT, LON, LAT + 0.005, LON) > GuardHome.RADIUS_M);
    }

    @Test
    public void aFixAtHomeIsHome() {
        assertEquals(GuardHome.Where.HOME,
                GuardHome.classify(GuardHome.parse(GuardHome.format(LAT, LON)), LAT, LON, 1_000L));
    }

    @Test
    public void aFixTwoKilometresAwayIsAway() {
        assertEquals(GuardHome.Where.AWAY,
                GuardHome.classify(GuardHome.parse(GuardHome.format(LAT, LON)),
                        LAT + 0.018, LON, 1_000L));
    }

    @Test
    public void noHomeIsUnknown() {
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(null, LAT, LON, 1_000L));
    }

    @Test
    public void noFixIsUnknown() {
        GuardHome.Point home = GuardHome.parse(GuardHome.format(LAT, LON));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, null, LON, 1_000L));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, LAT, null, 1_000L));
    }

    @Test
    public void aStaleOrNegativeAgeIsUnknown() {
        GuardHome.Point home = GuardHome.parse(GuardHome.format(LAT, LON));
        assertEquals(GuardHome.Where.HOME, GuardHome.classify(home, LAT, LON,
                GuardHome.FIX_MAX_AGE_MS));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, LAT, LON,
                GuardHome.FIX_MAX_AGE_MS + 1));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, LAT, LON, -1));
    }

    @Test
    public void aNonsenseFixIsUnknownRatherThanAway() {
        GuardHome.Point home = GuardHome.parse(GuardHome.format(LAT, LON));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, 0.0, 0.0, 1_000L));
        assertEquals(GuardHome.Where.UNKNOWN, GuardHome.classify(home, 95.0, 10.0, 1_000L));
    }
}
