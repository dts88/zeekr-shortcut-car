/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 录制键那一行怎么分（{@link RecordKeyRow}）和第二行的时长怎么写（{@link RecordButtonUi#clock}）。 */
public class RecordKeyRowTest {

    /** 实车：动作栏 380 dp、一道缝 12 dp，密度 2.0。 */
    private static final int ROW = 760;
    private static final int GUTTER = 24;

    /** 那个键最宽是这一行去掉一道缝之后的四分之一（92 dp）。 */
    @Test
    public void theKeyIsAtMostAQuarterOfTheRow() {
        assertEquals(184, RecordKeyRow.widestKey(ROW, GUTTER));
        assertEquals(0, RecordKeyRow.widestKey(0, GUTTER));
    }

    /** 没分时录制键占满；分完让出那个键的宽加一道缝；过渡中按比例。 */
    @Test
    public void theRecordKeyGivesUpTheKeyAndAGutter() {
        int key = 106;  // 中文的那个键：53 dp
        assertEquals(ROW, RecordKeyRow.recordWidth(ROW, GUTTER, key, 0f));
        assertEquals(ROW - key - GUTTER, RecordKeyRow.recordWidth(ROW, GUTTER, key, 1f));
        assertEquals(ROW - (key + GUTTER) / 2, RecordKeyRow.recordWidth(ROW, GUTTER, key, 0.5f));
        assertEquals("那个键看不见时量出来是 0：不让", ROW, RecordKeyRow.recordWidth(ROW, GUTTER, 0, 0f));
    }

    /** 收回是 500 ms，比出来慢（项目所有者 2026-11-14：放慢一倍）；出来 300 ms、先等 300 ms。 */
    @Test
    public void leavingTakesTwiceAsLongAsItUsedTo() {
        assertEquals(500, RecordKeyRow.LEAVE_MS);
        assertEquals(300, RecordKeyRow.ENTER_MS);
        assertEquals(300, RecordKeyRow.ENTER_DELAY_MS);
    }

    /** 亮着的键：没在往外滑时一直点得到；往外滑，滑出去一半之前还点得到（反悔），过了一半点不到。 */
    @Test
    public void theLitKeyCanBeTappedUntilItIsHalfwayOut() {
        assertTrue(RecordButtonUi.withinReach(false, 1f));
        assertTrue(RecordButtonUi.withinReach(true, 1f));
        assertTrue(RecordButtonUi.withinReach(true, 0.5f));
        assertFalse(RecordButtonUi.withinReach(true, 0.49f));
        assertFalse(RecordButtonUi.withinReach(true, 0f));
        assertTrue("拉回来的路上（没在往外滑）都点得到", RecordButtonUi.withinReach(false, 0.2f));
    }

    /**
     * 点亮时红的灯芯像警灯一样转：一个窗口每一圈从灯芯左边外面进来、到右边外面出去，转三圈；之后从左边进来铺满、定住。
     * 窗口两边一样就是不露。
     */
    @Test
    public void theRedLampSweepsThreeTimesThenStays() {
        float[] w = new float[2];
        RecordButtonUi.lampWindow(0f, w);
        assertEquals("没开：不露", w[0], w[1], 0f);
        float turn = RecordButtonUi.SENTRY_SWEEP_BY / RecordButtonUi.SENTRY_SWEEPS;
        for (int n = 0; n < RecordButtonUi.SENTRY_SWEEPS; n++) {
            RecordButtonUi.lampWindow(n * turn + turn * 0.02f, w);
            assertEquals("第 " + (n + 1) + " 圈刚进来：贴着左边", 0f, w[0], 0f);
            assertTrue("露一点点", w[1] > 0f && w[1] < 0.1f);
            RecordButtonUi.lampWindow(n * turn + turn * 0.5f, w);
            assertEquals("转到一半：窗口有那么宽", RecordButtonUi.SENTRY_BAND, w[1] - w[0], 0.001f);
            RecordButtonUi.lampWindow(n * turn + turn * 0.98f, w);
            assertEquals("快出去了：贴着右边", 1f, w[1], 0f);
            assertTrue("只剩一点点", w[1] - w[0] < 0.1f);
        }
        RecordButtonUi.lampWindow(RecordButtonUi.SENTRY_SWEEP_BY, w);
        assertEquals("转完三圈那一刻不露（往回走就停在这里）", w[0], w[1], 0f);
        RecordButtonUi.lampWindow((RecordButtonUi.SENTRY_SWEEP_BY + RecordButtonUi.SENTRY_FILL_BY) / 2f, w);
        assertEquals("定住：从左边铺进来", 0f, w[0], 0f);
        assertEquals(0.5f, w[1], 0.001f);
        for (float lit : new float[] {RecordButtonUi.SENTRY_FILL_BY, 0.9f, 1f}) {
            RecordButtonUi.lampWindow(lit, w);
            assertEquals(0f, w[0], 0f);
            assertEquals("铺满之后一直是整颗", 1f, w[1], 0f);
        }
        assertTrue("三道光在灯芯快定住时才开始长", RecordButtonUi.SENTRY_RAYS_FROM > RecordButtonUi.SENTRY_SWEEP_BY
                && RecordButtonUi.SENTRY_RAYS_FROM < RecordButtonUi.SENTRY_FILL_BY
                && RecordButtonUi.SENTRY_RAYS_BY < 1f);
    }

    /** 点亮的过渡按段走：每一段在它那一截里从 0 走到 1，截外不动。 */
    @Test
    public void eachPartOfTheLightingRunsInItsOwnSpan() {
        assertEquals(0f, RecordButtonUi.part(0f, 0.35f, 0.8f), 0f);
        assertEquals(0f, RecordButtonUi.part(0.35f, 0.35f, 0.8f), 0f);
        assertEquals(0.5f, RecordButtonUi.part(0.575f, 0.35f, 0.8f), 0.001f);
        assertEquals(1f, RecordButtonUi.part(0.8f, 0.35f, 0.8f), 0f);
        assertEquals(1f, RecordButtonUi.part(1f, 0.35f, 0.8f), 0f);
        assertEquals(1f, RecordButtonUi.part(0.45f, 0f, 0.45f), 0f);
    }

    /** 已录多久：不到一小时「mm:ss」，满一小时「h:mm:ss」。 */
    @Test
    public void recordedTimeGrowsAnHourField() {
        assertEquals("00:00", RecordButtonUi.clock(0));
        assertEquals("00:42", RecordButtonUi.clock(42_999));
        assertEquals("12:34", RecordButtonUi.clock(754_000));
        assertEquals("59:59", RecordButtonUi.clock(3_599_000));
        assertEquals("1:00:00", RecordButtonUi.clock(3_600_000));
        assertEquals("12:03:04", RecordButtonUi.clock((12 * 3600 + 3 * 60 + 4) * 1000L));
    }
}
