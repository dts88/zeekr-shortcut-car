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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「哨兵模式被触发」从读数里怎么认（{@link SentryWatch}）：读数变成警戒、报警的那一下。
 *
 * How "Sentry Mode was triggered" is recognised from readings ({@link SentryWatch}).
 */
public class SentryWatchTest {

    private static final int OFF = 0;
    private static final int ON = 1;
    private static final int ARMED = SentryWatch.SENTRY_ARMED;
    private static final int ALERT = SentryWatch.SENTRY_ALERT;
    private static final int ALARM = SentryWatch.SENTRY_ALARM;

    private final SentryWatch watch = new SentryWatch();

    @Test
    public void armedMeansArmedAlertOrAlarm() {
        watch.update(ON);
        assertFalse("开着、没布防（锁车还没满 90 秒或者没锁）", watch.armed());
        watch.update(ARMED);
        assertTrue(watch.armed());
        watch.update(ALERT);
        assertTrue("被触发以后还看着", watch.armed());
        watch.update(ALARM);
        assertTrue(watch.armed());
        watch.update(ON);
        assertFalse("解锁回到开启", watch.armed());
        watch.update(OFF);
        assertFalse(watch.armed());
    }

    @Test
    public void triggeredWhenTheReadingBecomesAlertOrAlarm() {
        assertNull(watch.update(ARMED));
        assertEquals(SentryWatch.Trigger.ALERT, watch.update(ALERT));
        assertNull("一直警戒不算新的一下", watch.update(ALERT));
        assertEquals("警戒升到报警也算一下", SentryWatch.Trigger.ALARM, watch.update(ALARM));
        assertNull(watch.update(ARMED));
        assertEquals("回到布防再报警是新的一下", SentryWatch.Trigger.ALARM, watch.update(ALARM));
    }

    @Test
    public void armingOrUnlockingIsNotATrigger() {
        assertNull(watch.update(OFF));
        assertNull(watch.update(ON));
        assertNull(watch.update(ARMED));
        assertNull(watch.update(ON));
    }

    /** 锁车以后车辆接口可能读不到：读不到不算变化，照上一次真读到的算。 */
    @Test
    public void aMissingReadingKeepsTheLastOne() {
        watch.update(ARMED);
        assertNull(watch.update(null));
        assertTrue(watch.armed());
        assertEquals(Integer.valueOf(ARMED), watch.reading());
        assertEquals("读不到之后再读到警戒，照样是一下", SentryWatch.Trigger.ALERT, watch.update(ALERT));
    }

    /** 进程起来后头一次读到就是报警：不知道是不是刚变的，宁可报。 */
    @Test
    public void theFirstReadingCanBeATrigger() {
        assertEquals(SentryWatch.Trigger.ALARM, watch.update(ALARM));
    }

    @Test
    public void resetForgetsEverything() {
        watch.update(ALERT);
        watch.reset();
        assertNull(watch.reading());
        assertFalse(watch.armed());
        assertEquals(SentryWatch.Trigger.ALERT, watch.update(ALERT));
    }
}
