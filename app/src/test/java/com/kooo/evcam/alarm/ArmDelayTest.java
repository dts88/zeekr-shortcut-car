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

import com.kooo.evcam.alarm.policy.ArmDelay;

/**
 * 布防之后等多久才发（{@link ArmDelay}）：布防满 30 秒（车在锁车后约 90 秒自己布防）。
 * 等的是自己走开的时间，不是把真事吞掉的时间。
 *
 * How long after arming to wait before sending ({@link ArmDelay}): 30 seconds (the car arms itself about
 * 90 seconds after locking). That waiting time is for walking away, not time that swallows a real event.
 */
public class ArmDelayTest {

    @Test
    public void thirtySecondsAfterArming() {
        assertEquals(30_000L, ArmDelay.AFTER_ARMED_MS);
        assertFalse(ArmDelay.allows(0));
        assertFalse(ArmDelay.allows(29_999L));
        assertTrue(ArmDelay.allows(30_000L));
        assertTrue(ArmDelay.allows(10 * 60_000L));
    }

    @Test
    public void notKnowingHowLongItHasBeenArmedDoesNotSilenceTheCar() {
        // 进程刚起来、车本来就布防着：不知道等了多久，宁可偶尔吵一下，也不能吞掉真事
        // The process just started and the car was already armed: we don't know how long it has been,
        // so it is better to occasionally sound off than to swallow a real event.
        assertTrue(ArmDelay.allows(-1));
        assertEquals(0, ArmDelay.remainingMs(-1));
    }

    @Test
    public void remainingCountsDownAndStopsAtZero() {
        assertEquals(15_000L, ArmDelay.remainingMs(15_000L));
        assertEquals(0, ArmDelay.remainingMs(30_000L));
        assertEquals(0, ArmDelay.remainingMs(5 * 60_000L));
    }
}
