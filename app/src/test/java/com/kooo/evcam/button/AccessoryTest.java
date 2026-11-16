/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public class AccessoryTest {

    @Test
    public void namesAreMatchedExactly() {
        assertEquals(Accessory.BUTTON, Accessory.ofName("ZEEKR_button"));
        assertEquals(Accessory.KNOB, Accessory.ofName("ZEEKR_knob"));
        assertNull(Accessory.ofName("zeekr_knob"));
        assertNull(Accessory.ofName("ZEEKR_knob2"));
        assertNull(Accessory.ofName(null));
    }

    @Test
    public void theKnobAlsoTurnsAndConnectsOnRelease() {
        assertArrayEquals(new int[]{SmartButtonAd.SINGLE, SmartButtonAd.DOUBLE}, Accessory.BUTTON.actionGestures());
        assertArrayEquals(new int[]{SmartButtonAd.SINGLE, SmartButtonAd.DOUBLE, SmartButtonAd.CLOCKWISE,
                SmartButtonAd.ANTICLOCKWISE}, Accessory.KNOB.actionGestures());
        assertFalse(Accessory.BUTTON.hasAction(SmartButtonAd.CLOCKWISE));
        assertFalse(Accessory.KNOB.hasAction(SmartButtonAd.RELEASE));
        assertEquals(SmartButtonAd.LONG, Accessory.BUTTON.connectGesture);
        assertEquals(SmartButtonAd.RELEASE, Accessory.KNOB.connectGesture);
        assertTrue(Accessory.isTurn(SmartButtonAd.CLOCKWISE));
        assertTrue(Accessory.isTurn(SmartButtonAd.ANTICLOCKWISE));
        assertFalse(Accessory.isTurn(SmartButtonAd.SINGLE));
    }

    @Test
    public void scanNamesFollowWhatIsOnBoundOrBeingBound() {
        List<Accessory> buttonOnly = Collections.singletonList(Accessory.BUTTON);
        assertEquals(new TreeSet<>(Collections.singletonList("ZEEKR_button")),
                SmartButtons.namesTS(buttonOnly, true, true, null));
        assertTrue(SmartButtons.namesTS(buttonOnly, false, true, null).isEmpty());
        assertEquals(new TreeSet<>(Arrays.asList("ZEEKR_button", "ZEEKR_knob")),
                SmartButtons.namesTS(buttonOnly, true, true, Accessory.KNOB));
        assertEquals(new TreeSet<>(Collections.singletonList("ZEEKR_button")),
                SmartButtons.namesTS(buttonOnly, true, false, Accessory.KNOB));
        assertEquals(new TreeSet<>(Collections.singletonList("ZEEKR_knob")),
                SmartButtons.namesTS(Arrays.asList(Accessory.KNOB, Accessory.KNOB), true, true, null));
        assertTrue(SmartButtons.namesTS(Collections.emptyList(), true, true, null).isEmpty());
    }
}
