package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * 输出前的限幅（{@link Limiter}）：峰值永远不超过上限；小信号只晚一点、原样出来；大信号只降增益、不改波形（削过的正弦还是正弦）；
 * 两个峰之间增益不回弹；峰过去增益回来；左右一起降；清空后是 0。
 */
public class LimiterTest {

    private static final int RATE = 48_000;

    @Test
    public void neverAboveTheCeiling() {
        Limiter lim = new Limiter(RATE);
        Random random = new Random(5);
        for (int i = 0; i < 5 * RATE; i++) {
            float loud = i % 20_000 < 9_000 ? 12f : 0.3f;
            lim.push((random.nextFloat() * 2f - 1f) * loud, (random.nextFloat() * 2f - 1f) * loud);
            assertTrue(Math.abs(lim.left) <= EngineVoice.CEILING);
            assertTrue(Math.abs(lim.right) <= EngineVoice.CEILING);
        }
    }

    @Test
    public void smallSignalsComeOutUnchangedAndLate() {
        Limiter lim = new Limiter(RATE);
        float[] in = new float[4000];
        for (int i = 0; i < in.length; i++) {
            in[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / RATE));
        }
        for (int i = 0; i < in.length; i++) {
            lim.push(in[i], -in[i]);
            float expected = i >= Limiter.LOOKAHEAD ? in[i - Limiter.LOOKAHEAD] : 0f;
            assertEquals(expected, lim.left, 0f);
            assertEquals(-expected, lim.right, 0f);
        }
    }

    @Test
    public void aLoudSineIsTurnedDownNotSquared() {
        // 200 Hz、上限的 3.4 倍：出来的峰值贴着上限，三次谐波很小（波形整形的限幅会把它变成方波，三次谐波约 −10 dB）
        Limiter lim = new Limiter(RATE);
        int n = RATE;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            lim.push((float) (3.0 * Math.sin(2 * Math.PI * 200 * i / RATE)));
            out[i] = lim.left;
        }
        int from = RATE / 2;
        float peak = 0f;
        for (int i = from; i < n; i++) {
            peak = Math.max(peak, Math.abs(out[i]));
        }
        assertEquals("峰值贴着上限", EngineVoice.CEILING, peak, 0.01f);
        double third = power(out, from, n, 600) / power(out, from, n, 200);
        assertTrue("三次谐波 " + 10 * Math.log10(third) + " dB", third < 1e-4);
    }

    @Test
    public void theGainComesBackAfterABurst() {
        Limiter lim = new Limiter(RATE);
        for (int i = 0; i < RATE / 5; i++) {
            lim.push((float) (4.0 * Math.sin(2 * Math.PI * 100 * i / RATE)));
        }
        assertTrue("压下去了", lim.gain() < 0.3f);
        for (int i = 0; i < RATE / 2; i++) {
            lim.push((float) (0.2 * Math.sin(2 * Math.PI * 100 * i / RATE)));
        }
        assertTrue("回来了: " + lim.gain(), lim.gain() > 0.99f);
    }

    @Test
    public void leftAndRightGoDownTogether() {
        Limiter lim = new Limiter(RATE);
        for (int i = 0; i < RATE / 4; i++) {
            float s = (float) Math.sin(2 * Math.PI * 300 * i / RATE);
            lim.push(5f * s, 0.5f * s);
            if (i > RATE / 8 && Math.abs(lim.right) > 1e-3f) {
                assertEquals("左右的比例不变", 10f, lim.left / lim.right, 1e-3f);
            }
        }
    }

    @Test
    public void afterAResetSilenceIsZero() {
        Limiter lim = new Limiter(RATE);
        for (int i = 0; i < 1000; i++) {
            lim.push(3f, -3f);
        }
        lim.reset();
        for (int i = 0; i < 1000; i++) {
            lim.push(0f, 0f);
            assertEquals(0f, lim.left, 0f);
            assertEquals(0f, lim.right, 0f);
        }
        assertEquals(1f, lim.gain(), 0f);
    }

    private static double power(float[] x, int from, int to, double hz) {
        double c = 2.0 * Math.cos(2.0 * Math.PI * hz / RATE);
        double s1 = 0;
        double s2 = 0;
        for (int i = from; i < to; i++) {
            double s0 = x[i] + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }
}
