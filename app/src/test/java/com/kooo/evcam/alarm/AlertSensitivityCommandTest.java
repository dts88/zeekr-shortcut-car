/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to this file; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.media.MotionSensitivity;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.telegram.TelegramCommand;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * 聊天里改灵敏度：改了真的存下来、到两头不假装挪动、每次都回一句话。
 *
 * <h3>钉住的三件事</h3>
 *
 * <ol>
 *   <li><b>存下来了。</b>{@code sens 8} 之后设置里就是 8 —— 车主看不到这个界面，只看到那一句回话，
 *       所以回话和设置必须说的是同一个数。</li>
 *   <li><b>两头停住。</b>10 再往上还是 10，1 再往下还是 1；回话是「到头了」那种，不是「改成 11」。</li>
 *   <li><b>每次都回，而且只回文字。</b>这条命令不改照片那条路：一晚上二十条警报的车上，
 *       一条调灵敏度的命令不该顺手拍一张。</li>
 * </ol>
 *
 * <p>走的还是真的那条路（{@code onSensitivityCommand} → {@code deliverText} → 通道），只有 ntfy 的
 * 发送被换成了计数用的影子，所以这里没有网络。</p>
 *
 * Three things pinned: the level really is stored (the owner sees only the reply, so reply and setting
 * must agree), the ends stop rather than pretend, and every form replies with text only — a command to
 * turn the sensitivity down must not take a picture on a car that sends twenty alerts a night. The real
 * path runs; only the ntfy send is a counting shadow, so there is no network here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowNtfySender.class})
public class AlertSensitivityCommandTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() throws Exception {
        // 单例活在整个沙箱里，而每个测试方法换一个 Application：不清掉它，写的是上一个方法那份设置。
        // The singleton lives for the whole sandbox while every test method gets a new Application;
        // without clearing it, the write lands in the previous method's settings file.
        forgetSingleton();
        context = RuntimeEnvironment.getApplication();
        ShadowNtfySender.reset();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsMotionSensitivity(MotionSensitivity.DEFAULT);
    }

    @After
    public void tearDown() {
        config.setAlertsMotionSensitivity(MotionSensitivity.DEFAULT);
    }

    @Test
    public void aNumberIsStoredAndRepliesWithIt() throws Exception {
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.SET, 8);
        assertEquals("设成 8 就该存成 8", 8, config.getAlertsMotionSensitivity());
        assertOneTextAndNoPicture();
    }

    @Test
    public void oneStepUpAndOneStepDown() throws Exception {
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.UP, 0);
        assertEquals(MotionSensitivity.DEFAULT + 1, config.getAlertsMotionSensitivity());
        ShadowNtfySender.reset();
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.DOWN, 0);
        assertEquals(MotionSensitivity.DEFAULT, config.getAlertsMotionSensitivity());
        assertOneTextAndNoPicture();
    }

    @Test
    public void theMostSensitiveStepDoesNotPretendToMove() throws Exception {
        config.setAlertsMotionSensitivity(MotionSensitivity.MAX);
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.UP, 0);
        assertEquals("10 再往上还是 10", MotionSensitivity.MAX, config.getAlertsMotionSensitivity());
        assertOneTextAndNoPicture();
    }

    @Test
    public void theLeastSensitiveStepDoesNotPretendToMove() throws Exception {
        config.setAlertsMotionSensitivity(MotionSensitivity.MIN);
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.DOWN, 0);
        assertEquals("1 再往下还是 1", MotionSensitivity.MIN, config.getAlertsMotionSensitivity());
        assertOneTextAndNoPicture();
    }

    @Test
    public void askingChangesNothingAndStillAnswers() throws Exception {
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.REPORT, 0);
        assertEquals("问一句不该动设置", MotionSensitivity.DEFAULT,
                config.getAlertsMotionSensitivity());
        assertOneTextAndNoPicture();
    }

    @Test
    public void aNumberOutsideTheRangeIsClampedNotRejected() {
        // 解析那一层已经拒绝 0 和 11（TelegramCommandTest）；到这一层再夹一次，是为了「已经存坏的设置」
        // 也能被一条命令救回来。
        // The parser already refuses 0 and 11; clamping again here is for a stored value that went bad,
        // which one command should be able to fix.
        SentryAlerts.get(context).onSensitivityCommand(TelegramCommand.SensitivityAction.SET, 99);
        assertEquals(MotionSensitivity.MAX, config.getAlertsMotionSensitivity());
    }

    /** 把 {@code SentryAlerts.instance} 清掉，让下一个 {@code get()} 用这一次的 Application。 */
    /** Clear {@code SentryAlerts.instance} so the next {@code get()} binds this test's Application. */
    private static void forgetSingleton() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void assertOneTextAndNoPicture() throws Exception {
        assertTrue("该回一句话", ShadowNtfySender.latch.await(5, TimeUnit.SECONDS));
        assertEquals("只发一句文字", 1, ShadowNtfySender.texts);
        assertEquals("调灵敏度不该拍照", 0, ShadowNtfySender.photos);
    }
}
