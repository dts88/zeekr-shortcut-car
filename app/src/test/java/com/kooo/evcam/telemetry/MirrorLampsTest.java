package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MirrorLamps}：值怎么算亮、一次换一盏、按值相等；信号表里那四行各对一盏。 */
public class MirrorLampsTest {

    @Test
    public void anythingButZeroIsOn() {
        assertEquals(Boolean.FALSE, MirrorLamps.on(0));
        assertEquals(Boolean.TRUE, MirrorLamps.on(1));
        assertEquals("开门预警见过 3：算亮", Boolean.TRUE, MirrorLamps.on(3));
        assertNull("读不到", MirrorLamps.on(null));
        assertNull("不是数", MirrorLamps.on("1"));
    }

    @Test
    public void withChangesOneLampAndKeepsTheSameObjectWhenNothingChanges() {
        MirrorLamps a = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, true);
        assertEquals(Boolean.TRUE, a.blindSpotRight);
        assertNull(a.blindSpotLeft);
        assertTrue(a.anyOn());
        assertSame(a, a.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, true));
        MirrorLamps b = a.with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, false);
        assertFalse(b.anyOn());
        assertEquals(Boolean.FALSE, b.get(MirrorLamps.Lamp.BLIND_SPOT_RIGHT));
    }

    @Test
    public void equalByValue() {
        MirrorLamps a = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.DOOR_WARNING_LEFT, true);
        MirrorLamps b = MirrorLamps.UNKNOWN.with(MirrorLamps.Lamp.DOOR_WARNING_LEFT, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(MirrorLamps.UNKNOWN));
        assertEquals("blindSpot L? R?, doorWarning L1 R?", a.toString());
    }

    /** 「车辆信号」页、信息条按信号表的那四行取：一行对一盏；别的信号不是灯。 */
    @Test
    public void theSignalTableRowsReadTheirLamp() {
        MirrorLamps lamps = MirrorLamps.UNKNOWN
                .with(MirrorLamps.Lamp.BLIND_SPOT_LEFT, true)
                .with(MirrorLamps.Lamp.BLIND_SPOT_RIGHT, false)
                .with(MirrorLamps.Lamp.DOOR_WARNING_RIGHT, true);
        assertEquals(Boolean.TRUE, lamps.get(Signal.BLIND_SPOT_LEFT));
        assertEquals(Boolean.FALSE, lamps.get(Signal.BLIND_SPOT_RIGHT));
        assertNull(lamps.get(Signal.DOOR_WARNING_LEFT));
        assertEquals(Boolean.TRUE, lamps.get(Signal.DOOR_WARNING_RIGHT));
        assertNull("转向灯不是后视镜上的灯", lamps.get(Signal.TURN_LEFT));
        assertEquals(3, lamps.knownCount());
        assertEquals(0, MirrorLamps.UNKNOWN.knownCount());
    }

    /**
     * 信号表里车机设置服务那几行（{@link Signal.Kind#SETTING}）就是读灯的来源读的四盏：常量名、区域和来源用的一样，
     * 每一行都对得上一盏灯；号不写死，车辆接口不读。别的行都是车辆接口读的，没有常量名。
     */
    @Test
    public void theSettingRowsAreTheFourLampsTheSourceReads() {
        assertEquals(MirrorLampSource.BLIND_SPOT, Signal.BLIND_SPOT_RIGHT.property);
        assertEquals(MirrorLampSource.AREA_LEFT, Signal.BLIND_SPOT_LEFT.zone);
        assertEquals(MirrorLampSource.AREA_RIGHT, Signal.BLIND_SPOT_RIGHT.zone);
        assertEquals(MirrorLampSource.DOOR_WARNING_LEFT, Signal.DOOR_WARNING_LEFT.property);
        assertEquals(MirrorLampSource.DOOR_WARNING_RIGHT, Signal.DOOR_WARNING_RIGHT.property);
        MirrorLamps all = MirrorLamps.UNKNOWN;
        for (MirrorLamps.Lamp lamp : MirrorLamps.Lamp.values()) {
            all = all.with(lamp, true);
        }
        int rows = 0;
        for (Signal s : Signal.values()) {
            boolean setting = s.kind == Signal.Kind.SETTING;
            assertEquals(s.name(), !setting, s.viaCarInterface());
            assertEquals(s.name(), setting, s.property != null);
            if (setting) {
                rows++;
                assertEquals(s.name(), 0, s.id);
                assertEquals(s.name(), Signal.Group.LAMPS, s.group);
                assertTrue("非开发者也勾得了：" + s.name(), s.usable());
                assertEquals("对得上一盏灯：" + s.name(), Boolean.TRUE, all.get(s));
            }
        }
        assertEquals(MirrorLamps.Lamp.values().length, rows);
    }
}
