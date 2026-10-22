package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「超音速（旧版）」（2026-10-22 的那一版，留着和新的装车对比）：只看不出事 —— 峰值永远不超过上限、一直是有限的数，静音就是真的 0，
 * 同样的输入出同样的声音。它的音色当时的测试在提交 0bfead5 的 JetVoiceTest 里。
 */
public class JetOldVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;

    private static VoiceParams params(float rpm, float load, boolean drive) {
        VoiceParams p = new VoiceParams();
        p.rpm = rpm;
        p.load = load;
        p.level = 1f;
        p.gain = 1f;
        p.drive = drive;
        return p;
    }

    private static float[] render(VoiceParams p, float seconds) {
        JetOldVoice voice = new JetOldVoice(RATE);
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[2 * blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            voice.renderStereo(block, BLOCK, p);
            System.arraycopy(block, 0, out, 2 * b * BLOCK, 2 * BLOCK);
        }
        return out;
    }

    @Test
    public void neverAboveTheCeilingAndAlwaysFinite() {
        JetOldVoice voice = new JetOldVoice(RATE);
        VoiceParams p = new VoiceParams();
        float[] block = new float[2 * BLOCK];
        long seed = 7L;
        for (int b = 0; b < 2000; b++) {
            if (b % 20 == 0) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int r = (int) (seed >>> 33);
                p.rpm = (r & 8191);
                p.load = ((r >> 3) & 255) / 170f - 0.25f;
                p.cut = ((r >> 6) & 15) / 15f;
                p.level = ((r >> 11) & 63) / 16f;
                p.gain = ((r >> 17) & 31) / 16f;
                p.toneHz = 300f + ((r >> 2) & 1023) * 20f;
                p.drive = (r & 0x100000) != 0;
                p.shiftSerial += (r >> 9) & 1;
                p.shiftUp = (r & 0x200000) != 0;
            }
            voice.renderStereo(block, BLOCK, p);
            for (float s : block) {
                assertFalse(Float.isNaN(s) || Float.isInfinite(s));
                assertTrue(Math.abs(s) <= EngineVoice.CEILING + 1e-6f);
            }
        }
    }

    @Test
    public void silenceIsExactlyZero() {
        VoiceParams p = params(3000f, 1f, true);
        p.gain = 0f;
        for (float s : render(p, 0.2f)) {
            assertEquals(0f, s, 0f);
        }
    }

    @Test
    public void theSameInputGivesTheSameSound() {
        assertArrayEquals(render(params(2500f, 0.7f, true), 0.3f), render(params(2500f, 0.7f, true), 0.3f), 0f);
    }
}
