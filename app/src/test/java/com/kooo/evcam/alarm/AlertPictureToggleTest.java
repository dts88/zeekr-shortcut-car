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
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * 「警报附一张照片」关掉之后，车到底做了什么。
 *
 * <h3>钉的是两件事，不是一句话</h3>
 *
 * <ol>
 *   <li><b>不去拍照。</b>不是拍完丢掉：相机、拼图、压缩一步都不走。车机上一次几百毫秒，一晚上可能十几次。</li>
 *   <li><b>仍然发一句话。</b>关掉图不是把警报关掉，双闪亮了还是要知道。</li>
 * </ol>
 *
 * <p>开着的时候反过来：拍一次、发的是图。两个方向都验，因为「默认值」这件事错了不会有人报错，
 * 只会静默变成另一种行为。</p>
 *
 * <h3>笑点在哪</h3>
 *
 * <p>{@code SnapshotTaker} 和 {@code NtfySender} 都被换成计数用的影子，所以这里没有相机、没有网络：
 * 断言的就是「有没有叫拍照」「发出去的是文字还是图」。这条警报走 ntfy，因为它的 {@code sendText} 和
 * {@code sendPhoto} 是一对干净的入口。</p>
 *
 * How the car behaves with "send a picture with each alert" off: it never takes the picture (a few hundred
 * milliseconds and a bitmap per alert, several times a night) and still sends the line. Both directions are
 * checked, because a wrong default fails silently. `SnapshotTaker` and `NtfySender` are counting shadows, so
 * there is no camera and no network here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowSnapshotTaker.class, ShadowNtfySender.class})
public class AlertPictureToggleTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() throws Exception {
        // 单例活在整个沙箱里，而每个测试方法换一个 Application（理由见 AlertSensitivityCommandTest）。
        // The singleton outlives the Application, which is replaced per test method.
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        context = RuntimeEnvironment.getApplication();
        ShadowSnapshotTaker.reset();
        ShadowNtfySender.reset();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsPictureEnabled(true);
    }

    @After
    public void tearDown() {
        config.setAlertsPictureEnabled(true);
    }

    @Test
    public void picturesOnTakesOneAndSendsAPicture() throws Exception {
        config.setAlertsPictureEnabled(true);
        SentryAlerts.get(context).sendEventSample(AlertEvent.HAZARD, null);
        // 拍照是同步叫的；发图在后面那条线程上，但这个影子不回调，所以只验「叫了拍照」。
        // The capture is called synchronously; sending would be on the other thread, and this shadow
        // never calls back, so what is asserted here is "it asked for a picture".
        assertEquals("开着的时候该拍一张", 1, ShadowSnapshotTaker.taken);
        assertEquals("不该走文字那条路", 0, ShadowNtfySender.texts);
    }

    @Test
    public void picturesOffSendsTextAndNeverTouchesTheCamera() throws Exception {
        config.setAlertsPictureEnabled(false);
        SentryAlerts.get(context).sendEventSample(AlertEvent.DOORS, null);
        assertTrue("文字那条路要说得上话", ShadowNtfySender.latch.await(5, TimeUnit.SECONDS));
        assertEquals("关掉图就不该叫相机", 0, ShadowSnapshotTaker.taken);
        assertEquals("发出去的应该是一句话", 1, ShadowNtfySender.texts);
        assertEquals("不该发图", 0, ShadowNtfySender.photos);
    }
}
