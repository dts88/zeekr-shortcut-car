/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 安静时段（{@link QuietHours}）：22:00 → 07:00 是一段，跨午夜也对；零长的窗口不是“全天安静”。
 */
public class QuietHoursTest {

    private static final int TEN_PM = 22 * 60;
    private static final int SEVEN_AM = 7 * 60;

    @Test
    public void offMeansNeverQuiet() {
        assertFalse(QuietHours.active(false, TEN_PM, SEVEN_AM, 23 * 60));
    }

    @Test
    public void aWindowAcrossMidnightCoversLateEveningAndEarlyMorning() {
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, 22 * 60));
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, 23 * 60 + 59));
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, 0));
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, 6 * 60 + 59));
        assertFalse("07:00 整已经出了窗口", QuietHours.active(true, TEN_PM, SEVEN_AM, SEVEN_AM));
        assertFalse(QuietHours.active(true, TEN_PM, SEVEN_AM, 12 * 60));
        assertFalse("21:59 还没进窗口", QuietHours.active(true, TEN_PM, SEVEN_AM, TEN_PM - 1));
    }

    @Test
    public void aWindowInsideOneDayWorksToo() {
        int noon = 12 * 60;
        int two = 14 * 60;
        assertTrue(QuietHours.active(true, noon, two, 13 * 60));
        assertFalse(QuietHours.active(true, noon, two, 11 * 60));
        assertFalse(QuietHours.active(true, noon, two, 15 * 60));
    }

    @Test
    public void aZeroLengthWindowIsNoWindowNotAllDay() {
        assertFalse(QuietHours.active(true, TEN_PM, TEN_PM, TEN_PM));
        assertFalse(QuietHours.active(true, TEN_PM, TEN_PM, 3 * 60));
    }

    @Test
    public void timesRoundTripAsHoursAndMinutes() {
        assertEquals(TEN_PM, QuietHours.parse("22:00"));
        assertEquals(SEVEN_AM, QuietHours.parse("07:00"));
        assertEquals(7 * 60 + 5, QuietHours.parse("7:05"));
        assertEquals(-1, QuietHours.parse("2200"));
        assertEquals(-1, QuietHours.parse("24:00"));
        assertEquals(-1, QuietHours.parse("22:61"));
        assertEquals(-1, QuietHours.parse(""));
        assertEquals(-1, QuietHours.parse(null));
        assertEquals("22:00", QuietHours.format(TEN_PM));
        assertEquals("07:05", QuietHours.format(7 * 60 + 5));
    }
}
