/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardToken;

/** 按钮上那一小段号码（{@link GuardToken}）。 */
public class GuardTokenTest {

    private static final String TOKEN = GuardToken.mint(1_700_000_000_000L);

    @Test
    public void thePayloadFitsTelegramsSixtyFourByteLimit() {
        for (String action : new String[]{GuardToken.ACTION_ON, GuardToken.ACTION_NO,
                GuardToken.ACTION_OFF, GuardToken.ACTION_STATE}) {
            String data = GuardToken.data(action, TOKEN);
            assertTrue(data, data.getBytes(StandardCharsets.UTF_8).length <= 64);
        }
    }

    @Test
    public void aPressFromThisReminderIsUnderstood() {
        assertEquals(GuardToken.ACTION_ON,
                GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_ON, TOKEN), TOKEN));
        assertEquals(GuardToken.ACTION_NO,
                GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_NO, TOKEN), TOKEN));
        assertEquals(GuardToken.ACTION_OFF,
                GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_OFF, TOKEN), TOKEN));
        assertEquals(GuardToken.ACTION_STATE,
                GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_STATE, TOKEN), TOKEN));
    }

    @Test
    public void aWeekOldButtonDoesNothing() {
        // 换了一次提醒就换了号：旧按钮按下去不认
        String older = GuardToken.mint(1_600_000_000_000L);
        String data = GuardToken.data(GuardToken.ACTION_ON, older);
        assertNull(GuardToken.actionFor(data, TOKEN));
    }

    @Test
    public void otherPeoplesButtonsAndJunkDoNothing() {
        assertNull(GuardToken.actionFor(null, TOKEN));
        assertNull(GuardToken.actionFor("", TOKEN));
        assertNull(GuardToken.actionFor("hd", TOKEN));
        assertNull(GuardToken.actionFor("g:on", TOKEN));
        assertNull(GuardToken.actionFor("g:on:" + TOKEN + ":x", TOKEN));
        assertNull(GuardToken.actionFor("x:on:" + TOKEN, TOKEN));
        // 认不出的动作：不猜
        assertNull(GuardToken.actionFor("g:delete:" + TOKEN, TOKEN));
        // 没有号
        assertNull(GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_ON, TOKEN), ""));
        assertNull(GuardToken.actionFor(GuardToken.data(GuardToken.ACTION_ON, TOKEN), null));
    }

    @Test
    public void everySeedGetsASixCharacterToken() {
        long[] seeds = {0L, 1L, 1_000L, 1_700_000_000_000L, Long.MAX_VALUE, -5L, Long.MIN_VALUE};
        for (long seed : seeds) {
            String token = GuardToken.mint(seed);
            assertEquals(token, 6, token.length());
            assertTrue(token, token.matches("[0-9a-z]+"));
        }
    }

    @Test
    public void twoRemindersAMinuteApartNeverShareAToken() {
        String first = GuardToken.mint(1_700_000_000_000L);
        String second = GuardToken.mint(1_700_000_060_000L);
        assertNotEquals(first, second);
        // 相邻的毫秒也不撞
        assertNotEquals(GuardToken.mint(1L), GuardToken.mint(2L));
    }

    @Test
    public void thePayloadCarriesNothingButTheActionAndTheToken() {
        String data = GuardToken.data(GuardToken.ACTION_ON, TOKEN);
        String[] parts = data.split(":");
        assertEquals(3, parts.length);
        assertEquals("g", parts[0]);
        assertEquals(GuardToken.ACTION_ON, parts[1]);
        assertEquals(TOKEN, parts[2]);
    }
}
