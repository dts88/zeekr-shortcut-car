package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/**
 * 车辆说屏幕黑没黑（设计 v3 §1.2，项目所有者 2026-10-12 D1）：屏幕电源事件和点火两项「且」起来；一项读不到看另一项；
 * 都读不到只看安卓（null）。点火「ACC 或更低」是白名单 [0x00200100, ACC]，没见过的码不拿来判黑。
 * 黑着、或者还没有车辆的判断时亮要有证据（项目所有者 2026-10-27）：车没通电就是黑。
 */
public class VehicleScreenTest {

    /** 车辆判过亮的那一段（人在车里，一直没黑过）：照两项的规则。 */
    private static final boolean LIT_STATE = false;
    /** 黑着、或者还没有车辆的判断：亮要有证据。 */
    private static final boolean NEEDS_EVIDENCE = true;

    private static final Integer ACC = Signal.IGNITION_ACC;
    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer DRIVING = Signal.IGNITION_DRIVING;
    /** 车机下电前点火 ACC → 0x00200102（名字不知道，多半是 OFF），夜里车机自己醒的那一次也是它。 */
    private static final Integer AFTER_ACC = 0x00200102;

    @Test
    public void bothReadableMeansEventAndIgnitionLow() {
        assertEquals("黑：事件 1、点火 ACC（车门关上那一下，同一毫秒）", Boolean.TRUE, VehicleScreen.verdict(true, ACC, LIT_STATE));
        assertEquals("黑：事件 1、点火 0x00200102（下电前、夜里醒着）", Boolean.TRUE, VehicleScreen.verdict(true, AFTER_ACC, LIT_STATE));
        assertEquals("亮：开车门点火先回 ON，事件还是 1", Boolean.FALSE, VehicleScreen.verdict(true, ON, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(true, DRIVING, LIT_STATE));
        assertEquals("亮：事件 0", Boolean.FALSE, VehicleScreen.verdict(false, ACC, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, ON, LIT_STATE));
    }

    @Test
    public void oneReadableDecidesAlone() {
        assertEquals("点火读不到：看事件", Boolean.TRUE, VehicleScreen.verdict(true, null, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, null, LIT_STATE));
        assertEquals("事件读不到（255 解码成 null）：看点火", Boolean.TRUE, VehicleScreen.verdict(null, ACC, LIT_STATE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(null, AFTER_ACC, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(null, ON, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(null, DRIVING, LIT_STATE));
    }

    @Test
    public void unknownIgnitionCodesAreUnreadable() {
        assertNull("都读不到：只看安卓", VehicleScreen.verdict(null, null, LIT_STATE));
        assertNull("没见过的码不判黑（比如启动中）", VehicleScreen.verdict(null, 0x00200106, LIT_STATE));
        assertNull(VehicleScreen.verdict(null, 0, LIT_STATE));
        assertNull("白名单下限以下", VehicleScreen.verdict(null, 0x002000FF, LIT_STATE));
        assertEquals("下限本身算低", Boolean.TRUE, VehicleScreen.verdict(null, 0x00200100, LIT_STATE));
        assertEquals("没见过的码时只看事件", Boolean.TRUE, VehicleScreen.verdict(true, 0x00200106, LIT_STATE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, 0x00200106, LIT_STATE));
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
     * 亮要有证据（2026-10-27）：黑着时车没通电（点火 ACC 或更低）就是黑，屏幕电源事件说亮也不算（远程开空调、远程看车）；
     * 点火不知道、事件说亮：说不上来（照上一次算一会儿）；车通电了（ON、DRIVING）照旧是亮；别的组合和两项的规则一样。
     */
    @Test
    public void litNeedsEvidenceWhileDark() {
        assertEquals("屏幕亮了、车没通电：还是黑", Boolean.TRUE, VehicleScreen.verdict(false, ACC, NEEDS_EVIDENCE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(false, AFTER_ACC, NEEDS_EVIDENCE));
        assertEquals("下限本身算低", Boolean.TRUE, VehicleScreen.verdict(false, 0x00200100, NEEDS_EVIDENCE));
        assertNull("屏幕亮了、点火读不到：说不上来", VehicleScreen.verdict(false, null, NEEDS_EVIDENCE));
        assertNull("没见过的码也是不知道", VehicleScreen.verdict(false, 0x00200106, NEEDS_EVIDENCE));
        assertEquals("车通电了：亮", Boolean.FALSE, VehicleScreen.verdict(false, ON, NEEDS_EVIDENCE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, DRIVING, NEEDS_EVIDENCE));
        assertEquals("开车门点火先回 ON，事件还是 1：亮", Boolean.FALSE, VehicleScreen.verdict(true, ON, NEEDS_EVIDENCE));
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(null, ON, NEEDS_EVIDENCE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(true, ACC, NEEDS_EVIDENCE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(true, null, NEEDS_EVIDENCE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(null, ACC, NEEDS_EVIDENCE));
        assertEquals("没见过的码时事件说黑：黑", Boolean.TRUE, VehicleScreen.verdict(true, 0x00200106, NEEDS_EVIDENCE));
        assertNull("都读不到：只看安卓", VehicleScreen.verdict(null, null, NEEDS_EVIDENCE));
    }

    /** 人在车里、一直没黑过：点火掉到 ACC、事件还是 0，照两项「且」还是亮；事件变 1 才黑。 */
    @Test
    public void aLitStateAtAccStaysLit() {
        assertEquals(Boolean.FALSE, VehicleScreen.verdict(false, ACC, LIT_STATE));
        assertEquals(Boolean.TRUE, VehicleScreen.verdict(true, ACC, LIT_STATE));
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
