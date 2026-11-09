/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 wysie - https://github.com/wysie
 * Portions Copyright (C) 2026 FerixzTanz - https://github.com/FerixzTanz
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.ChannelRules;

import org.junit.Test;

/**
 * {@link SideViewDecision}：左灯只显示左、右灯只显示右、灯灭就不要、原厂画面优先、环视出不了画面不显示；
 * 后视镜上的灯哪一侧亮算哪一侧（两侧怎么一起显示、先后在 {@code SideViewTriggerTest}）；情境一档包含下一档（2026-11-09）。
 *
 * <p>来源：FerixzTanz 的 PR #4、wysie 的 PR #19（GPL-3.0）。</p>
 */
public class SideViewDecisionTest {

    private static SideViewDecision.Input driving(Integer turn) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = turn;
        in.hazard = false;
        in.gear = "D";
        in.stock360Shown = false;
        in.stockPopupShown = false;
        in.parkAssistOn = false;
        in.speedKmh = 60f;
        in.minSpeedKmh = 30;
        return in;
    }

    @Test
    public void leftSignalShowsLeftOnly() {
        assertEquals(LaneCycle.LEFT, SideViewDecision.turnLane(driving(1)));
    }

    @Test
    public void rightSignalShowsRightOnly() {
        assertEquals(LaneCycle.RIGHT, SideViewDecision.turnLane(driving(2)));
    }

    @Test
    public void factoryViewsAlsoReleaseReadiness() {
        SideViewDecision.Input in = driving(0);
        in.stock360Shown = true;
        assertFalse(SideViewDecision.shouldStayReady(in));
        in.stock360Shown = false;
        in.stockPopupShown = true;
        assertFalse(SideViewDecision.shouldStayReady(in));
        in.stockPopupShown = false;
        in.parkAssistOn = true;
        assertFalse(SideViewDecision.shouldStayReady(in));
    }

    @Test
    public void signalOffCloses() {
        SideViewDecision.Input in = driving(0);
        in.showing = LaneCycle.LEFT;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(in));
    }

    @Test
    public void unreadableSignalShowsNothing() {
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(driving(null)));
    }

    @Test
    public void hazardsShowNothing() {
        SideViewDecision.Input in = driving(0);
        in.hazard = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(in));
    }

    @Test
    public void switchingSidesFollowsTheSignal() {
        SideViewDecision.Input in = driving(2);
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.turnLane(in));
    }

    @Test
    public void reverseGearWins() {
        SideViewDecision.Input in = driving(1);
        in.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(in));
    }

    @Test
    public void stockPopupWinsEvenWhenAlreadyShowing() {
        SideViewDecision.Input in = driving(1);
        in.showing = LaneCycle.LEFT;
        in.stockPopupShown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(in));
    }

    @Test
    public void stock360AndParkAssistWin() {
        SideViewDecision.Input a = driving(2);
        a.stock360Shown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(a));
        SideViewDecision.Input b = driving(2);
        b.parkAssistOn = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(b));
    }

    @Test
    public void belowSpeedThresholdDoesNotOpen() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        assertEquals(SideViewDecision.NONE, SideViewDecision.turnLane(in));
    }

    @Test
    public void slowingDownDoesNotCloseAnOpenView() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.LEFT, SideViewDecision.turnLane(in));
    }

    @Test
    public void staysReadyOnlyInDrive() {
        assertTrue(SideViewDecision.shouldStayReady(driving(0)));
        for (String gear : new String[]{"P", "N", "R", null}) {
            SideViewDecision.Input in = driving(0);
            in.gear = gear;
            assertFalse("gear " + gear, SideViewDecision.shouldStayReady(in));
        }
    }

    @Test
    public void surroundThatCannotStreamIsNotShown() {
        // 车机自己的后排画面拿走相机 1、环视被挡住（TAKEN），在救、救不回来：显示出来只会是黑框
        assertFalse(SideViewDecision.surroundCanShow(ChannelRules.Condition.TAKEN));
        assertFalse(SideViewDecision.surroundCanShow(ChannelRules.Condition.RESCUING));
        assertFalse(SideViewDecision.surroundCanShow(ChannelRules.Condition.GAVE_UP));
    }

    @Test
    public void surroundStreamingOrComingUpIsShown() {
        assertTrue(SideViewDecision.surroundCanShow(ChannelRules.Condition.STREAMING));
        assertTrue(SideViewDecision.surroundCanShow(ChannelRules.Condition.BRINGING_UP));
        assertTrue(SideViewDecision.surroundCanShow(null));
    }

    @Test
    public void zeroThresholdOrUnknownSpeedDoesNotBlock() {
        SideViewDecision.Input a = driving(1);
        a.speedKmh = 5f;
        a.minSpeedKmh = 0;
        assertEquals(LaneCycle.LEFT, SideViewDecision.turnLane(a));
        SideViewDecision.Input b = driving(1);
        b.speedKmh = null;
        assertEquals(LaneCycle.LEFT, SideViewDecision.turnLane(b));
    }

    // ------------------------------------------------------------------ 后视镜上的灯（2026-11-03）、情境（2026-11-09）

    @Test
    public void eachSidesLightsAreReadOnTheirOwn() {
        SideViewDecision.Input in = driving(0);
        in.blindSpotRight = true;
        assertTrue(SideViewDecision.blindSpotLit(in, LaneCycle.RIGHT));
        assertFalse(SideViewDecision.blindSpotLit(in, LaneCycle.LEFT));
        assertTrue(SideViewDecision.lit(in, LaneCycle.RIGHT));
        assertFalse(SideViewDecision.lit(in, LaneCycle.LEFT));
    }

    @Test
    public void lightsIgnoreTheSpeedThresholdAndHazards() {
        SideViewDecision.Input in = driving(0);
        in.gear = "P";
        in.speedKmh = 0f;
        in.hazard = true;
        in.doorWarningLeft = true;
        assertTrue(SideViewDecision.doorWarningLit(in, LaneCycle.LEFT));
        assertFalse(SideViewDecision.doorWarningLit(in, LaneCycle.RIGHT));
        assertTrue(SideViewDecision.lit(in, LaneCycle.LEFT));
    }

    /** 转向灯和另一侧的灯各算各的：左灯打着，右边亮着照样算亮（项目所有者 2026-11-04：两侧都显示）。 */
    @Test
    public void aTurnSignalAndTheOtherSidesLightAreIndependent() {
        SideViewDecision.Input in = driving(1);
        in.blindSpotRight = true;
        assertEquals(LaneCycle.LEFT, SideViewDecision.turnLane(in));
        assertTrue(SideViewDecision.lit(in, LaneCycle.RIGHT));
        assertFalse("没往右边打灯", SideViewDecision.signalsToward(in, LaneCycle.RIGHT));
    }

    /** 往哪一侧打灯：双闪、不打灯、读不到都不算；不看车速。 */
    @Test
    public void signalsTowardIgnoresSpeedButNotHazards() {
        SideViewDecision.Input in = driving(2);
        in.speedKmh = 5f;
        assertTrue("不看车速", SideViewDecision.signalsToward(in, LaneCycle.RIGHT));
        assertFalse(SideViewDecision.signalsToward(in, LaneCycle.LEFT));
        in.hazard = true;
        assertFalse("双闪不算", SideViewDecision.signalsToward(in, LaneCycle.RIGHT));
        SideViewDecision.Input unknown = driving(null);
        assertFalse(SideViewDecision.signalsToward(unknown, LaneCycle.LEFT));
        assertFalse(SideViewDecision.signalsToward(unknown, LaneCycle.RIGHT));
    }

    @Test
    public void factoryViewsWinOverLights() {
        SideViewDecision.Input in = driving(0);
        in.gear = "R";
        in.doorWarningLeft = true;
        assertTrue("倒挡：原厂画面优先（判定当场全收，SideViewTrigger）", SideViewDecision.factoryViewActive(in));
    }

    @Test
    public void unreadableLightsShowNothing() {
        SideViewDecision.Input in = driving(0);
        assertFalse(SideViewDecision.lit(in, LaneCycle.LEFT));
        assertFalse(SideViewDecision.lit(in, LaneCycle.RIGHT));
    }

    /** 情境一档包含下一档：简洁，标准再加只打转向灯，丰富再加只是盲区指示灯亮（项目所有者 2026-11-09）。 */
    @Test
    public void modesNestOneInsideTheNext() {
        assertFalse(SideViewDecision.Mode.MINIMAL.showsTurnSignal());
        assertFalse(SideViewDecision.Mode.MINIMAL.showsBlindSpot());
        assertTrue(SideViewDecision.Mode.STANDARD.showsTurnSignal());
        assertFalse(SideViewDecision.Mode.STANDARD.showsBlindSpot());
        assertTrue(SideViewDecision.Mode.FULL.showsTurnSignal());
        assertTrue(SideViewDecision.Mode.FULL.showsBlindSpot());
    }

    /** 存的值认不出来当标准（默认）；以前勾过「盲区提醒」的换成丰富，别的换成标准（项目所有者 2026-11-09）。 */
    @Test
    public void storedValuesAndTheOldAddOnsMapToAMode() {
        for (SideViewDecision.Mode mode : SideViewDecision.Mode.values()) {
            assertEquals(mode, SideViewDecision.Mode.of(mode.value));
        }
        assertEquals(SideViewDecision.Mode.STANDARD, SideViewDecision.Mode.of(null));
        assertEquals(SideViewDecision.Mode.STANDARD, SideViewDecision.Mode.of("rich"));
        assertEquals(SideViewDecision.Mode.FULL, SideViewDecision.Mode.fromAddOns(true));
        assertEquals(SideViewDecision.Mode.STANDARD, SideViewDecision.Mode.fromAddOns(false));
        assertEquals("没给情境的输入当标准", SideViewDecision.Mode.STANDARD, new SideViewDecision.Input().mode);
    }
}
