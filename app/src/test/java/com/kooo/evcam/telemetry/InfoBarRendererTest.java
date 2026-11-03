package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 转向灯箭头的边框（项目所有者 2026-11-03）：开门预警亮是红，不然盲区指示灯亮是黄；都没亮、读不到不换（0，照原来画）。
 */
public class InfoBarRendererTest {

    /** 开着的门那种红。 */
    private static final int RED = 0xFFF0555A;
    /** 方向盘左偏那种黄。 */
    private static final int YELLOW = 0xFFFFD54F;

    @Test
    public void doorWarningRedBeatsBlindSpotYellow() {
        assertEquals(0, InfoBarRenderer.lampBorder(null, null));
        assertEquals(0, InfoBarRenderer.lampBorder(false, false));
        assertEquals(YELLOW, InfoBarRenderer.lampBorder(true, null));
        assertEquals(YELLOW, InfoBarRenderer.lampBorder(true, false));
        assertEquals(RED, InfoBarRenderer.lampBorder(null, true));
        assertEquals("同一侧都亮：红", RED, InfoBarRenderer.lampBorder(true, true));
    }
}
