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
 * 锁车之后等多久才发（{@link ArmDelay}）：锁车满 3 分钟。读得到的是布防的那一刻（锁车后约 90 秒），
 * 所以从布防起再等 90 秒。等的是自己走开的时间，不是把真事吞掉的时间。
 *
 * How long after locking the car to wait before sending ({@link ArmDelay}): 3 minutes after locking,
 * which is 90 seconds after the car arms itself. That waiting time is for walking away, not time that
 * swallows a real event.
 */
public class ArmDelayTest {

    @Test
    public void threeMinutesAfterLockingIsNinetySecondsAfterArming() {
        assertEquals(3 * 60_000L, ArmDelay.AFTER_LOCK_MS);
        assertEquals(90_000L, ArmDelay.AFTER_ARMED_MS);
        assertFalse(ArmDelay.allows(0));
        assertFalse(ArmDelay.allows(89_999L));
        assertTrue(ArmDelay.allows(90_000L));
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
        assertEquals(75_000L, ArmDelay.remainingMs(15_000L));
        assertEquals(0, ArmDelay.remainingMs(90_000L));
        assertEquals(0, ArmDelay.remainingMs(5 * 60_000L));
    }
}
