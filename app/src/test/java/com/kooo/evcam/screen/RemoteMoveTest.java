/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 遥控挪车（推断）只记录（{@link RemoteMove}）：什么时候算开始、怎么算完、汇总里数什么。 */
public class RemoteMoveTest {

    private static final Boolean LOW = Boolean.TRUE;
    private static final Boolean POWERED = Boolean.FALSE;

    /** 车上 2026-11-22 12:31–12:33 那一种：熄屏、主驾座椅没人，车通电、P 和 D 之间来回挂，最后停在 P、点火掉到 ACC。 */
    @Test
    public void aRemoteMoveIsCountedAndEndsOnceItHasStoodStill() {
        RemoteMove m = new RemoteMove();
        assertEquals(RemoteMove.Change.NONE, m.readings(true, false, "P", false, POWERED, 0, 0));
        assertEquals(RemoteMove.Change.STARTED, m.readings(true, false, "D", false, POWERED, 0, 16_000));
        assertTrue(m.active());
        assertEquals("D", m.gear());
        m.readings(true, false, "P", false, POWERED, 0, 24_000);
        m.readings(true, false, "P", false, LOW, 0, 33_000);
        m.readings(true, false, "P", false, POWERED, 0, 36_000);
        m.readings(true, false, "D", false, POWERED, 0, 50_000);
        m.readings(true, false, "R", false, POWERED, 0, 60_000);
        m.readings(true, false, "P", false, POWERED, 0, 70_000);
        assertEquals("停在 P、点火还通着：没停稳", RemoteMove.Change.NONE,
                m.readings(true, false, "P", false, LOW, 0, 80_000));
        assertEquals(RemoteMove.Change.NONE, m.readings(true, false, "P", false, LOW, 0, 80_000 + RemoteMove.QUIET_MS - 1));
        assertEquals(RemoteMove.Change.ENDED, m.readings(true, false, "P", false, LOW, 0, 80_000 + RemoteMove.QUIET_MS));
        assertFalse(m.active());
        assertEquals(RemoteMove.End.QUIET, m.end());
        assertEquals("算到停稳的那一刻", 80_000 - 16_000, m.durationMs(200_000));
        assertEquals(2, m.drive());
        assertEquals(1, m.reverse());
        assertEquals(0, m.neutral());
        assertEquals(2, m.powerOff());
        assertEquals(1, m.powerOn());
    }

    /** 主驾座椅有人、读不到：可能是人在开，不算；亮着、车机要睡、在 P：都不算开始。 */
    @Test
    public void itOnlyStartsWhenDarkInGearWithTheDriverSeatEmpty() {
        RemoteMove m = new RemoteMove();
        assertEquals(RemoteMove.Change.NONE, m.readings(true, false, "D", true, POWERED, 0, 0));
        assertEquals(RemoteMove.Change.NONE, m.readings(true, false, "D", null, POWERED, 0, 0));
        assertEquals(RemoteMove.Change.NONE, m.readings(false, false, "D", false, POWERED, 0, 0));
        assertEquals(RemoteMove.Change.NONE, m.readings(true, true, "D", false, POWERED, 0, 0));
        assertEquals(RemoteMove.Change.NONE, m.readings(true, false, null, false, POWERED, 0, 0));
        assertFalse(m.active());
    }

    /** 挪完人上车（有了人的证据）、车机要睡：当场算完。 */
    @Test
    public void aPersonOrTheHeadUnitSleepingEndsIt() {
        RemoteMove m = new RemoteMove();
        m.readings(true, false, "D", false, POWERED, 2, 0);
        m.readings(true, false, "P", false, POWERED, 0, 20_000);
        assertEquals(RemoteMove.Change.ENDED, m.readings(false, false, "P", true, POWERED, 0, 30_000));
        assertEquals(RemoteMove.End.PERSON, m.end());
        assertEquals(30_000, m.durationMs(99_000));
        assertEquals("armed", m.sentryAtStart());
        assertEquals("off", m.sentryNow());

        m.readings(true, false, "R", false, POWERED, null, 100_000);
        assertEquals(RemoteMove.Change.ENDED, m.headUnitSleeps(110_000));
        assertEquals(RemoteMove.End.SLEEP, m.end());
        assertEquals(RemoteMove.Change.NONE, m.headUnitSleeps(120_000));
        assertEquals("?", m.sentryAtStart());
    }

    /** 停稳以后读数一直没变（没人叫这里）：下一次来的时候照停稳的那一刻算完，哪怕那一下是人回来了。 */
    @Test
    public void aLateCallStillEndsItWhereItStoodStill() {
        RemoteMove m = new RemoteMove();
        m.readings(true, false, "D", false, POWERED, 0, 0);
        m.readings(true, false, "P", false, LOW, 0, 10_000);
        assertEquals(RemoteMove.Change.ENDED, m.readings(false, false, "P", true, POWERED, 0, 500_000));
        assertEquals(RemoteMove.End.QUIET, m.end());
        assertEquals(10_000, m.durationMs(500_000));
        assertEquals(490_000, m.quietLagMs(500_000));
    }
}
