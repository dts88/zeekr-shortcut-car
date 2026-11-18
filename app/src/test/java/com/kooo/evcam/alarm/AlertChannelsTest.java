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

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.settings.DeveloperMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 工厂把设置翻成一个出口：现在走哪条、有没有配好、能不能带片段。
 *
 * <h3>为什么这几条要单独测</h3>
 *
 * <p>判断原来埋在 {@code SentryAlerts} 里，要测它得先把整个警报系统搭起来 —— 所以它一直没被测过。
 * 现在它是一个纯函数（设置进，出口出），四条规矩各钉一条：</p>
 *
 * <ol>
 *   <li>存着 telegram，交出来的是 Telegram</li>
 *   <li>存着 ntfy，交出来的是 ntfy</li>
 *   <li>存着认不出来的值，交出来的仍然是 Telegram —— 绝不出现「不知道走哪条」</li>
 *   <li>交出来的出口自己知道有没有配好，{@link AlertChannel.Kind} 说得出差哪一步</li>
 * </ol>
 *
 * <p>还有一个能力差异要钉住：片段只有 Telegram 能发。这条以前只写在注释里。</p>
 *
 * <h3>Why these four</h3>
 *
 * <p>The decision used to be buried inside {@code SentryAlerts}, so testing it meant standing up the
 * whole alert system — which is why it was never tested. It is a pure function now (settings in, exit
 * out) and each rule gets one test: telegram stored means Telegram, ntfy stored means ntfy, an
 * unrecognised value is still Telegram (never a "no idea which exit"), and the exit it hands out knows
 * whether it is configured and can name the step that is missing. One capability difference is pinned
 * too: only Telegram carries clips — a fact that until now lived in a comment.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AlertChannelsTest {

    private static final String BOT_TOKEN = "123456789:AAH_exampleTOKENvalue_0123456789";
    private static final String CHAT_ID = "-1001234567890";
    private static final String NTFY_TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // 静态状态跟着进程走，别的测试可能把它打开过
        // The static flag lives with the process; another test may have opened it
        DeveloperMode.lock(context);
        DeveloperMode.init(context);
        config = new AppConfig(context);
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsNtfyTopic("");
        config.setAlertsNtfyToken("");
        config.setAlertsNtfyPaid(false);
        config.setAlertsNtfyRiskAck("");
    }

    @Test
    public void telegramStoredYieldsTelegram() {
        config.setAlertsTelegramToken(BOT_TOKEN);
        config.setAlertsTelegramChatId(CHAT_ID);

        AlertChannel channel = AlertChannels.of(config);

        assertEquals(NtfyGate.TELEGRAM, channel.name());
        assertTrue(channel.ready());
        assertEquals(AlertChannel.Kind.OK, channel.blockedReason());
        assertTrue("Telegram 能带片段", channel.supportsClips());
    }

    @Test
    public void ntfyStoredYieldsNtfy() {
        config.setAlertsNtfyTopic(NTFY_TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(NTFY_TOPIC));
        config.setAlertsTransport(NtfyGate.NTFY);

        AlertChannel channel = AlertChannels.of(config);

        assertEquals(NtfyGate.NTFY, channel.name());
        assertTrue(channel.ready());
        assertFalse("片段在 ntfy 这条路上发不了，以前只写在注释里", channel.supportsClips());
    }

    @Test
    public void aValueNobodyRecognisesIsStillTelegram() {
        config.setAlertsTransport("nonsense");

        AlertChannel channel = AlertChannels.of(config);

        assertEquals("认不出来的值不能变成「不知道走哪条」", NtfyGate.TELEGRAM, channel.name());
    }

    @Test
    public void theExitSaysWhichStepIsMissing() {
        // 没配的 Telegram
        AlertChannel telegram = AlertChannels.of(config);
        assertFalse(telegram.ready());
        assertEquals(AlertChannel.Kind.NOT_CONFIGURED, telegram.blockedReason());

        // 有话题、没接受免费方案的提醒
        config.setAlertsNtfyTopic(NTFY_TOPIC);
        config.setAlertsTransport(NtfyGate.NTFY);
        AlertChannel ntfy = AlertChannels.of(config);
        assertFalse("提醒没接受就不能发", ntfy.ready());
        assertEquals(AlertChannel.Kind.NOT_ACKNOWLEDGED, ntfy.blockedReason());
    }
}
