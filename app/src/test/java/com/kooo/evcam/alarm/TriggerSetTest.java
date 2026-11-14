/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashSet;

/**
 * 车主选了哪几项算「有事」（{@link TriggerSet}）：存的是名字，读错、写坏都不能变成「一项都没有」。
 */
public class TriggerSetTest {

    @Test
    public void everythingIsOnByDefault() {
        TriggerSet set = TriggerSet.parse(null);
        for (String name : TriggerSet.ALL) {
            assertTrue(name, set.has(name));
        }
        assertEquals(TriggerSet.ALL.length, set.size());
    }

    @Test
    public void aSelectionKeepsOnlyKnownNames() {
        TriggerSet set = TriggerSet.parse("doors,screen,nonsense");
        assertTrue(set.has(TriggerSet.DOORS));
        assertTrue(set.has(TriggerSet.SCREEN));
        assertFalse(set.has(TriggerSet.MOTION));
        assertFalse("认不出来的名字不进集合", set.has("nonsense"));
        assertEquals(2, set.size());
    }

    @Test
    public void somethingBrokenMeansAllOnRatherThanSilence() {
        // 存坏了的设置不该把警报变成哑巴：认不出来按全开算
        assertEquals(TriggerSet.ALL.length, TriggerSet.parse(",,").size());
        assertEquals(TriggerSet.ALL.length, TriggerSet.parse("???,").size());
    }

    @Test
    public void writingBackUsesTheFixedOrder() {
        java.util.Set<String> picked = new LinkedHashSet<>();
        picked.add(TriggerSet.SCREEN);
        picked.add(TriggerSet.MOTION);
        assertEquals("motion,screen", TriggerSet.value(picked));
    }

    @Test
    public void anEmptySelectionWritesAnEmptyString() {
        assertEquals("", TriggerSet.value(new LinkedHashSet<>()));
        assertEquals("", TriggerSet.value(null));
    }

    @Test
    public void roundTripKeepsExactlyWhatWasChosen() {
        String csv = TriggerSet.value(new LinkedHashSet<>(
                java.util.Arrays.asList(TriggerSet.HAZARD, TriggerSet.DOORS)));
        TriggerSet back = TriggerSet.parse(csv);
        assertTrue(back.has(TriggerSet.HAZARD));
        assertTrue(back.has(TriggerSet.DOORS));
        assertFalse(back.has(TriggerSet.MOTION));
        assertEquals("hazard,doors", csv);
    }
}
