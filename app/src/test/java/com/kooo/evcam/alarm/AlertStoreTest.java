/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.settings.SettingsBackup;

/**
 * 哨兵提醒存的东西（{@link AlertStore}）：关了再开话题、同意都留着；服务器只收 https；话题不进设置备份。
 *
 * The sentry alerts' store ({@link AlertStore}): topic and consent survive Off; the server is https only;
 * the topic never reaches the settings backup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class AlertStoreTest {

    private Context app;
    private AlertStore store;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AlertStore.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        store = AlertStore.of(app);
    }

    @Test
    public void offByDefaultWithAPicture() {
        assertFalse(store.isOn());
        assertFalse(store.ready());
        assertTrue("附带画面默认开", store.picture());
        assertFalse(store.ntfyAccepted());
    }

    @Test
    public void aNewTopicComesWithConsentAndTurningOffKeepsBoth() {
        String topic = store.newTopic(1_700_000_000_000L, 1_699_000_000_000L, "test");
        assertTrue(NtfyRequest.looksLikeTopic(topic));
        assertTrue(store.ntfyAccepted());
        assertEquals(topic.substring(topic.length() - 4), store.topicTail());

        store.turnOnNtfy();
        assertTrue(store.ready());
        store.turnOff();
        assertFalse(store.ready());
        assertEquals("关了话题留着", topic, store.topic());
        assertTrue("同意也留着", store.ntfyAccepted());
        store.turnOnNtfy();
        assertTrue("再选 ntfy 直接开", store.ready());
    }

    @Test
    public void changingTheTopicGivesAnotherOne() {
        String first = store.newTopic(1L, 0L, "x");
        String second = store.newTopic(1L, 0L, "x");
        assertNotEquals(first, second);
        assertEquals(second, store.topic());
        assertTrue(store.ntfyAccepted());
    }

    @Test
    public void theServerIsHttpsOnlyAndTheDefaultIsStoredEmpty() {
        assertEquals("", store.server());
        assertFalse("http 不收", store.setServer("http://car.local"));
        assertEquals("", store.server());
        assertTrue(store.setServer("https://ntfy.example.com"));
        assertEquals("https://ntfy.example.com", store.server());
        assertFalse("一样的不算换了", store.setServer("ntfy.example.com/"));
        assertTrue("换回默认的存成空串", store.setServer("https://ntfy.sh"));
        assertEquals("", store.server());
    }

    @Test
    public void theLastResultIsRemembered() {
        assertEquals(0L, store.lastAt());
        store.noteResult(1234L, AlertChannel.Result.fail(AlertChannel.Kind.REJECTED, 403, "forbidden"));
        assertEquals(1234L, store.lastAt());
        assertFalse(store.lastOk());
        assertEquals(AlertChannel.Kind.REJECTED, store.lastKind());
        assertEquals(403, store.lastCode());
        store.noteResult(5678L, AlertChannel.Result.ok());
        assertTrue(store.lastOk());
    }

    @Test
    public void theTopicNeverReachesTheSettingsBackup() throws Exception {
        String topic = store.newTopic(1L, 0L, "x");
        store.turnOnNtfy();
        store.setServer("https://ntfy.example.com");
        JSONObject snapshot = SettingsBackup.snapshot(app);
        String text = snapshot.toString();
        assertFalse("话题不在导出的设置里", text.contains(topic));
        assertFalse("服务器不在导出的设置里", text.contains("ntfy.example.com"));
    }
}
