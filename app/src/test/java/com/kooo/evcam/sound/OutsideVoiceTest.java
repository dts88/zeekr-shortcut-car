/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 车外语音（{@link OutsideVoice}）：放进去的那段格式对、垫的静音够、读 WAV 不读错。
 *
 * <p>算错的表现：变调（格式不对还照放）、第一个字或最后一个字被吃掉、放出一声噪音（把文件头当声音）。</p>
 */
public class OutsideVoiceTest {

    private static byte[] wav(int rate, int bits, int channels, short[] samples, boolean extraChunk) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "WAVE");
        write(body, "fmt ");
        int32(body, 16);
        int16(body, 1);
        int16(body, channels);
        int32(body, rate);
        int32(body, rate * channels * bits / 8);
        int16(body, channels * bits / 8);
        int16(body, bits);
        if (extraChunk) {
            write(body, "LIST");
            int32(body, 3);
            write(body, "abc");
            body.write(0);
        }
        write(body, "data");
        int32(body, samples.length * 2);
        for (short s : samples) {
            int16(body, s);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        write(file, "RIFF");
        int32(file, body.size());
        file.write(body.toByteArray(), 0, body.size());
        return file.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            out.write(tag.charAt(i));
        }
    }

    private static void int16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write(v >> 8 & 0xFF);
    }

    private static void int32(ByteArrayOutputStream out, int v) {
        int16(out, v & 0xFFFF);
        int16(out, v >>> 16);
    }

    @Test
    public void readsTheSamples() {
        float[] x = OutsideVoice.parseWav(wav(48_000, 16, 1, new short[] {0, 16384, -32768}, false));
        assertEquals(3, x.length);
        assertEquals(0f, x[0], 0f);
        assertEquals(0.5f, x[1], 1e-6f);
        assertEquals(-1f, x[2], 0f);
    }

    /** 中间多一块（单数大小，后面补一个字节）：跳过去，不把它当声音。 */
    @Test
    public void skipsOtherChunks() {
        float[] x = OutsideVoice.parseWav(wav(48_000, 16, 1, new short[] {16384}, true));
        assertEquals(1, x.length);
        assertEquals(0.5f, x[0], 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anotherRateIsRefused() {
        OutsideVoice.parseWav(wav(44_100, 16, 1, new short[] {0}, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void stereoIsRefused() {
        OutsideVoice.parseWav(wav(48_000, 16, 2, new short[] {0, 0}, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void somethingElseIsRefused() {
        OutsideVoice.parseWav(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
    }

    /** 放进去的那段：格式对、不是空的、响度到位又没有削波。 */
    @Test
    public void theBundledClipIsWhatThePlayerExpects() throws IOException {
        File clip = new File("src/main/res/raw/outside_voice_go_first.wav");
        if (!clip.exists()) {
            clip = new File("app/src/main/res/raw/outside_voice_go_first.wav");
        }
        float[] x = OutsideVoice.parseWav(Files.readAllBytes(clip.toPath()));
        assertEquals(2.73, x.length / (double) SoundOutput.RATE, 0.01);
        float peak = 0f;
        for (float s : x) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertEquals("峰值 -1 dBFS", EngineVoice.CEILING, peak, 0.01f);
    }

    /** 前面是静音，语音从垫完的地方起，一个样本不差，左右一样；末尾之后是静音。 */
    @Test
    public void fillsSilenceAroundTheVoice() {
        float[] voice = {0.1f, 0.2f, 0.3f};
        float[] lr = new float[12];
        OutsideVoice.fill(voice, -2, lr, 6);
        float[] expected = {0f, 0f, 0f, 0f, 0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f, 0f, 0f};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], lr[i], 0f);
        }
    }

    /** 放完还要写够静音，关输出时丢掉的才不是语音。 */
    @Test
    public void theTailOutlastsTheOutputBuffer() {
        assertTrue(OutsideVoice.TAIL_MS >= SoundOutput.MAX_BUFFER_MS);
        int voice = SoundOutput.RATE;
        assertEquals(voice + SoundOutput.RATE * (OutsideVoice.LEAD_IN_MS + OutsideVoice.TAIL_MS) / 1000,
                OutsideVoice.totalFrames(voice));
    }
}
