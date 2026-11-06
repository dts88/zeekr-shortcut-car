/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MotionPolicy} 的判断规则。 */
public class MotionPolicyTest {

    @Test
    public void idleAlwaysAnimatesWhenSystemAllows() {
        assertTrue(MotionPolicy.allows(false, true, 1f));
        assertTrue(MotionPolicy.allows(false, false, 1f));
    }

    @Test
    public void recordingSuppressesOnlyWhenTheSettingIsOn() {
        assertFalse(MotionPolicy.allows(true, true, 1f));
        assertTrue(MotionPolicy.allows(true, false, 1f));
    }

    @Test
    public void systemAnimationsOffWinsOverEverything() {
        // 用户在系统里关了动画，这个应用不能自作主张再开
        assertFalse(MotionPolicy.allows(false, false, 0f));
        assertFalse(MotionPolicy.allows(true, false, 0f));
    }
}
