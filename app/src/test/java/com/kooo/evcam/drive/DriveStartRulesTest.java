/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.drive.DriveStartRules.Decision;
import com.kooo.evcam.settings.SettingsRegistry;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/** 上车时切驾驶模式的规矩（{@link DriveStartRules}）。 */
public class DriveStartRulesTest {

    private static final long READY = DriveStartRules.SETTLE_MS;
    private static final Integer ON = Signal.IGNITION_ON;

    private static Decision decide(long since, boolean connected, Integer ignition, String gear) {
        return DriveStartRules.decide(since, connected, ignition, gear, 0, since);
    }

    @Test
    public void everySettingValueMapsToItsDriveMode() {
        assertEquals(0, DriveStartRules.target(AppConfig.DRIVE_MODE_AT_START_OFF));
        assertEquals(Signal.DRIVE_MODE_COMFORT, DriveStartRules.target(AppConfig.DRIVE_MODE_AT_START_COMFORT));
        assertEquals(Signal.DRIVE_MODE_STANDARD, DriveStartRules.target(AppConfig.DRIVE_MODE_AT_START_STANDARD));
        assertEquals(Signal.DRIVE_MODE_SPORT, DriveStartRules.target(AppConfig.DRIVE_MODE_AT_START_SPORT));
        assertEquals(0, DriveStartRules.target(null));
        assertEquals(0, DriveStartRules.target("snow"));
        // 设置里能选的除了默认的关闭，每一个都有要切到的模式
        for (String value : SettingsRegistry.DRIVE_MODE_AT_START.values()) {
            assertEquals(value, SettingsRegistry.DRIVE_MODE_AT_START.defaultValue.equals(value),
                    DriveStartRules.target(value) == 0);
        }
    }

    @Test
    public void writesOnceSettledPoweredAndInPark() {
        assertEquals(Decision.WAIT, decide(READY - 1, true, ON, "P"));
        assertEquals(Decision.WRITE, decide(READY, true, ON, "P"));
        assertEquals(Decision.WRITE, decide(READY, true, Signal.IGNITION_DRIVING, "P"));
        assertEquals(Decision.WRITE, decide(DriveStartRules.WINDOW_MS, true, ON, "P"));
    }

    @Test
    public void waitsWhileTheCarIsNotReady() {
        assertEquals(Decision.WAIT, decide(READY, false, ON, "P"));
        assertEquals(Decision.WAIT, decide(READY, true, null, "P"));
        assertEquals(Decision.WAIT, decide(READY, true, Signal.IGNITION_ACC, "P"));
        // 没见过的点火码不当通电
        assertEquals(Decision.WAIT, decide(READY, true, 0x00200102, "P"));
        assertEquals(Decision.WAIT, decide(READY, true, ON, null));
    }

    @Test
    public void givesUpWhenTheWindowEndsBeforeTheCarIsReady() {
        assertEquals(Decision.TIMED_OUT, decide(DriveStartRules.WINDOW_MS + 1, false, null, null));
        assertEquals(Decision.TIMED_OUT, decide(DriveStartRules.WINDOW_MS + 1, true, ON, "P"));
    }

    @Test
    public void neverWritesOutOfPark() {
        for (String gear : new String[] {"D", "R", "N"}) {
            assertEquals(gear, Decision.LEFT_PARK, decide(READY, true, ON, gear));
            // 还没等够、车没通电时读到别的挡位也一样：已经开走了
            assertEquals(gear, Decision.LEFT_PARK, decide(0, false, null, gear));
        }
    }

    @Test
    public void retriesAFailedWriteAfterAGapUpToTheLimit() {
        assertEquals(Decision.WAIT, DriveStartRules.decide(READY + 1000, true, ON, "P", 1, DriveStartRules.RETRY_MS - 1));
        assertEquals(Decision.WRITE, DriveStartRules.decide(READY + 1000, true, ON, "P", 1, DriveStartRules.RETRY_MS));
        assertEquals(Decision.WRITE, DriveStartRules.decide(READY + 1000, true, ON, "P",
                DriveStartRules.MAX_WRITES - 1, DriveStartRules.RETRY_MS));
        assertEquals(Decision.FAILED, DriveStartRules.decide(READY + 1000, true, ON, "P",
                DriveStartRules.MAX_WRITES, DriveStartRules.RETRY_MS));
    }

    @Test
    public void aProcessStartCountsOnlyAfterTheCarWasLeftOrTheHeadUnitRebooted() {
        assertFalse(DriveStartRules.startsAtProcessStart(false, false));
        assertTrue(DriveStartRules.startsAtProcessStart(true, false));
        assertTrue(DriveStartRules.startsAtProcessStart(false, true));
    }
}
