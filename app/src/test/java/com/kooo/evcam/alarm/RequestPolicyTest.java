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
 * 人从 Telegram 要照片的频率上限（{@link RequestPolicy}）：30 秒一张，一小时 6 张。
 *
 * <p>和警报那套分开：警报要停着并且布防，人自己要是坐在车里、哨兵只开没布防也该给一张。</p>
 */
public class RequestPolicyTest {

    @Test
    public void theFirstAskAlwaysPasses() {
        assertTrue(new RequestPolicy().allows(1_000L));
    }

    @Test
    public void aSecondAskHasToWait() {
        RequestPolicy policy = new RequestPolicy();
        policy.noteSent(10_000L);
        assertFalse(policy.allows(10_000L + 1_000L));
        assertFalse(policy.allows(10_000L + RequestPolicy.COOLDOWN_MS - 1));
        assertTrue(policy.allows(10_000L + RequestPolicy.COOLDOWN_MS));
    }

    @Test
    public void sixInAnHourThenNoMore() {
        RequestPolicy policy = new RequestPolicy();
        long now = 0;
        for (int i = 0; i < RequestPolicy.MAX_PER_HOUR; i++) {
            now += RequestPolicy.COOLDOWN_MS;
            assertTrue("第 " + (i + 1) + " 张该给", policy.allows(now));
            policy.noteSent(now);
        }
        assertFalse(policy.allows(now + RequestPolicy.COOLDOWN_MS));
        assertEquals(0, policy.leftThisHour(now + RequestPolicy.COOLDOWN_MS));
    }

    @Test
    public void theHourlyBudgetComesBack() {
        RequestPolicy policy = new RequestPolicy();
        long now = 0;
        for (int i = 0; i < RequestPolicy.MAX_PER_HOUR; i++) {
            now += RequestPolicy.COOLDOWN_MS;
            policy.noteSent(now);
        }
        assertTrue(policy.allows(now + 3_600_000L));
        assertEquals(RequestPolicy.MAX_PER_HOUR, policy.leftThisHour(now + 3_600_000L));
    }

    @Test
    public void cooldownLeftCountsDownAndStopsAtZero() {
        RequestPolicy policy = new RequestPolicy();
        policy.noteSent(5_000L);
        assertEquals(RequestPolicy.COOLDOWN_MS - 20_000L, policy.cooldownLeftMs(25_000L));
        assertEquals(0, policy.cooldownLeftMs(5_000L + RequestPolicy.COOLDOWN_MS));
        assertEquals(0, new RequestPolicy().cooldownLeftMs(1_000L));
    }
}
