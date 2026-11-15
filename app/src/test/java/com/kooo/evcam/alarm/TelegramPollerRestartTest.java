/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 一个机器人只有一个轮询者（{@link TelegramPoller}）：在两轮之间被关掉又打开，
 * 手里那条线程一落地要<b>接着问</b>，不是把旗子吃掉就退场。
 */
@RunWith(RobolectricTestRunner.class)
public class TelegramPollerRestartTest {

    private static final String THREAD_NAME = "telegram-poll";

    private TelegramPoller poller;

    @After
    public void tearDown() {
        if (poller != null) {
            poller.stop();
        }
    }

    @Test
    public void aStopThenStartInsideOnePollWindowLeavesAPollerRunning() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        // 令牌留空：loop 里那条 25 秒的读不会真发生，所以用两轮之间的格子当那条打不断的读
        poller = new TelegramPoller(new AppConfig(context), null);

        CountDownLatch insideOnePollWindow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        poller.runBetweenPolls(() -> {
            insideOnePollWindow.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 打断不算收手：真挂在网络读上时就是这样，interrupt 打不断它
                Thread.currentThread().interrupt();
            }
        });
        poller.start();
        assertTrue("轮询线程没走到两轮之间", insideOnePollWindow.await(5, TimeUnit.SECONDS));

        poller.stop();                 // 等不到它落地（3 秒）：引用留着，这份不换手
        poller.start();                // 手里那条还活着 → 只许把这一轮交接给它，不再起第二条
        release.countDown();           // 它一落地就该接着问

        assertTrue("交接之后没接着问：poller 只是被旗了一下就退了", waitUntilRunning(3_000L));
        assertEquals("一个机器人只能有一个轮询者", 1, pollerThreads());
    }

    private boolean waitUntilRunning(long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (poller.isRunning()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return poller.isRunning();
    }

    private static int pollerThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (THREAD_NAME.equals(t.getName())) {
                n++;
            }
        }
        return n;
    }
}
