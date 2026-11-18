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

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import com.kooo.evcam.alarm.policy.TriggerSet;

/** 车能发给机器人的每一条消息（{@link AlertEvent}）。 */
public class AlertEventTest {

    @Test
    public void everyRowHasItsOwnTitleAndDescription() {
        Set<Integer> titles = new HashSet<>();
        for (AlertEvent event : AlertEvent.values()) {
            assertNotEquals(0, event.titleRes);
            assertNotEquals(0, event.descriptionRes);
            assertTrue("换个标题：两行撞在一起就读不出区别了", titles.add(event.titleRes));
        }
    }

    @Test
    public void theFourClipRowsCarryTheirLaneAndNothingElseDoes() {
        int clips = 0;
        for (AlertEvent event : AlertEvent.values()) {
            if (event.lane() >= 0) {
                clips++;
            }
        }
        assertEquals(4, clips);
        assertEquals(0, AlertEvent.CLIP_FRONT.lane());
        assertEquals(1, AlertEvent.CLIP_REAR.lane());
        assertEquals(2, AlertEvent.CLIP_LEFT.lane());
        assertEquals(3, AlertEvent.CLIP_RIGHT.lane());
        assertEquals(-1, AlertEvent.STATUS.lane());
    }

    @Test
    public void theRowsThatNeedASnapshotSaySo() {
        for (AlertEvent event : new AlertEvent[] {AlertEvent.HAZARD, AlertEvent.FLASH,
                AlertEvent.DOORS, AlertEvent.LAMPS, AlertEvent.SCREEN, AlertEvent.MOTION,
                AlertEvent.PICTURE, AlertEvent.HD_PICTURE}) {
            assertTrue(event.name(), event.isPicture());
        }
        for (AlertEvent event : new AlertEvent[] {AlertEvent.STATUS, AlertEvent.HELP,
                AlertEvent.CLIP_FRONT}) {
            assertFalse(event.name(), event.isPicture());
        }
    }

    @Test
    public void allSixTriggersAreOnTheList() {
        // 设置里能勾的六项，这里必须都有一行，否则「每一条消息」是假的
        assertEquals(6, TriggerSet.ALL.length);
        Set<Integer> headlines = new HashSet<>();
        for (AlertEvent event : new AlertEvent[] {AlertEvent.HAZARD, AlertEvent.FLASH,
                AlertEvent.DOORS, AlertEvent.LAMPS, AlertEvent.SCREEN, AlertEvent.MOTION}) {
            assertTrue(event.name(), headlines.add(event.titleRes));
        }
    }
}
