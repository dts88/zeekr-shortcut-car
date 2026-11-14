/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 警报发几张、发哪几路（{@link AlertPicture}）：排版和勾选的存法。
 *
 * <p>位图那部分（切格子、掰直、压缩）要 Robolectric 的原生绘图，这里只钉读写的规矩 ——
 * 一串设置值无论怎么写坏，落到「要发哪几路」上都必须是能发出去的那几路。</p>
 */
public class AlertPictureTest {

    @Test
    public void layoutRoundTripsAndFallsBackToTheGrid() {
        assertEquals(AlertPicture.Layout.GRID, AlertPicture.layout("grid"));
        assertEquals(AlertPicture.Layout.SEPARATE, AlertPicture.layout("separate"));
        assertEquals("grid", AlertPicture.layoutValue(AlertPicture.Layout.GRID));
        assertEquals("separate", AlertPicture.layoutValue(AlertPicture.Layout.SEPARATE));
        assertEquals("认不出来按四宫格算", AlertPicture.Layout.GRID, AlertPicture.layout("nonsense"));
        assertEquals(AlertPicture.Layout.GRID, AlertPicture.layout(null));
    }

    @Test
    public void anEmptySelectionMeansAllFour() {
        assertArrayEquals(new int[] {0, 1, 2, 3}, AlertPicture.lanes(""));
        assertArrayEquals(new int[] {0, 1, 2, 3}, AlertPicture.lanes(null));
        assertArrayEquals(new int[] {0, 1, 2, 3}, AlertPicture.lanes(" , ,"));
        assertArrayEquals("越界的号不算，剩下一个都没有时回到全部",
                new int[] {0, 1, 2, 3}, AlertPicture.lanes("9,-1,abc"));
    }

    @Test
    public void lanesComeBackSortedAndDeduplicated() {
        assertArrayEquals(new int[] {0, 2}, AlertPicture.lanes("2,0,2"));
        assertArrayEquals(new int[] {1}, AlertPicture.lanes("1"));
        assertEquals("0,2", AlertPicture.lanesValue(new int[] {2, 0, 2}));
    }

    @Test
    public void allLanesOnlyWhenFourDistinct() {
        assertTrue(AlertPicture.allLanes(new int[] {0, 1, 2, 3}));
        assertFalse(AlertPicture.allLanes(new int[] {0, 1, 2}));
        assertFalse(AlertPicture.allLanes(null));
    }

    @Test
    public void quadrantsMatchTheSavedGridOrder() {
        // 2×2 四宫格的座次：左上 0 前、右上 1 后、左下 2 左、右下 3 右（CompositeBitmapComposer 的默认排列）
        assertEquals(2, AlertPicture.COLUMNS);
        assertEquals(2, AlertPicture.ROWS);
        assertEquals(4, AlertPicture.LANES);
    }
}
