/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/**
 * 车辆读数的两种判法。亮着时（人在车上）屏幕黑没黑（项目所有者 2026-10-12 D1）：屏幕电源事件和点火两项「且」起来；
 * 一项读不到看另一项；都读不到是 null。点火「ACC 或更低」是白名单 [0x00200100, ACC]，没见过的码不拿来判。
 * 黑着时算不算人回来了（项目所有者 2026-11-21）：车通电了、而且刚拉开过车门或刚碰过应用；车辆读不到只认碰过应用；车没通电不算。
 */
public class VehicleScreenTest {

    private static final Integer ACC = Signal.IGNITION_ACC;
    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer DRIVING = Signal.IGNITION_DRIVING;
    /** 车机下电前点火 ACC → 0x00200102（名字不知道，多半是 OFF），夜里车机自己醒的那一次也是它。 */
    private static final Integer AFTER_ACC = 0x00200102;

    @Test
    public void bothReadableMeansEventAndIgnitionLow() {
        assertEquals("黑：事件 1、点火 ACC（车门关上那一下，同一毫秒）", Boolean.TRUE, VehicleScreen.whileLit(true, ACC));
        assertEquals("黑：事件 1、点火 0x00200102（下电前、夜里醒着）", Boolean.TRUE, VehicleScreen.whileLit(true, AFTER_ACC));
        assertEquals("亮：开车门点火先回 ON，事件还是 1", Boolean.FALSE, VehicleScreen.whileLit(true, ON));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(true, DRIVING));
        assertEquals("亮：事件 0", Boolean.FALSE, VehicleScreen.whileLit(false, ACC));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(false, ON));
    }

    @Test
    public void oneReadableDecidesAlone() {
        assertEquals("点火读不到：看事件", Boolean.TRUE, VehicleScreen.whileLit(true, null));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(false, null));
        assertEquals("事件读不到（255 解码成 null）：看点火", Boolean.TRUE, VehicleScreen.whileLit(null, ACC));
        assertEquals(Boolean.TRUE, VehicleScreen.whileLit(null, AFTER_ACC));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(null, ON));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(null, DRIVING));
    }

    @Test
    public void unknownIgnitionCodesAreUnreadable() {
        assertNull("都读不到：说不上来", VehicleScreen.whileLit(null, null));
        assertNull("没见过的码不判黑（比如启动中）", VehicleScreen.whileLit(null, 0x00200106));
        assertNull(VehicleScreen.whileLit(null, 0));
        assertNull("白名单下限以下", VehicleScreen.whileLit(null, 0x002000FF));
        assertEquals("下限本身算低", Boolean.TRUE, VehicleScreen.whileLit(null, 0x00200100));
        assertEquals("没见过的码时只看事件", Boolean.TRUE, VehicleScreen.whileLit(true, 0x00200106));
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(false, 0x00200106));
    }

    @Test
    public void ignitionLowWhitelist() {
        assertEquals(Boolean.TRUE, VehicleScreen.ignitionLow(ACC));
        assertEquals(Boolean.TRUE, VehicleScreen.ignitionLow(AFTER_ACC));
        assertEquals(Boolean.FALSE, VehicleScreen.ignitionLow(ON));
        assertEquals(Boolean.FALSE, VehicleScreen.ignitionLow(DRIVING));
        assertNull(VehicleScreen.ignitionLow(null));
        assertNull(VehicleScreen.ignitionLow(0x00200106));
    }

    /**
     * 黑着时算不算人回来了（2026-11-21）：车通电了（ON、DRIVING），而且刚拉开过车门或刚碰过应用；车自己通电不算。
     * 点火读不到（没见过的码也是）只认碰过应用，车门不算；车没通电什么都不算（远程开空调、人在车里没通电时点亮屏幕）。
     */
    @Test
    public void becomingLitNeedsAPersonAndThePower() {
        assertTrue("拉开车门、车通电", VehicleScreen.becomesLit(false, ON, true, false, false));
        assertTrue("碰过应用、车通电", VehicleScreen.becomesLit(false, DRIVING, false, true, false));
        assertTrue(VehicleScreen.becomesLit(null, ON, true, true, false));
        assertTrue("开车门点火先回 ON，事件还是 1", VehicleScreen.becomesLit(true, ON, true, false, false));
        assertFalse("车自己通电", VehicleScreen.becomesLit(false, ON, false, false, false));
        assertFalse(VehicleScreen.becomesLit(false, DRIVING, false, false, false));
        assertFalse("车没通电：拉开车门也不算", VehicleScreen.becomesLit(false, ACC, true, false, false));
        assertFalse("车没通电：碰过应用也不算", VehicleScreen.becomesLit(false, ACC, false, true, false));
        assertFalse(VehicleScreen.becomesLit(true, AFTER_ACC, true, true, false));
        assertFalse("下限本身算低", VehicleScreen.becomesLit(false, 0x00200100, true, true, false));
        assertTrue("点火读不到：只认碰过应用", VehicleScreen.becomesLit(null, null, false, true, false));
        assertTrue(VehicleScreen.becomesLit(false, null, false, true, false));
        assertFalse("点火读不到：车门不算", VehicleScreen.becomesLit(null, null, true, false, false));
        assertFalse(VehicleScreen.becomesLit(null, null, false, false, false));
        assertTrue("没见过的码当读不到", VehicleScreen.becomesLit(false, 0x00200106, false, true, false));
        assertFalse(VehicleScreen.becomesLit(false, 0x00200106, true, false, false));
    }

    /**
     * 驾驶座此刻有人也算（项目所有者 2026-11-21）：车通电了就亮 —— 行驶中进程重启、人坐在车里不开车门就启动，不用再碰一下应用。
     * 车没通电不算；点火读不到也不算（那时只认碰过应用）。
     */
    @Test
    public void someoneInTheDriversSeatCountsWhenTheCarIsPowered() {
        assertTrue(VehicleScreen.becomesLit(false, ON, false, false, true));
        assertTrue(VehicleScreen.becomesLit(true, DRIVING, false, false, true));
        assertFalse("车没通电：坐着也不算", VehicleScreen.becomesLit(false, ACC, false, false, true));
        assertFalse(VehicleScreen.becomesLit(true, AFTER_ACC, false, false, true));
        assertFalse("点火读不到：不算", VehicleScreen.becomesLit(null, null, false, false, true));
        assertFalse(VehicleScreen.becomesLit(false, 0x00200106, false, false, true));
    }

    /** 点火读不到、屏幕电源事件说熄着：碰了应用也不算 —— 照它亮了，下一眼（whileLit）就又黑。 */
    @Test
    public void aTouchDoesNotCountWhileTheCarSaysTheScreenIsOff() {
        assertFalse(VehicleScreen.becomesLit(true, null, false, true, false));
        assertFalse("没见过的码当读不到", VehicleScreen.becomesLit(true, 0x00200106, true, true, false));
        assertEquals("亮了的话下一眼是黑", Boolean.TRUE, VehicleScreen.whileLit(true, null));
    }

    /** 人在车里、一直没黑过：点火掉到 ACC、事件还是 0，照两项「且」还是亮；事件变 1 才黑。 */
    @Test
    public void aLitStateAtAccStaysLit() {
        assertEquals(Boolean.FALSE, VehicleScreen.whileLit(false, ACC));
        assertEquals(Boolean.TRUE, VehicleScreen.whileLit(true, ACC));
    }

    @Test
    public void unpoweredLit() {
        assertTrue(VehicleScreen.unpoweredLit(false, ACC));
        assertTrue(VehicleScreen.unpoweredLit(false, AFTER_ACC));
        assertFalse("事件是 1", VehicleScreen.unpoweredLit(true, ACC));
        assertFalse("车通电了", VehicleScreen.unpoweredLit(false, ON));
        assertFalse("点火读不到", VehicleScreen.unpoweredLit(false, null));
        assertFalse("事件读不到", VehicleScreen.unpoweredLit(null, ACC));
    }

    /** 黑匣子里的写法和车辆信号那几行一样：0 / 1、ACC / ON / DRIVING、别的照原值写十六进制，读不到「?」。 */
    @Test
    public void blackBoxTexts() {
        assertEquals("1", VehicleScreen.eventText(true));
        assertEquals("0", VehicleScreen.eventText(false));
        assertEquals("?", VehicleScreen.eventText(null));
        assertEquals("ACC", VehicleScreen.ignitionText(ACC));
        assertEquals("ON", VehicleScreen.ignitionText(ON));
        assertEquals("DRIVING", VehicleScreen.ignitionText(DRIVING));
        assertEquals("0x00200102 不起名字，照原值写", "0x00200102", VehicleScreen.ignitionText(AFTER_ACC));
        assertEquals("?", VehicleScreen.ignitionText(null));
        assertEquals(" 0 → 1", VehicleScreen.change("0", "1"));
        assertEquals("=1", VehicleScreen.change("1", "1"));
    }
}
