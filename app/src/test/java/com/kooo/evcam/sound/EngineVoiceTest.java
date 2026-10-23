package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * 合成器：听起来对不对要上车听，这里钉的是听不出来、但一错就出事的几件 —— 峰值永远不超过 −3 dBFS、转速突变不出「咔哒」、
 * 静音就是真的 0、点火阶次是主体、各层（起动机、倒挡啸叫、阀门、负荷提亮）真的起作用、同样的输入出同样的声音。
 */
public class EngineVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;
    private static final SoundProfile P = SoundProfile.V8;

    private static EngineVoice.Params params(float rpm, float load, float level, float gain) {
        EngineVoice.Params p = new EngineVoice.Params();
        p.rpm = rpm;
        p.load = load;
        p.level = level;
        p.gain = gain;
        return p;
    }

    private static float[] render(EngineVoice voice, EngineVoice.Params p, float seconds) {
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[blocks * BLOCK];
        for (int b = 0; b < blocks; b++) {
            voice.render(out, b * BLOCK, BLOCK, p);
        }
        return out;
    }

    /** 跳过开头那一段（平滑还没到位），从 {@code fromSeconds} 起。 */
    private static float[] tail(float[] x, float fromSeconds) {
        int from = Math.round(fromSeconds * RATE);
        float[] out = new float[x.length - from];
        System.arraycopy(x, from, out, 0, out.length);
        return out;
    }

    /** 某一个频率上的功率（Goertzel）。 */
    private static double power(float[] x, double hz) {
        double c = 2.0 * Math.cos(2.0 * Math.PI * hz / RATE);
        double s1 = 0;
        double s2 = 0;
        for (float v : x) {
            double s0 = v + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }

    private static float maxStep(float[] x, int from, int to) {
        float max = 0f;
        for (int i = Math.max(1, from); i < to; i++) {
            max = Math.max(max, Math.abs(x[i] - x[i - 1]));
        }
        return max;
    }

    @Test
    public void theFiringOrderCarriesTheSound() {
        // 3000 rpm：曲轴 50 Hz，V8 点火阶次（4 阶）200 Hz，2 阶 100 Hz；237.5 Hz 夹在阶次之间
        float[] x = tail(render(new EngineVoice(P, RATE), params(3000f, 1f, 1f, 0.5f), 1.2f), 0.2f);
        double gap = power(x, 237.5);
        assertTrue("点火阶次是主体", power(x, 200) > 1000 * gap);
        assertTrue("2 阶也在", power(x, 100) > 1000 * gap);
    }

    @Test
    public void anRpmJumpDoesNotClick() {
        EngineVoice voice = new EngineVoice(P, RATE);
        float[] a = render(voice, params(1000f, 1f, 1f, 1f), 0.3f);
        float[] b = render(voice, params(6000f, 1f, 1f, 1f), 0.3f);
        float[] x = new float[a.length + b.length];
        System.arraycopy(a, 0, x, 0, a.length);
        System.arraycopy(b, 0, x, a.length, b.length);
        float atJump = maxStep(x, a.length - BLOCK, a.length + 2 * BLOCK);
        float steady = maxStep(x, a.length + RATE * 15 / 100, x.length);
        assertTrue("转速从 1000 跳到 6000，那一下的样本变化不比稳定在 6000 时大: " + atJump + " vs " + steady,
                atJump <= steady * 1.5f);
    }

    @Test
    public void thePeakNeverExceedsTheCeiling() {
        EngineVoice voice = new EngineVoice(P, RATE);
        Random random = new Random(1);
        float[] out = new float[BLOCK];
        EngineVoice.Params p = new EngineVoice.Params();
        for (int i = 0; i < 800; i++) {
            p.rpm = random.nextFloat() * 7000f;
            p.load = random.nextFloat();
            p.level = random.nextFloat() * 2f;
            p.gain = random.nextFloat();
            p.brightDb = random.nextFloat() * 5f;
            p.intakeBoostDb = random.nextFloat() * 6f;
            p.burble = random.nextFloat();
            p.starter = random.nextFloat();
            p.whineHz = random.nextFloat() * 1100f;
            p.whineAmp = random.nextFloat();
            p.toneHz = 300f + random.nextFloat() * 19_700f;
            voice.render(out, 0, BLOCK, p);
            for (float v : out) {
                assertTrue("峰值 " + v, Math.abs(v) <= EngineVoice.CEILING + 1e-4f);
            }
        }
    }

    @Test
    public void zeroGainIsTrueSilence() {
        float[] x = render(new EngineVoice(P, RATE), params(3000f, 1f, 1f, 0f), 0.1f);
        for (float v : x) {
            assertEquals(0f, v, 0f);
        }
    }

    @Test
    public void theSameInputGivesTheSameSound() {
        float[] a = render(new EngineVoice(P, RATE), params(3000f, 0.7f, 1f, 0.5f), 0.1f);
        float[] b = render(new EngineVoice(P, RATE), params(3000f, 0.7f, 1f, 0.5f), 0.1f);
        assertArrayEquals(a, b, 0f);
    }

    @Test
    public void theStarterIsHeardWhileCranking() {
        EngineVoice.Params on = params(250f, 0.12f, 0.15f, 1f);
        on.starter = 1f;
        EngineVoice.Params off = params(250f, 0.12f, 0.15f, 1f);
        float[] withStarter = tail(render(new EngineVoice(P, RATE), on, 0.5f), 0.1f);
        float[] without = tail(render(new EngineVoice(P, RATE), off, 0.5f), 0.1f);
        assertTrue(power(withStarter, EngineVoice.STARTER_WHINE_HZ) > 100 * power(without, EngineVoice.STARTER_WHINE_HZ));
    }

    @Test
    public void reverseGetsItsGearWhine() {
        EngineVoice.Params on = params(1000f, 0.2f, 0.1f, 1f);
        on.whineHz = 500f;
        on.whineAmp = 1f;
        EngineVoice.Params off = params(1000f, 0.2f, 0.1f, 1f);
        off.whineHz = 500f;
        float[] whine = tail(render(new EngineVoice(P, RATE), on, 0.5f), 0.1f);
        float[] plain = tail(render(new EngineVoice(P, RATE), off, 0.5f), 0.1f);
        assertTrue(power(whine, 500) > 100 * power(plain, 500));
    }

    @Test
    public void aClosedValveMufflesTheHighOrders() {
        EngineVoice.Params closed = params(4000f, 1f, 1f, 1f);
        closed.toneHz = 400f;
        float[] open = tail(render(new EngineVoice(P, RATE), params(4000f, 1f, 1f, 1f), 0.5f), 0.1f);
        float[] muffled = tail(render(new EngineVoice(P, RATE), closed, 0.5f), 0.1f);
        // 4000 rpm：16 阶 1066.7 Hz
        assertTrue(power(open, 4000.0 / 60 * 16) > 10 * power(muffled, 4000.0 / 60 * 16));
    }

    @Test
    public void loadBrightensTheHighOrders() {
        float[] full = tail(render(new EngineVoice(P, RATE), params(3000f, 1f, 1f, 1f), 0.6f), 0.1f);
        float[] light = tail(render(new EngineVoice(P, RATE), params(3000f, 0.1f, 1f, 1f), 0.6f), 0.1f);
        // 12 阶 600 Hz 相对 4 阶 200 Hz
        double fullRatio = power(full, 600) / power(full, 200);
        double lightRatio = power(light, 600) / power(light, 200);
        assertTrue("负荷大时高阶次相对更响", fullRatio > 2 * lightRatio);
    }

    @Test
    public void theLimiterIsMonotonicAndBounded() {
        float last = -1f;
        for (int i = -1000; i <= 1000; i++) {
            float y = EngineVoice.limit(i / 100f);
            assertTrue(Math.abs(y) <= EngineVoice.CEILING + 1e-6f);
            assertTrue("单调", y >= last - 1e-6f);
            last = y;
        }
        assertEquals("小信号原样", 0.01f, EngineVoice.limit(0.01f), 1e-4f);
    }

    @Test
    public void ordersAboveTheBandFadeOut() {
        assertEquals(1f, EngineVoice.bandWeight(1000f), 0f);
        assertEquals(1f, EngineVoice.bandWeight(EngineVoice.BAND_FADE_HZ), 0f);
        assertEquals(0.5f, EngineVoice.bandWeight((EngineVoice.BAND_FADE_HZ + EngineVoice.BAND_TOP_HZ) / 2f), 1e-6f);
        assertEquals(0f, EngineVoice.bandWeight(EngineVoice.BAND_TOP_HZ), 0f);
    }
}
