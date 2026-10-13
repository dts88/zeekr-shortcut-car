package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/**
 * 车辆说屏幕黑没黑（设计 v3 §1.2，项目所有者 2026-10-12 D1）：屏幕电源事件和点火两项「且」起来；一项读不到看另一项；
 * 都读不到只看安卓（null）。点火「ACC 或更低」是白名单 [0x00200100, ACC]，没见过的码不拿来判黑。
 */
public class VehicleScreenTest {

    private static final Integer ACC = Signal.IGNITION_ACC;
    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer DRIVING = Signal.IGNITION_DRIVING;
    /** 车机下电前点火 ACC → 0x00200102（名字不知道，多半是 OFF），夜里车机自己醒的那一次也是它。 */
    private static final Integer AFTER_ACC = 0x00200102;

    @Test
    public void bothReadableMeansEventAndIgnitionLow() {
        assertEquals("黑：事件 1、点火 ACC（车门关上那一下，同一毫秒）", Boolean.TRUE, VehicleScreen.verdict(true, ACC));
        assertEquals("黑：事件 1、点火 0x00200102（下电前、夜里醒着）", Boolean.TRUE, VehicleScreen.verdict(true, AFTER_ACC));
        assertEquals("亮：开车门点火先回 ON，事件还是 1", Boolean.FALSE, VehicleScreen.verdict(true, ON));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(true, DRIVING));
        assertEquals("亮：事件 0", Boolean.FALSE, VehicleScreen.verdict(false, ACC));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, ON));
    }

    @Test
    public void oneReadableDecidesAlone() {
        assertEquals("点火读不到：看事件", Boolean.TRUE, VehicleScreen.verdict(true, null));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, null));
        assertEquals("事件读不到（255 解码成 null）：看点火", Boolean.TRUE, VehicleScreen.verdict(null, ACC));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(null, AFTER_ACC));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(null, ON));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(null, DRIVING));
    }

    @Test
    public void unknownIgnitionCodesAreUnreadable() {
        assertNull("都读不到：只看安卓", VehicleScreen.verdict(null, null));
        assertNull("没见过的码不判黑（比如启动中）", VehicleScreen.verdict(null, 0x00200106));
        assertNull(VehicleScreen.verdict(null, 0));
        assertNull("白名单下限以下", VehicleScreen.verdict(null, 0x002000FF));
        assertEquals("下限本身算低", Boolean.TRUE, VehicleScreen.verdict(null, 0x00200100));
        assertEquals("没见过的码时只看事件", Boolean.TRUE, VehicleScreen.verdict(true, 0x00200106));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, 0x00200106));
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
