/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.settings.DeveloperMode;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 换出口不擦设置，开发者选项关着就回到 Telegram（{@code docs/ntfy/DECISIONS.md} D14）。
 *
 * <h3>为什么要一条一条读回来比对</h3>
 *
 * <p>「切换」最容易写成的样子是「把另一边的键清掉」。那种写法当场看不出问题，
 * 要等车主切回去、发现令牌和话题都没了才知道 —— 而那一刻正是他需要警报的时候。</p>
 *
 * Switching exits erases nothing, and developer options closed means Telegram again (D14). The
 * failure this test exists for is the obvious wrong implementation — clearing the other side's keys —
 * which looks fine until the owner switches back and finds the token and the topic gone, at the exact
 * moment they wanted alerts.
 */
@RunWith(RobolectricTestRunner.class)
public class AlertTransportTest {

    private static final String BOT_TOKEN = "123456789:AAH_exampleTOKENvalue_0123456789";
    private static final String CHAT_ID = "-1001234567890";
    private static final String NTFY_TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";
    private static final String NTFY_TOKEN = "tk_exampleTOKENvalue";

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
        config.setAlertsNtfyServer("");
        config.setAlertsNtfyPaid(false);
        config.setAlertsNtfyTopic("");
        config.setAlertsNtfyToken("");
        config.setAlertsNtfyRiskAck("");
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
    }

    @After
    public void tearDown() {
        DeveloperMode.lock(context);
    }

    @Test
    public void switchingExitsKeepsBothConfigurations() {
        seedBothSides();

        String telegramToken = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        String topic = config.getAlertsNtfyTopic();
        String ntfyToken = config.getAlertsNtfyToken();
        String ack = config.getAlertsNtfyRiskAck();

        config.setAlertsTransport(NtfyGate.NTFY);
        assertBothIntact(telegramToken, chatId, topic, ntfyToken, ack);

        config.setAlertsTransport(NtfyGate.TELEGRAM);
        assertBothIntact(telegramToken, chatId, topic, ntfyToken, ack);

        config.setAlertsTransport(NtfyGate.NTFY);
        assertBothIntact(telegramToken, chatId, topic, ntfyToken, ack);
    }

    @Test
    public void anAcceptedWarningSurvivesTheRoundTrip() {
        seedBothSides();
        String topic = config.getAlertsNtfyTopic();
        config.setAlertsTransport(NtfyGate.NTFY);
        assertTrue(NtfyRisk.accepted(topic, config.getAlertsNtfyRiskAck()));
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        config.setAlertsTransport(NtfyGate.NTFY);
        assertTrue("切回来不该再问一遍已经答应过的提醒",
                NtfyRisk.accepted(topic, config.getAlertsNtfyRiskAck()));
    }

    @Test
    public void theOwnersChoiceAppliesWithDeveloperOptionsClosedToo() {
        // 2026-11-15：这一项从开发者选项搬到「停车警报」，成了普通选择 ——
        // 所以锁上开发者选项之后，车主选的这条仍然算数；只有存坏的值才回到 Telegram。
        // 2026-11-15: the choice moved out of Developer options into the alerts section, so it applies with
        // that switch closed too; only a stored value that makes no sense falls back to Telegram.
        seedBothSides();
        config.setAlertsTransport(NtfyGate.NTFY);
        DeveloperMode.unlock(context, "6651");
        assertEquals(NtfyGate.NTFY, config.getAlertsTransport());

        DeveloperMode.lock(context);
        assertEquals("存着的没变", NtfyGate.NTFY, config.getAlertsTransportStored());
        assertEquals("车主选了 ntfy，锁上开发者选项也还是 ntfy",
                NtfyGate.NTFY, config.getAlertsTransport());
        assertTrue(config.isAlertsNtfy());
        assertEquals("另一边的设置还在", NTFY_TOPIC, config.getAlertsNtfyTopic());

        // 存坏的值仍然按 telegram 算
        config.setAlertsTransport("nonsense");
        assertEquals(NtfyGate.TELEGRAM, config.getAlertsTransport());
        assertFalse(config.isAlertsNtfy());
    }

    @Test
    public void theTopicAndTokenAreNotInTheSettingsExport() throws Exception {
        seedBothSides();
        JSONObject snapshot = new JSONObject(
                com.kooo.evcam.settings.SettingsBackup.snapshot(context).toString());
        String export = snapshot.toString();
        assertFalse("话题名就是钥匙，不能跟着设置出门", export.contains(NTFY_TOPIC));
        assertFalse("令牌不能跟着设置出门", export.contains(NTFY_TOKEN));
        assertFalse("Telegram 令牌同理", export.contains(BOT_TOKEN));
        // 存着的出口与档位是设置，不是秘密：它们应该在导出里
        assertTrue(export.contains("alerts_transport"));
    }

    @Test
    public void theSecretsLiveInTheirOwnFile() {
        seedBothSides();
        assertNotEquals(AppConfig.PREF_NAME, AppConfig.SECRETS_PREF_NAME);
        assertEquals(NTFY_TOPIC, context.getSharedPreferences(AppConfig.SECRETS_PREF_NAME,
                Context.MODE_PRIVATE).getString("alerts_ntfy_topic", ""));
        assertEquals("", context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE)
                .getString("alerts_ntfy_topic", ""));
    }

    private void seedBothSides() {
        config.setAlertsTelegramToken(BOT_TOKEN);
        config.setAlertsTelegramChatId(CHAT_ID);
        config.setAlertsNtfyTopic(NTFY_TOPIC);
        config.setAlertsNtfyToken(NTFY_TOKEN);
        config.setAlertsNtfyPaid(true);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(NTFY_TOPIC));
    }

    private void assertBothIntact(String telegramToken, String chatId, String topic,
                                  String ntfyToken, String ack) {
        assertEquals("Telegram 令牌被擦掉了", telegramToken, config.getAlertsTelegramToken());
        assertEquals("Telegram 会话号被擦掉了", chatId, config.getAlertsTelegramChatId());
        assertEquals("ntfy 话题被擦掉了", topic, config.getAlertsNtfyTopic());
        assertEquals("ntfy 令牌被擦掉了", ntfyToken, config.getAlertsNtfyToken());
        assertEquals("风险提示的接受记录被擦掉了", ack, config.getAlertsNtfyRiskAck());
        assertTrue("付费档被改掉了", config.isAlertsNtfyPaid());
    }
}
