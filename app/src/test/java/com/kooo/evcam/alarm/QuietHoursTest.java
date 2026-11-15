/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
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
 *
 * Quiet hours ({@link QuietHours}): 22:00 → 07:00 is one window, crossing
 * midnight is fine too; a zero-length window is not "quiet all day".
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
    public void aDstChangeMovesTheWindowByOneRealHour() {
        // D-10：安静时段看的是墙上的钟（只看一天里的第几分钟），不是绝对时间。
        // 所以同一个瞬间在时针跳了一格之后就是另一个分钟数，窗口在现实中跟着多/少一小时。
        java.time.OffsetDateTime sameInstant =
                java.time.OffsetDateTime.parse("2026-03-08T06:30:00Z");
        int beforeTheChange = minuteOfDay(sameInstant, java.time.ZoneOffset.ofHours(-5));
        int afterTheChange = minuteOfDay(sameInstant, java.time.ZoneOffset.ofHours(-4));

        assertEquals(60, afterTheChange - beforeTheChange);
        // 判断只看分钟数：两个分钟数它都给同一个答案 —— 行为没动，动的是一小时现实时间。
        // 春进那一夜窗口在现实中是五小时，回拨那一夜是九小时（D-10）。
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, beforeTheChange));
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, afterTheChange));
    }

    private static int minuteOfDay(java.time.OffsetDateTime at, java.time.ZoneOffset offset) {
        java.time.OffsetDateTime local = at.withOffsetSameInstant(offset);
        return local.getHour() * 60 + local.getMinute();
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
