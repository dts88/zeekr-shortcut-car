package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * 车辆信号上一次真变了是什么时候（2.11.4 第 1a 项：开座舱那一行写「点火、主驾门、屏幕电源事件多久以前变过」）。
 * 起点（第一次读到）、读不到的进出都不算变化 —— 不然进程一起来、对账读不到一下，就像刚开过车门。
 */
public class SignalAgesTest {

    @After
    public void tearDown() {
        SignalAges.clear();
    }

    @Test
    public void onlyARealChangeBetweenTwoKnownValuesCounts() {
        assertEquals("没见它变过", -1, SignalAges.ageMs(Signal.IGNITION, 1_000));
        SignalAges.changed(Signal.IGNITION, null, Signal.IGNITION_ON, 1_000);
        assertEquals("起点不算", -1, SignalAges.ageMs(Signal.IGNITION, 2_000));
        SignalAges.changed(Signal.IGNITION, Signal.IGNITION_ON, null, 2_000);
        assertEquals("变成读不到不算", -1, SignalAges.ageMs(Signal.IGNITION, 3_000));
        SignalAges.changed(Signal.IGNITION, Signal.IGNITION_ON, Signal.IGNITION_ON, 3_000);
        assertEquals("没变不算", -1, SignalAges.ageMs(Signal.IGNITION, 3_000));
        SignalAges.changed(Signal.IGNITION, Signal.IGNITION_ACC, Signal.IGNITION_ON, 4_000);
        assertEquals(1_500, SignalAges.ageMs(Signal.IGNITION, 5_500));
        assertEquals("别的信号各算各的", -1, SignalAges.ageMs(Signal.DOOR_DRIVER, 5_500));
        SignalAges.changed(Signal.DOOR_DRIVER, Boolean.FALSE, Boolean.TRUE, 5_000);
        assertEquals(500, SignalAges.ageMs(Signal.DOOR_DRIVER, 5_500));
        assertEquals("钟往回走不出负数", 0, SignalAges.ageMs(Signal.DOOR_DRIVER, 4_000));
    }
}
