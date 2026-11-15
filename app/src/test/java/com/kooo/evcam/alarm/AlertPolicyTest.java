/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 停车警报什么时候发（{@link AlertPolicy}）：只要停着、哨兵开着，还要等冷却、守每小时的上限。
 *
 * When a parked-car alert is sent ({@link AlertPolicy}): only while parked and
 * sentry is on, and it still must respect the cooldown and the hourly cap.
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
    public void aFailedSendOpensTheFailureBackoff() {
        AlertPolicy policy = ready();
        // D-12（说好的代价）：警报在判断这一步就占位，发不出去记一次失败退避。
        // 旧规矩「发失败下一次马上能重试」不再成立。
        // D-12 (the accepted cost): an alert reserves the slot at decision time, and a
        // failed send records a failure backoff. The old rule -- a failed send can retry
        // right away -- no longer holds.
        assertEquals(AlertPolicy.Decision.ALLOW, policy.reserve(1_000L));
        policy.noteFailure(1_000L);
        assertEquals(AlertPolicy.Decision.FAILURE_BACKOFF, policy.decide(2_000L));
        assertEquals(AlertPolicy.Decision.FAILURE_BACKOFF,
                policy.decide(1_000L + AlertPolicy.FAILURE_BACKOFF_MS - 1));
        // 退避一跳完，占位那一格的冷却早就过了：又能发。
        // Once the backoff is over the reserved slot's cooldown is long gone: allowed again.
        assertEquals(AlertPolicy.Decision.ALLOW,
                policy.decide(1_000L + AlertPolicy.FAILURE_BACKOFF_MS));
    }

    @Test
    public void reserveTakesTheSlotAtOnce() {
        AlertPolicy policy = ready();
        assertEquals(AlertPolicy.Decision.ALLOW, policy.reserve(1_000L));
        // 占位当场就算数，不等发送回来 —— 不然两条触发边会各发一张。
        // The reservation counts at once, without waiting for the send to come back;
        // otherwise two trigger edges would each send one picture.
        assertEquals(AlertPolicy.Decision.COOLDOWN, policy.decide(2_000L));
        assertEquals(1, policy.sentThisHour(2_000L));
    }

    @Test
    public void decideStaysAPureCheck() {
        AlertPolicy policy = ready();
        // decide 只判断，不占位：诊断靠它看「现在能不能发」。
        // decide only judges and does not reserve: diagnostics use it for "can it send now".
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(1_000L));
        assertEquals(AlertPolicy.Decision.ALLOW, policy.decide(1_001L));
        assertEquals(0, policy.sentThisHour(1_001L));
    }

    @Test
    public void cooldownLeftCountsDown() {
        AlertPolicy policy = ready();
        policy.noteSent(5_000L);
        assertEquals(AlertPolicy.COOLDOWN_MS - 20_000L, policy.cooldownLeftMs(25_000L));
        assertEquals(0, policy.cooldownLeftMs(5_000L + AlertPolicy.COOLDOWN_MS + 1));
    }

    @Test
    public void cooldownLeftReportsTheFailureBackoffToo() {
        AlertPolicy policy = ready();
        // 占位那一格同时开着两条窗口：报剩得多的那条 —— 那才是实际还要静的多久。
        // Reserving opens both windows at once: report the larger remainder, which is how long the
        // sender really stays quiet.
        assertEquals(AlertPolicy.Decision.ALLOW, policy.reserve(1_000L));
        policy.noteFailure(1_000L);
        assertEquals(AlertPolicy.FAILURE_BACKOFF_MS - 20_000L, policy.cooldownLeftMs(21_000L));
        // 退避一跳完（占位那一格的冷却早就过了）：0。
        // Once the backoff runs out (the reserved slot's cooldown is long gone): 0.
        assertEquals(0, policy.cooldownLeftMs(1_000L + AlertPolicy.FAILURE_BACKOFF_MS));
    }
}
