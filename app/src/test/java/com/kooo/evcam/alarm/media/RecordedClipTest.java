/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 从盘上的录像里切那一刻的短视频（{@link RecordedClip}）：窗口怎么算、哪个文件盖住那一刻、窗口跨接缝时夹到哪。
 *
 * <p>切录像的那一小段（{@code MediaExtractor} 读、{@code MediaMuxer} 写）在这里测不了：
 * 单元测试跑在 JVM 上，没有真的 mp4、也没有系统 muxer。它只能在车机上验 —— 这一点写在报告里，
 * 不拿「测试绿了」冒充「muxer 对了」。这里锁的是算术：算错了，片子要么短一截要么整段都不是那一刻。</p>
 *
 * The clip cut out of the recordings on storage ({@link RecordedClip}): how the window is computed,
 * which file covers the moment, and where a window straddling a boundary is clamped. The muxing
 * itself ({@code MediaExtractor} read, {@code MediaMuxer} write) cannot be tested here: the unit
 * tests run on the JVM with no real MP4 and no system muxer. It is verified on the head unit only —
 * stated in the report rather than dressed up as "the tests are green, so the muxer is right". What is
 * locked here is the arithmetic: get it wrong and the clip is either short or about another moment.
 */
public class RecordedClipTest {

    private static final long SEC = 1_000L;
    /** 一整条录像通常 1 分钟一段；这里不必跟它对齐，够说明问题。 */
    private static final long MIN = 60 * SEC;

    // ---------- 窗口 ----------

    @Test
    public void theWindowIsCentredOnTheMoment() {
        long moment = 1_700_000_000_000L;
        assertArrayEquals(new long[] {moment - 5 * SEC, moment + 5 * SEC},
                RecordedClip.window(moment, 10));
        // 奇数秒按毫秒折半：11 秒是 ±5.5 秒，两边仍对称
        // Odd seconds halve in milliseconds: 11 s is ±5.5 s, still symmetric
        assertArrayEquals(new long[] {moment - 5_500L, moment + 5_500L},
                RecordedClip.window(moment, 11));
    }

    @Test
    public void aWindowNeedsAMomentAndALength() {
        assertNull(RecordedClip.window(0L, 10));
        assertNull(RecordedClip.window(-1L, 10));
        assertNull(RecordedClip.window(1_700_000_000_000L, 0));
        assertNull(RecordedClip.window(1_700_000_000_000L, -5));
    }

    // ---------- 一个文件盖不盖住那一刻 ----------

    @Test
    public void aMomentInsideTheFileIsCovered() {
        assertTrue(RecordedClip.contains(1_000 * SEC, MIN, 1_000 * SEC));
        assertTrue(RecordedClip.contains(1_000 * SEC, MIN, 1_030 * SEC));
    }

    @Test
    public void theEndOfAFileBelongsToTheNextOne() {
        // 结束那一刻不算：下一个文件从那里开始，否则两个文件会同时说「那一刻是我的」
        // The end instant is not covered: the next file starts there, or both would claim the moment
        assertFalse(RecordedClip.contains(1_000 * SEC, MIN, 1_060 * SEC));
    }

    @Test
    public void aMomentBeforeTheFileOrWithoutADurationIsNotCovered() {
        assertFalse(RecordedClip.contains(1_000 * SEC, MIN, 999 * SEC));
        assertFalse(RecordedClip.contains(1_000 * SEC, 0L, 1_030 * SEC));
        assertFalse(RecordedClip.contains(1_000 * SEC, -5L, 1_030 * SEC));
        assertFalse(RecordedClip.contains(-1L, MIN, 1_030 * SEC));  // 名字读不出开录时刻
    }

    // ---------- 一个文件盖不盖住整个窗口 ----------

    @Test
    public void aWholeWindowInsideTheFileCovers() {
        assertTrue(RecordedClip.covers(1_000 * SEC, MIN, 1_020 * SEC, 1_030 * SEC));
        // 正好顶到两头也算盖住：切的时候不用夹
        // Touching both ends still counts: nothing has to be clamped out
        assertTrue(RecordedClip.covers(1_000 * SEC, MIN, 1_000 * SEC, 1_060 * SEC));
    }

    @Test
    public void aWindowHangingOverAnEndIsNotCovered() {
        assertFalse(RecordedClip.covers(1_000 * SEC, MIN, 1_050 * SEC, 1_070 * SEC));
        assertFalse(RecordedClip.covers(1_000 * SEC, MIN, 990 * SEC, 1_010 * SEC));
        assertFalse(RecordedClip.covers(1_000 * SEC, MIN, 1_030 * SEC, 1_030 * SEC));  // 空窗口不算
    }

    // ---------- 哪个文件盖住那一刻 ----------

    @Test
    public void theFileHoldingTheMomentIsPicked() {
        long[] starts = {1_000 * SEC, 1_060 * SEC, 1_120 * SEC};
        long[] durations = {MIN, MIN, MIN};
        assertEquals(0, RecordedClip.containing(starts, durations, 1_030 * SEC));
        assertEquals(1, RecordedClip.containing(starts, durations, 1_060 * SEC));
        assertEquals(2, RecordedClip.containing(starts, durations, 1_179 * SEC));
    }

    @Test
    public void aGapBetweenRecordingsHasNoFile() {
        // 停过录制：1_060 结束，1_100 才又开 —— 中间那一刻谁都不认
        // Recording stopped: 1_060 ends, 1_100 starts again — no file claims the moment between them
        long[] starts = {1_000 * SEC, 1_100 * SEC};
        long[] durations = {MIN, MIN};
        assertEquals(-1, RecordedClip.containing(starts, durations, 1_080 * SEC));
        assertEquals(-1, RecordedClip.containing(starts, durations, 900 * SEC));
    }

    @Test
    public void overlappingRecordingsPickTheOneThatStartedLatest() {
        // 两路相机各写一个文件时会重叠；取开录晚的那条，它离那一刻最近
        // Two lanes writing separate files overlap; the later start wins because it is nearest the moment
        long[] starts = {1_000 * SEC, 1_005 * SEC};
        long[] durations = {MIN, MIN};
        assertEquals(1, RecordedClip.containing(starts, durations, 1_030 * SEC));
    }

    @Test
    public void badInputHasNoFile() {
        assertEquals(-1, RecordedClip.containing(null, null, 1_030 * SEC));
        assertEquals(-1, RecordedClip.containing(new long[0], new long[0], 1_030 * SEC));
        assertEquals(-1, RecordedClip.containing(new long[] {1_000 * SEC}, new long[0], 1_030 * SEC));
    }

    // ---------- 窗口跨接缝时夹到哪 ----------

    @Test
    public void aWindowInsideOneFileIsKeptWhole() {
        assertArrayEquals(new long[] {1_020 * SEC, 1_030 * SEC},
                RecordedClip.spanInside(1_000 * SEC, MIN, 1_020 * SEC, 1_030 * SEC));
    }

    @Test
    public void aWindowHangingOverTheEndIsCutBackToTheFile() {
        // 那一刻在 1_055，要的是 ±5 秒 → 1_050..1_060；这个文件 1_060 就结束了，结束那头正好顶住
        // The moment is 1_055 and ±5 s asks for 1_050..1_060; this file ends exactly at 1_060
        assertArrayEquals(new long[] {1_050 * SEC, 1_060 * SEC},
                RecordedClip.spanInside(1_000 * SEC, MIN, 1_050 * SEC, 1_060 * SEC));
        // 跨进下一段的四秒不要：于是片子只有 6 秒，不比要的 10 秒 —— 但接缝那一边一句声音也不混进来
        // The 4 s past the boundary is dropped, so a 6 s clip instead of 10 s — but not a syllable from the other side leaks in
        assertArrayEquals(new long[] {1_054 * SEC, 1_060 * SEC},
                RecordedClip.spanInside(1_000 * SEC, MIN, 1_054 * SEC, 1_064 * SEC));
    }

    @Test
    public void aWindowHangingOverTheStartIsCutBackToTheFile() {
        assertArrayEquals(new long[] {1_000 * SEC, 1_006 * SEC},
                RecordedClip.spanInside(1_000 * SEC, MIN, 996 * SEC, 1_006 * SEC));
    }

    @Test
    public void aWindowThatMissesTheFileEntirelyHasNothing() {
        assertNull(RecordedClip.spanInside(1_000 * SEC, MIN, 1_070 * SEC, 1_080 * SEC));
        assertNull(RecordedClip.spanInside(1_000 * SEC, MIN, 990 * SEC, 1_000 * SEC));
        assertNull(RecordedClip.spanInside(1_000 * SEC, 0L, 1_000 * SEC, 1_010 * SEC));
        assertNull(RecordedClip.spanInside(1_000 * SEC, MIN, 1_030 * SEC, 1_030 * SEC));
    }
}
