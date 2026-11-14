/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
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
 *
 * Which items the owner picked count as "something happened" ({@link TriggerSet}):
 * what is stored are names, and a misread or corrupted value must never become
 * "not a single item".
 */
public class TriggerSetTest {

    @Test
    public void theDefaultIsWhatTheCarItselfDoes() {
        // 默认只开车自己会做的：闪双闪、亮灯、亮屏。
        // The default only enables what the car itself does: flash hazards, light
        // up lamps, light up the screen.
        // 移动 / 远光 / 车门默认关，留给车主自己选。
        // Motion / high beam / doors are off by default, left for the owner to
        // choose.
        TriggerSet set = TriggerSet.parse(TriggerSet.DEFAULT);
        assertTrue(set.has(TriggerSet.HAZARD));
        assertTrue(set.has(TriggerSet.LAMPS));
        assertTrue(set.has(TriggerSet.SCREEN));
        assertFalse(set.has(TriggerSet.MOTION));
        assertFalse(set.has(TriggerSet.FLASH));
        assertFalse(set.has(TriggerSet.DOORS));
        assertEquals(3, set.size());
        // 存坏了（空串 / 认不出来的名字）仍然按全开算 —— 那是安全网，不是默认值
        // A corrupted value (empty string / unrecognized names) still means all
        // on; that is a safety net, not the default.
        assertEquals(TriggerSet.ALL.length, TriggerSet.parse(null).size());
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
        // A corrupted setting must not turn the alert mute: unrecognized input
        // means all on
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
    public void anEmptySelectionIsStoredAsAMarkerNotAnEmptyString() {
        // 空串是「存坏了」的安全网，会被读回全开 —— 所以车主真的一个都没选时要存记号。
        // The empty string is the "stored broken" safety net and reads back as all on, so a real
        // "nothing selected" has to store a marker.
        assertEquals(TriggerSet.NONE, TriggerSet.value(new LinkedHashSet<>()));
        assertEquals(TriggerSet.NONE, TriggerSet.value(null));
        assertEquals(0, TriggerSet.parse(TriggerSet.NONE).size());
    }

    @Test
    public void choosingNothingStaysNothingAfterAStoreAndReload() {
        // 全不勾 → 存 → 重开界面：还是全不勾，不能跳回全开。
        // Uncheck all, store, reopen the screen: still all unchecked, it must not jump back to all on.
        TriggerSet back = TriggerSet.parse(TriggerSet.value(new LinkedHashSet<String>()));
        assertEquals(0, back.size());
        for (String name : TriggerSet.ALL) {
            assertFalse(name, back.has(name));
        }
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
