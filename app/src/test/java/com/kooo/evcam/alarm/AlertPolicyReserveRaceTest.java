/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.kooo.evcam.alarm.policy.AlertPolicy;

/**
 * 两条线程同时占位（{@link AlertPolicy#reserve}）：只许一条拿到 ALLOW（D-03、F-00.04）。
 *
 * <p>以前判断与记账分两步，编码器的 GL 线程和主线程能在对方记下之前各判一次 ALLOW，
 * 同一件事就发重了。现在占位在判断里一次做完，方法统一加锁。
 * 这条测试用两条普通的线程，不用 Robolectric。</p>
 *
 * Two threads reserving at the same time ({@link AlertPolicy#reserve}): only one may get ALLOW
 * (D-03, F-00.04).
 *
 * <p>The decision and the bookkeeping used to be two steps, so the encoder's GL thread and the
 * main thread could each decide ALLOW before the other recorded it, and the same event was sent
 * twice. The reservation now happens inside the decision in one go, and the methods are all
 * locked. This test uses two plain threads, no Robolectric.</p>
 */
public class AlertPolicyReserveRaceTest {

    @Test
    public void twoThreadsReservingAtOnceShareOneSlot() throws Exception {
        AlertPolicy policy = new AlertPolicy(true);
        policy.setConfigured(true);
        policy.setParked(true);
        policy.setArmed(true);

        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final AlertPolicy.Decision[] result = new AlertPolicy.Decision[2];
        Thread a = new Thread(job(policy, 0, ready, go, result), "reserve-0");
        Thread b = new Thread(job(policy, 1, ready, go, result), "reserve-1");
        a.start();
        b.start();
        assertTrue("两条线程没能就位", ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        a.join(5_000L);
        b.join(5_000L);

        int allowed = (result[0] == AlertPolicy.Decision.ALLOW ? 1 : 0)
                + (result[1] == AlertPolicy.Decision.ALLOW ? 1 : 0);
        assertEquals("两条线程一起占位，只许一条拿到 ALLOW", 1, allowed);
        assertEquals("没拿到的那条要看到冷却", AlertPolicy.Decision.COOLDOWN,
                result[0] == AlertPolicy.Decision.ALLOW ? result[1] : result[0]);
        assertEquals("占位当场就算进每小时上限", 1, policy.sentThisHour(System.currentTimeMillis()));
    }

    private static Runnable job(AlertPolicy policy, final int slot, CountDownLatch ready,
                                CountDownLatch go, final AlertPolicy.Decision[] result) {
        return () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            result[slot] = policy.reserve(System.currentTimeMillis());
        };
    }
}
