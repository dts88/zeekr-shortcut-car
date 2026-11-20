/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 连着崩溃就不再自动回来（{@link CrashHold}，项目所有者 2026-11-21）：崩一次不按住（偶尔一次照样被周期任务带回来），
 * 连着两次才按住 —— 一遍遍崩、一遍遍被拉回来，可能把容器也带崩。
 */
public class CrashHoldTest {

    @Test
    public void twoCrashesInARowHold() {
        assertEquals(2, CrashHold.MAX);
        assertFalse(CrashHold.holds(0));
        assertFalse("偶尔崩一次：照样回来", CrashHold.holds(1));
        assertTrue(CrashHold.holds(2));
        assertTrue(CrashHold.holds(5));
    }

    /** 清零要等进程好好跑满一阵：刚起来就算好了的话，起来就崩的那种永远数不到两次。 */
    @Test
    public void aHealthyRunTakesMinutesNotSeconds() {
        assertTrue(CrashHold.HEALTHY_MS >= 60_000L);
    }
}
