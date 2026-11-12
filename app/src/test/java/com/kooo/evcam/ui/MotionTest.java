/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link Motion}：时长只有三档，最长 300 ms（ui-spec §十四）。 */
public class MotionTest {

    @Test
    public void threeTiersNoLongerThan300() {
        assertTrue(Motion.FAST < Motion.MEDIUM);
        assertTrue(Motion.MEDIUM < Motion.SLOW);
        assertTrue(Motion.SLOW <= 300);
    }

    /** 窗口动效的时长写在 res/values/motion.xml（XML 引用不到 Motion）：两边得一样。 */
    @Test
    public void windowAnimationDurationsMatch() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/values/motion.xml")),
                StandardCharsets.UTF_8);
        assertEquals(Motion.FAST, integer(xml, "motion_fast"));
        assertEquals(Motion.MEDIUM, integer(xml, "motion_medium"));
        assertEquals(Motion.COLLAPSE, integer(xml, "motion_collapse"));
    }

    private static long integer(String xml, String name) {
        Matcher matcher = Pattern.compile("<integer name=\"" + name + "\">(\\d+)</integer>").matcher(xml);
        assertTrue("motion.xml 里没有 " + name, matcher.find());
        return Long.parseLong(matcher.group(1));
    }

    /** 往前一步、往回一步：旧的退、新的进，合起来是最长的一档；退的那一半短。 */
    @Test
    public void steppingFitsInTheLongestTier() {
        assertTrue(Motion.LEAVE < Motion.SLOW - Motion.LEAVE);
    }

    @Test
    public void collapsingIsNotSlowerThanExpanding() {
        assertTrue(Motion.COLLAPSE <= Motion.MEDIUM);
    }
}
