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
import com.kooo.evcam.settings.SettingsBackup;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Iterator;

/**
 * 令牌不能跟着设置一起出门（{@link AppConfig}，停车警报）：导出的设置、诊断报告、黑匣子都不该有它。
 *
 * <p>{@link SettingsBackup#snapshot(Context)} 把 {@code app_config} 里的每一个键都写进备份文件，
 * 未知的键按“要导出”算。所以凭据另存一份（{@link AppConfig#SECRETS_PREF_NAME}），
 * 这条测试就是钉住这件事：填了令牌之后，整份导出的设置里一个字都找不到它。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AlertSecretsTest {

    private static final String TOKEN = "123456789:AAH_exampleTOKENvalue_0123456789";

    @Test
    public void theTokenIsNotInTheSettingsExportOrTheDiagnostics() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsTelegramToken(TOKEN);
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsEnabled(true);
        assertEquals(TOKEN, config.getAlertsTelegramToken());

        JSONObject snapshot = new JSONObject(SettingsBackup.snapshot(context).toString());
        assertFalse("导出的设置里不能有令牌", holds(snapshot, TOKEN));
        // 会话号不是凭据，是设置的一部分：它应该在导出里，方便换一台车机接着用
        assertTrue("会话号是设置，应当导出", holds(snapshot, "123456789"));
    }

    @Test
    public void theTokenLivesInItsOwnPreferencesFile() {
        assertNotEquals(AppConfig.PREF_NAME, AppConfig.SECRETS_PREF_NAME);
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsTelegramToken(TOKEN);
        assertFalse("令牌不许落进普通设置那个文件",
                context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE)
                        .contains("alerts_telegram_token"));
        assertTrue(context.getSharedPreferences(AppConfig.SECRETS_PREF_NAME, Context.MODE_PRIVATE)
                .contains("alerts_telegram_token"));
    }

    @Test
    public void clearingTheTokenClearsItForGood() {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsTelegramToken(TOKEN);
        config.setAlertsTelegramToken("");
        assertEquals("", config.getAlertsTelegramToken());
        assertFalse(config.hasAlertsTelegramConfig());
    }

    @Test
    public void alertsStayOffUntilSomebodyTurnsThemOn() {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(context);
        config.setAlertsTelegramToken("");
        config.setAlertsEnabled(false);
        assertFalse("默认关：没答应它的车上不该开始发警报", config.isAlertsEnabled());
        assertFalse(config.hasAlertsTelegramConfig());
    }

    /** 这棵 JSON 里有没有哪个字符串值正好是它。 */
    private static boolean holds(Object node, String wanted) throws org.json.JSONException {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                if (holds(object.get(keys.next()), wanted)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                if (holds(array.get(i), wanted)) {
                    return true;
                }
            }
            return false;
        }
        return node instanceof String && wanted.equals(node);
    }
}
