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
 * 锁车之后等多久才发（{@link ArmDelay}）：等的那一分钟是自己走开的时间，不是把真事吞掉的时间。
 */
public class ArmDelayTest {

    @Test
    public void rightAwayMeansRightAway() {
        assertTrue(ArmDelay.allows(0, 0));
        assertTrue(ArmDelay.allows(0, -1));
    }

    @Test
    public void aMinuteIsAMinute() {
        assertFalse(ArmDelay.allows(1, 0));
        assertFalse(ArmDelay.allows(1, 59_000L));
        assertTrue(ArmDelay.allows(1, 60_000L));
        assertTrue(ArmDelay.allows(1, 10 * 60_000L));
    }

    @Test
    public void threeMinutesIsThreeMinutes() {
        assertFalse(ArmDelay.allows(3, 2 * 60_000L));
        assertTrue(ArmDelay.allows(3, 3 * 60_000L));
    }

    @Test
    public void notKnowingHowLongItHasBeenLockedDoesNotSilenceTheCar() {
        // 进程刚起来、车本来就锁着：不知道等了多久，宁可偶尔吵一下，也不能吞掉真事
        assertTrue(ArmDelay.allows(3, -1));
        assertEquals(0, ArmDelay.remainingMs(3, -1));
    }

    @Test
    public void anythingElseFallsBackToTheDefault() {
        assertEquals(ArmDelay.DEFAULT_MIN, ArmDelay.clamp(7));
        assertEquals(ArmDelay.DEFAULT_MIN, ArmDelay.clamp(-5));
        assertEquals(3, ArmDelay.clamp(3));
        assertEquals(0, ArmDelay.clamp(0));
    }

    @Test
    public void remainingCountsDownAndStopsAtZero() {
        assertEquals(45_000L, ArmDelay.remainingMs(1, 15_000L));
        assertEquals(0, ArmDelay.remainingMs(1, 90_000L));
        assertEquals(0, ArmDelay.remainingMs(0, 0));
    }
}
