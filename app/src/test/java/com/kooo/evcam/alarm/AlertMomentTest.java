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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/**
 * 警报里那个时刻（{@link AlertMoment}）：写出来长什么样、读回来是不是同一刻。
 *
 * <p>这里全部用「设备默认时区里的今天」算期望值，所以换个时区跑也是同一套断言；只有 ISO 的时区
 * 偏移随机器变，所以那一条只钉形状不钉具体偏移。</p>
 *
 * The moment inside an alert ({@link AlertMoment}): what it looks like written down, and
 * whether reading it back lands on the same instant.
 *
 * <p>Every expectation here is built as "today in the device's default zone", so the assertions
 * hold in any zone; only the ISO offset varies by machine, so that case pins the shape and not
 * the offset.</p>
 */
public class AlertMomentTest {

    /** 一天某一刻的墙钟毫秒，按默认时区算。 */
    private static long today(int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void isoCarriesTheDateTheTimeAndTheOffset() {
        String iso = AlertMoment.iso(today(18, 16, 4));
        assertTrue("要带时区偏移 / must carry the offset: " + iso,
                iso.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d[+-]\\d\\d:\\d\\d"));
        // 纪元那一刻、冬天、夏天都只要求形状对：偏移随机器变。
        // Epoch, winter and summer only need the right shape: the offset varies by machine.
        for (long wallMs : new long[]{0L, 1_700_000_000_000L, today(0, 0, 0)}) {
            assertTrue(AlertMoment.iso(wallMs),
                    AlertMoment.iso(wallMs).matches(
                            "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d[+-]\\d\\d:\\d\\d"));
        }
    }

    @Test
    public void clockIsTheTimeOfDayOnly() {
        assertEquals("18:16", AlertMoment.clock(today(18, 16, 4)));
        assertEquals("00:05", AlertMoment.clock(today(0, 5, 0)));
    }

    @Test
    public void everyAcceptedFormReadsToTheSameMoment() {
        long now = today(23, 30, 0);
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("18:22", now));
        assertEquals(Long.valueOf(today(18, 22, 30)), AlertMoment.parseClock("18:22:30", now));
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("6:22pm", now));
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("6:22 pm", now));
        assertEquals(Long.valueOf(today(18, 0, 0)), AlertMoment.parseClock("6pm", now));
        // 头一个字丢掉，前后空格也丢掉。
        // The leading word goes, and so does the surrounding space.
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("pic 18:22", now));
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("  PIC 6:22pm  ", now));
        assertEquals(Long.valueOf(today(18, 22, 0)), AlertMoment.parseClock("status 18:22", now));
    }

    @Test
    public void aFutureMomentDoesNotParse() {
        long now = today(10, 0, 0);
        assertNull("比现在晚的读不懂 / later than now does not parse",
                AlertMoment.parseClock("18:22", now));
        assertNull(AlertMoment.parseClock("10:01:01", now));
        // 一分钟以内的容差还是要认：字写的是分钟，警报可能就在此刻。
        // The one-minute slack still parses: the text is minute-precision, the alert may be now.
        assertNotNull(AlertMoment.parseClock("10:00", now));
        assertNull(AlertMoment.parseClock("10:01:02", now));
    }

    @Test
    public void amAndPmTurnMidnightAndNoonAround() {
        long now = today(23, 30, 0);
        assertEquals("12am 是零点 / 12am is midnight",
                Long.valueOf(today(0, 0, 0)), AlertMoment.parseClock("12am", now));
        assertEquals("12pm 是正午 / 12pm is noon",
                Long.valueOf(today(12, 0, 0)), AlertMoment.parseClock("12pm", now));
        assertEquals(Long.valueOf(today(1, 0, 0)), AlertMoment.parseClock("1am", now));
        assertEquals(Long.valueOf(today(13, 0, 0)), AlertMoment.parseClock("1pm", now));
        // 二十四小时制的零点写法。
        // The 24-hour way of writing midnight.
        assertEquals(Long.valueOf(today(0, 0, 0)), AlertMoment.parseClock("00:00", now));
    }

    @Test
    public void nonsenseDoesNotParse() {
        long now = today(23, 30, 0);
        assertNull(AlertMoment.parseClock(null, now));
        assertNull(AlertMoment.parseClock("", now));
        assertNull(AlertMoment.parseClock("   ", now));
        assertNull(AlertMoment.parseClock("nonsense", now));
        assertNull(AlertMoment.parseClock("pic", now));
        assertNull(AlertMoment.parseClock("25:00", now));
        assertNull(AlertMoment.parseClock("18:60", now));
        assertNull(AlertMoment.parseClock("6:2", now));
        assertNull(AlertMoment.parseClock("13pm", now));
        assertNull(AlertMoment.parseClock("0pm", now));
        assertNull("没有 am/pm 的裸小时说不清上下午 / a bare hour with no am/pm is ambiguous",
                AlertMoment.parseClock("6", now));
    }

    @Test
    public void withinRingHoldsBothEdgesAndRefusesTheRest() {
        long event = today(18, 16, 0);
        assertTrue("刚发生 / just happened", AlertMoment.withinRing(event, event, 60_000L));
        assertTrue("正好到边也算在里面 / the far edge is still inside",
                AlertMoment.withinRing(event, event + 60_000L, 60_000L));
        assertFalse("过了窗口 / past the window",
                AlertMoment.withinRing(event, event + 60_001L, 60_000L));
        assertFalse("时刻在未来不算 / a future moment is outside",
                AlertMoment.withinRing(event, event - 1L, 60_000L));
        assertFalse("窗口为零装不下东西 / a zero window holds nothing",
                AlertMoment.withinRing(event, event, 0L));
        assertFalse("窗口为负装不下东西 / a negative window holds nothing",
                AlertMoment.withinRing(event, event, -1L));
    }

    @Test
    public void describeDropsTheDateOnlyForToday() {
        long now = today(18, 20, 0);
        assertEquals("18:16", AlertMoment.describe(today(18, 16, 0), now));

        Calendar earlier = Calendar.getInstance();
        earlier.setTimeInMillis(now);
        earlier.add(Calendar.DAY_OF_MONTH, -1);
        earlier.set(Calendar.HOUR_OF_DAY, 23);
        earlier.set(Calendar.MINUTE, 50);
        earlier.set(Calendar.SECOND, 0);
        earlier.set(Calendar.MILLISECOND, 0);
        long yesterday = earlier.getTimeInMillis();

        String described = AlertMoment.describe(yesterday, now);
        assertTrue("隔天要带日期 / a different day carries the date: " + described,
                described.matches("\\d{4}-\\d\\d-\\d\\d 23:50"));
        // 绝不能退回只写时间那种写法，那样看的人会以为是今天。
        // It must never fall back to the time-only form, which reads as today.
        assertFalse("前一天那种只写时间 / never the time-only form", described.equals("23:50"));
    }
}
