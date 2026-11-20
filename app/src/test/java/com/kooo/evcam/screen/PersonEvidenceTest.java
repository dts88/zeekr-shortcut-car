/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 有人的证据怎么记（{@link PersonEvidence}）：各算一分钟，熄屏那一下作废，熄屏时还开着的车门要先关上再拉开。 */
public class PersonEvidenceTest {

    private static final long FRESH = ScreenTransitions.EVIDENCE_FRESH_MS;

    /** 拉开车门、碰应用各算一分钟；没有的时候什么都不算。 */
    @Test
    public void evidenceCountsForAMinute() {
        PersonEvidence e = new PersonEvidence();
        assertFalse(e.door(1_000));
        assertFalse(e.touch(1_000));
        assertEquals(-1, e.doorAgoMs(1_000));
        assertEquals(-1, e.touchAgoMs(1_000));
        assertEquals(0, e.touchAtMs());

        e.doors(true, 1_000);
        e.touched(2_000);
        assertTrue(e.door(1_000 + FRESH));
        assertFalse(e.door(1_001 + FRESH));
        assertTrue(e.touch(2_000 + FRESH));
        assertFalse(e.touch(2_001 + FRESH));
        assertEquals(2_000, e.touchAtMs());
        assertEquals(500, e.doorAgoMs(1_500));
        assertEquals(100, e.touchAgoMs(2_100));
    }

    /** 车门一直开着：每一批都算，关上以后从最后一次读到开着起算一分钟。 */
    @Test
    public void aDoorKeptOpenStaysFreshUntilAMinuteAfterItCloses() {
        PersonEvidence e = new PersonEvidence();
        e.doors(true, 1_000);
        e.doors(true, 100_000);
        e.doors(false, 110_000);
        assertTrue(e.door(100_000 + FRESH));
        assertFalse(e.door(100_001 + FRESH));
    }

    /** 熄屏那一下之前的都作废；之后再拉开车门、再碰应用照常算。 */
    @Test
    public void goingDarkVoidsWhatCameBefore() {
        PersonEvidence e = new PersonEvidence();
        e.doors(true, 1_000);
        e.doors(false, 1_500);
        e.touched(1_000);
        e.wentDark(false);
        assertFalse(e.door(2_000));
        assertFalse(e.touch(2_000));
        assertEquals(0, e.touchAtMs());

        e.doors(true, 5_000);
        e.touched(6_000);
        assertTrue(e.door(5_000));
        assertTrue(e.touch(6_000));
    }

    /** 熄屏那一下还开着的车门：读到开着不算，要先读到关上、再读到开着。 */
    @Test
    public void aDoorOpenWhenItWentDarkMustCloseFirst() {
        PersonEvidence e = new PersonEvidence();
        e.doors(true, 1_000);
        e.wentDark(true);
        e.doors(true, 2_000);
        e.doors(true, 3_000);
        assertFalse(e.door(3_000));
        e.doors(false, 4_000);
        assertFalse(e.door(4_000));
        e.doors(true, 5_000);
        assertTrue(e.door(5_000));
    }
}
