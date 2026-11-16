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

import com.kooo.evcam.alarm.policy.SentryFacts;

/**
 * 停车警报怎么读车辆信号（{@link SentryFacts}）：只有「锁着、布防」（哨兵 2）才算数，
 * 所以车主解锁回来那一下之后的亮屏、亮灯都不会发出去。
 *
 * How parked-car alerts read the vehicle signals ({@link SentryFacts}): only
 * "locked and armed" (sentry 2) counts, so the screen and lamps that light up
 * right after the owner unlocks and comes back are never sent.
 */
public class SentryFactsTest {

    @Test
    public void onlyArmedCountsAsArmed() {
        assertTrue(SentryFacts.armed(2));
        assertFalse("开着但还没锁车：不发", SentryFacts.armed(1));
        assertFalse("哨兵关着", SentryFacts.armed(0));
        assertFalse("读不到就不当真", SentryFacts.armed(null));
    }

    @Test
    public void theOwnerComingBackDropsOutOfArmedBeforeAnythingLightsUp() {
        // 车上记录的：解锁时防盗位 0x20240300 先 1 → 0，10 毫秒后哨兵 2 → 1；屏幕和车灯是更晚的事。
        // On the car: the anti-theft bit 0x20240300 goes 1 → 0 first, sentry 2 → 1 ten
        // milliseconds later; the screen and lamps are even later.
        assertTrue(SentryFacts.armed(2));
        assertFalse(SentryFacts.armed(1));
    }

    @Test
    public void screenPowerEventReadsZeroAsLit() {
        assertEquals(Boolean.TRUE, SentryFacts.screenLit(0));
        assertEquals(Boolean.FALSE, SentryFacts.screenLit(1));
        assertNull("255 是读不到，不是黑着", SentryFacts.screenLit(255));
        assertNull(SentryFacts.screenLit(null));
    }

    @Test
    public void anyExteriorLampIsEnoughAndUnknownStaysUnknown() {
        assertEquals(Boolean.TRUE, SentryFacts.lampsOn(true, false, false, false));
        assertEquals(Boolean.TRUE, SentryFacts.lampsOn(false, false, false, true));
        assertEquals(Boolean.FALSE, SentryFacts.lampsOn(false, false, false, false));
        assertNull(SentryFacts.lampsOn(null, null, null, null));
        assertEquals("一个读到、其余读不到：按读到的那一个说",
                Boolean.TRUE, SentryFacts.lampsOn(null, null, true, null));
    }

    @Test
    public void risingNeedsABeforeThatWasNotOn() {
        assertTrue(SentryFacts.rising(Boolean.TRUE, Boolean.FALSE));
        assertTrue("第一次读到就是亮的也算（没有前一帧可比）", SentryFacts.rising(Boolean.TRUE, null));
        assertFalse("本来就是亮的，不是这一下才亮", SentryFacts.rising(Boolean.TRUE, Boolean.TRUE));
        assertFalse(SentryFacts.rising(Boolean.FALSE, Boolean.TRUE));
        assertFalse(SentryFacts.rising(null, Boolean.FALSE));
    }
}
