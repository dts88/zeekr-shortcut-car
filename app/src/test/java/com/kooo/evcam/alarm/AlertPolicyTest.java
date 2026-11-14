/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 停车警报什么时候发（{@link AlertPolicy}）：只要停着、哨兵开着，还要等冷却、守每小时的上限。
 */
public class AlertPolicyTest {

    private static AlertPolicy ready() {
        AlertPolicy policy = new AlertPolicy(true);
        policy.setConfigured(true);
        policy.setParked(true);
        policy.setArmed(true);
        return policy;
    }

    @Test
    public void parkedArmedAndConfiguredAllowsOne() {
        assertEquals(AlertPolicy.Decision.ALLOW, ready().decide(1_000L));
    }

    @Test
    public void offBeatsEverythingElse() {
        AlertPolicy policy = new AlertPolicy(false);
        policy.setConfigured(true);
        policy.setParked(true);
        policy.setArmed(true);
        assertEquals(AlertPolicy.Decision.OFF, policy.decide(1_000L));
    }

    @Test
    public void aMissingTokenCountsAsOff() {
        AlertPolicy policy = new AlertPolicy(true);
        policy.setConfigured(false);
        policy.setParked(true);
        policy.setArmed(true);
        assertEquals(AlertPolicy.Decision.OFF, policy.decide(1_000L));
    }

    @Test
    public void movingCarNeverAlerts() {
        AlertPolicy policy = ready();
        policy.setParked(false);
        assertEquals(AlertPolicy.Decision.NOT_PARKED, policy.decide(1_000L));
    }

    @Test
    public void sentryOffNeverAlerts() {
        AlertPolicy policy = ready();
        policy.setArmed(false);
        assertEquals(AlertPolicy.Decision.NOT_ARMED, policy.decide(1_000L));
    }

    @Test
    public void cooldownSwallowsTheRestOfOneEvent() {
        AlertPolicy policy = ready();
        policy.noteSent(10_000L);
        assertEquals(AlertPolicy.Decision.COOLDOWN, policy.decide(10_000L + 1_000L));
        assertEquals(AlertPolicy.Decision.COOLDOWN,
                policy.decide(10_000L + AlertPolicy.COOLDOWN_MS - 1));
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(10_000L + AlertPolicy.COOLDOWN_MS));
    }

    @Test
    public void hourlyCapStopsABusyCarPark() {
        AlertPolicy policy = ready();
        long now = 0;
        for (int i = 0; i < AlertPolicy.MAX_PER_HOUR; i++) {
            now += AlertPolicy.COOLDOWN_MS;
            assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(now));
            policy.noteSent(now);
        }
        now += AlertPolicy.COOLDOWN_MS;
        assertEquals(AlertPolicy.Decision.HOURLY_CAP, policy.decide(now));
    }

    @Test
    public void theCapResetsWithTheNextHour() {
        AlertPolicy policy = ready();
        long now = 0;
        for (int i = 0; i < AlertPolicy.MAX_PER_HOUR; i++) {
            now += AlertPolicy.COOLDOWN_MS;
            policy.noteSent(now);
        }
        assertEquals(AlertPolicy.Decision.HOURLY_CAP, policy.decide(now));
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(now + 3_600_000L));
    }

    @Test
    public void aFailedSendDoesNotStartTheCooldown() {
        AlertPolicy policy = ready();
        // 发失败了就不该调 noteSent：下一次事件马上能重试，不用干等一分钟。
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(1_000L));
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(2_000L));
    }

    @Test
    public void cooldownLeftCountsDown() {
        AlertPolicy policy = ready();
        policy.noteSent(5_000L);
        assertEquals(AlertPolicy.COOLDOWN_MS - 20_000L, policy.cooldownLeftMs(25_000L));
        assertEquals(0, policy.cooldownLeftMs(5_000L + AlertPolicy.COOLDOWN_MS + 1));
    }
}
