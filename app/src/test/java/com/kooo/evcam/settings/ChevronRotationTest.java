/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 可展开的行尾 ›：展开朝上、收着朝下，从右往左时也一样（› 已经翻成 ‹，转的方向跟着反过来）。
 * 从左往右：› 逆时针 90° 朝上、顺时针 90° 朝下；从右往左：‹ 顺时针 90° 朝上、逆时针 90° 朝下。
 */
public class ChevronRotationTest {

    @Test
    public void leftToRight() {
        assertEquals(0f, ValueActionPreference.chevronRotation(null, false), 0f);
        assertEquals(-90f, ValueActionPreference.chevronRotation(true, false), 0f);
        assertEquals(90f, ValueActionPreference.chevronRotation(false, false), 0f);
    }

    @Test
    public void rightToLeftTurnsTheOtherWay() {
        assertEquals(0f, ValueActionPreference.chevronRotation(null, true), 0f);
        assertEquals(90f, ValueActionPreference.chevronRotation(true, true), 0f);
        assertEquals(-90f, ValueActionPreference.chevronRotation(false, true), 0f);
    }
}
