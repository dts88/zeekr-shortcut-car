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
import com.kooo.evcam.alarm.telegram.TelegramCommand;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * 聊天里的 {@code alerts off}：停发、停看，但绝不关掉回程路。
 *
 * <h3>钉住的那一条</h3>
 *
 * <p>命令是从聊天里来的，收命令那条线程要求「警报开着」。所以暂停<b>必须</b>留着总开关 ——
 * 真关总开关等于车主再也发不进 {@code alerts on}，只能上车。这一条错了不会报错，只会让「恢复」
 * 从一个词变成一趟车，所以用断言钉住。</p>
 *
 * <p>另外两件：重复一条 {@code alerts off} 不假装又关一次；问一句 {@code alerts} 有答案，
 * 而且答案和诊断报告里那个状态是同一个值。</p>
 *
 * The one thing pinned: commands arrive through the chat and the thread that reads them needs
 * "alerts on", so a pause must leave the master switch alone — turning it off would turn "resume"
 * from one word into a drive to the car. That failure is silent, hence the assertion. Also: a repeated
 * {@code alerts off} does not pretend to pause twice, and a bare {@code alerts} answers with the same
 * value the diagnostics report carries.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowNtfySender.class})
public class AlertPauseCommandTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() throws Exception {
        // 单例活在整个沙箱里，每个测试方法换一个 Application（理由见 AlertSensitivityCommandTest）。
        // The singleton outlives the Application, which is replaced per test method.
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        context = RuntimeEnvironment.getApplication();
        ShadowNtfySender.reset();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsPaused(false);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
    }

    @After
    public void tearDown() {
        config.setAlertsPaused(false);
        config.setAlertsEnabled(true);
    }

    @Test
    public void offPausesWithoutTouchingTheMasterSwitch() throws Exception {
        SentryAlerts.get(context).onAlertsCommand(TelegramCommand.AlertsState.OFF);
        assertTrue("暂停了", config.isAlertsPaused());
        assertTrue("总开关必须还开着，否则回程路就断了", config.isAlertsEnabled());
        assertFalse("但警报不算在跑", config.isAlertsActive());
        assertOneTextOnly();
    }

    @Test
    public void onResumesAfterAPause() throws Exception {
        config.setAlertsPaused(true);
        SentryAlerts.get(context).onAlertsCommand(TelegramCommand.AlertsState.ON);
        assertFalse("恢复了", config.isAlertsPaused());
        assertTrue(config.isAlertsActive());
        assertOneTextOnly();
    }

    @Test
    public void aRepeatedOffDoesNotPretendToPauseTwice() throws Exception {
        config.setAlertsPaused(true);
        SentryAlerts.get(context).onAlertsCommand(TelegramCommand.AlertsState.OFF);
        assertTrue("还是暂停着", config.isAlertsPaused());
        assertOneTextOnly();
    }

    @Test
    public void askingReportsTheStateWithoutChangingIt() throws Exception {
        config.setAlertsPaused(true);
        SentryAlerts.get(context).onAlertsCommand(TelegramCommand.AlertsState.REPORT);
        assertTrue("问一句不该改状态", config.isAlertsPaused());
        assertOneTextOnly();
    }

    @Test
    public void theDiagnosticsReportCarriesTheSameState() throws Exception {
        SentryAlerts.get(context).onAlertsCommand(TelegramCommand.AlertsState.OFF);
        JSONObject report = new JSONObject(SentryAlerts.get(context).debugReport());
        assertTrue("诊断报告里要能看到暂停，否则查「为什么没发」会查错方向",
                report.getJSONObject("settings").getBoolean("pausedFromChat"));
    }

    private void assertOneTextOnly() throws Exception {
        assertTrue("该回一句话", ShadowNtfySender.latch.await(5, TimeUnit.SECONDS));
        assertEquals("只发一句文字", 1, ShadowNtfySender.texts);
        assertEquals("暂停不该拍照", 0, ShadowNtfySender.photos);
    }
}
