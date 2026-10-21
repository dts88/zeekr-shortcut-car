package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「超音速」（试制）：峰值永远不超过 −3 dBFS、静音就是真的 0、同样的输入出同样的声音；主音跟着转子
 * （怠速约 530 Hz、转满约 1200 Hz，P / N 里转到 4500 就算满）；怠速的哨声听得出来；一踩油门「轰」一下；升挡「音爆」。
 * 参考量出来的数在 docs/local/drive-sound-plan.md §13，阈值的原型数在 docs/local/drive-sound/check_round6.py。
 */
public class JetVoiceTest {

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

    /** (左 + 右) / 2，{@code seconds} 长；第 {@code at} 块起负荷换成 {@code loadAfter}、换挡记号换成 {@code serialAfter}。 */
    private static float[] render(JetVoice voice, VoiceParams p, float seconds, int at, float loadAfter, int serialAfter,
            boolean up) {
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            if (b == at) {
                p.load = loadAfter;
                p.shiftSerial = serialAfter;
                p.shiftUp = up;
            }
            voice.renderStereo(block, BLOCK, p);
            for (int i = 0; i < BLOCK; i++) {
                out[b * BLOCK + i] = 0.5f * (block[2 * i] + block[2 * i + 1]);
            }
        }
        return out;
    }

    private static float[] render(VoiceParams p, float seconds) {
        return render(new JetVoice(RATE), p, seconds, -1, 0f, 0, true);
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

    private static double energy(float[] x, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) {
            e += (double) x[i] * x[i];
        }
        return e;
    }

    /** {@code lo}–{@code hi} Hz（各两个二阶）之后的那一段。 */
    private static float[] band(float[] x, float lo, float hi) {
        Biquad[] chain = {Biquad.highpass(lo, 0.7071f, RATE), Biquad.highpass(lo, 0.7071f, RATE),
                Biquad.lowpass(hi, 0.7071f, RATE), Biquad.lowpass(hi, 0.7071f, RATE)};
        float[] y = x.clone();
        for (Biquad f : chain) {
            for (int i = 0; i < y.length; i++) {
                y[i] = f.process(y[i]);
            }
        }
        return y;
    }

    // ================================================================= 不出事

    @Test
    public void neverAboveTheCeilingAndAlwaysFinite() {
        VoiceParams loud = params(7000f, 1f, true);
        loud.level = 8f;
        loud.gain = 8f;
        JetVoice v = new JetVoice(RATE);
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < 400; b++) {
            loud.shiftSerial = b / 40;
            v.renderStereo(block, BLOCK, loud);
            for (float s : block) {
                assertTrue(Math.abs(s) <= EngineVoice.CEILING + 1e-6f);
            }
        }
        JetVoice voice = new JetVoice(RATE);
        VoiceParams p = new VoiceParams();
        long seed = 7L;
        for (int b = 0; b < 2000; b++) {
            if (b % 20 == 0) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int r = (int) (seed >>> 33);
                p.rpm = (r & 8191);
                p.load = ((r >> 3) & 255) / 170f - 0.25f;
                p.overrun = ((r >> 5) & 255) / 170f - 0.25f;
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
        VoiceParams p = params(2500f, 0.7f, true);
        assertArrayEquals(render(p, 0.3f), render(params(2500f, 0.7f, true), 0.3f), 0f);
    }

    // ================================================================= 转子、哨声

    @Test
    public void theWhineFollowsTheSpool() {
        assertEquals("怠速", JetVoice.IDLE_HZ, settled(900f, 0f, true), JetVoice.IDLE_HZ * 0.03f);
        assertEquals("D 挡转满", JetVoice.TOP_HZ, settled(JetVoice.FULL_RPM, 1f, true), JetVoice.TOP_HZ * 0.03f);
        assertEquals("P 挡踩到底", JetVoice.TOP_HZ, settled(JetVoice.FREE_FULL_RPM, 1f, false), JetVoice.TOP_HZ * 0.03f);
        float light = settled(JetVoice.FREE_FULL_RPM, 0.2f, false);
        assertTrue("同样的转速，油门小转子低一截: " + light, light > 900f && light < 1100f);
    }

    private static float settled(float rpm, float load, boolean drive) {
        JetVoice v = new JetVoice(RATE);
        render(v, params(rpm, load, drive), 1f, -1, 0f, 0, true);
        return v.mainHz();
    }

    @Test
    public void theIdleWhineStandsOut() {
        // 怠速、怠速的电平：左边那台的主音比两边（±5 %）高得多（原型最差 2000 倍）
        VoiceParams p = params(900f, 0f, true);
        p.level = EngineVoice.dbToLin(VirtualEngine.LEVEL_FLOOR_DB);
        float[] x = render(p, 1f);
        int from = Math.round(0.5f * RATE);
        int to = Math.round(0.8f * RATE);
        double hz = JetVoice.IDLE_HZ * (1f - JetVoice.DETUNE);
        double sides = Math.max(power(x, from, to, hz * 0.95), power(x, from, to, hz * 1.05));
        assertTrue(power(x, from, to, hz) > 30 * sides);
    }

    // ================================================================= 喷流、换挡

    @Test
    public void aStabWhoomps() {
        // 3000 rpm 不动，第 40 块负荷 0 → 1：头 150 ms 的 40–100 Hz 比 0.7 s 之后同样长的一段大（原型十二个种子最差 2.1 倍）
        float[] x = band(render(new JetVoice(RATE), params(3000f, 0f, true), 1.4f, 40, 1f, 0, true), 40f, 100f);
        int at = 40 * BLOCK;
        int n = Math.round(0.15f * RATE);
        int later = at + Math.round(0.7f * RATE);
        assertTrue(energy(x, at, at + n) > 1.5 * energy(x, later, later + n));
    }

    @Test
    public void anUpshiftBooms() {
        // 同一套随机数、只差换挡记号：记号变之前一点不差；之后差出来的（「音爆」、一捶、「轰」）不小（原型比发动机低 9 dB），
        // 大半在 200 Hz 以下（原型 68 %）；降挡没有「音爆」，差得少
        float[] same = render(new JetVoice(RATE), params(5000f, 1f, true), 0.6f, 40, 1f, 0, true);
        float[] up = render(new JetVoice(RATE), params(5000f, 1f, true), 0.6f, 40, 1f, 1, true);
        float[] down = render(new JetVoice(RATE), params(5000f, 1f, true), 0.6f, 40, 1f, 1, false);
        int at = 40 * BLOCK;
        for (int i = 0; i < at; i++) {
            assertEquals(same[i], up[i], 0f);
        }
        int n = Math.round(0.15f * RATE);
        float[] dUp = new float[same.length];
        float[] dDown = new float[same.length];
        for (int i = 0; i < same.length; i++) {
            dUp[i] = up[i] - same[i];
            dDown[i] = down[i] - same[i];
        }
        double diff = energy(dUp, at, at + n);
        assertTrue("差得不小", diff > 0.03 * energy(same, at, at + n));
        assertTrue("大半在低频", energy(band(dUp, 20f, 200f), at, at + n) > 0.4 * diff);
        assertTrue("降挡差得少", energy(dDown, at, at + n) < 0.8 * diff);
    }
}
