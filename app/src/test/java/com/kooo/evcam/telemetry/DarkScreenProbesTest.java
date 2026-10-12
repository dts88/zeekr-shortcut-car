package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 找黑屏信号只加记录（2026-10-12）：要记的那几项、黑匣子里写成什么样。
 * 它们不能动信号表（「系统信息」页、信息条都从那张表来），名字和号要一一对上。
 */
public class DarkScreenProbesTest {

    @Test
    public void probesAreNotInTheSignalTable() {
        assertEquals(DarkScreenProbes.IDS.length, DarkScreenProbes.NAMES.length);
        Set<Integer> table = new HashSet<>();
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.FUNCTION || s.kind == Signal.Kind.FUNCTION_ZONE) {
                table.add(s.id);
            }
        }
        Set<Integer> seen = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < DarkScreenProbes.IDS.length; i++) {
            int id = DarkScreenProbes.IDS[i];
            assertFalse("信号表里已经有的号走信号表那条路：0x" + Integer.toHexString(id), table.contains(id));
            assertTrue("号不重复", seen.add(id));
            assertTrue("名字不重复", names.add(DarkScreenProbes.NAMES[i]));
            assertEquals(i, DarkScreenProbes.indexOf(id));
        }
        for (Signal s : DarkScreenProbes.WATCHED) {
            assertTrue("和信号表那几项的名字不撞", names.add(DarkScreenProbes.key(s)));
            assertFalse("只放离散的", s.isFloat());
        }
        assertEquals(-1, DarkScreenProbes.indexOf(0x20259000));
        assertEquals(0, DarkScreenProbes.indexOf(0x20290300));
    }

    @Test
    public void watchesOnlyTheChosenSignals() {
        assertTrue(DarkScreenProbes.watches(Signal.IGNITION));
        assertTrue(DarkScreenProbes.watches(Signal.GEAR));
        assertTrue(DarkScreenProbes.watches(Signal.SENTRY_MODE));
        assertTrue(DarkScreenProbes.watches(Signal.DOOR_DRIVER));
        assertTrue(DarkScreenProbes.watches(Signal.SEAT_DRIVER));
        assertTrue(DarkScreenProbes.watches(Signal.BELT_DRIVER));
        assertFalse(DarkScreenProbes.watches(Signal.SPEED));
        assertFalse(DarkScreenProbes.watches(Signal.DOOR_PASSENGER));
        assertEquals("door_driver", DarkScreenProbes.key(Signal.DOOR_DRIVER));
    }

    /** 原值照记，占位值 255 也记（它变成别的数正是要看的）；ECARX 枚举那种大数写十六进制。 */
    @Test
    public void rawValues() {
        assertEquals("0", DarkScreenProbes.raw(0));
        assertEquals("1", DarkScreenProbes.raw(1));
        assertEquals("255", DarkScreenProbes.raw(255));
        assertEquals("-1", DarkScreenProbes.raw(-1));
        assertEquals("-65535", DarkScreenProbes.raw(-65535));
        assertEquals("0x2032A801", DarkScreenProbes.raw(0x2032A801));
        assertEquals("0x00200105", DarkScreenProbes.raw(0x00200105));
        assertEquals("?", DarkScreenProbes.raw(null));
        assertEquals("?", DarkScreenProbes.raw("x"));
    }

    /** 仿 ECARX 的 FunctionStatus（可用状态回调里带的那个枚举）。 */
    private enum FunctionStatus { active, notactive }

    /** 区域不是 0 的单独一项（不和区域 0 混成来回跳）；可用状态又是一项，名字都不撞。 */
    @Test
    public void zonedAndStatusNames() {
        int power = DarkScreenProbes.indexOf(0x21100100);
        assertEquals("screen_power_event", DarkScreenProbes.name(0, 0));
        assertEquals("screen_power_event@0x1", DarkScreenProbes.name(0, 1));
        assertEquals("bcm_power_onoff@0x20000000", DarkScreenProbes.name(power, 0x20000000));
        assertEquals("bcm_power_onoff.status", DarkScreenProbes.name(power, 0) + DarkScreenProbes.STATUS);
        assertEquals("notactive", DarkScreenProbes.status(FunctionStatus.notactive));
        assertEquals("active", DarkScreenProbes.status(FunctionStatus.active));
        assertEquals("?", DarkScreenProbes.status(null));
        for (int i = 0; i < DarkScreenProbes.NAMES.length; i++) {
            assertEquals("对账读的不带区域：和区域 0 的回调是同一项", DarkScreenProbes.NAMES[i], DarkScreenProbes.name(i, 0));
        }
    }

    /** 信号表那几项用它们自己的解法，写成短记号。 */
    @Test
    public void decodedValues() {
        assertEquals("P", DarkScreenProbes.text(Signal.GEAR, Signal.GEAR.decode(0x00200230)));
        assertEquals("D", DarkScreenProbes.text(Signal.GEAR, Signal.GEAR.decode(0x00200220)));
        assertEquals("ACC", DarkScreenProbes.text(Signal.IGNITION, Signal.IGNITION.decode(Signal.IGNITION_ACC)));
        assertEquals("ON", DarkScreenProbes.text(Signal.IGNITION, Signal.IGNITION.decode(Signal.IGNITION_ON)));
        assertEquals("DRIVING", DarkScreenProbes.text(Signal.IGNITION, Signal.IGNITION.decode(Signal.IGNITION_DRIVING)));
        assertEquals("0x00200101", DarkScreenProbes.text(Signal.IGNITION, Signal.IGNITION.decode(0x00200101)));
        assertEquals("off", DarkScreenProbes.text(Signal.SENTRY_MODE, Signal.SENTRY_MODE.decode(0)));
        assertEquals("on", DarkScreenProbes.text(Signal.SENTRY_MODE, Signal.SENTRY_MODE.decode(1)));
        assertEquals("armed", DarkScreenProbes.text(Signal.SENTRY_MODE, Signal.SENTRY_MODE.decode(2)));
        assertEquals("open", DarkScreenProbes.text(Signal.DOOR_DRIVER, Signal.DOOR_DRIVER.decode(1)));
        assertEquals("closed", DarkScreenProbes.text(Signal.DOOR_DRIVER, Signal.DOOR_DRIVER.decode(0)));
        assertEquals("occupied", DarkScreenProbes.text(Signal.SEAT_DRIVER, Signal.SEAT_DRIVER.decode(0x00203302)));
        assertEquals("empty", DarkScreenProbes.text(Signal.SEAT_DRIVER, Signal.SEAT_DRIVER.decode(0x00203301)));
        assertEquals("buckled", DarkScreenProbes.text(Signal.BELT_DRIVER, Signal.BELT_DRIVER.decode(1)));
        assertEquals("unbuckled", DarkScreenProbes.text(Signal.BELT_DRIVER, Signal.BELT_DRIVER.decode(0)));
        assertEquals("没数据（占位值）", "?", DarkScreenProbes.text(Signal.GEAR, Signal.GEAR.decode(255)));
        assertEquals("?", DarkScreenProbes.text(Signal.DOOR_DRIVER, null));
    }
}
