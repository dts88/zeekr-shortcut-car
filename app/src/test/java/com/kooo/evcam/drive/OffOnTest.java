/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 连着几次关着、几次开着怎么数（{@link OffOn}）。 */
public class OffOnTest {

    private static OffOn after(Integer... values) {
        OffOn track = OffOn.NONE;
        for (Integer value : values) {
            track = track.next(value);
        }
        return track;
    }

    @Test
    public void countsReadingsInARow() {
        assertEquals(0, OffOn.NONE.off);
        assertEquals(0, OffOn.NONE.on);
        assertEquals(2, after(0, 0).off);
        assertEquals(0, after(0, 0).on);
        assertEquals(3, after(0, 1, 1, 1).on);
        assertEquals(0, after(0, 1, 1, 1).off);
    }

    @Test
    public void theOtherValueStartsTheCountAgain() {
        assertEquals(1, after(0, 1, 0).off);
        assertEquals(1, after(1, 0, 1).on);
    }

    @Test
    public void anUnreadableOrOddValueStartsBothCountsAgain() {
        assertEquals(1, after(0, null, 0).off);
        assertEquals(1, after(1, 255, 1).on);
        assertEquals(0, after(1, 1, null).on);
        assertEquals(0, after(0, 0, 2).off);
    }
}
