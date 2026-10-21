package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 逐缸合成：好不好听要上车听，这里钉的是听不出来、但一错就出事或者一错就退回「单薄」的几件 —— 峰值永远不超过 −3 dBFS、
 * 静音就是真的 0、同样的输入出同样的声音；怠速以点火阶次为主，交叉平面的「突突」在（同排的遮蔽让整数阶次的边带长出来）；
 * 负荷越大越沙哑；左右两排不一样、低频左右一样；机械增压有啸叫，气流声随转速长；
 * 起动机、倒挡啸叫、开阀门、跟着挡位变的变速箱啸叫。阈值都比原型（docs/local/drive-sound/check_pulse_tests.py）
 * 换着噪声种子量出来的最差值留了至少一倍的余量；单个频点落在噪声上起伏很大，只拿来比确定的音。
 */
public class PulseVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;
    private static final PulseProfile SC = PulseProfile.SUPERCHARGED_V8;
    /** 同一台、没有机械增压：拿「有」和「没有」比。 */
    private static final PulseProfile PLAIN = new PulseProfile.Builder().build();

    private static VoiceParams params(float rpm, float load) {
        VoiceParams p = new VoiceParams();
        p.rpm = rpm;
        p.load = load;
        p.level = 1f;
        p.gain = 1f;
        return p;
    }

    /** 立体声（左右交错），{@code seconds} 长。 */
    private static float[] render(PulseVoice voice, VoiceParams p, float seconds) {
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[2 * blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            voice.renderStereo(block, BLOCK, p);
            System.arraycopy(block, 0, out, 2 * b * BLOCK, 2 * BLOCK);
        }
        return out;
    }

    private static float[] render(PulseProfile profile, VoiceParams p, float seconds) {
        return render(new PulseVoice(profile, RATE), p, seconds);
    }

    /** 从 {@code fromSeconds} 起的 (左 + 右) / 2。 */
    private static float[] mid(float[] lr, float fromSeconds) {
        return combine(lr, fromSeconds, 1f);
    }

    /** 从 {@code fromSeconds} 起的 (左 − 右) / 2。 */
    private static float[] side(float[] lr, float fromSeconds) {
        return combine(lr, fromSeconds, -1f);
    }

    private static float[] combine(float[] lr, float fromSeconds, float sign) {
        int from = Math.round(fromSeconds * RATE);
        int frames = lr.length / 2;
        float[] out = new float[frames - from];
        for (int i = from; i < frames; i++) {
            out[i - from] = 0.5f * (lr[2 * i] + sign * lr[2 * i + 1]);
        }
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

    /** 这个频率比两边（±{@code spread}）的大多少倍。 */
    private static double standsOut(float[] x, double hz, double spread) {
        return power(x, hz) / Math.max(power(x, hz * (1 - spread)), power(x, hz * (1 + spread)));
    }

    private static double energy(float[] x) {
        double e = 0;
        for (float v : x) {
            e += (double) v * v;
        }
        return e;
    }

    private static float[] filter(float[] x, Biquad... chain) {
        float[] y = x.clone();
        for (Biquad f : chain) {
            for (int i = 0; i < y.length; i++) {
                y[i] = f.process(y[i]);
            }
        }
        return y;
    }

    private static float[] highpass(float[] x, float hz) {
        return filter(x, Biquad.highpass(hz, 0.7071f, RATE), Biquad.highpass(hz, 0.7071f, RATE));
    }

    // ================================================================= 不出事

    @Test
    public void neverAboveTheCeilingAndAlwaysFinite() {
        for (PulseProfile profile : new PulseProfile[] {SC, PLAIN}) {
            VoiceParams loud = params(7000f, 1f);
            loud.level = 8f;
            loud.gain = 8f;
            loud.brightDb = 6f;
            loud.burble = 1f;
            loud.drive = true;
            loud.speedKmh = 200f;
            loud.gearIndex = 7;
            for (float s : render(profile, loud, 1f)) {
                assertTrue(Math.abs(s) <= EngineVoice.CEILING + 1e-6f);
            }
            // 每个输入都乱走一遍
            PulseVoice voice = new PulseVoice(profile, RATE);
            VoiceParams p = new VoiceParams();
            float[] block = new float[2 * BLOCK];
            long seed = 42L;
            for (int b = 0; b < 2000; b++) {
                if (b % 20 == 0) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L;
                    int r = (int) (seed >>> 33);
                    p.rpm = (r & 8191);
                    p.load = ((r >> 3) & 255) / 170f - 0.25f;
                    p.overrun = ((r >> 5) & 255) / 170f - 0.25f;
                    p.starter = (r >> 13 & 1) * ((r >> 6) & 15) / 15f;
                    p.burble = ((r >> 9) & 15) / 15f;
                    p.level = ((r >> 11) & 63) / 16f;
                    p.gain = ((r >> 17) & 31) / 16f;
                    p.brightDb = ((r >> 4) & 7);
                    p.intakeBoostDb = ((r >> 7) & 7);
                    p.toneHz = 300f + ((r >> 2) & 1023) * 20f;
                    p.whineHz = ((r >> 8) & 1023);
                    p.whineAmp = ((r >> 12) & 15) / 15f;
                    p.speedKmh = ((r >> 14) & 255) - 5f;
                    p.gearIndex = ((r >> 19) & 15) - 1;
                    p.drive = (r & 0x100000) != 0;
                }
                voice.renderStereo(block, BLOCK, p);
                for (float s : block) {
                    assertFalse(Float.isNaN(s) || Float.isInfinite(s));
                    assertTrue(Math.abs(s) <= EngineVoice.CEILING + 1e-6f);
                }
            }
        }
    }

    @Test
    public void silenceIsExactlyZero() {
        VoiceParams p = params(3000f, 1f);
        p.gain = 0f;
        for (float s : render(SC, p, 0.2f)) {
            assertEquals(0f, s, 0f);
        }
    }

    @Test
    public void theSameInputGivesTheSameSound() {
        VoiceParams p = params(2500f, 0.7f);
        p.overrun = 0.3f;
        assertArrayEquals(render(SC, p, 0.3f), render(SC, p, 0.3f), 0f);
    }

    // ================================================================= 发动机本身

    @Test
    public void theFiringOrderCarriesTheIdle() {
        // 900 rpm：曲轴 15 Hz，点火阶次（4 阶）60 Hz；3.5、4.5 阶夹在两边
        float[] x = mid(render(SC, params(900f, 0f), 2f), 0.5f);
        assertTrue(power(x, 60) > 30 * Math.max(power(x, 52.5), power(x, 67.5)));
    }

    @Test
    public void theCrossPlaneBurbleIsThere() {
        // 同一排上一个点火隔 90° 的那一下最弱、隔 270° 的最强，每转一个来回：点火阶次两边长出整数阶次的边带（3、5 阶）。
        // 关掉每一下的随机起伏，和没有这种遮蔽的同一台比（原型里差 33 倍以上）
        PulseProfile masked = new PulseProfile.Builder().variation(0f, 0f, 0f, 0f, 0f).build();
        PulseProfile flat = new PulseProfile.Builder().variation(0f, 0f, 0f, 0f, 0f).recovery(107f, 0f, 0f).build();
        assertTrue(sidebands(masked) > 10 * sidebands(flat));
        // 1-8-4-3-6-5-7-2，单号缸在左排：每排的间隔是 90、180、270、180
        PulseVoice voice = new PulseVoice(SC, RATE);
        int[] count = new int[3];
        for (int c = 1; c <= 8; c++) {
            float gap = voice.sameBankGapDeg(c);
            assertTrue("缸 " + c + " " + gap, gap == 90f || gap == 180f || gap == 270f);
            count[Math.round(gap / 90f) - 1]++;
        }
        assertArrayEquals(new int[] {2, 4, 2}, count);
    }

    /**
     * 900 rpm 怠速，同步平均 10 个周期（一个 720° 周期正好 6400 个样本，平均掉随机的那部分）之后，
     * 3、5 阶加起来相对 4 阶的功率。
     */
    private static double sidebands(PulseProfile profile) {
        float[] x = mid(render(profile, params(900f, 0f), 1.8f), 0f);
        int n = 6400;
        double[] cycle = new double[n];
        for (int c = 2; c < 12; c++) {
            for (int i = 0; i < n; i++) {
                cycle[i] += x[c * n + i] / 10.0;
            }
        }
        return (cyclePower(cycle, 6) + cyclePower(cycle, 10)) / cyclePower(cycle, 8);
    }

    /** 一个 720° 周期里第 {@code k} 个谐波（= k / 2 阶）的功率。 */
    private static double cyclePower(double[] cycle, int k) {
        double re = 0;
        double im = 0;
        for (int i = 0; i < cycle.length; i++) {
            double ph = 2.0 * Math.PI * k * i / cycle.length;
            re += cycle[i] * Math.cos(ph);
            im -= cycle[i] * Math.sin(ph);
        }
        return re * re + im * im;
    }

    @Test
    public void loadMakesItRaspier() {
        float[] light = mid(render(SC, params(2500f, 0f), 1.5f), 0.5f);
        float[] full = mid(render(SC, params(2500f, 1f), 1.5f), 0.5f);
        double lightShare = energy(highpass(light, 1000f)) / energy(light);
        double fullShare = energy(highpass(full, 1000f)) / energy(full);
        assertTrue("1 kHz 以上的份额 " + lightShare + " → " + fullShare, fullShare > 4 * lightShare);
    }

    @Test
    public void theBanksAreStereoButTheBassIsMono() {
        float[] x = render(SC, params(2500f, 0.8f), 1.5f);
        assertTrue("左右两排不一样", energy(side(x, 0.5f)) > 0.05 * energy(mid(x, 0.5f)));
        float[] idle = render(SC, params(900f, 0f), 2f);
        assertTrue("点火阶次（60 Hz）左右一样",
                power(side(idle, 0.5f), 60) < 0.01 * power(mid(idle, 0.5f), 60));
    }

    // ================================================================= 增压

    @Test
    public void theSuperchargerWhines() {
        // 5000 rpm（低转时本来就淡）：啸叫在叶片扫过出口的阶次上（11.56 阶 ≈ 963 Hz），是一个音（比两边高得多），
        // 没有机械增压的同一台那里没有（单个频点落在噪声上起伏很大，所以拿「有」和「没有」比，不拿没有的那台自己和两边比）
        double hz = SC.superchargerOrder * 5000 / 60.0;
        float[] sc = mid(render(SC, params(5000f, 1f), 1.5f), 0.7f);
        float[] plain = mid(render(PLAIN, params(5000f, 1f), 1.5f), 0.7f);
        assertTrue(standsOut(sc, hz, 0.04) > 10);
        assertTrue(power(sc, hz) > 10 * power(plain, hz));
    }

    @Test
    public void theSuperchargerAirGrowsWithTheRevs() {
        // 挤过出口的气流、进口的风声（2.5–7 kHz）：满负荷 6000 rpm 比没有机械增压的同一台多一倍以上，
        // 而且这一份随转速长得比发动机本身快。原型换十个噪声种子量出来最差 2.9 倍、3000 → 6000 rpm 又大 2.2 倍；
        // 宽频带的能量很稳，余量不用留到一倍
        double at6000 = air(SC, 6000f) / air(PLAIN, 6000f);
        double at3000 = air(SC, 3000f) / air(PLAIN, 3000f);
        assertTrue("6000 rpm " + at6000, at6000 > 2);
        assertTrue("3000 rpm " + at3000 + " → 6000 rpm " + at6000, at6000 > 1.6 * at3000);
    }

    /** 满负荷时 2.5–7 kHz 的能量。 */
    private static double air(PulseProfile profile, float rpm) {
        float[] x = mid(render(profile, params(rpm, 1f), 1f), 0.4f);
        return energy(filter(x, Biquad.highpass(2500f, 0.7071f, RATE), Biquad.highpass(2500f, 0.7071f, RATE),
                Biquad.lowpass(7000f, 0.7071f, RATE), Biquad.lowpass(7000f, 0.7071f, RATE)));
    }

    // ================================================================= 起动机、倒挡、阀门、传动

    @Test
    public void theStarterCranks() {
        VoiceParams on = params(250f, 0.12f);
        on.level = 0.15f;
        on.starter = 1f;
        VoiceParams off = params(250f, 0.12f);
        off.level = 0.15f;
        float hz = EngineVoice.STARTER_WHINE_HZ;
        assertTrue(power(mid(render(SC, on, 0.6f), 0.1f), hz) > 100 * power(mid(render(SC, off, 0.6f), 0.1f), hz));
    }

    @Test
    public void reverseGearWhines() {
        VoiceParams on = params(1000f, 0.2f);
        on.level = 0.1f;
        on.whineHz = 600f;
        on.whineAmp = 1f;
        VoiceParams off = params(1000f, 0.2f);
        off.level = 0.1f;
        off.whineHz = 600f;
        assertTrue(power(mid(render(SC, on, 0.6f), 0.1f), 600) > 100 * power(mid(render(SC, off, 0.6f), 0.1f), 600));
    }

    @Test
    public void theValveMufflesWhileJoining() {
        VoiceParams closed = params(4000f, 1f);
        closed.toneHz = VirtualEngine.JOIN_TONE_FROM_HZ;
        double muffled = energy(highpass(mid(render(SC, closed, 0.6f), 0.1f), 2000f));
        double open = energy(highpass(mid(render(SC, params(4000f, 1f), 0.6f), 0.1f), 2000f));
        assertTrue(muffled < 0.05 * open);
    }

    @Test
    public void theGearboxWhineFollowsTheGear() {
        // 3000 rpm，发动机压低 20 dB（传动的声音只跟总音量）
        int[] gears = {0, 3, -1};
        float[] orders = {SC.gearOrders[0], SC.gearOrders[3], SC.cvtOrder};
        for (int k = 0; k < gears.length; k++) {
            VoiceParams p = params(3000f, 1f);
            p.level = 0.1f;
            p.drive = true;
            p.speedKmh = 60f;
            p.gearIndex = gears[k];
            float[] x = mid(render(SC, p, 1f), 0.4f);
            assertTrue("挡位 " + gears[k], standsOut(x, orders[k] * 50f, 0.03) > 30);
        }
        VoiceParams driving = params(3000f, 1f);
        driving.level = 0.1f;
        driving.drive = true;
        driving.speedKmh = 60f;
        driving.gearIndex = 0;
        VoiceParams parked = params(3000f, 1f);
        parked.level = 0.1f;
        parked.speedKmh = 60f;
        parked.gearIndex = 0;
        float hz = orders[0] * 50f;
        assertTrue("不在 D 挡没有",
                power(mid(render(SC, driving, 1f), 0.4f), hz) > 30 * power(mid(render(SC, parked, 1f), 0.4f), hz));
    }
}
