/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SideViewAlert}：盲区指示灯亮黄、往那一侧打灯黄闪、开门预警红闪（红色只给开门预警，项目所有者 2026-11-08）；
 * 读不到不描边、和情境无关；没有「安全」的颜色；闪是安全提醒，不看装饰动效（项目所有者 2026-11-09）。
 */
public class SideViewAlertTest {

    private static SideViewDecision.Input driving(Integer turn) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = turn;
        in.hazard = false;
        in.gear = "D";
        in.speedKmh = 60f;
        return in;
    }

    @Test
    public void blindSpotLightWithoutSignalIsSteadyYellow() {
        SideViewDecision.Input in = driving(0);
        in.blindSpotLeft = true;
        assertEquals(SideViewAlert.CAUTION, SideViewAlert.level(in, LaneCycle.LEFT));
        assertEquals(SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.RIGHT));
        assertFalse(SideViewAlert.blinks(SideViewAlert.CAUTION));
        assertEquals(SideViewAlert.YELLOW, SideViewAlert.color(SideViewAlert.CAUTION, false));
    }

    /** 车上那盏灯这时闪（7X 手册「盲区辅助」）：边框照样黄色，跟着闪。 */
    @Test
    public void signallingTowardALitSideBlinksYellow() {
        SideViewDecision.Input in = driving(2);
        in.blindSpotRight = true;
        assertEquals(SideViewAlert.CAUTION_BLINK, SideViewAlert.level(in, LaneCycle.RIGHT));
        assertTrue(SideViewAlert.blinks(SideViewAlert.CAUTION_BLINK));
        assertEquals(SideViewAlert.YELLOW, SideViewAlert.color(SideViewAlert.CAUTION_BLINK, true));
        assertEquals("暗的那一半同色", SideViewAlert.YELLOW & 0x00FFFFFF,
                SideViewAlert.color(SideViewAlert.CAUTION_BLINK, false) & 0x00FFFFFF);
    }

    /** 红色只给开门预警：盲区指示灯亮，打不打灯都是黄色。 */
    @Test
    public void onlyTheDoorWarningIsRed() {
        for (int level : new int[]{SideViewAlert.CAUTION, SideViewAlert.CAUTION_BLINK}) {
            assertEquals(SideViewAlert.YELLOW, SideViewAlert.color(level, true));
        }
        assertEquals(SideViewAlert.RED, SideViewAlert.color(SideViewAlert.WARNING, true));
    }

    @Test
    public void signallingAwayFromALitSideStaysYellow() {
        SideViewDecision.Input in = driving(1);
        in.blindSpotRight = true;
        assertEquals(SideViewAlert.CAUTION, SideViewAlert.level(in, LaneCycle.RIGHT));
        assertEquals("左边没亮：不描边", SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.LEFT));
    }

    @Test
    public void hazardsAreNotASignalTowardEitherSide() {
        SideViewDecision.Input in = driving(1);
        in.hazard = true;
        in.blindSpotLeft = true;
        assertEquals(SideViewAlert.CAUTION, SideViewAlert.level(in, LaneCycle.LEFT));
    }

    @Test
    public void unreadableSignalWithALitSideStaysYellow() {
        SideViewDecision.Input in = driving(null);
        in.blindSpotLeft = true;
        assertEquals(SideViewAlert.CAUTION, SideViewAlert.level(in, LaneCycle.LEFT));
    }

    @Test
    public void doorWarningIsRedAndBlinksEvenParked() {
        SideViewDecision.Input in = driving(0);
        in.gear = "P";
        in.speedKmh = 0f;
        in.doorWarningLeft = true;
        assertEquals(SideViewAlert.WARNING, SideViewAlert.level(in, LaneCycle.LEFT));
        in.blindSpotLeft = true;
        assertEquals("两盏都亮：按更急的开门预警", SideViewAlert.WARNING, SideViewAlert.level(in, LaneCycle.LEFT));
    }

    @Test
    public void unreadableLightsDrawNothing() {
        SideViewDecision.Input in = driving(1);
        assertEquals(SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.LEFT));
        assertEquals(SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.RIGHT));
        assertEquals(0, SideViewAlert.color(SideViewAlert.NONE, true));
    }

    @Test
    public void unlitLightsDrawNothingAndThereIsNoSafeColour() {
        SideViewDecision.Input in = driving(1);
        in.blindSpotLeft = false;
        in.doorWarningLeft = false;
        assertEquals(SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.LEFT));
        for (int level : new int[]{SideViewAlert.NONE, SideViewAlert.CAUTION, SideViewAlert.CAUTION_BLINK,
                SideViewAlert.WARNING}) {
            int color = SideViewAlert.color(level, true);
            int green = (color >> 8) & 0xFF;
            int red = (color >> 16) & 0xFF;
            assertTrue("没有偏绿的颜色：" + Integer.toHexString(color), color == 0 || red > green);
        }
    }

    /** 和情境无关：画面显示着、灯亮着就描边（项目所有者 2026-11-09）。 */
    @Test
    public void theFrameFollowsTheLightsWhateverTheMode() {
        for (SideViewDecision.Mode mode : SideViewDecision.Mode.values()) {
            SideViewDecision.Input in = driving(1);
            in.mode = mode;
            in.blindSpotLeft = true;
            in.doorWarningRight = true;
            assertEquals(mode.name(), SideViewAlert.CAUTION_BLINK, SideViewAlert.level(in, LaneCycle.LEFT));
            assertEquals(mode.name(), SideViewAlert.WARNING, SideViewAlert.level(in, LaneCycle.RIGHT));
        }
    }

    /** 闪是安全提醒：开门预警、往盲区那一侧打灯一直闪，装饰动效放不了也闪（项目所有者 2026-11-09）；常亮的那一档不闪。 */
    @Test
    public void blinkingIsASafetyCueNotDecoration() {
        assertTrue(SideViewAlert.blinks(SideViewAlert.WARNING));
        assertTrue(SideViewAlert.blinks(SideViewAlert.CAUTION_BLINK));
        assertFalse(SideViewAlert.blinks(SideViewAlert.CAUTION));
        assertFalse(SideViewAlert.blinks(SideViewAlert.NONE));
        SideViewDecision.Input in = driving(2);
        in.fade = false;
        in.blindSpotRight = true;
        assertEquals(SideViewAlert.CAUTION_BLINK, SideViewAlert.level(in, LaneCycle.RIGHT));
    }

    @Test
    public void theDimHalfOfABlinkIsStillVisible() {
        int dim = SideViewAlert.color(SideViewAlert.WARNING, false);
        assertEquals(SideViewAlert.RED & 0x00FFFFFF, dim & 0x00FFFFFF);
        int alpha = dim >>> 24;
        assertTrue("暗的那一半看得见", alpha > 0x33);
        assertTrue("和亮的那一半分得开", alpha < 0x80);
    }

    @Test
    public void hudStrokeScalesWithThePictureButNotBelowFourPixels() {
        assertEquals(4f, SideViewAlert.hudStrokePx(80, 40), 0f);
        assertEquals(12f, SideViewAlert.hudStrokePx(660, 300), 0f);
    }

    /** 开门预警哪一侧亮红哪一侧。 */
    @Test
    public void doorWarningIsRedOnItsOwnSide() {
        SideViewDecision.Input in = driving(0);
        in.doorWarningRight = true;
        assertEquals(SideViewAlert.WARNING, SideViewAlert.level(in, LaneCycle.RIGHT));
        assertEquals(SideViewAlert.NONE, SideViewAlert.level(in, LaneCycle.LEFT));
    }
}
