/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KnobFeelTest {

    private static final String KNOB = "66:77:88:99:AA:BB";

    @Test
    public void aSingleNudgeMovesOneStep() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_800));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 11_700));
    }

    @Test
    public void aSteadyTurnGetsItsFirstNotchBack() {
        KnobFeel feel = new KnobFeel();
        int total = feel.feelTS(KNOB, 1, 1, 0, 10_000) + feel.feelTS(KNOB, 1, 1, 0, 10_250)
                + feel.feelTS(KNOB, 1, 1, 0, 10_500);
        assertEquals(4, total);
    }

    @Test
    public void aFastTurnGetsItsFirstNotchBackRightAway() {
        KnobFeel feel = new KnobFeel();
        assertEquals(4, feel.feelTS(KNOB, 1, 3, 0, 10_000));
        assertEquals(2, feel.feelTS(KNOB, 1, 2, 0, 10_170));
    }

    @Test
    public void turningBackStartsAgain() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        assertEquals(-1, feel.feelTS(KNOB, -1, 1, 0, 10_300));
        assertEquals(-2, feel.feelTS(KNOB, -1, 1, 0, 10_500));
        assertEquals(-1, feel.feelTS(KNOB, -1, 1, 0, 10_700));
    }

    @Test
    public void aPauseStartsAgain() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        assertEquals(2, feel.feelTS(KNOB, 1, 1, 0, 10_300));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 12_400));
        assertEquals(2, feel.feelTS(KNOB, 1, 1, 0, 12_600));
    }

    @Test
    public void aLostAdvertInTheMiddleOfATurnIsCounted() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        assertEquals(3, feel.feelTS(KNOB, 1, 1, 1, 10_340));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_510));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 1, 12_000));
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 1, 12_900));
        assertEquals(3, feel.feelTS(KNOB, 1, 1, 2, 13_700));
    }

    @Test
    public void aPressForgetsTheTurn() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        feel.forget(KNOB);
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_200));
        assertEquals(2, feel.feelTS(KNOB, 1, 1, 0, 10_400));
    }

    @Test
    public void eachKnobKeepsItsOwnTurn() {
        KnobFeel feel = new KnobFeel();
        assertEquals(1, feel.feelTS(KNOB, 1, 1, 0, 10_000));
        assertEquals(1, feel.feelTS("00:11:22:33:44:55", 1, 1, 0, 10_200));
        assertEquals(2, feel.feelTS(KNOB, 1, 1, 0, 10_300));
    }

    @Test
    public void missedCountsTheGapInTheCounter() {
        assertEquals(0, KnobFeel.missed(5, 4));
        assertEquals(1, KnobFeel.missed(5, 3));
        assertEquals(2, KnobFeel.missed(5, 2));
        assertEquals(0, KnobFeel.missed(9, 2));
        assertEquals(1, KnobFeel.missed(0, 254));
        assertEquals(0, KnobFeel.missed(5, -1));
        assertEquals(0, KnobFeel.missed(5, 5));
    }

    @Test
    public void thePanelStopsAtBothEnds() {
        assertEquals(0, KnobPanel.next(-1, 3, 4));
        assertEquals(3, KnobPanel.next(1, 5, 4));
        assertEquals(0, KnobPanel.next(1, -5, 4));
        assertEquals(2, KnobPanel.next(1, 1, 4));
    }

    @Test
    public void temperaturesCoverTheWholeRange() {
        KnobDials.Positions p = KnobDials.temperatures(22.0f);
        assertEquals(27, p.labels.length);
        assertEquals("15.5°", p.labels[0]);
        assertEquals("28.5°", p.labels[26]);
        assertEquals("22.0°", p.labels[p.index]);
        assertEquals(26, KnobDials.temperatures(32f).index);
    }
}
