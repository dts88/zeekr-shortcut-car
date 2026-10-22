package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 逐缸合成：好不好听要上车听，这里钉的是听不出来、但一错就出事或者一错就退回「单薄」的几件 —— 峰值永远不超过 −3 dBFS、
 * 静音就是真的 0、同样的输入出同样的声音；怠速以点火阶次为主，交叉平面的「突突」在（同排的遮蔽让整数阶次的边带长出来）；
 * 负荷越大越沙哑；左右两排不一样、低频左右一样；机械增压有啸叫，气流声随转速长；收油的沙哑随转速长；
 * 升挡断扭、换挡的「咚」；车内听感更闷但差不多响；车速越高进气风声越大；每个缸摆在自己的位置上；
 * 起动机、倒挡啸叫、开阀门、跟着挡位变的变速箱啸叫。阈值都比原型（docs/local/drive-sound/check_pulse_tests.py）
 * 换着噪声种子量出来的最差值留了至少一倍的余量；单个频点落在噪声上起伏很大，只拿来比确定的音。
 */
public class PulseVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;
    private static final PulseProfile SC = PulseProfile.SUPERCHARGED_V8;
    /** 发动机压低 20 dB 的电平（按风格的 {@link PulseProfile#levelPow} 取次方之后）：起动机、倒挡、传动的声音只跟总音量。 */
    private static final float ENGINE_DOWN_20 = (float) Math.pow(0.1, 1.0 / SC.levelPow);
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
        // 2850 rpm 满负荷（车主偏好的那台录音的转速）：最响的是同步齿轮啮合的第 2 次（10.24 × 3 × 2 = 61.44 阶 ≈ 2.9 kHz），
        // 是一个音（比两边 ±6 % 高得多，±4 % 会碰上转子的边带；原型最差 35 倍），没有机械增压的同一台那里没有（原型最差 25 倍）。
        // 单个频点落在噪声上起伏很大，所以拿「有」和「没有」比，不拿没有的那台自己和两边比
        double hz = SC.superchargerOrder * SC.meshMultiple * 2 * 2850 / 60.0;
        float[] sc = mid(render(SC, params(2850f, 1f), 1.5f), 0.7f);
        float[] plain = mid(render(PLAIN, params(2850f, 1f), 1.5f), 0.7f);
        assertTrue(standsOut(sc, hz, 0.06) > 10);
        assertTrue(power(sc, hz) > 10 * power(plain, hz));
    }

    @Test
    public void theSuperchargerAirGrowsWithTheRevs() {
        // 2.5–7 kHz（齿轮声、挤过出口的气流）：满负荷 6000 rpm 比没有机械增压的同一台多一半以上（2026-10-22 起齿轮声照 EngineV1
        // 那台带负荷的录音定，2850 rpm 以上和点火阶次的比例不再变，高转比原来少约 4 dB；原来要求多一倍），
        // 而且增压器加的这一份 6000 rpm 时是 3000 rpm 时的好几倍（原型最差 5.3 倍）；宽频带的能量很稳，余量不用留到一倍
        double sc6000 = air(SC, 6000f);
        double plain6000 = air(PLAIN, 6000f);
        double added3000 = air(SC, 3000f) - air(PLAIN, 3000f);
        assertTrue("6000 rpm " + sc6000 / plain6000, sc6000 > 1.5 * plain6000);
        assertTrue("增压器加的 " + added3000 + " → " + (sc6000 - plain6000), sc6000 - plain6000 > 2.5 * added3000);
    }

    /** 满负荷时 2.5–7 kHz 的能量。 */
    /** {@code [lo, hi]} 里每赫兹一个点的功率加起来（一段 1 秒的信号，Goertzel 的频点间隔就是 1 Hz）。 */
    private static double bandPower(float[] x, double lo, double hi) {
        double e = 0;
        for (double f = Math.ceil(lo); f <= hi; f += (double) RATE / x.length) {
            e += power(x, f);
        }
        return e;
    }

    /** 齿轮啮合第 2 次（±1.5 %）比 4 阶（±3 %）低多少 dB：和 EngineV1 那台的录音同一个量法。 */
    private static double meshRe4(PulseVoice voice, float rpm, float load) {
        float[] x = mid(render(voice, params(rpm, load), 2f), 1f);
        double f1 = rpm / 60.0;
        double mesh = SC.superchargerOrder * SC.meshMultiple * 2 * f1;
        return 10 * Math.log10(bandPower(x, mesh * 0.985, mesh * 1.015) / bandPower(x, 4 * f1 * 0.97, 4 * f1 * 1.03));
    }

    @Test
    public void underBoostAtLowRevsTheGearsWhineAlmostAsLoudAsTheFiring() {
        // 项目所有者 2026-10-22：起压时机械质感严重不足。EngineV1 那台带负荷 1900 rpm：齿轮第 2 次比 4 阶还高 2 dB（1300 rpm 高 3 dB）；
        // 怠速 702 rpm 时低 23 dB
        double boost = meshRe4(new PulseVoice(SC, RATE), 1900f, 1f);
        assertTrue("1900 rpm 增压时齿轮声: " + boost, boost > -6);
        double idle = meshRe4(new PulseVoice(SC, RATE), 900f, 0f);
        assertTrue("怠速时轻: " + idle, idle < -18);
    }

    @Test
    public void theCabinViewKeepsTheSuperchargerWhine() {
        // 车内听感：啸叫只过一个一阶低通、低几 dB，不过整个防火墙的低通（原来低 20 多 dB，几乎听不出来）
        double outside = meshRe4(new PulseVoice(SC, RATE), 1900f, 1f);
        double cabin = meshRe4(new PulseVoice(SC, RATE, true), 1900f, 1f);
        assertTrue("车内比车外只低一截: " + cabin + " vs " + outside, cabin > outside - 12 && cabin < outside);
    }

    @Test
    public void underBoostTheEngineOrdersRingAroundTheWhine() {
        // 项目所有者 2026-10-22：齿轮声太单一，参考那台像好几组齿轮的和弦。EngineV1 那台带负荷时 10–34 阶几乎全是清楚的整数阶
        // （转速跟踪后量的）—— 增压时进气腔的压力脉动：10–28 阶里至少 8 根冒出来（比两边 ±0.25 阶高 10 倍；量下来 11–15 根），
        // 整数阶加起来比半阶响 6 dB 以上（两排错开一圈，半阶抵掉；量下来 +9.8 dB 起）；没有机械增压的同一台只有噪声
        // （量下来最多 2 根、±4 dB 以内）；松油门（旁通、断油）时轻 10 dB 以上（量下来 −15 dB）
        for (float rpm : new float[] {1300f, 2850f}) {
            double[] boost = chord(SC, rpm, 1f, 0f);
            double[] lifted = chord(SC, rpm, 0f, 1f);
            double[] plain = chord(PLAIN, rpm, 1f, 0f);
            assertTrue(rpm + " rpm 增压时冒出来的整数阶: " + boost[0], boost[0] >= 8);
            assertTrue(rpm + " rpm 整数阶比半阶: " + db(boost[1]), db(boost[1]) > 6);
            assertTrue(rpm + " rpm 没有机械增压: " + plain[0] + " 根, " + db(plain[1]), plain[0] <= 4 && db(plain[1]) < 3);
            assertTrue(rpm + " rpm 松油门: " + db(lifted[2] / boost[2]), db(lifted[2] / boost[2]) < -10);
        }
    }

    @Test
    public void theWhineIsASmallBunchNotOnePureLine() {
        // 项目所有者 2026-10-22：一根主线太过突出，应该是层次更多的一组声音。EngineV1 那台的齿轮声每一刻都是一小团
        // （±0.5–0.7 %，几个峰此起彼伏）：1900 rpm 增压时齿轮第 2 次 ±1.5 % 里最响的那一个点（1 秒、1 Hz 一格）
        // 只占三成以下（一根纯正弦几乎占满；量下来约一成半）
        float[] x = mid(render(SC, params(1900f, 1f), 2f), 1f);
        double mesh = SC.superchargerOrder * SC.meshMultiple * 2 * 1900 / 60.0;
        double band = 0;
        double top = 0;
        for (double f = Math.ceil(mesh * 0.985); f <= mesh * 1.015; f += 1.0) {
            double pk = power(x, f);
            band += pk;
            top = Math.max(top, pk);
        }
        assertTrue("最响的一格占 " + top / band, top / band < 0.3);
    }

    @Test
    public void theWhinesColourChangesWithTheRevs() {
        // 项目所有者 2026-10-22：实机不同转速音色略有变化。照 EngineV1 那台四个转速的录音：低转 h1 比 h2 响、h3 很弱（暗），
        // 2350 rpm 起 h2、h3 上来（亮）。相对 h2（±1.5 % 的能量），1300 → 2350 rpm：h1 至少降 2 dB（参考 −4.1 dB，量下来 −3.8），
        // h3 至少升 4 dB（参考 +8.8 dB，量下来 +7.3）。再往上齿轮声比底噪弱，窗口里量的多半是噪声，不比
        double[] low = harmonicsRe2(1300f);
        double[] high = harmonicsRe2(2350f);
        assertTrue("h1 相对 h2: " + low[0] + " → " + high[0], high[0] < low[0] - 2);
        assertTrue("h3 相对 h2: " + low[1] + " → " + high[1], high[1] > low[1] + 4);
    }

    /**
     * 满增压时齿轮第 1、3 次比第 2 次（各自 ±1.5 % 的能量，3 秒，离发动机整数阶 0.1 阶以内的点不算 —— 那是进气腔的和弦）差多少 dB；
     * 音量压低，不让限幅的增益起伏把别的阶次调制进来。
     */
    private static double[] harmonicsRe2(float rpm) {
        VoiceParams p = params(rpm, 1f);
        p.gain = 0.05f;
        float[] x = mid(render(SC, p, 4f), 1f);
        double f1 = rpm / 60.0;
        double m1 = SC.superchargerOrder * SC.meshMultiple * f1;
        double[] e = new double[3];
        for (int h = 1; h <= 3; h++) {
            for (double f = Math.ceil(h * m1 * 0.985); f <= h * m1 * 1.015; f += (double) RATE / x.length) {
                double o = f / f1;
                if (Math.abs(o - Math.round(o)) >= 0.1) {
                    e[h - 1] += power(x, f);
                }
            }
        }
        return new double[] {db(e[0] / e[1]), db(e[2] / e[1])};
    }

    /** 10–28 阶（1 秒，从第 1 秒起）：比两边 ±0.25 阶高 10 倍的整数阶有几根、整数阶加起来比半阶加起来、整数阶加起来。 */
    private static double[] chord(PulseProfile profile, float rpm, float load, float overrun) {
        VoiceParams p = params(rpm, load);
        p.overrun = overrun;
        float[] x = mid(render(profile, p, 2f), 1f);
        double f1 = rpm / 60.0;
        int count = 0;
        double ints = 0;
        double halves = 0;
        for (int k = 10; k <= 28; k++) {
            double pk = power(x, k * f1);
            if (pk > 10 * Math.max(power(x, (k - 0.25) * f1), power(x, (k + 0.25) * f1))) {
                count++;
            }
            ints += pk;
            if (k < 28) {
                halves += power(x, (k + 0.5) * f1);
            }
        }
        return new double[] {count, ints / halves, ints};
    }

    private static double air(PulseProfile profile, float rpm) {
        float[] x = mid(render(profile, params(rpm, 1f), 1f), 0.4f);
        return energy(filter(x, Biquad.highpass(2500f, 0.7071f, RATE), Biquad.highpass(2500f, 0.7071f, RATE),
                Biquad.lowpass(7000f, 0.7071f, RATE), Biquad.lowpass(7000f, 0.7071f, RATE)));
    }

    // ================================================================= 第六轮（项目所有者 2026-10-21）：随转速、换挡、听感、位置

    /** {@code lo}–{@code hi} Hz 的能量（两个二阶高通 + 两个二阶低通）。 */
    private static double band(float[] x, float lo, float hi) {
        return energy(filter(x, Biquad.highpass(lo, 0.7071f, RATE), Biquad.highpass(lo, 0.7071f, RATE),
                Biquad.lowpass(hi, 0.7071f, RATE), Biquad.lowpass(hi, 0.7071f, RATE)));
    }

    private static double db(double ratio) {
        return 10 * Math.log10(ratio);
    }

    @Test
    public void overrunRaspGrowsWithTheRevs() {
        // 收油：低转很闷、高转一下亮起来（参考车录音 1300 → 2750 rpm 时 2–8 kHz 的占比 +13 dB，原型最差 +9.3 dB）
        VoiceParams low = params(1300f, 0f);
        low.overrun = 1f;
        VoiceParams high = params(2750f, 0f);
        high.overrun = 1f;
        float[] a = mid(render(SC, low, 1.5f), 0.5f);
        float[] b = mid(render(SC, high, 1.5f), 0.5f);
        double growth = db(band(b, 2000f, 8000f) / energy(b)) - db(band(a, 2000f, 8000f) / energy(a));
        assertTrue("2–8 kHz 占比多了 " + growth + " dB", growth > 4);
    }

    @Test
    public void anUpshiftCutDropsTheExhaust() {
        // 5000 rpm 满负荷：断扭时排气的主体（150–500 Hz）掉下来（原型最差 4.9 dB）；增压器照转，所以高频掉得少
        VoiceParams cut = params(5000f, 1f);
        cut.cut = 1f;
        float[] on = mid(render(SC, params(5000f, 1f), 1f), 0.3f);
        float[] off = mid(render(SC, cut, 1f), 0.3f);
        double drop = db(band(on, 150f, 500f) / band(off, 150f, 500f));
        assertTrue("断扭掉了 " + drop + " dB", drop > 2);
    }

    /** 4000 rpm 满负荷，第 40 块时换挡记号变一次（{@code serial} 不变 = 没换挡）。 */
    private static float[] shifting(int serial, boolean up) {
        PulseVoice voice = new PulseVoice(SC, RATE);
        VoiceParams p = params(4000f, 1f);
        int blocks = Math.round(0.6f * RATE / BLOCK);
        float[] out = new float[2 * blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            if (b == 40) {
                p.shiftSerial = serial;
                p.shiftUp = up;
            }
            voice.renderStereo(block, BLOCK, p);
            System.arraycopy(block, 0, out, 2 * b * BLOCK, 2 * BLOCK);
        }
        return mid(out, 0f);
    }

    @Test
    public void aGearChangeThunks() {
        // 同一套随机数、只差换挡的记号：差出来的就是「咚」和「咔」。记号变之前一点不差；差的大半在 30–90 Hz，
        // 和发动机自己这一段差不多响（「不大，但是有」，原型 +0.4 dB）；降挡轻一些（原型能量是升挡的一半）
        float[] same = shifting(0, true);
        float[] up = shifting(1, true);
        float[] down = shifting(1, false);
        int at = 40 * BLOCK;
        for (int i = 0; i < at; i++) {
            assertEquals(same[i], up[i], 0f);
        }
        int n = Math.round(0.15f * RATE);
        float[] dUp = new float[n];
        float[] dDown = new float[n];
        float[] engine = new float[n];
        for (int i = 0; i < n; i++) {
            dUp[i] = up[at + i] - same[at + i];
            dDown[i] = down[at + i] - same[at + i];
            engine[i] = same[at + i];
        }
        assertTrue("「咚」在低频", band(dUp, 30f, 90f) > 0.5 * energy(dUp));
        assertTrue("听得出来", band(dUp, 30f, 90f) > 0.25 * band(engine, 30f, 90f));
        assertTrue("降挡轻一些", energy(dUp) > 1.5 * energy(dDown));
    }

    @Test
    public void theCabinViewIsDarkerButAboutAsLoud() {
        // 车内听感：隔着防火墙、车身，2–8 kHz 的占比少了（原型最差 11.5 dB），100 Hz 以上的响度差不多（原型车外大 2 dB）
        float[] out = mid(render(new PulseVoice(SC, RATE), params(5000f, 1f), 1f), 0.3f);
        float[] in = mid(render(new PulseVoice(SC, RATE, true), params(5000f, 1f), 1f), 0.3f);
        double darker = db(band(out, 2000f, 8000f) / energy(out)) - db(band(in, 2000f, 8000f) / energy(in));
        assertTrue("车内闷了 " + darker + " dB", darker > 6);
        double louder = db(energy(highpass(out, 100f)) / energy(highpass(in, 100f)));
        assertTrue("响度差 " + louder + " dB", Math.abs(louder) < 4.5);
    }

    @Test
    public void theInletWindGrowsWithSpeed() {
        // 6000 rpm 满负荷：200 km/h 时进气的风声（1–2.5 kHz）比停着多（原型最差 +1.2 dB）
        VoiceParams fast = params(6000f, 1f);
        fast.speedKmh = 200f;
        float[] still = mid(render(SC, params(6000f, 1f), 1f), 0.3f);
        float[] moving = mid(render(SC, fast, 1f), 0.3f);
        double more = db(band(moving, 1000f, 2500f) / band(still, 1000f, 2500f));
        assertTrue("多了 " + more + " dB", more > 0.5);
    }

    @Test
    public void eachCylinderSitsInItsPlace() {
        // 单号缸在左排：气门、燃烧声偏左；1、2 缸在发动机最前面（离驾驶座最远）：最轻、最晚到；7、8 缸最近
        PulseVoice v = new PulseVoice(SC, RATE);
        float[] c1 = v.cylinderPlace(1);
        float[] c2 = v.cylinderPlace(2);
        float[] c7 = v.cylinderPlace(7);
        assertTrue("1 缸偏左", c1[2] > c1[3]);
        assertTrue("2 缸偏右", c2[3] > c2[2]);
        assertEquals("最近的缸不晚到", 0f, c7[1], 0f);
        assertTrue("最前面的缸晚到", c1[1] > 1f);
        assertTrue("最前面的缸轻一点", c1[0] < c7[0]);
        // 等功率摆位
        assertEquals(0.7071f, PulseVoice.panLeft(0f), 1e-3f);
        assertEquals(1f, PulseVoice.panLeft(-1f), 1e-6f);
        assertEquals(0f, PulseVoice.panRight(-1f), 1e-6f);
        assertEquals(1f, PulseVoice.panLeft(0.3f) * PulseVoice.panLeft(0.3f) + PulseVoice.panRight(0.3f) * PulseVoice.panRight(0.3f), 1e-5f);
    }

    // ================================================================= 起动机、倒挡、阀门、传动

    @Test
    public void theStarterCranks() {
        VoiceParams on = params(250f, 0.12f);
        on.level = ENGINE_DOWN_20;
        on.starter = 1f;
        VoiceParams off = params(250f, 0.12f);
        off.level = ENGINE_DOWN_20;
        float hz = EngineVoice.STARTER_WHINE_HZ;
        assertTrue(power(mid(render(SC, on, 0.6f), 0.1f), hz) > 100 * power(mid(render(SC, off, 0.6f), 0.1f), hz));
    }

    @Test
    public void reverseGearWhines() {
        VoiceParams on = params(1000f, 0.2f);
        on.level = ENGINE_DOWN_20;
        on.whineHz = 600f;
        on.whineAmp = 1f;
        VoiceParams off = params(1000f, 0.2f);
        off.level = ENGINE_DOWN_20;
        off.whineHz = 600f;
        assertTrue(power(mid(render(SC, on, 0.6f), 0.1f), 600) > 100 * power(mid(render(SC, off, 0.6f), 0.1f), 600));
    }

    @Test
    public void theValveMufflesWhenClosed() {
        // 现在一直全开（行驶中接入改成发动机起动，2026-10-22）；换回开阀门式时用
        VoiceParams closed = params(4000f, 1f);
        closed.toneHz = 400f;
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
            p.level = ENGINE_DOWN_20;
            p.drive = true;
            p.speedKmh = 60f;
            p.gearIndex = gears[k];
            float[] x = mid(render(SC, p, 1f), 0.4f);
            assertTrue("挡位 " + gears[k], standsOut(x, orders[k] * 50f, 0.03) > 30);
        }
        VoiceParams driving = params(3000f, 1f);
        driving.level = ENGINE_DOWN_20;
        driving.drive = true;
        driving.speedKmh = 60f;
        driving.gearIndex = 0;
        VoiceParams parked = params(3000f, 1f);
        parked.level = ENGINE_DOWN_20;
        parked.speedKmh = 60f;
        parked.gearIndex = 0;
        float hz = orders[0] * 50f;
        assertTrue("不在 D 挡没有",
                power(mid(render(SC, driving, 1f), 0.4f), hz) > 30 * power(mid(render(SC, parked, 1f), 0.4f), hz));
        VoiceParams clutchOpen = params(3000f, 1f);
        clutchOpen.level = ENGINE_DOWN_20;
        clutchOpen.drive = true;
        clutchOpen.speedKmh = 60f;
        clutchOpen.gearIndex = 0;
        clutchOpen.fire = 0f;
        assertTrue("没点火（离合开着）也没有",
                power(mid(render(SC, driving, 1f), 0.4f), hz) > 30 * power(mid(render(SC, clutchOpen, 1f), 0.4f), hz));
    }

    // ================================================================= 点着、熄火（项目所有者 2026-10-22）

    @Test
    public void withoutFireOnlyTheCompressionPuffsAreLeft() {
        VoiceParams fired = params(900f, 0f);
        VoiceParams unfired = params(900f, 0f);
        unfired.fire = 0f;
        double on = energy(mid(render(SC, fired, 0.8f), 0.3f));
        double off = energy(mid(render(SC, unfired, 0.8f), 0.3f));
        double db = 10 * Math.log10(off / on);
        assertTrue("没点火轻很多，但还听得见: " + db + " dB", db < -6 && db > -25);
    }

    @Test
    public void aCatchRocksTheEngineOnItsMounts() {
        // 同样的怠速，一个刚点着：之后那一小段 40 Hz 附近（发动机在支座上晃）明显多
        PulseVoice plain = new PulseVoice(SC, RATE);
        PulseVoice caught = new PulseVoice(SC, RATE);
        VoiceParams p = params(900f, 0.3f);
        render(plain, p, 0.3f);
        render(caught, p, 0.3f);
        VoiceParams q = params(900f, 0.3f);
        q.catchSerial = 1;
        float[] a = mid(render(plain, p, 0.15f), 0f);
        float[] b = mid(render(caught, q, 0.15f), 0f);
        assertTrue(power(b, SC.rockHz) > 10 * power(a, SC.rockHz));
    }

    @Test
    public void aStopRunsDownSettlesAndGoesSilent() {
        PulseVoice v = new PulseVoice(SC, RATE);
        VoiceParams p = params(900f, 0f);
        double idle = energy(mid(render(v, p, 0.5f), 0.2f)) / (0.3 * RATE);
        // 熄火：0.7 秒从怠速落到 0（和虚拟发动机差不多），再停着 0.5 秒
        p.fire = 0f;
        int blocks = Math.round(0.7f * RATE / BLOCK);
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            p.rpm = 900f * (1f - (b + 1f) / blocks);
            v.renderStereo(block, BLOCK, p);
        }
        p.rpm = 0f;
        float[] after = mid(render(v, p, 0.5f), 0f);
        float[] settle = java.util.Arrays.copyOfRange(after, 0, Math.round(0.15f * RATE));
        float[] tail = java.util.Arrays.copyOfRange(after, Math.round(0.35f * RATE), after.length);
        double settleRms = energy(settle) / settle.length;
        assertTrue("停住那一下晃一下，听得出、比怠速轻: " + 10 * Math.log10(settleRms / idle),
                settleRms < idle && settleRms > 1e-3 * idle);
        float[] same = java.util.Arrays.copyOfRange(tail, tail.length - settle.length / 2, tail.length);
        assertTrue("晃的是低频", power(settle, SC.rockHz) > 100 * power(same, SC.rockHz));
        double tailRms = energy(tail) / tail.length;
        assertTrue("然后就没声了: " + 10 * Math.log10(tailRms / idle), tailRms < 1e-3 * idle);
    }
}
