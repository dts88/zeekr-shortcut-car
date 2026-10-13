package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 显示屏状态在黑匣子里的名字（2026-10-12，找黑屏信号只加记录）。系统自己的 {@code Display.stateToString} 是隐藏的，
 * 这里照 {@code android.view.Display} 的常量值写一份；认错了，「屏幕真黑了」那一行就读不懂。
 */
public class DisplayWatchTest {

    @Test
    public void stateNames() {
        assertEquals("UNKNOWN", DisplayWatch.stateName(0));
        assertEquals("OFF", DisplayWatch.stateName(1));
        assertEquals("ON", DisplayWatch.stateName(2));
        assertEquals("DOZE", DisplayWatch.stateName(3));
        assertEquals("DOZE_SUSPEND", DisplayWatch.stateName(4));
        assertEquals("VR", DisplayWatch.stateName(5));
        assertEquals("ON_SUSPEND", DisplayWatch.stateName(6));
        assertEquals("没见过的照写数字", "STATE_42", DisplayWatch.stateName(42));
    }
}
