package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 读数怎么归一。占位值的规则是车上遍历看出来的（zeekr-shortcut-lab findings §2.1），
 * 认错一个，页面上就会把「没数据」写成「关」。
 */
public class SignalDecodeTest {

    @Test
    public void integerPlaceholdersAreUnknown() {
        assertNull(Signal.intOrNull(255));
        assertNull(Signal.intOrNull(254));
        assertNull(Signal.intOrNull(253));
        assertNull(Signal.intOrNull(-1));
        assertNull(Signal.intOrNull(-65535));
        assertEquals(Integer.valueOf(0), Signal.intOrNull(0));
        assertEquals(Integer.valueOf(1), Signal.intOrNull(1));
        assertEquals(Integer.valueOf(0x00200230), Signal.intOrNull(0x00200230));
    }

    /** 没数据的浮点不是 0.0，是一个极小的非零数；真正的 0（停着的车速）要留下。 */
    @Test
    public void floatPlaceholdersAreUnknownButZeroIsZero() {
        assertNull(Signal.floatOrNull(255f));
        assertNull(Signal.floatOrNull(-65535f));
        assertNull(Signal.floatOrNull(1e-9f));
        assertNull(Signal.floatOrNull(Float.NaN));
        assertEquals(Float.valueOf(0f), Signal.floatOrNull(0f));
        assertEquals(Float.valueOf(11053f), Signal.floatOrNull(11053f));
        assertEquals(Float.valueOf(-0.011f), Signal.floatOrNull(-0.011f));
    }

    @Test
    public void onOffOnlyAcceptsZeroAndOne() {
        assertEquals(Boolean.FALSE, Signal.LOW_BEAM.decode(0));
        assertEquals(Boolean.TRUE, Signal.LOW_BEAM.decode(1));
        assertNull(Signal.LOW_BEAM.decode(2));
        assertNull(Signal.LOW_BEAM.decode(255));
        assertNull(Signal.LOW_BEAM.decode(null));
    }

    /** 车门开关过程中读到 0x21020101（DOOR_PAUSE）：算没数据，不算开也不算关。 */
    @Test
    public void doorTransientIsUnknown() {
        assertEquals(Boolean.TRUE, Signal.DOOR_DRIVER.decode(1));
        assertEquals(Boolean.FALSE, Signal.DOOR_DRIVER.decode(0));
        assertNull(Signal.DOOR_DRIVER.decode(0x21020101));
    }

    @Test
    public void seatUsesTheLowByte() {
        assertEquals(Boolean.FALSE, Signal.SEAT_DRIVER.decode(0x00203301));
        assertEquals(Boolean.TRUE, Signal.SEAT_DRIVER.decode(0x00203302));
        assertNull(Signal.SEAT_DRIVER.decode(0x00203300));
    }

    @Test
    public void stock360ShownIsOneHiddenIsTwo() {
        assertEquals(Boolean.TRUE, Signal.STOCK_360.decode(1));
        assertEquals(Boolean.FALSE, Signal.STOCK_360.decode(2));
        // 0 是侧方小窗（推测），不是 360 画面
        assertEquals(Boolean.FALSE, Signal.STOCK_360.decode(0));
        assertNull(Signal.STOCK_360.decode(3));
    }

    @Test
    public void mirrorDipAndDayNightKeepTheirCodes() {
        assertEquals(Integer.valueOf(Signal.MIRROR_TILTING), Signal.MIRROR_DIP_DRIVER.decode(4));
        assertEquals(Integer.valueOf(Signal.MIRROR_NORMAL), Signal.MIRROR_DIP_PASSENGER.decode(1));
        assertNull(Signal.MIRROR_DIP_DRIVER.decode(5));
        assertEquals(Integer.valueOf(Signal.NIGHT), Signal.DAY_NIGHT.decode(0x00201002));
        assertNull(Signal.DAY_NIGHT.decode(0x00201003));
        assertEquals(Boolean.TRUE, Signal.STOCK_POPUP.decode(1));
        assertEquals(Boolean.FALSE, Signal.STOCK_POPUP.decode(0));
    }

    /**
     * 屏幕电源事件：0 亮着、1 黑了（车主 2026-10-12 确认），别的（255 读不到、没见过的数）都是不知道 ——
     * 熄屏判断把不知道当「这一项读不到」，不能当成亮着。先用着的信号，非开发者也拿得到。
     */
    @Test
    public void screenPowerEventIsZeroLitOneDark() {
        Signal s = Signal.SCREEN_POWER_EVENT;
        assertEquals(Boolean.FALSE, s.decode(0));
        assertEquals(Boolean.TRUE, s.decode(1));
        assertNull(s.decode(255));
        assertNull(s.decode(2));
        assertNull(s.decode(null));
        assertEquals(Signal.Format.SCREEN, s.format);
        assertEquals(Signal.Kind.FUNCTION, s.kind);
        assertEquals(0x20290300, s.id);
        assertTrue(s.usable());
        assertFalse("不按节拍读：熄屏判断那一层（只读离散信号）也要它", s.isFloat());
    }

    @Test
    public void indicatorIsZeroToThree() {
        assertEquals(Integer.valueOf(0), Signal.INDICATOR.decode(0));
        assertEquals(Integer.valueOf(1), Signal.INDICATOR.decode(1));
        assertEquals(Integer.valueOf(2), Signal.INDICATOR.decode(2));
        assertEquals(Integer.valueOf(3), Signal.INDICATOR.decode(3));
        assertNull(Signal.INDICATOR.decode(4));
    }

    @Test
    public void ignitionKeepsTheCode() {
        assertEquals(Integer.valueOf(Signal.IGNITION_DRIVING), Signal.IGNITION.decode(0x00200107));
        assertEquals(Integer.valueOf(Signal.IGNITION_ACC), Signal.IGNITION.decode(0x00200104));
    }

    @Test
    public void gearLettersComeFromSensorEvents() {
        assertEquals("P", Signal.GEAR.decode(0x00200230));
        assertEquals("R", Signal.GEAR.decode(0x00200240));
        assertEquals("N", Signal.GEAR.decode(0x00200210));
        assertEquals("D", Signal.GEAR.decode(0x00200220));
        assertNull(Signal.GEAR.decode(0x00200250));
    }

    @Test
    public void floatFormatsPassNumbersThrough() {
        assertEquals(Float.valueOf(11053f), Signal.ODOMETER.decode(11053f));
        assertEquals(Float.valueOf(0f), Signal.SPEED.decode(0f));
        assertNull(Signal.SPEED.decode(255f));
        // 方向盘读数是弧度、左正右负；我们这边正 = 向右：左打满 8.789 ≈ -504°，右打满 -8.746 ≈ +501°
        assertEquals(-503.6f, (Float) Signal.STEERING.decode(8.789f), 0.1f);
        assertEquals(501.1f, (Float) Signal.STEERING.decode(-8.746f), 0.1f);
        // 刹车踩到底约 44 = 100 % 行程，开车时的 22 是一半
        assertEquals(100f, (Float) Signal.BRAKE_DEPTH.decode(44f), 0.01f);
        assertEquals(50f, (Float) Signal.BRAKE_DEPTH.decode(22f), 0.01f);
        assertEquals(Integer.valueOf(2), Signal.SENTRY_MODE.decode(2));
        assertNull(Signal.SENTRY_MODE.decode(3));
    }

    /** 车速传感器给的是 m/s（Lab 0.10.0：15.833 = 57 km/h），表里直接换成 km/h。 */
    @Test
    public void speedIsMetresPerSecondTurnedIntoKmh() {
        assertEquals(57f, (Float) Signal.SPEED.decode(15.833f), 0.01f);
        assertEquals(1f, (Float) Signal.SPEED.decode(0.2778f), 0.001f);
        assertEquals(Float.valueOf(0f), Signal.SPEED.decode(0f));
    }

    /** 前碰预警读的是灵敏度：0 关，低 / 中 / 高（0x200E0201–03）都算开。 */
    @Test
    public void aLevelIsOnUnlessZero() {
        assertEquals(Boolean.FALSE, Signal.FCW.decode(0));
        assertEquals(Boolean.TRUE, Signal.FCW.decode(0x200E0202));
        assertNull(Signal.FCW.decode(255));
    }

    /** 驾驶模式按值用（Lab 2026-10-20）：三个对上的、没对上的都照原样留着号码，占位值算没数据。 */
    @Test
    public void driveModeKeepsTheCode() {
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_COMFORT), Signal.DRIVE_MODE.decode(0x22010101));
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_STANDARD), Signal.DRIVE_MODE.decode(0x22010102));
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_SPORT), Signal.DRIVE_MODE.decode(0x22010103));
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_SNOW), Signal.DRIVE_MODE.decode(0x22010109));
        assertEquals(Integer.valueOf(0x22010140), Signal.DRIVE_MODE.decode(0x22010140));
        assertNull(Signal.DRIVE_MODE.decode(255));
    }

    @Test
    public void lightSwitchKeepsTheCode() {
        assertEquals(Integer.valueOf(0), Signal.LIGHT_SWITCH.decode(0));
        assertEquals(Integer.valueOf(Signal.LIGHT_SWITCH_AUTO), Signal.LIGHT_SWITCH.decode(0x20040E03));
    }

    /** 同一个读法 + 号码 + 区域只能对应一条信号，否则回调分不清给谁。 */
    @Test
    public void everyAddressIsUnique() {
        Set<String> seen = new HashSet<>();
        for (Signal s : Signal.values()) {
            assertTrue(s.name(), seen.add(s.kind + "/" + s.id + "/" + s.zone));
        }
    }
}
