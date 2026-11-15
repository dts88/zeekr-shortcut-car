/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 警报照片下面那排按钮（{@link AlertButton}）。 */
public class AlertButtonTest {

    private static final String TOKEN = GuardToken.mint(1_700_000_000_000L);

    @Test
    public void everyActionRoundTrips() {
        assertEquals(AlertButton.ACTION_HIGH, AlertButton.actionFor(
                AlertButton.data(AlertButton.ACTION_HIGH, TOKEN), TOKEN));
        for (int lane = 0; lane < 4; lane++) {
            String action = AlertButton.clipAction(lane);
            assertEquals(action, AlertButton.actionFor(AlertButton.data(action, TOKEN), TOKEN));
            assertEquals(lane, AlertButton.laneOf(action));
        }
    }

    @Test
    public void anOlderAlertsButtonDoesNothing() {
        String older = GuardToken.mint(1_600_000_000_000L);
        assertNull(AlertButton.actionFor(AlertButton.data(AlertButton.ACTION_HIGH, older), TOKEN));
    }

    @Test
    public void theReminderButtonsAreNotOurs() {
        // 忘了开哨兵那套是 g:…，两套按钮不串
        assertNull(AlertButton.actionFor(GuardToken.data(GuardToken.ACTION_ON, TOKEN), TOKEN));
    }

    @Test
    public void junkAndOutOfRangeLanesDoNothing() {
        assertNull(AlertButton.actionFor(null, TOKEN));
        assertNull(AlertButton.actionFor("", TOKEN));
        assertNull(AlertButton.actionFor("a:hq", TOKEN));
        assertNull(AlertButton.actionFor("a:c4:" + TOKEN, TOKEN));
        assertNull(AlertButton.actionFor("a:c-1:" + TOKEN, TOKEN));
        assertNull(AlertButton.actionFor("a:del:" + TOKEN, TOKEN));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf("hq"));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf(null));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf("c9"));
    }

    @Test
    public void everyPayloadFitsTelegramsSixtyFourByteLimit() {
        for (int lane = 0; lane < 4; lane++) {
            String data = AlertButton.data(AlertButton.clipAction(lane), TOKEN);
            assertTrue(data, data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        }
    }
}
