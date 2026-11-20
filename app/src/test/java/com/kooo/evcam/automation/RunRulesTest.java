/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/** 一次触发里每个动作怎么走（{@link RunRules}）：情形照 Calvin 的 DriveStartRulesTest、ClimateOnRulesTest、HillDescentRulesTest。 */
public class RunRulesTest {

    private static RunRules.Step plain(long since, boolean ready, int writes, long sinceWrite) {
        return RunRules.decide(since, ready, false, "P", false, false, 0, writes, sinceWrite);
    }

    @Test
    public void writesOnceReadyAndWaitsBeforeThat() {
        assertEquals(RunRules.Step.WAIT, plain(2_500, false, 0, 0));
        assertEquals(RunRules.Step.WRITE, plain(2_500, true, 0, 0));
    }

    @Test
    public void givesUpWhenNothingIsReadyWithinTheWindow() {
        assertEquals(RunRules.Step.WAIT, plain(RunRules.WINDOW_MS, false, 0, 0));
        assertEquals(RunRules.Step.TIMED_OUT, plain(RunRules.WINDOW_MS + 1, false, 0, 0));
        assertEquals("已经写过一次的接着重试，不受时限", RunRules.Step.WRITE,
                plain(RunRules.WINDOW_MS + 1, true, 1, RunRules.RETRY_MS));
    }

    @Test
    public void retriesThreeSecondsApartAtMostThreeTimes() {
        assertEquals(RunRules.Step.WAIT, plain(10_000, true, 1, RunRules.RETRY_MS - 1));
        assertEquals(RunRules.Step.WRITE, plain(10_000, true, 1, RunRules.RETRY_MS));
        assertEquals(RunRules.Step.WRITE, plain(10_000, true, 2, RunRules.RETRY_MS));
        assertEquals(RunRules.Step.FAILED, plain(10_000, true, RunRules.MAX_WRITES, RunRules.RETRY_MS));
    }

    /** 只在 P 挡写的：读到别的挡就算了；挡位读不到再等等。 */
    @Test
    public void parkOnlyStopsOnceTheCarLeavesPark() {
        assertEquals(RunRules.Step.WRITE, RunRules.decide(3_000, true, true, "P", false, false, 0, 0, 0));
        assertEquals(RunRules.Step.LEFT_PARK, RunRules.decide(3_000, true, true, "D", false, false, 0, 0, 0));
        assertEquals(RunRules.Step.LEFT_PARK, RunRules.decide(3_000, false, true, "R", false, false, 0, 0, 0));
        assertEquals(RunRules.Step.WAIT, RunRules.decide(3_000, true, true, null, false, false, 0, 0, 0));
        assertEquals("别的不看挡位", RunRules.Step.WRITE, RunRules.decide(3_000, true, false, "D", false, false, 0, 0, 0));
    }

    /** 要等能用的（陡坡缓降）：每秒问一次，确认能用才写；不受 30 秒的时限。 */
    @Test
    public void waitingForAvailabilityAsksEverySecondWithoutAWindow() {
        assertEquals(RunRules.Step.ASK, RunRules.decide(60_000, true, false, "P", true, false, RunRules.WATCH_MS, 0, 0));
        assertEquals(RunRules.Step.WAIT, RunRules.decide(60_000, true, false, "P", true, false, RunRules.WATCH_MS - 1, 0, 0));
        assertEquals(RunRules.Step.WRITE, RunRules.decide(60_000, true, false, "P", true, true, 0, 0, 0));
        assertEquals(RunRules.Step.WAIT, RunRules.decide(60_000, false, false, "P", true, false, RunRules.WATCH_MS, 0, 0));
    }

    @Test
    public void poweredMeansOnOrDriving() {
        assertTrue(RunRules.powered(Signal.IGNITION_ON));
        assertTrue(RunRules.powered(Signal.IGNITION_DRIVING));
        assertFalse(RunRules.powered(Signal.IGNITION_ACC));
        assertFalse(RunRules.powered(null));
        assertFalse(RunRules.powered(0x1234));
    }

    /** 进程起来时屏幕亮着：在等下一次上车、或者车机真正开过机才算一次上车。 */
    @Test
    public void processStartCountsAsGettingInOnlyAfterLeavingOrAReboot() {
        assertTrue(RunRules.startsAtProcessStart(true, false));
        assertTrue(RunRules.startsAtProcessStart(false, true));
        assertFalse(RunRules.startsAtProcessStart(false, false));
    }

    /** 连着两次读到同一个值才算（Calvin 的 OffOn）；读不到从头数。 */
    @Test
    public void steadyNeedsTwoReadingsInARow() {
        Steady s = Steady.NONE.next(1);
        assertFalse(s.confirmed());
        s = s.next(1);
        assertTrue(s.justConfirmed());
        s = s.next(1);
        assertTrue(s.confirmed());
        assertFalse("确认之后再读到不算又一次", s.justConfirmed());
        s = s.next(null);
        assertFalse(s.confirmed());
        s = s.next(0).next(1);
        assertFalse("换了值从头数", s.confirmed());
    }
}
