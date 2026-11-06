/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 录像要不要读后视镜上的灯：四盏里勾了任意一盏才读（项目所有者 2026-11-03：允许不勾，不勾的维持原来的转向灯，
 * 也就用不着读）。
 */
public class InfoBarTest {

    @Test
    public void lampsAreReadOnlyWhenOneOfThemIsTicked() {
        Set<String> defaults = InfoBarLayout.defaultSelection();
        assertTrue("默认勾着", InfoBar.wantsLamps(defaults));
        Set<String> without = new LinkedHashSet<>(defaults);
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.SETTING) {
                without.remove(s.name());
            }
        }
        assertTrue(without.contains(Signal.INDICATOR.name()));
        assertFalse("转向灯勾着、四盏灯都不勾：不读", InfoBar.wantsLamps(without));
        assertFalse(InfoBar.wantsLamps(Collections.<String>emptySet()));
        assertTrue("勾了一盏就读", InfoBar.wantsLamps(Collections.singleton(Signal.DOOR_WARNING_RIGHT.name())));
    }
}
