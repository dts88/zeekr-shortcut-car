/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;

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
        int key = 164;  // 中文两行字的那个键：82 dp
        assertEquals(ROW, RecordKeyRow.recordWidth(ROW, GUTTER, key, 0f));
        assertEquals(ROW - key - GUTTER, RecordKeyRow.recordWidth(ROW, GUTTER, key, 1f));
        assertEquals(ROW - (key + GUTTER) / 2, RecordKeyRow.recordWidth(ROW, GUTTER, key, 0.5f));
        assertEquals("那个键看不见时量出来是 0：不让", ROW, RecordKeyRow.recordWidth(ROW, GUTTER, 0, 0f));
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
