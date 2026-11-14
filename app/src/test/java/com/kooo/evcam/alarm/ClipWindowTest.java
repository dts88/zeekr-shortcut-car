/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 从已编码的样本里切最后几秒（{@link ClipWindow}）：开头必须是关键帧，声音跟着画面走。
 */
public class ClipWindowTest {

    /** 1 fps 的画面，关键帧每秒一个：0、1 s、2 s…… */
    private static long[] pictures(int count) {
        long[] pts = new long[count];
        for (int i = 0; i < count; i++) {
            pts[i] = i * 1_000_000L;
        }
        return pts;
    }

    private static boolean[] allKeyframes(int count) {
        boolean[] keys = new boolean[count];
        java.util.Arrays.fill(keys, true);
        return keys;
    }

    private static boolean[] noAudio(int count) {
        return new boolean[count];
    }

    @Test
    public void fiveSecondsEndsAtTheNewestFrameAndStartsOnAKeyframe() {
        long[] pts = pictures(60);                       // 60 s
        ClipWindow.Cut cut = ClipWindow.choose(pts, allKeyframes(60), noAudio(60),
                59_000_000L, 5_000_000L);
        // 窗口起点是 54 s：从不晚于它的那个关键帧开始，宁可比 5 秒多一帧
        assertEquals(54, cut.from);
        assertEquals("到最后", 60, cut.to);
        assertEquals(pts[54], cut.baseUs);
    }

    @Test
    public void aWindowWithoutAnInsideKeyframeStartsAtTheFirstKeyframeInstead() {
        // 只有最后一帧是关键帧：往里找不到，只能从它开始（宁可比 5 秒短）
        long[] pts = pictures(60);
        boolean[] keys = new boolean[60];
        keys[59] = true;
        ClipWindow.Cut cut = ClipWindow.choose(pts, keys, noAudio(60), 59_000_000L, 5_000_000L);
        assertEquals(59, cut.from);
        assertEquals(60, cut.to);
    }

    @Test
    public void noKeyframesAtAllMeansNoClip() {
        ClipWindow.Cut cut = ClipWindow.choose(pictures(60), new boolean[60], noAudio(60),
                59_000_000L, 5_000_000L);
        assertTrue(cut.isEmpty());
        assertEquals(0, cut.count());
    }

    @Test
    public void audioSamplesInTheWindowRideAlong() {
        // 30 帧画面，每帧后面跟一个声音样本
        long[] pts = new long[60];
        boolean[] keys = new boolean[60];
        boolean[] audio = new boolean[60];
        for (int i = 0; i < 30; i++) {
            pts[i * 2] = i * 1_000_000L;
            keys[i * 2] = true;
            pts[i * 2 + 1] = i * 1_000_000L + 500_000L;
            audio[i * 2 + 1] = true;
        }
        ClipWindow.Cut cut = ClipWindow.choose(pts, keys, audio, 29_000_000L, 5_000_000L);
        // 窗口起点 24 s：画面索引 48（第 24 帧），声音跟在后面
        assertEquals(48, cut.from);
        assertEquals("最后一帧画面在 58，它后面那个声音样本不算", 59, cut.to);
        assertTrue(ClipWindow.hasAudio(java.util.Arrays.asList(
                audio[cut.from], audio[cut.from + 1], audio[cut.to - 1])));
    }

    @Test
    public void relativeTimeStartsAtZeroAndNeverGoesBackwards() {
        assertEquals(0L, ClipWindow.relativeMs(55_000_000L, 55_000_000L));
        assertEquals(250L, ClipWindow.relativeMs(55_250_000L, 55_000_000L));
        assertEquals("夹在基准之前的也算 0", 0L, ClipWindow.relativeMs(54_000_000L, 55_000_000L));
    }

    @Test
    public void badInputIsAnEmptyCutNotACrash() {
        assertTrue(ClipWindow.choose(null, null, null, 0, 0).isEmpty());
        assertTrue(ClipWindow.choose(new long[0], new boolean[0], new boolean[0], 0, 0).isEmpty());
        assertTrue(ClipWindow.choose(pictures(5), new boolean[5], new boolean[5], 0, 5_000_000L).isEmpty());
        assertTrue(ClipWindow.choose(pictures(5), allKeyframes(5), noAudio(5), 4_000_000L, 0).isEmpty());
        assertFalse(ClipWindow.hasAudio(null));
    }
}
