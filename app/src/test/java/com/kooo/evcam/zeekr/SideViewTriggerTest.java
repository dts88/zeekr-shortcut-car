package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SideViewTrigger}：D 挡备着、打灯显示、灯灭后留 1 秒、离开 D 挡 30 秒后摘掉、原厂画面和熄屏立刻全收。 */
public class SideViewTriggerTest {

    private static SideViewDecision.Input in(String gear, Integer turn) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = turn;
        in.hazard = false;
        in.gear = gear;
        in.stock360Shown = false;
        in.stockPopupShown = false;
        in.parkAssistOn = false;
        in.speedKmh = 60f;
        in.minSpeedKmh = 30;
        return in;
    }

    @Test
    public void driveKeepsTheWindowReadyAndHidden() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewTrigger.Result r = t.update(0, in("D", 0), false);
        assertTrue(r.attached);
        assertFalse(r.shown);
        assertEquals(0, r.wakeAt);
    }

    @Test
    public void parkWithoutSignalAttachesNothing() {
        SideViewTrigger.Result r = new SideViewTrigger().update(0, in("P", 0), false);
        assertFalse(r.attached);
        assertFalse(r.shown);
    }

    @Test
    public void signalShowsThatSideAndHoldsOneSecondAfterItEnds() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewTrigger.Result r = t.update(0, in("D", 2), false);
        assertTrue(r.shown);
        assertEquals(LaneCycle.RIGHT, r.lane);

        r = t.update(5_000, in("D", 0), false);
        assertTrue("灯灭后还留着", r.shown);
        assertEquals(5_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS, r.wakeAt);

        r = t.update(5_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS, in("D", 0), false);
        assertFalse(r.shown);
        assertTrue("D 挡：回到备着", r.attached);
        assertEquals("窗口停在上一次那一侧", LaneCycle.RIGHT, r.lane);
    }

    @Test
    public void signallingAgainDuringTheHoldKeepsShowing() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        t.update(1_000, in("D", 0), false);
        SideViewTrigger.Result r = t.update(1_500, in("D", 1), false);
        assertTrue(r.shown);
        assertEquals(0, r.wakeAt);
        r = t.update(2_100, in("D", 1), false);
        assertTrue("保留已经取消，原来的到点不收", r.shown);
    }

    @Test
    public void switchingSidesMovesOver() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        SideViewTrigger.Result r = t.update(500, in("D", 2), false);
        assertTrue(r.shown);
        assertEquals(LaneCycle.RIGHT, r.lane);
    }

    @Test
    public void leavingDriveDetachesThirtySecondsAfterLeaving() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 0), false);
        // 在 D 挡停了很久、读数一直没变：30 秒从离开 D 挡那一刻起算，不从上一次判定起算
        SideViewTrigger.Result r = t.update(100_000, in("N", 0), false);
        assertTrue("30 秒内还备着", r.attached);
        assertEquals(100_000 + SideViewTrigger.READY_GRACE_MS, r.wakeAt);
        r = t.update(100_000 + SideViewTrigger.READY_GRACE_MS - 1, in("P", 0), false);
        assertTrue(r.attached);
        r = t.update(100_000 + SideViewTrigger.READY_GRACE_MS, in("P", 0), false);
        assertFalse(r.attached);
        assertEquals(0, r.wakeAt);
    }

    @Test
    public void backToDriveWithinTheGraceKeepsItReady() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 0), false);
        t.update(10_000, in("N", 0), false);
        SideViewTrigger.Result r = t.update(20_000, in("D", 0), false);
        assertTrue(r.attached);
        assertEquals("回到 D 挡就不用等了", 0, r.wakeAt);
        r = t.update(20_000 + SideViewTrigger.READY_GRACE_MS + 1, in("D", 0), false);
        assertTrue(r.attached);
    }

    @Test
    public void factoryViewTakesEverythingDownAtOnce() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        SideViewTrigger.Result r = t.update(100, in("R", 1), false);
        assertFalse(r.attached);
        assertFalse(r.shown);
        SideViewDecision.Input stock = in("D", 1);
        stock.stockPopupShown = true;
        r = t.update(200, stock, false);
        assertFalse("原厂画面在：不备着、不等 30 秒", r.attached);
        r = t.update(300, in("D", 0), false);
        assertTrue("原厂画面走了、还在 D 挡：备着", r.attached);
    }

    @Test
    public void blockedTakesEverythingDown() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 2), false);
        SideViewTrigger.Result r = t.update(100, in("D", 2), true);
        assertFalse(r.attached);
        assertFalse(r.shown);
    }

    @Test
    public void closeButtonDismissesThisSignalOnly() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        t.dismiss();
        SideViewTrigger.Result r = t.update(100, in("D", 1), false);
        assertFalse(r.shown);
        assertTrue("D 挡：回到备着", r.attached);
        r = t.update(200, in("D", 1), false);
        assertFalse("同一次打灯不再显示", r.shown);
        t.update(300, in("D", 0), false);
        r = t.update(400, in("D", 1), false);
        assertTrue("新的一次打灯照常显示", r.shown);
    }

    @Test
    public void belowTheSpeedThresholdOutsideDriveShowsNothing() {
        SideViewDecision.Input parked = in("P", 1);
        parked.speedKmh = 0f;
        SideViewTrigger.Result r = new SideViewTrigger().update(0, parked, false);
        assertFalse(r.attached);
        assertFalse(r.shown);
    }

    @Test
    public void withoutThresholdASignalInParkShowsAndThenDetaches() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input parked = in("P", 2);
        parked.speedKmh = 0f;
        parked.minSpeedKmh = 0;
        SideViewTrigger.Result r = t.update(0, parked, false);
        assertTrue(r.shown);
        assertTrue("显示着的一定挂着", r.attached);
        SideViewDecision.Input off = in("P", 0);
        off.minSpeedKmh = 0;
        t.update(1_000, off, false);
        r = t.update(1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS, off, false);
        assertFalse(r.shown);
        assertFalse("不在 D 挡：收起后就移走", r.attached);
    }

    @Test
    public void resetForgetsTheDismissal() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        t.dismiss();
        t.reset();
        SideViewTrigger.Result r = t.update(100, in("D", 1), false);
        assertTrue(r.shown);
    }
}
