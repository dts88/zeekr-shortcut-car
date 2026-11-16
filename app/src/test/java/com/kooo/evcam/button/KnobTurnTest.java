/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class KnobTurnTest {

    @Test
    public void keysAreUniqueAndUnknownIsNone() {
        Set<String> keys = new HashSet<>();
        for (KnobTurn turn : KnobTurn.values()) {
            assertTrue(turn.key, keys.add(turn.key));
            assertSame(turn, KnobTurn.fromKey(turn.key));
        }
        assertSame(KnobTurn.NONE, KnobTurn.fromKey(null));
        assertSame(KnobTurn.NONE, KnobTurn.fromKey(""));
        assertSame(KnobTurn.NONE, KnobTurn.fromKey("volume_up"));
    }

    @Test
    public void everyFunctionHasAGroup() {
        assertNull(KnobTurn.NONE.group);
        assertNull(KnobTurn.NONE.dial);
        for (KnobTurn turn : KnobTurn.values()) {
            if (turn != KnobTurn.NONE) {
                assertNotNull(turn.key, turn.group);
                assertTrue(turn.key, KnobTurn.of(turn.group).contains(turn));
            }
        }
        assertNull(KnobTurn.TRACK.dial);
        assertNull(KnobTurn.GEAR.dial);
        assertNotNull(KnobTurn.SUSPENSION.dial);
    }

    @Test
    public void groupsFollowTheCatalog() {
        assertEquals(Arrays.asList(ButtonAction.Group.MEDIA, ButtonAction.Group.CLIMATE, ButtonAction.Group.SEATS,
                ButtonAction.Group.DRIVE, ButtonAction.Group.BODY, ButtonAction.Group.LIGHTS), KnobTurn.groups());
        int total = 0;
        for (ButtonAction.Group group : KnobTurn.groups()) {
            total += KnobTurn.of(group).size();
        }
        assertEquals(36, total);
        assertEquals(KnobTurn.values().length - 1, total);
    }

    @Test
    public void homeIsInCatalogOrder() {
        List<KnobTurn> home = KnobTurn.home();
        assertEquals(13, home.size());
        assertSame(KnobTurn.VOLUME, home.get(0));
        for (int i = 1; i < home.size(); i++) {
            assertTrue(home.get(i).key, home.get(i - 1).ordinal() < home.get(i).ordinal());
        }
    }

    @Test
    public void choosingWrapsAround() {
        assertEquals(0, KnobMode.move(12, 1, 13));
        assertEquals(12, KnobMode.move(0, -1, 13));
        assertEquals(2, KnobMode.move(0, 15, 13));
    }
}
