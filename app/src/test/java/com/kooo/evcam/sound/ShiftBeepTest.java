package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 提示音（{@link ShiftBeep}）：算错的表现是出「咔」、把发动机顶破上限、响个不停，或者没按也响。 */
public class ShiftBeepTest {

    private static final float RATE = 48_000f;
    private static final int BLOCK = 256;
    /** 够放完一声的块数。 */
    private static final int BLOCKS = Math.round(ShiftBeep.SEC * RATE / BLOCK) + 2;

    /** 放一声，给发动机一个固定的电平，返回每个左声道样本。 */
    private static float[] play(ShiftBeep beep, float engine, float gain) {
        float[] out = new float[BLOCKS * BLOCK];
        float[] lr = new float[2 * BLOCK];
        for (int b = 0; b < BLOCKS; b++) {
            Arrays.fill(lr, engine);
            beep.mix(lr, BLOCK, gain);
            for (int i = 0; i < BLOCK; i++) {
                assertEquals("左右一样", lr[2 * i], lr[2 * i + 1], 0f);
                out[b * BLOCK + i] = lr[2 * i];
            }
        }
        return out;
    }

    @Test
    public void silentUntilStarted() {
        for (float s : play(new ShiftBeep(RATE), 0.5f, 1f)) {
            assertEquals(0.5f, s, 0f);
        }
    }

    @Test
    public void oneShortBeepThatFadesInAndOut() {
        ShiftBeep beep = new ShiftBeep(RATE);
        beep.start();
        float[] out = play(beep, 0f, 1f);
        int last = -1;
        float peak = 0f;
        for (int i = 0; i < out.length; i++) {
            if (out[i] != 0f) {
                last = i;
            }
            peak = Math.max(peak, Math.abs(out[i]));
        }
        assertEquals("短促：约 90 毫秒", ShiftBeep.SEC * RATE, last + 1, 2f);
        assertEquals(ShiftBeep.LEVEL, peak, 0.01f);
        assertTrue("开头不出咔", Math.abs(out[1]) < 0.05f);
        assertTrue("结尾不出咔", Math.abs(out[last]) < 0.05f);
        // 放完就不响了
        for (float s : play(beep, 0f, 1f)) {
            assertEquals(0f, s, 0f);
        }
    }

    @Test
    public void startingAgainPlaysFromTheTop() {
        ShiftBeep beep = new ShiftBeep(RATE);
        beep.start();
        float[] first = play(beep, 0f, 1f);
        beep.start();
        float[] second = play(beep, 0f, 1f);
        assertEquals(first[200], second[200], 0f);
    }

    /** 发动机已经顶到上限：加上这一声也不超过（发动机让开一点）。 */
    @Test
    public void neverPushesAFullEnginePastTheCeiling() {
        ShiftBeep beep = new ShiftBeep(RATE);
        beep.start();
        for (float s : play(beep, EngineVoice.CEILING, 1f)) {
            assertTrue("样本 " + s, Math.abs(s) <= EngineVoice.CEILING + 1e-4f);
        }
        beep.start();
        for (float s : play(beep, -EngineVoice.CEILING, 1f)) {
            assertTrue("样本 " + s, Math.abs(s) <= EngineVoice.CEILING + 1e-4f);
        }
    }

    /** 声音被门控关了（通话中、淡出）：这一声也没有，发动机不动。 */
    @Test
    public void followsTheGain() {
        ShiftBeep muted = new ShiftBeep(RATE);
        muted.start();
        for (float s : play(muted, 0.5f, 0f)) {
            assertEquals(0.5f, s, 0f);
        }
        ShiftBeep quiet = new ShiftBeep(RATE);
        quiet.start();
        float peak = 0f;
        for (float s : play(quiet, 0f, 0.5f)) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertEquals(ShiftBeep.LEVEL * 0.5f, peak, 0.01f);
    }
}
