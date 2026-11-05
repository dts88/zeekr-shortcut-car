/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.ChannelRules.DarkOutlook;

import org.junit.Test;

/**
 * 录制键上那行小字：相机管家说的那一种写成哪一句。2026-11-02 起熄屏怎么录只由相机管家判（项目所有者「熄屏判断收归通道调度」），
 * 规矩和它的测试在 {@code ChannelRules} / {@code RecordInDarkTest}；这里只剩句子。
 */
public class ScreenOffPlanTest {

    @Test
    public void eachOutlookHasItsOwnSentence() {
        assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(DarkOutlook.CONTINUE));
        assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(DarkOutlook.PAUSE));
        assertEquals(ScreenOffPlan.STOP, ScreenOffPlan.of(DarkOutlook.STOP));
        assertEquals(R.string.record_screen_off_continue, ScreenOffPlan.CONTINUE.text);
        assertEquals(R.string.record_screen_off_pause, ScreenOffPlan.PAUSE.text);
        assertEquals(R.string.record_screen_off_stop, ScreenOffPlan.STOP.text);
    }
}
