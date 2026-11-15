/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;

/** 截取屏幕的文件名：时间在前，同照片、整帧原图的写法。 */
public class ScreenCaptureTest {

    @Test
    public void fileNameStartsWithTheTime() {
        Calendar when = Calendar.getInstance();
        when.set(2026, Calendar.NOVEMBER, 15, 14, 30, 5);
        assertEquals("20261115_143005_screenshot.png", ScreenCapture.fileName(when.getTime()));
    }
}
