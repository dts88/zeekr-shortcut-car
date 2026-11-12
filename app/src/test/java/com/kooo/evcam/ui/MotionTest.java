/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link Motion}：时长只有三档，最长 300 ms（ui-spec §十四）。 */
public class MotionTest {

    @Test
    public void threeTiersNoLongerThan300() {
        assertTrue(Motion.FAST < Motion.MEDIUM);
        assertTrue(Motion.MEDIUM < Motion.SLOW);
        assertTrue(Motion.SLOW <= 300);
    }

    @Test
    public void collapsingIsNotSlowerThanExpanding() {
        assertTrue(Motion.COLLAPSE <= Motion.MEDIUM);
        assertTrue(Motion.STAGGER * 8 <= Motion.MEDIUM);
    }
}
