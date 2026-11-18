/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.settings.DeveloperMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 换话题这件事，做全了三件没有：新名字、旧名字作废、同意清掉。
 *
 * <h3>为什么三件要一起测</h3>
 *
 * <p>少做第二件，车还在往旧名字发，而车主以为换过了 —— 那正好是「有人看见了我的锁屏」之后
 * 最不该发生的事。少做第三件，等于替车主答应了下一个名字的公开风险，而那个名字他还没看过。
 * 两件都不会当场报错，所以用测试钉住。</p>
 *
 * Rotation does three things, and this test exists because two of them fail silently. Skip the old
 * name and the car keeps publishing to it while the owner believes it changed — the exact wrong
 * outcome after "someone saw my lock screen". Skip clearing consent and the owner is counted as
 * having accepted a public name he never read. Neither errors; both are pinned here.
 */
@RunWith(RobolectricTestRunner.class)
public class NtfyTopicRotationTest {

    private static final String OLD_TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // 静态状态跟着进程走：别的测试可能把开发者选项打开过。
        // The static flag lives with the process; another test may have left it unlocked.
        DeveloperMode.lock(context);
        DeveloperMode.init(context);
        config = new AppConfig(context);
        config.setAlertsNtfyPaid(false);
        config.setAlertsNtfyTopic(OLD_TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(OLD_TOPIC));
    }

    @After
    public void tearDown() {
        DeveloperMode.lock(context);
    }

    @Test
    public void rotationMintsANewLegalNameAndTheOldOneIsGone() {
        String fresh = config.rotateAlertsNtfyTopic();
        assertTrue("新名字不合格: " + fresh, NtfyRequest.looksLikeTopic(fresh));
        assertNotEquals("换完还是原来那个", OLD_TOPIC, fresh);
        assertEquals("车还在往旧名字发", fresh, config.getAlertsNtfyTopic());
    }

    @Test
    public void rotationClearsTheRiskConsent() {
        assertTrue("前提：旧名字的同意是真的", NtfyRisk.accepted(OLD_TOPIC, config.getAlertsNtfyRiskAck()));
        String fresh = config.rotateAlertsNtfyTopic();
        assertEquals("新名字要重新问一次", "", config.getAlertsNtfyRiskAck());
        assertFalse("旧名字的同意不能跟着新名字走",
                NtfyRisk.accepted(fresh, NtfyRisk.ackFor(OLD_TOPIC)));
    }

    @Test
    public void twoRotationsInARowAreNeverTheSameName() {
        String first = config.rotateAlertsNtfyTopic();
        String second = config.rotateAlertsNtfyTopic();
        assertNotEquals("同一毫秒里连换两次", first, second);
        assertEquals(second, config.getAlertsNtfyTopic());
    }

}
