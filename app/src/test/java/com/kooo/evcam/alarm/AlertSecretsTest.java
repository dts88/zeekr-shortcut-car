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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.settings.SettingsBackup;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 话题名是钥匙：知道名字的人就能看。
 *
 * <p>它和 ntfy 的令牌一样是凭据，所以和以前令牌的规矩相同：
 * {@link SettingsBackup#snapshot(Context)} 会把 {@code app_config} 里的每一个键写进导出文件，
 * 没见过的键也算「导出」。凭据存在单独的文件里（{@link AppConfig#SECRETS_PREF_NAME}），
 * 这个测试把这件事钉住：话题配好之后，导出的整份设置里一个字符都不出现。</p>
 *
 * The topic is the key: whoever knows the name can read.
 *
 * <p>Like the token before it, it is a credential, so it follows the same rule:
 * {@link SettingsBackup#snapshot(Context)} writes every key in {@code app_config}
 * into the backup file, and unknown keys count as "export". The credential is
 * stored separately ({@link AppConfig#SECRETS_PREF_NAME}), and this test pins
 * that down: after the topic is set, not one character of it appears
 * anywhere in the whole exported settings.
 */
@RunWith(RobolectricTestRunner.class)
public class AlertSecretsTest {

    private static final String TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    @Test
    public void theTopicIsNotInTheSettingsExportOrTheDiagnostics() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyToken("tk_AAH_exampleTOKENvalue_0123456789");
        config.setAlertsEnabled(true);
        assertEquals(TOPIC, config.getAlertsNtfyTopic());

        JSONObject snapshot = new JSONObject(SettingsBackup.snapshot(context).toString());
        assertFalse("导出的设置里不能有话题名", holds(snapshot, TOPIC));
        // ntfy 的令牌也是凭据：和话题一起放在单独的文件里，同样不进导出
        // The ntfy token is also a credential: it lives in the same separate file as
        // the topic, and also stays out of the export.
        assertFalse("导出的设置里不能有 ntfy 令牌", holds(snapshot, "tk_AAH_exampleTOKENvalue_0123456789"));
    }

    @Test
    public void theTopicLivesInItsOwnPreferencesFile() {
        assertNotEquals(AppConfig.PREF_NAME, AppConfig.SECRETS_PREF_NAME);
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsNtfyTopic(TOPIC);
        assertFalse("话题名不许落进普通设置那个文件",
                context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE)
                        .contains("alerts_ntfy_topic"));
        assertTrue(context.getSharedPreferences(AppConfig.SECRETS_PREF_NAME, Context.MODE_PRIVATE)
                .contains("alerts_ntfy_topic"));
    }

    @Test
    public void clearingTheTopicClearsItForGood() {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyTopic("");
        assertEquals("", config.getAlertsNtfyTopic());
        assertFalse(config.hasAlertsNtfyConfig());
    }

    @Test
    public void alertsStayOffUntilSomebodyTurnsThemOn() {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsNtfyTopic("");
        config.setAlertsEnabled(false);
        assertFalse("默认关：没答应它的车上不该开始发警报", config.isAlertsEnabled());
        assertFalse(config.hasAlertsNtfyConfig());
    }

    /**
     * 这棵 JSON 里有没有哪个字符串值正好是它。
     *
     * Whether any string value in this JSON tree is exactly the wanted one.
     */
    private static boolean holds(Object node, String wanted) throws org.json.JSONException {
        if (node instanceof JSONObject) {
            org.json.JSONObject object = (org.json.JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (holds(object.get(keys.next()), wanted)) {
                    return true;
                }
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                if (holds(array.get(i), wanted)) {
                    return true;
                }
            }
        } else if (node instanceof String) {
            return node.equals(wanted);
        }
        return false;
    }
}
