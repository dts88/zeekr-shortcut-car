package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link SideViewDecision} 的单元测试：左灯只弹左、右灯只弹右、灯灭就收、原厂画面优先。
 */
public class SideViewDecisionTest {

    private static SideViewDecision.Input driving(Integer turn) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = turn;
        in.hazard = false;
        in.gear = "D";
        in.stock360Shown = false;
        in.stockPopupShown = false;
        in.parkAssistOn = false;
        in.speedKmh = 60f;
        in.minSpeedKmh = 30;
        return in;
    }

    @Test
    public void leftSignalShowsLeftOnly() {
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(driving(1)));
    }

    @Test
    public void rightSignalShowsRightOnly() {
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(driving(2)));
    }

    @Test
    public void signalOffCloses() {
        SideViewDecision.Input in = driving(0);
        in.showing = LaneCycle.LEFT;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void unreadableSignalShowsNothing() {
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(driving(null)));
    }

    @Test
    public void hazardsShowNothing() {
        SideViewDecision.Input in = driving(0);
        in.hazard = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void switchingSidesFollowsTheSignal() {
        SideViewDecision.Input in = driving(2);
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(in));
    }

    @Test
    public void reverseGearWins() {
        SideViewDecision.Input in = driving(1);
        in.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void stockPopupWinsEvenWhenAlreadyShowing() {
        SideViewDecision.Input in = driving(1);
        in.showing = LaneCycle.LEFT;
        in.stockPopupShown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void stock360AndParkAssistWin() {
        SideViewDecision.Input a = driving(2);
        a.stock360Shown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(a));
        SideViewDecision.Input b = driving(2);
        b.parkAssistOn = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(b));
    }

    @Test
    public void belowSpeedThresholdDoesNotOpen() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void slowingDownDoesNotCloseAnOpenPopup() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(in));
    }

    @Test
    public void staysReadyOnlyInDrive() {
        org.junit.Assert.assertTrue(SideViewDecision.shouldStayReady(driving(0)));
        for (String gear : new String[]{"P", "N", "R", null}) {
            SideViewDecision.Input in = driving(0);
            in.gear = gear;
            org.junit.Assert.assertFalse("gear " + gear, SideViewDecision.shouldStayReady(in));
        }
    }

    @Test
    public void zeroThresholdOrUnknownSpeedDoesNotBlock() {
        SideViewDecision.Input a = driving(1);
        a.speedKmh = 5f;
        a.minSpeedKmh = 0;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(a));
        SideViewDecision.Input b = driving(1);
        b.speedKmh = null;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(b));
    }
}
