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
        // 2850 rpm 满负荷（车主偏好的那台录音的转速）：最响的是同步齿轮啮合的第 2 次（2026-10-24 起照参考视频那台的音高，
        // 12.03 × 2 × 2 = 48.12 阶 ≈ 2.3 kHz），是一小团（±1.5 % 里每赫兹的能量比两边 6–7.5 % 处高得多；负载时转子、点火调出来的
        // 边带在 ±3.3（两个转子之差）、5.0（阴转子）、8.3（阳转子、点火）、10 %，避开），
        // 没有机械增压的同一台那里没有。2026-10-23 起齿轮声的几个来源错开 ±0.8 %（项目所有者：一条主的音线太过突出），
        // 按 ±1.5 % 的能量量（和 EngineV1 那台的录音同一个量法），不再按单个频点；摊开以后每赫兹的能量比两边高三倍以上就是一个音
        // （原来三个来源只错开 ±0.2 %，单个频点比两边高十倍）
        double hz = SC.superchargerOrder * SC.meshMultiple * 2 * 2850 / 60.0;
        float[] sc = mid(render(SC, params(2850f, 1f), 1.5f), 0.7f);
        float[] plain = mid(render(PLAIN, params(2850f, 1f), 1.5f), 0.7f);
        double bunch = bandPower(sc, hz * 0.985, hz * 1.015) / 0.03;
        double sides = Math.max(bandPower(sc, hz * 0.925, hz * 0.94), bandPower(sc, hz * 1.06, hz * 1.075)) / 0.015;
        assertTrue("比两边高 " + bunch / sides, bunch > 3 * sides);
        assertTrue("比没有增压器的 " + bunch / bandPower(plain, hz * 0.985, hz * 1.015), bunch > 10 * bandPower(plain, hz * 0.985, hz * 1.015));
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
        return energy(passBand(x, lo, hi));
    }

    private static float[] passBand(float[] x, float lo, float hi) {
        return filter(x, Biquad.highpass(lo, 0.7071f, RATE), Biquad.highpass(lo, 0.7071f, RATE),
                Biquad.lowpass(hi, 0.7071f, RATE), Biquad.lowpass(hi, 0.7071f, RATE));
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

    // ================================================================= 2026-10-23：松油门、持续高转

    /** {@code x} 里 {@code from}–{@code to} 秒的 RMS（dB）。 */
    private static double rmsDb(float[] x, float from, float to) {
        int a = Math.round(from * RATE);
        int b = Math.min(x.length, Math.round(to * RATE));
        double e = 0;
        for (int i = a; i < b; i++) {
            e += (double) x[i] * x[i];
        }
        return 10 * Math.log10(e / (b - a) + 1e-30);
    }

    @Test
    public void afterALiftTheEngineKeepsRunning() {
        // 项目所有者 2026-10-23：松开油门后引擎声音太小，踩和不踩的落差太大 —— 不踩油门时发动机仍被车拖着、和变速箱、车连成一体在转，
        // 每个缸照样吸气、压缩、排气（断油了，只是没有燃烧）。车内听感、同一个转速，电平照虚拟发动机给的（满负荷 0 dB、收油 −19 dB）：
        // 满负荷和收油差 3–7 dB（原来 10 dB 以上）；燃烧不是一下断掉（电控先把扭矩收掉），松开后头 0.15 s 比 1 s 以后响
        PulseVoice v = new PulseVoice(SC, RATE, true);
        VoiceParams full = params(3500f, 1f);
        full.gain = 0.3f;
        float[] on = mid(render(v, full, 1.5f), 0.5f);
        VoiceParams lift = params(3500f, 0f);
        lift.overrun = 1f;
        lift.gain = 0.3f;
        lift.level = EngineVoice.dbToLin(VirtualEngine.LEVEL_FLOOR_DB);
        float[] off = mid(render(v, lift, 1.6f), 0f);
        double drop = rmsDb(on, 0f, 1f) - rmsDb(off, 1f, 1.6f);
        assertTrue("落差 " + drop + " dB", drop > 3 && drop < 7);
        double ramp = rmsDb(off, 0.03f, 0.15f) - rmsDb(off, 1f, 1.6f);
        assertTrue("先收扭矩再断油: " + ramp + " dB", ramp > 1);
    }

    @Test
    public void aSustainedPullWarmsTheExhaustAndMovesItsResonancesUp() {
        // 项目所有者 2026-10-23：低转速下引擎的轰鸣不错，转速上去之后厚度、力量感不足。排气管里的气体温度跟着功率（负荷 × 转速）走、
        // 管壁有热惯性：巡航时管子、消声器的共振基本不动（低转的味道不变），刚踩下去时也还没动，持续高转满负荷几秒以后一起往上移
        // 约 1.3 倍（声速跟温度的平方根），托住高转时的 2–4.5 阶
        PulseVoice v = new PulseVoice(SC, RATE, true);
        VoiceParams cruise = params(2000f, 0.25f);
        cruise.gain = 0.3f;
        render(v, cruise, 8f);
        assertEquals("巡航时基本不动", 1.0, v.gasScale(), 0.03);
        VoiceParams pull = params(5500f, 1f);
        pull.gain = 0.3f;
        render(v, pull, 0.5f);
        assertTrue("刚踩下去还没动: " + v.gasScale(), v.gasScale() < 1.08f);
        render(v, pull, 8f);
        assertTrue("拉了几秒热起来: " + v.gasScale(), v.gasScale() > 1.25f);
    }

    @Test
    public void theSuperchargerLinesHaveNoNoiseSkirts() {
        // 项目所有者 2026-10-23：颗粒状噪音会破坏整体氛围，要着重避免。颗粒是每根线两边一小片随机的窄噪声（超音速那里查出来的；
        // 这里原来是增压器转速的随机抖动 0.6 %）。1900 rpm 满增压，齿轮声第 1、2 次：两边 1.5–2.8 % 处每赫兹的能量比线本身
        // （±0.6 %）低 14 dB 以上（转子的边带在 3.3 % 以外）；随机抖动那一版只低 11–14 dB
        for (boolean cabin : new boolean[] {false, true}) {
            float[] x = mid(render(new PulseVoice(SC, RATE, cabin), params(1900f, 1f), 2.5f), 1.5f);
            for (int h = 1; h <= 2; h++) {
                double m = SC.superchargerOrder * SC.meshMultiple * h * 1900 / 60.0;
                double core = bandPower(x, m * 0.994, m * 1.006) / (0.012 * m);
                double skirt = (bandPower(x, m * 0.972, m * 0.985) + bandPower(x, m * 1.015, m * 1.028)) / (0.026 * m);
                double db = 10 * Math.log10(skirt / core);
                assertTrue((cabin ? "车内" : "车外") + " 第 " + h + " 次两边 " + db + " dB", db < -14);
            }
        }
    }

    @Test
    public void theDischargePulsationIsALayerOfItsOwn() {
        // 项目所有者 2026-10-23：增压器有好几个出声的部件，位置、转速各不同，应该分开、更有层次（不是单纯的风声）。出口的压力
        // 脉动（最响的那一次：2026-10-24 换成参考视频那台的音高以后是叶片频率的第 3 次，36.09 阶）是做功、压缩那一层：满增压时
        // 它在那里加的能量和同步齿轮的啮合声差不多响（1900 rpm 车外比齿轮声第 1 次低不到 12 dB），旁通阀开着（不增压）时几乎没有。
        // 叶片频率的每一次都挨着发动机的某个整数阶（36.09 对 36），所以拿同一台「有、没有这一层」两份比
        PulseProfile none = new PulseProfile.Builder().supercharger(SC.superchargerOrder, SC.superchargerDb)
                .loudness(SC.levelPow, SC.cabinLevelPow, SC.rpmLoudDb, SC.norm).discharge(-100f).build();
        double f1 = 1900 / 60.0;
        int top = 0;
        for (int k = 1; k < SC.dischargeHarmonics.length; k++) {
            if (SC.dischargeHarmonicsDb[k] > SC.dischargeHarmonicsDb[top]) {
                top = k;
            }
        }
        double l2 = SC.superchargerOrder * SC.dischargeHarmonics[top] * f1;
        double m1 = SC.superchargerOrder * SC.meshMultiple * f1;
        float[] with = mid(render(SC, params(1900f, 1f), 2.5f), 1.5f);
        float[] without = mid(render(none, params(1900f, 1f), 2.5f), 1.5f);
        double added = bandPower(with, l2 * 0.985, l2 * 1.015) - bandPower(without, l2 * 0.985, l2 * 1.015);
        double gears = bandPower(with, m1 * 0.985, m1 * 1.015);
        assertTrue("加的能量和齿轮声比 " + db(added / gears) + " dB", added > gears * EngineVoice.dbToLin(-24f));
        float[] freeWith = mid(render(SC, params(1900f, 0.05f), 2.5f), 1.5f);
        float[] freeWithout = mid(render(none, params(1900f, 0.05f), 2.5f), 1.5f);
        double freeAdded = bandPower(freeWith, l2 * 0.985, l2 * 1.015) - bandPower(freeWithout, l2 * 0.985, l2 * 1.015);
        assertTrue("不增压时加的 " + db(Math.abs(freeAdded) / added) + " dB", Math.abs(freeAdded) < added * EngineVoice.dbToLin(-30f));
    }

    // ================================================================= 2026-10-23：松开油门以后的啸叫、节气门的嘶声（参考视频）

    /** 同一台机械增压，只是改了一样（{@code tweak}）：拿「有、没有这一层」两份的差量这一层自己（别的完全一样，随机数也一样）。 */
    private static PulseProfile scWith(java.util.function.UnaryOperator<PulseProfile.Builder> tweak) {
        return tweak.apply(new PulseProfile.Builder().supercharger(SC.superchargerOrder, SC.superchargerDb)
                .loudness(SC.levelPow, SC.cabinLevelPow, SC.rpmLoudDb, SC.norm)).build();
    }

    /** 两份（同样的输入）的差，(左 + 右) / 2。 */
    private static float[] difference(float[] a, float[] b) {
        float[] d = new float[a.length / 2];
        for (int i = 0; i < d.length; i++) {
            d[i] = 0.5f * (a[2 * i] + a[2 * i + 1] - b[2 * i] - b[2 * i + 1]);
        }
        return d;
    }

    private static float[] slice(float[] x, float from, float to) {
        return java.util.Arrays.copyOfRange(x, Math.round(from * RATE), Math.round(to * RATE));
    }

    /**
     * 这个转速满增压 1 秒，再松开（断油、电平落到收油）1 秒；松开以后的啸叫这一层（有 − 没有）在叶片频率第 2 次（±1.5 %）的能量：
     * {踩着 0.6–0.9 s, 松开头 0.05–0.35 s, 松开 0.6–0.9 s 以后}。
     */
    private static double[] decelLine(PulseProfile none, float rpm) {
        VoiceParams on = params(rpm, 1f);
        on.gain = 0.3f;
        VoiceParams off = params(rpm, 0f);
        off.overrun = 1f;
        off.level = EngineVoice.dbToLin(VirtualEngine.LEVEL_FLOOR_DB);
        off.gain = 0.3f;
        float[][] lr = new float[2][];
        PulseProfile[] pair = {SC, none};
        for (int k = 0; k < 2; k++) {
            PulseVoice v = new PulseVoice(pair[k], RATE);
            float[] a = render(v, on, 1f);
            float[] b = render(v, off, 1f);
            lr[k] = new float[a.length + b.length];
            System.arraycopy(a, 0, lr[k], 0, a.length);
            System.arraycopy(b, 0, lr[k], a.length, b.length);
        }
        float[] d = difference(lr[0], lr[1]);
        double l2 = SC.superchargerOrder * 2 * rpm / 60.0;
        return new double[] {bandPower(slice(d, 0.6f, 0.9f), l2 * 0.985, l2 * 1.015),
                bandPower(slice(d, 1.05f, 1.35f), l2 * 0.985, l2 * 1.015),
                bandPower(slice(d, 1.6f, 1.9f), l2 * 0.985, l2 * 1.015)};
    }

    @Test
    public void afterTheThrottleClosesTheSuperchargerWhinesClean() {
        // 项目所有者 2026-10-23 给的参考视频（发动机舱，停着轰油门）：踩着油门、发动机出力时增压器承受着压力，放开油门后的声调明显
        // 不同 —— 节气门关上、旁通阀打开，燃烧、压缩那几层没了，剩下干净的啸叫（叶片频率的第 2 次最响）；松开那一下最响，随转速
        // 落下去很快变淡（4600 → 3300 rpm 约 −7 dB）；踩着油门时没有这一层
        PulseProfile none = scWith(b -> b.decel(-100f));
        double[] high = decelLine(none, 4500f);
        double[] low = decelLine(none, 1500f);
        assertTrue("踩着油门时没有: " + db(high[0] / high[2]) + " dB", high[0] < high[2] * 1e-3);
        assertTrue("松开那一下最响: " + db(high[1] / high[2]) + " dB", high[1] > high[2] * 1.6);
        assertTrue("低转淡: " + db(low[2] / high[2]) + " dB", low[2] < high[2] * Math.pow(10, -1.5));
    }

    /** 节气门的嘶声这一层（有 − 没有，车外）1–2 s：{RMS dB, 1 ms 包络的起伏（标准差 / 平均）}。 */
    private static double[] hissLayer(PulseProfile none, float rpm, float load) {
        VoiceParams p = params(rpm, load);
        p.gain = 0.3f;
        float[] d = slice(difference(render(SC, p, 2f), render(none, p, 2f)), 1f, 2f);
        int block = RATE / 1000;
        int n = d.length / block;
        double[] env = new double[n];
        double sum = 0;
        double energy = 0;
        for (int j = 0; j < n; j++) {
            double e = 0;
            for (int i = 0; i < block; i++) {
                e += (double) d[j * block + i] * d[j * block + i];
            }
            energy += e;
            env[j] = Math.sqrt(e / block);
            sum += env[j];
        }
        double mean = sum / n;
        double var = 0;
        for (double v : env) {
            var += (v - mean) * (v - mean);
        }
        return new double[] {10 * Math.log10(energy / (n * block) + 1e-30), Math.sqrt(var / n) / mean};
    }

    @Test
    public void theThrottleHissesAtIdleAndGetsDenserWithTheRevs() {
        // 项目所有者 2026-10-23：P 挡落到怠速、稳住以后节气门的声音相对更明显；转速高了不是变小，是更快更密、被差不多频率或更响的
        // 声音盖住。节气门几乎关着时窄缝里声速的气流，每吸一口气涌一股：怠速时一股一股分得开（参考视频发动机舱怠速 2–9 kHz 按点火
        // 频率起伏 80 % 以上），转速高了连成一片（4500 rpm 收油时只起伏 30 % 上下），总的差不多一样响；节气门开大了没了
        PulseProfile none = scWith(b -> b.hiss(-100f));
        double[] idle = hissLayer(none, 900f, 0f);
        double[] high = hissLayer(none, 4000f, 0f);
        double[] open = hissLayer(none, 4000f, 1f);
        assertTrue("怠速时一股一股: " + idle[1], idle[1] > 0.7);
        assertTrue("转速高了连成一片: " + high[1] + " / " + idle[1], high[1] < idle[1] - 0.25);
        assertTrue("不是变小（差不多一样响）: " + (high[0] - idle[0]) + " dB", high[0] - idle[0] > -2 && high[0] - idle[0] < 5);
        assertTrue("节气门开大了没了: " + (open[0] - high[0]) + " dB", open[0] - high[0] < -20);
    }

    // ================================================================= 2026-10-24：沙沙声、负载时的机械感

    @Test
    public void theThrottleHissHasNoSizzleAbove() {
        // 项目所有者 2026-10-24：车外有过于明显的沙沙状的噪音 —— 是节气门的嘶声：一级宽带通（3 kHz、Q 0.6）一直拖到 12 kHz
        // （6–12 kHz 的能量反而比 1.5–3 kHz 的高约 2 dB），车外怠速 2.5–12.5 kHz 线和线之间的底噪比没有它时高 5–18 dB。
        // 两级带通（2 kHz、Q 1）以后，6–12 kHz 的能量比 1.5–3 kHz 的低 12 dB 以上
        PulseProfile none = scWith(b -> b.hiss(-100f));
        VoiceParams p = params(900f, 0f);
        p.gain = 0.3f;
        float[] d = slice(difference(render(SC, p, 2f), render(none, p, 2f)), 1f, 2f);
        double air = bandPower(d, 1500, 3000);
        double sizzle = bandPower(d, 6000, 12000);
        assertTrue("6–12 kHz 比 1.5–3 kHz 低 " + db(air / sizzle) + " dB", sizzle < air * Math.pow(10, -1.2));
    }

    /** {@code x} 里 2–5 kHz（增压器的啸叫在车内占主导的那一带）的包络（幅度）起伏得多粗：40–300 Hz 的起伏能量 / 平均的平方（dB）。 */
    private static double roughness(float[] x) {
        float[] b = filter(x, Biquad.highpass(2000f, 0.7071f, RATE), Biquad.highpass(2000f, 0.7071f, RATE),
                Biquad.lowpass(5000f, 0.7071f, RATE), Biquad.lowpass(5000f, 0.7071f, RATE));
        float[] e = new float[b.length];
        for (int i = 0; i < b.length; i++) {
            e[i] = Math.abs(b[i]);
        }
        e = filter(e, Biquad.lowpass(600f, 0.7071f, RATE), Biquad.lowpass(600f, 0.7071f, RATE));
        double mean = 0;
        for (float v : e) {
            mean += v;
        }
        mean /= e.length;
        float[] m = filter(e, Biquad.highpass(40f, 0.7071f, RATE), Biquad.highpass(40f, 0.7071f, RATE),
                Biquad.lowpass(300f, 0.7071f, RATE), Biquad.lowpass(300f, 0.7071f, RATE));
        return 10 * Math.log10(energy(m) / (e.length * mean * mean) + 1e-30);
    }

    @Test
    public void underLoadTheSuperchargerGrowlsAndAfterALiftItIsClean() {
        // 项目所有者 2026-10-24：还是没有听到机械增压负载和松油门以后音色的明显差异，负载时有更重的机械感。负载时齿轮受力，转子
        // 每转一圈、每次点火都调一下（边带落在整数阶之间），粗、带着低吼；松开以后只剩干净的啸叫。车内听感，同一个转速：2–5 kHz
        // 的包络在 40–300 Hz 上的起伏，负载比松开大 3 dB 以上（量下来 2000 rpm +8、4500 rpm +12 dB；上一版两个差不多）
        for (float rpm : new float[] {2000f, 4500f}) {
            VoiceParams on = params(rpm, 1f);
            on.gain = 0.3f;
            VoiceParams off = params(rpm, 0f);
            off.overrun = 1f;
            off.level = EngineVoice.dbToLin(VirtualEngine.LEVEL_FLOOR_DB);
            off.gain = 0.3f;
            double r1 = roughness(mid(render(new PulseVoice(SC, RATE, true), on, 3f), 1f));
            double r0 = roughness(mid(render(new PulseVoice(SC, RATE, true), off, 3f), 1f));
            assertTrue(rpm + " rpm 负载比松开粗 " + (r1 - r0) + " dB", r1 - r0 > 3);
        }
    }

    @Test
    public void theHousingCarriesTheLoadNotTheLift() {
        // 负载时出口的压力顶着壳体、齿轮受力，壳体的振动经支座、车身传进车里（偏中低频，不过防火墙）：车内听感 2000 rpm 满增压，
        // 这一路在 0.4–1.2 kHz 加的能量不少于那一带的十分之一；松开以后几乎没有（低 20 dB 以上）。拿有、没有这一路两份的差量
        PulseProfile none = scWith(b -> b.body(-100f));
        VoiceParams on = params(2000f, 1f);
        on.gain = 0.3f;
        VoiceParams off = params(2000f, 0f);
        off.overrun = 1f;
        off.level = EngineVoice.dbToLin(VirtualEngine.LEVEL_FLOOR_DB);
        off.gain = 0.3f;
        float[] withOn = render(new PulseVoice(SC, RATE, true), on, 2f);
        float[] layerOn = slice(difference(withOn, render(new PulseVoice(none, RATE, true), on, 2f)), 1f, 2f);
        float[] layerOff = slice(difference(render(new PulseVoice(SC, RATE, true), off, 2f),
                render(new PulseVoice(none, RATE, true), off, 2f)), 1f, 2f);
        double band = bandPower(slice(mid(withOn, 0f), 1f, 2f), 400, 1200);
        double added = bandPower(layerOn, 400, 1200);
        double offAdded = bandPower(layerOff, 400, 1200);
        assertTrue("负载时壳体那一路占 " + db(added / band) + " dB", added > 0.1 * band);
        assertTrue("松开以后 " + db(offAdded / added) + " dB", offAdded < added * 0.01);
    }

    @Test
    public void pressingDownPushesTheLobeOnce() {
        // 参考视频：踩下去起压那一下叶片基频猛一下（比松开时高约 15 dB）。一下就过去（和发动机第 12 阶来不及拍出「呜哇」）：
        // 负荷从 0 跳到满，头 0.15 s 里这一下（有、没有两份的差）在叶片基频上的能量，比 1 s 以后（增压稳了）大 100 倍以上
        PulseProfile none = scWith(b -> b.press(0f));
        VoiceParams idle = params(2500f, 0f);
        idle.gain = 0.3f;
        VoiceParams full = params(2500f, 1f);
        full.gain = 0.3f;
        float[][] lr = new float[2][];
        PulseProfile[] pair = {SC, none};
        for (int k = 0; k < 2; k++) {
            PulseVoice v = new PulseVoice(pair[k], RATE);
            float[] a = render(v, idle, 0.5f);
            float[] b = render(v, full, 1.5f);
            lr[k] = new float[a.length + b.length];
            System.arraycopy(a, 0, lr[k], 0, a.length);
            System.arraycopy(b, 0, lr[k], a.length, b.length);
        }
        float[] d = difference(lr[0], lr[1]);
        double lobe = SC.superchargerOrder * 2500 / 60.0;
        double early = bandPower(slice(d, 0.5f, 0.65f), lobe * 0.97, lobe * 1.03);
        double late = bandPower(slice(d, 1.5f, 1.65f), lobe * 0.97, lobe * 1.03);
        assertTrue("起压那一下 " + db(early / late) + " dB", early > 100 * late);
    }

    // ================================================================= 2026-10-24：立体感

    /** 从 {@code fromSeconds} 起的一个声道（0 左、1 右）。 */
    private static float[] channel(float[] lr, float fromSeconds, int which) {
        int from = Math.round(fromSeconds * RATE);
        float[] x = new float[lr.length / 2 - from];
        for (int i = 0; i < x.length; i++) {
            x[i] = lr[2 * (i + from) + which];
        }
        return x;
    }

    /** 两路的相关（1 = 一样，0 = 不相干，负的 = 反相）。 */
    private static double correlation(float[] a, float[] b) {
        double ab = 0;
        double aa = 0;
        double bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        return ab / Math.sqrt(aa * bb + 1e-30);
    }

    @Test
    public void theCabinHearsTheSuperchargerAcrossTheWholeFront() {
        // 项目所有者 2026-10-24：美式 V8 的声音汇聚在一起，像一个声道放出来的。车内满增压 3000 rpm，1–4 kHz（齿轮声、中冷器）
        // 左右原来几乎一样（相关约 0.95）；齿轮声的几个来源一左一中一右、两个中冷器一左一右、中高频左右的差再放大 3 dB 以后
        // 低于 0.85 —— 但不反相（高于 0.2，不发虚）
        float[] x = render(new PulseVoice(SC, RATE, true), params(3000f, 1f), 2f);
        double c = correlation(passBand(channel(x, 1f, 0), 1000f, 4000f), passBand(channel(x, 1f, 1), 1000f, 4000f));
        assertTrue("1–4 kHz 左右相关 " + c, c < 0.85 && c > 0.2);
    }

    @Test
    public void theWideningLeavesTheMonoSumAndTheLowSideAlone() {
        // 只放大中高频本来就有的左右差（700 Hz 起的一阶搁架）：左右相加一点不变（车外喇叭、把左右合成一路的车机听到的一样）；
        // 120–250 Hz（两排点火不均匀，本来就有反相的成分，放大了发虚）左右的差几乎不变，2–4 kHz 大约大 3 dB
        PulseProfile flat = scWith(b -> b.width(0f));
        VoiceParams p = params(3000f, 0.8f);
        p.gain = 0.3f;
        float[] wide = render(new PulseVoice(SC, RATE, true), p, 1.5f);
        float[] narrow = render(new PulseVoice(flat, RATE, true), p, 1.5f);
        float[] monoWide = mid(wide, 0.5f);
        float[] monoNarrow = mid(narrow, 0.5f);
        double off = 0;
        for (int i = 0; i < monoWide.length; i++) {
            double d = monoWide[i] - monoNarrow[i];
            off += d * d;
        }
        // 只差浮点舍入（左右各自还要过几道滤波，约 −75 dB）
        assertTrue("左右相加差了 " + db(off / energy(monoNarrow)) + " dB", off < 1e-6 * energy(monoNarrow));
        float[] sideWide = side(wide, 0.5f);
        float[] sideNarrow = side(narrow, 0.5f);
        double low = db(band(sideWide, 120f, 250f) / band(sideNarrow, 120f, 250f));
        double high = db(band(sideWide, 2000f, 4000f) / band(sideNarrow, 2000f, 4000f));
        assertTrue("120–250 Hz 左右的差大了 " + low + " dB", Math.abs(low) < 1);
        assertTrue("2–4 kHz 左右的差大了 " + high + " dB", high > 2 && high < 3.2);
    }
}
