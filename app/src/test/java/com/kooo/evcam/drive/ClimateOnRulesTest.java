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
import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.drive.ClimateOnRules.Decision;
import com.kooo.evcam.settings.SettingsRegistry;

import org.junit.Test;

/** 前排空调重新打开后恢复循环模式、二排空调的规矩（{@link ClimateOnRules}）。 */
public class ClimateOnRulesTest {

    private static OffOn after(boolean awaiting, Integer... powers) {
        OffOn track = OffOn.start(awaiting);
        for (Integer power : powers) {
            track = track.next(power);
        }
        return track;
    }

    private static Decision decide(OffOn track) {
        return ClimateOnRules.decide(track, 0, 0);
    }

    @Test
    public void everySettingValueMapsToItsCirculation() {
        assertEquals(0, ClimateOnRules.circulation(AppConfig.CIRCULATION_AT_CLIMATE_ON_OFF));
        assertEquals(CarMoves.CIRCULATION_INNER, ClimateOnRules.circulation(AppConfig.CIRCULATION_AT_CLIMATE_ON_INNER));
        assertEquals(CarMoves.CIRCULATION_OUTER, ClimateOnRules.circulation(AppConfig.CIRCULATION_AT_CLIMATE_ON_OUTER));
        assertEquals(0, ClimateOnRules.circulation(null));
        assertEquals(0, ClimateOnRules.circulation("auto"));
        // 设置里能选的除了默认的关闭，每一个都有要切到的循环模式
        for (String value : SettingsRegistry.CIRCULATION_AT_CLIMATE_ON.values()) {
            assertEquals(value, SettingsRegistry.CIRCULATION_AT_CLIMATE_ON.defaultValue.equals(value),
                    ClimateOnRules.circulation(value) == 0);
        }
    }

    @Test
    public void writesAfterTheClimateWasSeenOffThenOn() {
        assertEquals(Decision.WAIT, decide(after(false, 1, 1, 1)));
        assertEquals(Decision.WAIT, decide(after(false, 1, 0, 0)));
        assertEquals(Decision.WAIT, decide(after(false, 1, 0, 0, 1)));
        assertEquals(Decision.WRITE, decide(after(false, 1, 0, 0, 1, 1)));
        assertEquals(Decision.WRITE, decide(after(false, 0, 0, 0, 0, 1, 1, 1)));
    }

    @Test
    public void oneOffReadingIsNotEnough() {
        assertFalse(after(false, 1, 0).awaiting);
        assertEquals(Decision.WAIT, decide(after(false, 1, 0, 1, 1)));
        // 两次关着中间隔了一次开着、一次读不到：不算连着
        assertFalse(after(false, 0, 1, 0).awaiting);
        assertFalse(after(false, 0, null, 0).awaiting);
        assertFalse(after(false, 0, 255, 0).awaiting);
        assertTrue(after(false, 0, 0).awaiting);
    }

    @Test
    public void onMustBeSeenTwiceInARow() {
        assertEquals(Decision.WAIT, decide(after(true, 1)));
        assertEquals(Decision.WAIT, decide(after(true, 1, null, 1)));
        assertEquals(Decision.WAIT, decide(after(true, 1, 0, 1)));
        assertEquals(Decision.WRITE, decide(after(true, 1, 1)));
        // 车没通电的那一会儿：连着的次数从头数，在等照旧
        OffOn resumed = after(true, 1).unknown().next(1);
        assertTrue(resumed.awaiting);
        assertEquals(Decision.WAIT, decide(resumed));
        assertEquals(Decision.WRITE, decide(resumed.next(1)));
    }

    @Test
    public void staysDoneUntilTheClimateGoesOffAgain() {
        OffOn done = after(false, 0, 0, 1, 1).done();
        assertFalse(done.awaiting);
        assertEquals(Decision.WAIT, decide(done.next(1).next(1)));
        assertEquals(Decision.WRITE, decide(done.next(0).next(0).next(1).next(1)));
    }

    @Test
    public void retriesAFailedWriteAfterAGapUpToTheLimit() {
        OffOn on = after(true, 1, 1);
        assertEquals(Decision.WAIT, ClimateOnRules.decide(on, 1, ClimateOnRules.RETRY_MS - 1));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(on, 1, ClimateOnRules.RETRY_MS));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(on, ClimateOnRules.MAX_WRITES - 1, ClimateOnRules.RETRY_MS));
        assertEquals(Decision.FAILED, ClimateOnRules.decide(on, ClimateOnRules.MAX_WRITES, ClimateOnRules.RETRY_MS));
        // 空调又关了：不写，等它再开
        assertEquals(Decision.WAIT, ClimateOnRules.decide(on.next(0), ClimateOnRules.MAX_WRITES, ClimateOnRules.RETRY_MS));
    }
}
