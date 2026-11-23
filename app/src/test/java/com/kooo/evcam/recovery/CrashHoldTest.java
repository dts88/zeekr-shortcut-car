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
 * 一次接一次的运行都是崩溃结束的，连着两次才按住 —— 一遍遍崩、一遍遍被拉回来，可能把容器也带崩。
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

    /** 「连着」是一次接一次的运行都崩：中间有一次不是崩溃结束的（被系统收走、车机下电），之前的不算。 */
    @Test
    public void aRunThatDidNotCrashBreaksTheRow() {
        assertEquals(1, CrashHold.inARow(true, 1));
        assertEquals(2, CrashHold.inARow(true, 2));
        assertEquals("上一次运行没崩：从头数", 0, CrashHold.inARow(false, 1));
        assertEquals(0, CrashHold.inARow(false, 7));
        assertEquals(0, CrashHold.inARow(false, 0));
    }

    /** 走一遍：崩、被带回来又崩、第三次起来按住；隔了一次没崩的运行，几天后再崩一次不会被按住。 */
    @Test
    public void walkThrough() {
        int stored = 0;
        // 第一次运行崩了
        stored = CrashHold.inARow(false, stored) + 1;
        assertFalse(CrashHold.holds(CrashHold.inARow(true, stored)));
        // 被带回来，又崩了
        stored = CrashHold.inARow(true, stored) + 1;
        assertTrue("连着两次：这一次起来按住", CrashHold.holds(CrashHold.inARow(true, stored)));

        // 另一种：崩一次，下一次运行好好的（没崩就结束了），再下一次运行崩
        int other = CrashHold.inARow(false, 0) + 1;
        other = CrashHold.inARow(true, other);
        assertEquals(1, other);
        other = CrashHold.inARow(false, other) + 1;
        assertFalse("中间隔了一次没崩的：不算连着", CrashHold.holds(CrashHold.inARow(true, other)));
    }
}
