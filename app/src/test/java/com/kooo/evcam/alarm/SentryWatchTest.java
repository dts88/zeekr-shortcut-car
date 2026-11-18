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
 * 「哨兵模式布防满 30 秒后，车辆闪烁双闪或车灯自行亮起」从读数里怎么认（{@link SentryWatch}）。
 *
 * How the moment is recognised from readings ({@link SentryWatch}).
 */
public class SentryWatchTest {

    private static final int OFF = 0;
    private static final int ON = 1;
    private static final int ARMED = SentryWatch.SENTRY_ARMED;
    private static final int HAZARD = SentryWatch.INDICATOR_HAZARD;

    private final SentryWatch watch = new SentryWatch();

    /** 只给哨兵和转向指示，灯都关着。Sentry and indicator only, every lamp off. */
    private SentryWatch.Trigger feed(Integer sentry, Integer indicator, long now) {
        return watch.update(sentry, indicator, false, false, false, false, now);
    }

    @Test
    public void armedMeansTheSentryReadingSaysArmed() {
        feed(ON, 0, 0);
        assertFalse("开着、没布防（锁车还没满 90 秒或者没锁）", watch.armed());
        feed(ARMED, 0, 1_000);
        assertTrue(watch.armed());
        feed(ON, 0, 2_000);
        assertFalse("解锁回到「开启」", watch.armed());
        feed(OFF, 0, 3_000);
        assertFalse(watch.armed());
    }

    @Test
    public void aMissingReadingKeepsTheLastRealOne() {
        feed(ON, 0, 0);
        feed(ARMED, 0, 1_000);
        feed(null, null, 2_000);
        assertTrue("锁车后读不到，照上一次真读到的算", watch.armed());
    }

    @Test
    public void thirtySecondsAfterArming() {
        feed(ON, 0, 0);
        feed(ARMED, 0, 10_000);
        assertEquals(30_000L, watch.waitMs(10_000));
        assertEquals(10_000L, watch.waitMs(30_000));
        assertEquals(0L, watch.waitMs(40_000));
        assertEquals(0L, watch.waitMs(500_000));
    }

    @Test
    public void armedFromTheStartMeansNoWait() {
        // 进程起来时车已经布防着：不知道布防了多久，照常提醒
        feed(ARMED, 0, 5_000);
        assertTrue(watch.armed());
        assertEquals(0L, watch.waitMs(5_000));
    }

    @Test
    public void aFreshArmingStartsTheWaitAgain() {
        feed(ON, 0, 0);
        feed(ARMED, 0, 1_000);
        feed(ON, 0, 200_000);
        feed(ARMED, 0, 300_000);
        assertEquals(30_000L, watch.waitMs(300_000));
    }

    @Test
    public void hazardCountsOnceWhenItComesOn() {
        feed(ARMED, 0, 0);
        assertEquals(SentryWatch.Trigger.HAZARD, feed(ARMED, HAZARD, 1_000));
        assertNull("一直亮着不算新的一下", feed(ARMED, HAZARD, 2_000));
        assertNull("读不到不算熄灭", feed(ARMED, null, 3_000));
        assertNull(feed(ARMED, HAZARD, 4_000));
        assertNull(feed(ARMED, 0, 5_000));
        assertEquals("灭了再亮是新的一下", SentryWatch.Trigger.HAZARD, feed(ARMED, HAZARD, 6_000));
    }

    @Test
    public void turnSignalsAreNotHazards() {
        feed(ARMED, 0, 0);
        assertNull(feed(ARMED, 1, 1_000));
        assertNull(feed(ARMED, 2, 2_000));
    }

    @Test
    public void anyLampComingOnCounts() {
        watch.update(ARMED, 0, false, false, false, false, 0);
        assertEquals(SentryWatch.Trigger.LAMPS,
                watch.update(ARMED, 0, false, true, false, false, 1_000));
        assertNull("又亮一盏，还是亮着", watch.update(ARMED, 0, true, true, false, false, 2_000));
        assertNull("读不到不算熄灭", watch.update(ARMED, 0, null, null, null, null, 3_000));
        assertNull(watch.update(ARMED, 0, false, false, false, false, 4_000));
        assertEquals(SentryWatch.Trigger.LAMPS,
                watch.update(ARMED, 0, false, false, false, true, 5_000));
    }

    @Test
    public void hazardWinsWhenBothComeOnTogether() {
        watch.update(ARMED, 0, false, false, false, false, 0);
        assertEquals(SentryWatch.Trigger.HAZARD, watch.update(ARMED, HAZARD, true, false, false, false, 1_000));
    }

    @Test
    public void aLightAlreadyOnAtTheFirstReadingCounts() {
        // 进程起来后头一次读到就亮着：不知道之前是不是灭的，宁可报
        assertEquals(SentryWatch.Trigger.HAZARD, feed(ARMED, HAZARD, 0));
    }

    @Test
    public void resetForgetsEverything() {
        feed(ON, 0, 0);
        feed(ARMED, HAZARD, 1_000);
        watch.reset();
        assertFalse(watch.armed());
        assertEquals(SentryWatch.Trigger.HAZARD, feed(ON, HAZARD, 2_000));
    }
}
