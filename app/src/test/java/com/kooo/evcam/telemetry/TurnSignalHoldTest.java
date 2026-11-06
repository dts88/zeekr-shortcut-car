/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 转向灯闪烁的熄灭半拍不算「不打了」；两侧一起是双闪；读不到就是不知道。
 */
public class TurnSignalHoldTest {

    @Test
    public void nothingReadableMeansUnknown() {
        TurnSignalHold hold = new TurnSignalHold();
        TurnSignalHold.Result r = hold.update(0, null, null);
        assertNull(r.turn);
        assertNull(r.hazard);
    }

    @Test
    public void aBlinkingLeftLampStaysLeftThroughTheOffHalf() {
        TurnSignalHold hold = new TurnSignalHold();
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), hold.update(0, true, false).turn);
        // 365 ms 后灭着：仍然算在打左灯
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), hold.update(365, false, false).turn);
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), hold.update(730, true, false).turn);
        // 松手 1.5 秒后才算停
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), hold.update(730 + 1400, false, false).turn);
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), hold.update(730 + 1500, false, false).turn);
    }

    @Test
    public void bothLampsMeanHazard() {
        TurnSignalHold hold = new TurnSignalHold();
        TurnSignalHold.Result r = hold.update(0, true, true);
        assertTrue(r.hazard);
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), r.turn);
        TurnSignalHold.Result later = hold.update(400, false, false);
        assertTrue("熄灭的半拍仍是双闪", later.hazard);
    }

    @Test
    public void offLampsAreOffNotUnknown() {
        TurnSignalHold hold = new TurnSignalHold();
        TurnSignalHold.Result r = hold.update(0, false, false);
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), r.turn);
        assertFalse(r.hazard);
    }
}
