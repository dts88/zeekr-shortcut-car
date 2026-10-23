package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 哪一块屏是 HUD（{@link HudScreen}）。 */
public class HudScreenTest {

    private static HudScreen.Candidate on(int id, int w, int h) {
        return new HudScreen.Candidate(id, w, h, true);
    }

    /** 7X 诊断报告里的三块屏：主屏、仪表盘、HUD —— 按尺寸找到 3。 */
    @Test
    public void theSevenXFindsTheHudBySize() {
        assertEquals(3, HudScreen.find(Arrays.asList(on(0, 3200, 2000), on(2, 2560, 536), on(3, 1440, 480))));
        assertEquals(3, HudScreen.findBySize(Arrays.asList(on(0, 3200, 2000), on(2, 2560, 536), on(3, 1440, 480))));
    }

    /** 尺寸对上的才算：主屏就算是 1440×480 也不算。 */
    @Test
    public void theDefaultDisplayIsNeverTheHud() {
        assertEquals(HudScreen.NONE, HudScreen.find(Collections.singletonList(on(0, 1440, 480))));
    }

    /** 没有这个尺寸的屏：退到 id 3；它是仪表盘那个尺寸就不退 —— 宁可找不到，也不往仪表盘上画。 */
    @Test
    public void fallsBackToIdThreeButNeverToTheCluster() {
        assertEquals(3, HudScreen.find(Arrays.asList(on(0, 3200, 2000), on(3, 1920, 720))));
        assertEquals(HudScreen.NONE, HudScreen.findBySize(Arrays.asList(on(0, 3200, 2000), on(3, 1920, 720))));
        assertEquals(HudScreen.NONE, HudScreen.find(Arrays.asList(on(0, 3200, 2000), on(3, 2560, 536))));
        assertEquals(HudScreen.NONE, HudScreen.find(Arrays.asList(on(0, 3200, 2000), on(2, 1920, 720))));
    }

    /** 关着的屏不算（HUD 关了，系统还留着这块屏）。 */
    @Test
    public void aDisplayThatIsOffIsNotTheHud() {
        HudScreen.Candidate off = new HudScreen.Candidate(3, 1440, 480, false);
        assertEquals(HudScreen.NONE, HudScreen.find(Arrays.asList(on(0, 3200, 2000), off)));
    }
}
