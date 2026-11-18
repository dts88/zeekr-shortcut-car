/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 一张照片能有多大：先降画质、再降尺寸（{@link PictureBudget}）。 */
public class PictureBudgetTest {

    @Test
    public void theFirstAlertPictureIsBudgetedAtOneHundredKilobytes() {
        assertEquals(100 * 1024, PictureBudget.ALERT_BYTES);
        assertTrue(PictureBudget.fits(100 * 1024, PictureBudget.ALERT_BYTES));
        assertFalse(PictureBudget.fits(100 * 1024 + 1, PictureBudget.ALERT_BYTES));
    }

    @Test
    public void qualityDropsBeforeThePictureShrinks() {
        // 前四步都是全尺寸，只是画质一档档往下
        for (int step = 0; step < 4; step++) {
            assertEquals(1.0f, PictureBudget.step(step)[0], 0.0001f);
        }
        assertEquals(70, (int) PictureBudget.step(0)[1]);
        assertEquals(60, (int) PictureBudget.step(1)[1]);
        assertEquals(50, (int) PictureBudget.step(2)[1]);
        assertEquals(45, (int) PictureBudget.step(3)[1]);
        // 第五步才开始缩图，而且回到最高的那一档画质
        assertEquals(0.85f, PictureBudget.step(4)[0], 0.0001f);
        assertEquals(70, (int) PictureBudget.step(4)[1]);
    }

    @Test
    public void theLadderEndsAndNeverGoesBelowTheMinimumScale() {
        assertEquals(PictureBudget.steps(), 12);
        assertNotNull(PictureBudget.step(PictureBudget.steps() - 1));
        assertNull(PictureBudget.step(PictureBudget.steps()));
        assertNull(PictureBudget.step(-1));
        for (int step = 0; step < PictureBudget.steps(); step++) {
            assertTrue(PictureBudget.step(step)[0] >= PictureBudget.MIN_SCALE);
        }
    }

    @Test
    public void aShrunkStepNeedsItsOwnBitmap() {
        assertFalse(PictureBudget.shrinks(1.0f));
        assertTrue(PictureBudget.shrinks(0.85f));
    }

    @Test
    public void sizeReadsLikeAPersonWouldSayIt() {
        assertEquals("100 kB", PictureBudget.size(100 * 1024));
        assertEquals("1 kB", PictureBudget.size(1));
        assertEquals("2.0 MB", PictureBudget.size(2 * 1024 * 1024));
        assertEquals("10.0 MB", PictureBudget.size(10 * 1024 * 1024));
        assertEquals("0 kB", PictureBudget.size(0));
    }

    @Test
    public void nothingEverCountsAsFittingWhenNothingWasEncoded() {
        assertFalse(PictureBudget.fits(0, PictureBudget.ALERT_BYTES));
        assertFalse(PictureBudget.fits(-1, PictureBudget.ALERT_BYTES));
    }
}
