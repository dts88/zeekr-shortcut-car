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

import com.kooo.evcam.alarm.policy.QuietHours;

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
        // D-10：安静时段看的是车机墙上的钟（只看一天里的第几分钟），不是绝对时间。
        // 所以时针跳一格，同一个瞬间就是另一个分钟数 —— 窗口在现实中跟着多/少一小时。
        // 钉的是两条边（不是 JDK 的偏移算术）：同一个 UTC 瞬间，换一格偏移，答案得翻过来。
        java.time.ZoneOffset before = java.time.ZoneOffset.ofHours(-5);
        java.time.ZoneOffset after = java.time.ZoneOffset.ofHours(-4);

        // 收尾那条边：换之前是清晨 06:30（在窗口里），换之后是 07:30（出去了）。
        java.time.OffsetDateTime nearTheEnd = java.time.OffsetDateTime.parse("2026-03-08T11:30:00Z");
        assertTrue(QuietHours.active(true, TEN_PM, SEVEN_AM, minuteOfDay(nearTheEnd, before)));
        assertFalse("跳一小时就该出窗口", QuietHours.active(true, TEN_PM, SEVEN_AM, minuteOfDay(nearTheEnd, after)));

        // 起头那条边：同一个瞬间换之前是 21:30（还没进），换之后是 22:30（进去了）。
        java.time.OffsetDateTime nearTheStart = java.time.OffsetDateTime.parse("2026-03-08T02:30:00Z");
        assertFalse(QuietHours.active(true, TEN_PM, SEVEN_AM, minuteOfDay(nearTheStart, before)));
        assertTrue("跳一小时就该进窗口", QuietHours.active(true, TEN_PM, SEVEN_AM, minuteOfDay(nearTheStart, after)));
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
