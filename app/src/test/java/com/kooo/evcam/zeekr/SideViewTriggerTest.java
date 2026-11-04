package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SideViewTrigger}：D 挡备着、打灯显示、灯灭后留 1 秒（再淡 1 秒）、离开 D 挡 30 秒后摘掉、原厂画面和熄屏立刻全收；
 * 左右各管各的（2026-11-04），只能显示一侧时的先后。
 */
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
        assertTrue("黑匣子写「离开 D 挡 30 秒」", r.graceEnded);
        assertEquals(0, r.wakeAt);
        r = t.update(100_000 + SideViewTrigger.READY_GRACE_MS + 1, in("P", 0), false);
        assertFalse("只在到点那一次", r.graceEnded);
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
        t.dismiss(LaneCycle.LEFT);
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
        assertFalse("不是 30 秒到点，是显示结束", r.graceEnded);
    }

    @Test
    public void resetForgetsTheDismissal() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, in("D", 1), false);
        t.dismiss(LaneCycle.LEFT);
        t.reset();
        SideViewTrigger.Result r = t.update(100, in("D", 1), false);
        assertTrue(r.shown);
    }

    // ------------------------------------------------------------------ 后视镜上的灯（2026-11-03）

    @Test
    public void doorWarningInParkShowsThenDetachesAfterTheHold() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input lit = in("P", 0);
        lit.speedKmh = 0f;
        lit.useDoorWarning = true;
        lit.doorWarningLeft = true;
        SideViewTrigger.Result r = t.update(0, lit, false);
        assertTrue("停着车也挂、也显示", r.attached);
        assertTrue(r.shown);
        assertEquals(LaneCycle.LEFT, r.lane);
        assertEquals(SideViewTrigger.Cause.DOOR_WARNING, r.cause);

        SideViewDecision.Input out = in("P", 0);
        out.speedKmh = 0f;
        out.useDoorWarning = true;
        out.doorWarningLeft = false;
        r = t.update(4_000, out, false);
        assertTrue("灭了先留 1 秒", r.shown);
        assertEquals(null, r.cause);
        r = t.update(4_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS, out, false);
        assertFalse(r.shown);
        assertFalse("不在 D 挡：移走", r.attached);
    }

    @Test
    public void theCauseSaysWhichLightOrTheTurnSignal() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input in = in("D", 0);
        in.useBlindSpot = true;
        in.blindSpotRight = true;
        assertEquals(SideViewTrigger.Cause.BLIND_SPOT, t.update(0, in, false).cause);
        in.turnSignal = 2;
        assertEquals("打着转向灯以转向灯为准", SideViewTrigger.Cause.TURN, t.update(100, in, false).cause);
    }

    @Test
    public void closeButtonDismissesTheLitSideUntilItGoesOut() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input lit = in("D", 0);
        lit.useBlindSpot = true;
        lit.blindSpotRight = true;
        assertTrue(t.update(0, lit, false).shown);
        t.dismiss(LaneCycle.RIGHT);
        SideViewTrigger.Result r = t.update(100, lit, false);
        assertFalse("还亮着：这一次关掉了", r.shown);
        assertTrue("D 挡：回到备着", r.attached);

        SideViewDecision.Input out = in("D", 0);
        out.useBlindSpot = true;
        out.blindSpotRight = false;
        assertFalse(t.update(2_000, out, false).shown);
        assertTrue("灭了再亮是新的一次", t.update(3_000, lit, false).shown);
    }

    @Test
    public void dismissingALightDoesNotHideTheOtherSide() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input lit = in("D", 0);
        lit.useBlindSpot = true;
        lit.blindSpotLeft = true;
        assertEquals(LaneCycle.LEFT, t.update(0, lit, false).lane);
        t.dismiss(LaneCycle.LEFT);
        lit.blindSpotRight = true;
        SideViewTrigger.Result r = t.update(100, lit, false);
        assertTrue("右边亮了照样显示", r.shown);
        assertEquals(LaneCycle.RIGHT, r.lane);
    }

    // ------------------------------------------------------------------ 淡出（项目所有者 2026-11-04）

    /** 装饰动效放得了（服务照 MotionPolicy 填）。 */
    private static SideViewDecision.Input fading(String gear, Integer turn) {
        SideViewDecision.Input in = in(gear, turn);
        in.fade = true;
        return in;
    }

    /** 留够 1 秒之后再淡 1 秒：这一秒里照旧显示着、报还要淡多久，淡完才收。 */
    @Test
    public void afterTheHoldItFadesForOneSecondThenHides() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, fading("D", 2), false);
        SideViewTrigger.Result r = t.update(5_000, fading("D", 0), false);
        assertTrue(r.shown);
        assertEquals("留着的时候不淡", 0, r.fadeMs);

        long held = 5_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;
        r = t.update(held, fading("D", 0), false);
        assertTrue("淡出时照旧显示着", r.shown);
        assertEquals(SideViewTrigger.FADE_MS, r.fadeMs);
        assertEquals(held + SideViewTrigger.FADE_MS, r.wakeAt);

        r = t.update(held + 400, fading("D", 0), false);
        assertEquals("淡到一半：报还剩多少", SideViewTrigger.FADE_MS - 400, r.fadeMs);

        r = t.update(held + SideViewTrigger.FADE_MS, fading("D", 0), false);
        assertFalse(r.shown);
        assertEquals(0, r.fadeMs);
        assertTrue("D 挡：回到备着", r.attached);
    }

    /** 判定来晚了（留够之后 300 毫秒才来）：照留够那一刻起算，淡完的时刻不往后拖。 */
    @Test
    public void aLateWakeDoesNotStretchTheFade() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, fading("D", 1), false);
        t.update(1_000, fading("D", 0), false);
        long held = 1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;
        SideViewTrigger.Result r = t.update(held + 300, fading("D", 0), false);
        assertTrue(r.shown);
        assertEquals(SideViewTrigger.FADE_MS - 300, r.fadeMs);
        assertEquals(held + SideViewTrigger.FADE_MS, r.wakeAt);
    }

    /** 淡出中同一侧又打灯：回到正常（不再淡），原来淡完的那一刻不收。 */
    @Test
    public void signallingAgainDuringTheFadeShowsAgain() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, fading("D", 1), false);
        t.update(1_000, fading("D", 0), false);
        long held = 1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;
        t.update(held + 200, fading("D", 0), false);
        SideViewTrigger.Result r = t.update(held + 500, fading("D", 1), false);
        assertTrue(r.shown);
        assertEquals(0, r.fadeMs);
        assertEquals(0, r.wakeAt);
        r = t.update(held + SideViewTrigger.FADE_MS, fading("D", 1), false);
        assertTrue("原来淡完的那一刻不收", r.shown);
    }

    /** 淡出中另一侧打灯：换过去，不再淡。 */
    @Test
    public void theOtherSideDuringTheFadeSwitchesOver() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, fading("D", 1), false);
        t.update(1_000, fading("D", 0), false);
        long held = 1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;
        t.update(held + 100, fading("D", 0), false);
        SideViewTrigger.Result r = t.update(held + 300, fading("D", 2), false);
        assertTrue(r.shown);
        assertEquals(LaneCycle.RIGHT, r.lane);
        assertEquals(0, r.fadeMs);
    }

    /** 装饰动效不放时照以前：留够就收；点关闭、倒挡当场收，不淡。 */
    @Test
    public void noFadeWhenNotAllowedNorOnDismissOrFactoryView() {
        long held = 1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;

        SideViewTrigger plain = new SideViewTrigger();
        plain.update(0, in("D", 2), false);
        plain.update(1_000, in("D", 0), false);
        SideViewTrigger.Result r = plain.update(held, in("D", 0), false);
        assertFalse("不淡：留够就收", r.shown);
        assertEquals(0, r.fadeMs);

        SideViewTrigger closed = new SideViewTrigger();
        closed.update(0, fading("D", 2), false);
        closed.update(1_000, fading("D", 0), false);
        closed.update(held, fading("D", 0), false);
        closed.dismiss(LaneCycle.RIGHT);
        r = closed.update(held + 100, fading("D", 0), false);
        assertFalse("点了关闭：当场收", r.shown);
        assertEquals(0, r.fadeMs);

        SideViewTrigger reverse = new SideViewTrigger();
        reverse.update(0, fading("D", 2), false);
        reverse.update(1_000, fading("D", 0), false);
        reverse.update(held, fading("D", 0), false);
        r = reverse.update(held + 100, fading("R", 0), false);
        assertFalse("倒挡：当场全收", r.attached);
        assertEquals(0, r.fadeMs);
    }

    // ------------------------------------------------------------------ 左右两侧（项目所有者 2026-11-04）

    private static SideViewDecision.Input lights(String gear, Integer turn, boolean left, boolean right) {
        SideViewDecision.Input in = in(gear, turn);
        in.useBlindSpot = true;
        in.blindSpotLeft = left;
        in.blindSpotRight = right;
        return in;
    }

    /** 后视镜上的灯左右都亮：两侧都显示；只能显示一侧时先亮起来的那一侧。 */
    @Test
    public void bothLightsShowBothSides() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, lights("D", 0, false, true), false);
        SideViewTrigger.Result r = t.update(500, lights("D", 0, true, true), false);
        assertTrue(r.left.shown);
        assertTrue(r.right.shown);
        assertEquals(SideViewTrigger.Cause.BLIND_SPOT, r.left.cause);
        assertEquals("只能显示一侧时：先亮的右边", LaneCycle.RIGHT, r.lane);
    }

    /** 同时亮起来的：只能显示一侧时先左边。 */
    @Test
    public void lightsComingOnTogetherPutTheLeftFirst() {
        SideViewTrigger.Result r = new SideViewTrigger().update(0, lights("D", 0, true, true), false);
        assertTrue(r.left.shown);
        assertTrue(r.right.shown);
        assertEquals(LaneCycle.LEFT, r.lane);
    }

    /** 打着转向灯、另一侧的灯也亮：两侧都显示，只能显示一侧时转向灯那一侧优先（项目所有者 2026-11-04）。 */
    @Test
    public void aTurnSignalWithTheOtherSideLitShowsBothAndComesFirst() {
        SideViewTrigger t = new SideViewTrigger();
        t.update(0, lights("D", 0, true, false), false);
        SideViewTrigger.Result r = t.update(300, lights("D", 2, true, false), false);
        assertTrue("左边的灯照样显示", r.left.shown);
        assertTrue(r.right.shown);
        assertEquals(SideViewTrigger.Cause.TURN, r.right.cause);
        assertEquals("转向灯那一侧优先，虽然左边先亮", LaneCycle.RIGHT, r.lane);
        assertEquals(SideViewTrigger.Cause.TURN, r.cause);
    }

    /** 一侧灭了只收那一侧（留 1 秒、淡 1 秒），另一侧照旧；只能显示一侧时换到还亮着的那一侧。 */
    @Test
    public void eachSideHoldsAndFadesOnItsOwn() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input both = lights("D", 0, true, true);
        both.fade = true;
        t.update(0, both, false);
        SideViewDecision.Input rightOnly = lights("D", 0, false, true);
        rightOnly.fade = true;
        SideViewTrigger.Result r = t.update(1_000, rightOnly, false);
        assertTrue("左边灭了先留着", r.left.shown);
        assertEquals(null, r.left.cause);
        assertTrue(r.right.shown);
        assertEquals("只能显示一侧时换到还亮着的右边", LaneCycle.RIGHT, r.lane);
        assertEquals(1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS, r.wakeAt);

        long held = 1_000 + SideViewTrigger.HOLD_AFTER_SIGNAL_MS;
        r = t.update(held, rightOnly, false);
        assertEquals("左边在淡", SideViewTrigger.FADE_MS, r.left.fadeMs);
        assertEquals("右边不淡", 0, r.right.fadeMs);
        r = t.update(held + SideViewTrigger.FADE_MS, rightOnly, false);
        assertFalse(r.left.shown);
        assertTrue(r.right.shown);
    }

    /** 关掉一侧：只关那一侧，另一侧照旧显示；两侧都能各自关掉。 */
    @Test
    public void closingOneSideLeavesTheOther() {
        SideViewTrigger t = new SideViewTrigger();
        SideViewDecision.Input both = lights("D", 0, true, true);
        t.update(0, both, false);
        t.dismiss(LaneCycle.LEFT);
        SideViewTrigger.Result r = t.update(100, both, false);
        assertFalse("左边这一次关掉了", r.left.shown);
        assertTrue(r.right.shown);
        t.dismiss(LaneCycle.RIGHT);
        r = t.update(200, both, false);
        assertFalse(r.left.shown);
        assertFalse("右边也关掉了", r.right.shown);
        assertTrue("D 挡：回到备着", r.attached);
    }

    /** 一侧亮着、另一侧没有：D 挡照样备着，两侧各报各的（中控屏两块各管一侧时照它）。 */
    @Test
    public void eachSideReportsOnItsOwn() {
        SideViewTrigger.Result r = new SideViewTrigger().update(0, lights("D", 0, false, true), false);
        assertTrue("D 挡：备着", r.attached);
        assertFalse(r.side(LaneCycle.LEFT).shown);
        assertTrue(r.side(LaneCycle.RIGHT).shown);
        assertEquals(SideViewTrigger.Cause.BLIND_SPOT, r.side(LaneCycle.RIGHT).cause);
    }
}
