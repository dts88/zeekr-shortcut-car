/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 争用日志里表上那几路怎么写：别的程序占着的、从相机列表消失的分开列，按编号排好（项目所有者 2026-11-16）；
 * 还没开始听时查相机列表当它在，照旧的说法。
 */
public class CameraContentionTest {

    /** 离车后后座舱 1 从相机列表消失、车机醒来那一下还拿着 0 和 2：「别的程序占着相机 [0, 2]、相机 [1] 不在相机列表」。 */
    @Test
    public void takenAndVanishedAreListedSeparatelyInCameraOrder() {
        List<String> took = Arrays.asList("2", "1", "0");
        Set<String> vanished = new HashSet<>(Collections.singletonList("1"));
        assertEquals("[0, 2]", CameraContention.ids(took, vanished, false));
        assertEquals("[1]", CameraContention.ids(took, vanished, true));
    }

    /** 一组里一个都没有：空的，那半句不写。 */
    @Test
    public void anEmptyGroupIsBlank() {
        Set<String> onlyOne = new HashSet<>(Collections.singletonList("1"));
        assertEquals("", CameraContention.ids(Collections.singletonList("1"), onlyOne, false));
        assertEquals("", CameraContention.ids(Collections.<String>emptyList(), new HashSet<String>(), true));
    }

    /** 还没开始听（没有 CameraManager）时查不了列表：当它在，黑匣子照旧写「别的程序拿了」。 */
    @Test
    public void beforeListeningACameraCountsAsListed() {
        assertTrue(CameraAvailabilityWatch.listed("1"));
        assertTrue(CameraAvailabilityWatch.listed(null));
    }
}
