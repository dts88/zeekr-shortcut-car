/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import static org.junit.Assert.assertEquals;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.drive.ClimateOnRules.Decision;
import com.kooo.evcam.settings.SettingsRegistry;

import org.junit.Test;

/** 前排空调重新打开后恢复循环模式、二排空调的规矩（{@link ClimateOnRules}）；关着、开着怎么数在 {@code OffOnTest}。 */
public class ClimateOnRulesTest {

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
    public void writesOnceItHasBeenOnTwiceInARowAfterBeingOff() {
        assertEquals(Decision.WAIT, ClimateOnRules.decide(true, 0, 0, 0));
        assertEquals(Decision.WAIT, ClimateOnRules.decide(true, OffOn.CONFIRM - 1, 0, 0));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(true, OffOn.CONFIRM, 0, 0));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(true, 60, 0, 0));
    }

    @Test
    public void doesNothingWhileNothingIsPending() {
        assertEquals(Decision.WAIT, ClimateOnRules.decide(false, 60, 0, 0));
        assertEquals(Decision.WAIT, ClimateOnRules.decide(false, 60, ClimateOnRules.MAX_WRITES, 0));
    }

    @Test
    public void retriesAFailedWriteAfterAGapUpToTheLimit() {
        assertEquals(Decision.WAIT, ClimateOnRules.decide(true, 5, 1, ClimateOnRules.RETRY_MS - 1));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(true, 5, 1, ClimateOnRules.RETRY_MS));
        assertEquals(Decision.WRITE, ClimateOnRules.decide(true, 5, ClimateOnRules.MAX_WRITES - 1, ClimateOnRules.RETRY_MS));
        assertEquals(Decision.FAILED, ClimateOnRules.decide(true, 5, ClimateOnRules.MAX_WRITES, ClimateOnRules.RETRY_MS));
        // 写满了次数就算了，不看这一会儿开没开着
        assertEquals(Decision.FAILED, ClimateOnRules.decide(true, 0, ClimateOnRules.MAX_WRITES, 0));
    }
}
