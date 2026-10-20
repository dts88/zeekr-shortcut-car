package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 声音和画面对时间：推错了，声音在录像里就前后错开 —— 关车门的声音比画面早或晚。错开的方向和大小都钉在这里。
 */
public class FootageAudioMathTest {

    private static final long SEC = 1_000_000_000L;

    @Test
    public void framesAfterTheAnchorAreLaterBySampleRate() {
        // 第 48000 帧在 100 秒采到：第 96000 帧是 101 秒，第 24000 帧是 99.5 秒
        assertEquals(101 * SEC, FootageAudioMath.captureNs(100 * SEC, 48_000, 96_000, 48_000));
        assertEquals(99 * SEC + SEC / 2, FootageAudioMath.captureNs(100 * SEC, 48_000, 24_000, 48_000));
        assertEquals(100 * SEC, FootageAudioMath.captureNs(100 * SEC, 48_000, 48_000, 48_000));
    }

    @Test
    public void tellsWhichClockTheCameraUses() {
        // 开机 1000 秒，睡过 600 秒：BOOTTIME 1000 秒，MONOTONIC 400 秒。刚到的那一帧早几十毫秒
        long boot = 1000 * SEC;
        long mono = 400 * SEC;
        assertTrue(FootageAudioMath.cameraUsesMonotonic(mono - SEC / 30, boot, mono));
        assertFalse(FootageAudioMath.cameraUsesMonotonic(boot - SEC / 30, boot, mono));
    }

    @Test
    public void boottimeMovesOntoTheCameraClock() {
        long boot = 1000 * SEC;
        long mono = 400 * SEC;
        // 声音在 BOOTTIME 999 秒采到：相机用 MONOTONIC 的话是 399 秒，用 BOOTTIME 就是 999 秒
        assertEquals(399 * SEC, FootageAudioMath.toCameraClock(999 * SEC, true, boot, mono));
        assertEquals(999 * SEC, FootageAudioMath.toCameraClock(999 * SEC, false, boot, mono));
    }

    @Test
    public void levelsAreInDecibelsBelowFullScale() {
        assertEquals(FootageAudioMath.SILENT_DB, FootageAudioMath.dbfs(0, 1024));
        assertEquals(FootageAudioMath.SILENT_DB, FootageAudioMath.dbfs(5, 0));
        // 满幅方波：0 dB；一半：约 -6 dB
        assertEquals(0, FootageAudioMath.dbfs(32768.0 * 32768.0 * 10, 10));
        assertEquals(-6, FootageAudioMath.dbfs(16384.0 * 16384.0 * 10, 10));
    }

    @Test
    public void readsLittleEndianSixteenBitSamples() {
        byte[] pcm = {(byte) 0xFF, 0x7F, 0x00, (byte) 0x80, (byte) 0xFF, (byte) 0xFF};
        assertEquals(32767, FootageAudioMath.sample16(pcm, 0));
        assertEquals(-32768, FootageAudioMath.sample16(pcm, 2));
        assertEquals(-1, FootageAudioMath.sample16(pcm, 4));
    }
}
