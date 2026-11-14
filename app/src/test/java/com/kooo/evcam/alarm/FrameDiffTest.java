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

/**
 * 画面里有没有东西在动（{@link FrameDiff}）：三道过滤 —— 曝光跳变、平均差、显著变化占比。
 */
public class FrameDiffTest {

    private static int[] flat(int value, int count) {
        int[] frame = new int[count];
        java.util.Arrays.fill(frame, value);
        return frame;
    }

    @Test
    public void identicalFramesDoNotMove() {
        assertEquals(0.0, FrameDiff.score(flat(100, 9216), flat(100, 9216)), 0.0);
    }

    @Test
    public void aPersonWalkingPastIsMovement() {
        int[] before = flat(90, 9216);
        int[] after = flat(90, 9216);
        // 一块 30x20 的区域变了 120 个灰度级：占画面 6.5%
        for (int y = 10; y < 30; y++) {
            for (int x = 20; x < 50; x++) {
                after[y * 128 + x] = 210;
            }
        }
        assertTrue(FrameDiff.score(before, after) > FrameDiff.DEFAULT_THRESHOLD);
        assertTrue(FrameDiff.isMovement(FrameDiff.score(before, after), FrameDiff.DEFAULT_THRESHOLD));
    }

    @Test
    public void anExposureJumpIsNotMovement() {
        // 整幅从 60 跳到 200：平均亮度变了 0.55，超过 EXPOSURE_JUMP
        assertEquals(0.0, FrameDiff.score(flat(60, 9216), flat(200, 9216)), 0.0);
    }

    @Test
    public void aSingleFlickeringPixelIsNotMovement() {
        int[] before = flat(120, 9216);
        int[] after = flat(120, 9216);
        after[4000] = 255;
        assertEquals(0.0, FrameDiff.score(before, after), 0.0);
    }

    @Test
    public void aDimUniformChangeUnderTheExposureLimitStillNeedsArea() {
        // 每个像素都只差 10：不算显著变化，也不算移动
        assertEquals(0.0, FrameDiff.score(flat(100, 9216), flat(110, 9216)), 0.0);
    }

    @Test
    public void mismatchedOrMissingFramesAreSafe() {
        assertEquals(0.0, FrameDiff.score(null, flat(1, 100)), 0.0);
        assertEquals(0.0, FrameDiff.score(flat(1, 100), flat(1, 50)), 0.0);
        assertEquals(0.0, FrameDiff.score(new int[0], new int[0]), 0.0);
    }

    @Test
    public void thresholdIsInclusive() {
        assertTrue(FrameDiff.isMovement(FrameDiff.DEFAULT_THRESHOLD, FrameDiff.DEFAULT_THRESHOLD));
        assertFalse(FrameDiff.isMovement(FrameDiff.DEFAULT_THRESHOLD - 0.01, FrameDiff.DEFAULT_THRESHOLD));
    }
}
