/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 工厂把设置翻成一个出口：是哪条路、有没有配好。
 *
 * <h3>为什么这几条要单独测</h3>
 *
 * <p>判断原来埋在 {@code SentryAlerts} 里，要测它得先把整个警报系统搭起来 —— 所以它一直没被测过。
 * 现在它是一个纯函数（设置进，出口出），几条规矩各钉一条：</p>
 *
 * <ol>
 *   <li>交出来的永远是 ntfy 这条路 —— 名字对得上，不会冒出第二种</li>
 *   <li>交出来的出口自己知道有没有配好，{@link AlertChannel.Kind} 说得出差哪一步</li>
 *   <li>话题有了、免费方案的提醒没接受，出口会拒绝发（{@link NtfyRisk}）</li>
 * </ol>
 *
 * <h3>Why these</h3>
 *
 * <p>The decision used to be buried inside {@code SentryAlerts}, so testing it meant standing up the
 * whole alert system — which is why it was never tested. It is a pure function now (settings in, exit
 * out) and each rule gets one test: the exit is always ntfy, the exit knows whether it is configured
 * and can name the step that is missing, and a topic without an accepted free-plan warning yields an
 * exit that refuses to send.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AlertChannelsTest {

    private static final String NTFY_TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        config = new AppConfig(context);
        config.setAlertsNtfyTopic("");
        config.setAlertsNtfyToken("");
        config.setAlertsNtfyPaid(false);
        config.setAlertsNtfyRiskAck("");
    }

    @Test
    public void theExitIsAlwaysNtfy() {
        AlertChannel channel = AlertChannels.of(config);

        assertEquals(NtfyGate.NTFY, channel.name());
    }

    @Test
    public void theExitSaysWhichStepIsMissing() {
        // 没配话题
        AlertChannel channel = AlertChannels.of(config);
        assertFalse(channel.ready());
        assertEquals(AlertChannel.Kind.NOT_CONFIGURED, channel.blockedReason());

        // 有话题、没接受免费方案的提醒
        config.setAlertsNtfyTopic(NTFY_TOPIC);
        AlertChannel unacked = AlertChannels.of(config);
        assertFalse("提醒没接受就不能发", unacked.ready());
        assertEquals(AlertChannel.Kind.NOT_ACKNOWLEDGED, unacked.blockedReason());

        // 接受了才真的算配好
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(NTFY_TOPIC));
        AlertChannel ready = AlertChannels.of(config);
        assertTrue(ready.ready());
        assertEquals(AlertChannel.Kind.OK, ready.blockedReason());
    }
}
