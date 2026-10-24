package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「涡轮四缸」（试制）：峰值永远不超过 −3 dBFS、静音就是真的 0、同样的输入出同样的声音；点火阶次（2、4、6 阶）领先、半阶次填在中间；
 * 主体在 300–1000 Hz；涡轮低转起压慢、松油门很快泄掉但轴还转着；高转松油门才放炮；升挡掉 6 dB 左右；排气热了音色往上走；两根尾管。
 * 参考量出来的数在 docs/local/drive-sound-plan.md §13。
 */
public class TurboFourVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;

    private static VoiceParams params(float rpm, float load) {
        VoiceParams p = new VoiceParams();
        p.rpm = rpm;
        p.load = load;
        p.level = 1f;
        p.gain = 1f;
        p.drive = true;
        p.speedKmh = 60f;
        return p;
    }

    /** 左、右各 {@code seconds} 长（交错的立体声）。 */
    private static float[] renderStereo(TurboFourVoice voice, VoiceParams p, float seconds) {
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[2 * blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            voice.renderStereo(block, BLOCK, p);
            System.arraycopy(block, 0, out, 2 * b * BLOCK, 2 * BLOCK);
        }
        return out;
    }

    /** (左 + 右) / 2。 */
    private static float[] render(TurboFourVoice voice, VoiceParams p, float seconds) {
        float[] lr = renderStereo(voice, p, seconds);
        float[] m = new float[lr.length / 2];
        for (int i = 0; i < m.length; i++) {
            m[i] = 0.5f * (lr[2 * i] + lr[2 * i + 1]);
        }
        return m;
    }

    private static double power(float[] x, int from, int n, double hz) {
        double c = 2.0 * Math.cos(2.0 * Math.PI * hz / RATE);
        double s1 = 0;
        double s2 = 0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n);
            double s0 = x[from + i] * w + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }

    /** {@code hz} 附近 ±1.5 % 里最响的那一格（dB）：线的位置有点抖。 */
    private static double lineDb(float[] x, int from, int n, double hz) {
        double best = 0;
        for (double f = hz * 0.985; f <= hz * 1.015; f += RATE / (double) n / 2) {
            best = Math.max(best, power(x, from, n, f));
        }
        return 10 * Math.log10(best + 1e-30);
    }

    /** {@code lo}–{@code hi} Hz 的能量（dB，汉宁窗 Goertzel 每一格加起来）。 */
    private static double bandDb(float[] x, int from, int n, double lo, double hi) {
        double sum = 0;
        for (int k = (int) Math.ceil(lo * n / RATE); k <= hi * n / RATE; k++) {
            sum += power(x, from, n, (double) k * RATE / n);
        }
        return 10 * Math.log10(sum + 1e-30);
    }

    private static double rmsDb(float[] x, int from, int to) {
        double e = 0;
        for (int i = from; i < to; i++) {
            e += (double) x[i] * x[i];
        }
        return 10 * Math.log10(e / (to - from) + 1e-30);
    }

    // ================================================================= 不出事

    @Test
    public void neverAboveTheCeilingAndAlwaysFinite() {
        TurboFourVoice voice = new TurboFourVoice(RATE);
        VoiceParams p = new VoiceParams();
        float[] block = new float[2 * BLOCK];
        long seed = 11L;
        for (int b = 0; b < 3000; b++) {
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
                p.burble = ((r >> 13) & 7) / 7f;
                p.fire = (r & 0x400000) != 0 ? 1f : 0f;
                p.catchSerial += (r >> 23) & 1;
                p.brightDb = ((r >> 4) & 7) - 2f;
                p.speedKmh = (r >> 7) & 255;
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
        VoiceParams p = params(3000f, 1f);
        p.gain = 0f;
        for (float s : render(new TurboFourVoice(RATE), p, 0.2f)) {
            assertEquals(0f, s, 0f);
        }
    }

    @Test
    public void theSameInputGivesTheSameSound() {
        assertArrayEquals(render(new TurboFourVoice(RATE), params(3500f, 0.7f), 0.3f),
                render(new TurboFourVoice(RATE), params(3500f, 0.7f), 0.3f), 0f);
    }

    // ================================================================= 发动机、排气

    @Test
    public void firingOrdersLeadAndHalfOrdersFillIn() {
        // 直列四缸每 180° 点一次火：2、4、6 阶最响；缸和缸的细小差别、歧管长短让半阶次出来（参考比最响的低 13–30 dB）
        VoiceParams p = params(4500f, 1f);
        p.gain = 0.3f;
        float[] x = render(new TurboFourVoice(RATE), p, 3f);
        int n = 16384;
        int from = Math.round(2f * RATE);
        double crank = 4500 / 60.0;
        double firing = 0;
        double top = -1e9;
        for (double order : new double[] {2, 4, 6}) {
            double db = lineDb(x, from, n, order * crank);
            firing += db / 3;
            top = Math.max(top, db);
        }
        double half = 0;
        for (double order : new double[] {1.5, 2.5, 3.5, 4.5, 5.5}) {
            double db = lineDb(x, from, n, order * crank);
            half += db / 5;
            assertTrue(order + " 阶: " + (db - top), db > top - 38);
        }
        assertTrue("点火阶次比半阶次高: " + (firing - half), firing - half > 6);
    }

    @Test
    public void theBodySitsInTheMidrange() {
        // 参考全油门：300–1000 Hz 最响，1–3 kHz 低约 15 dB，3–8 kHz 低 30 dB 以上（这里放宽到 20 dB）
        VoiceParams p = params(5000f, 1f);
        p.gain = 0.3f;
        float[] x = render(new TurboFourVoice(RATE), p, 3f);
        int n = 8192;
        int from = Math.round(2f * RATE);
        double mid = bandDb(x, from, n, 300, 1000);
        double low = bandDb(x, from, n, 100, 300);
        double upper = bandDb(x, from, n, 1000, 3000);
        double top = bandDb(x, from, n, 3000, 8000);
        assertTrue("100–300 Hz 不比主体响: " + (low - mid), low < mid);
        assertTrue("1–3 kHz: " + (upper - mid), upper - mid < -8 && upper - mid > -25);
        assertTrue("3–8 kHz: " + (top - mid), top - mid < -20);
    }

    @Test
    public void theTurboLagsAtLowRevsAndKeepsSpinningAfterALift() {
        TurboFourVoice low = new TurboFourVoice(RATE);
        render(low, params(1000f, 1f), 1f);
        assertTrue("1000 rpm 排气不够，几乎没有增压: " + low.boost(), low.boost() < 0.1f);

        TurboFourVoice v = new TurboFourVoice(RATE);
        render(v, params(4000f, 0f), 0.5f);
        render(v, params(4000f, 1f), 0.15f);
        assertTrue("起压要一会儿: " + v.boost(), v.boost() < 0.7f);
        render(v, params(4000f, 1f), 1.35f);
        assertTrue("一秒半后满增压: " + v.boost(), v.boost() > 0.9f);
        VoiceParams lift = params(4000f, 0f);
        lift.overrun = 1f;
        render(v, lift, 0.3f);
        assertTrue("松油门旁通阀一开就泄掉: " + v.boost(), v.boost() < 0.2f);
        assertTrue("涡轮轴还转着: " + v.shaft(), v.shaft() > 0.4f);
    }

    @Test
    public void onlyAHighRevLiftCrackles() {
        // 刚松油门、转速高的那一两秒排气里偶尔「噗」「啪」；低转松油门没有
        TurboFourVoice high = new TurboFourVoice(RATE);
        render(high, params(5000f, 1f), 1f);
        VoiceParams lift = params(5000f, 0f);
        lift.overrun = 1f;
        render(high, lift, 0.05f);
        assertTrue("高转松油门: " + high.burble(), high.burble() > 0.5f);
        // 松油门那一下（消声器、气流噪声一下子收）不算：从 0.1 s 起数
        float[] after = render(high, lift, 1.5f);
        assertTrue("一炮一炮的高频: " + crackles(after, Math.round(0.05f * RATE)), crackles(after, Math.round(0.05f * RATE)) >= 2);
        render(high, lift, 3f);
        assertTrue("几秒后停了: " + high.burble(), high.burble() < 0.05f);

        TurboFourVoice slow = new TurboFourVoice(RATE);
        render(slow, params(2000f, 1f), 1f);
        VoiceParams easy = params(2000f, 0f);
        easy.overrun = 1f;
        render(slow, easy, 0.05f);
        float[] quiet = render(slow, easy, 1.5f);
        assertEquals("低转松油门不放炮", 0f, slow.burble(), 0f);
        assertTrue("没有一炮一炮的: " + crackles(quiet, Math.round(0.05f * RATE)), crackles(quiet, Math.round(0.05f * RATE)) <= 1);
    }

    /**
     * 从 {@code from} 起，5 ms 一格的二阶差分（高频）能量比前后半秒的中位数高 9 dB 以上的峰有几个（参考就是这么数的）。参考底下有路噪，
     * 这里没有：涡轮哨声停了以后一下一下的排气脉冲在极低的高频上也能高出 9 dB —— 一炮是宽带的，那一格高频占全部能量的份额大，
     * 只数高频不低于全部 40 dB 的。
     */
    private static int crackles(float[] all, int from) {
        float[] x = java.util.Arrays.copyOfRange(all, from, all.length);
        int f = 240;
        int n = x.length / f;
        double[] e = new double[n];
        double[] share = new double[n];
        for (int j = 0; j < n; j++) {
            double h = 0;
            double t = 0;
            for (int i = Math.max(2, j * f); i < (j + 1) * f; i++) {
                double d = x[i] - 2.0 * x[i - 1] + x[i - 2];
                h += d * d;
                t += (double) x[i] * x[i];
            }
            e[j] = 10 * Math.log10(h / f + 1e-15);
            share[j] = 10 * Math.log10((h + 1e-30) / (t + 1e-30));
        }
        int count = 0;
        for (int j = 1; j < n - 1; j++) {
            double[] around = new double[Math.min(n, j + 50) - Math.max(0, j - 50)];
            System.arraycopy(e, Math.max(0, j - 50), around, 0, around.length);
            java.util.Arrays.sort(around);
            if (e[j] - around[around.length / 2] > 9 && e[j] >= e[j - 1] && e[j] >= e[j + 1] && share[j] > -40) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void anUpshiftDipsAboutSixDb() {
        // 参考的 e-Shift 升挡：电平掉 4–7 dB、一百多毫秒
        TurboFourVoice v = new TurboFourVoice(RATE);
        VoiceParams p = params(5000f, 1f);
        p.gain = 0.3f;
        float[] before = render(v, p, 1.5f);
        p.cut = 1f;
        p.shiftSerial = 1;
        float[] cut = render(v, p, 0.12f);
        double dip = rmsDb(cut, Math.round(0.04f * RATE), cut.length) - rmsDb(before, before.length - Math.round(0.3f * RATE), before.length);
        assertTrue("掉了 " + dip + " dB", dip < -3 && dip > -10);
    }

    @Test
    public void theExhaustWarmsUpAndOpensTheSound() {
        // 同样的转速、负荷，排气热了以后管子、消声器的共振往上走（声速跟温度的平方根）
        TurboFourVoice v = new TurboFourVoice(RATE);
        render(v, params(900f, 0f), 4f);
        VoiceParams p = params(5000f, 1f);
        p.gain = 0.3f;
        float[] early = render(v, p, 0.5f);
        render(v, p, 3f);
        float[] warm = render(v, p, 0.5f);
        int n = 16384;
        double a = centroid(early, early.length - n, n, 300, 2000);
        double b = centroid(warm, warm.length - n, n, 300, 2000);
        assertTrue("重心 " + a + " → " + b, b > a * 1.04);
    }

    private static double centroid(float[] x, int from, int n, double lo, double hi) {
        double sum = 0;
        double weighted = 0;
        for (int k = (int) Math.ceil(lo * n / RATE); k <= hi * n / RATE; k++) {
            double hz = (double) k * RATE / n;
            double pw = power(x, from, n, hz);
            sum += pw;
            weighted += pw * hz;
        }
        return weighted / sum;
    }

    @Test
    public void twoTailpipesButOneBass() {
        // 两根尾管：高处各自有一点不同（出口的湍流各自独立），低频左右一样
        VoiceParams p = params(5000f, 1f);
        p.gain = 0.3f;
        float[] lr = renderStereo(new TurboFourVoice(RATE), p, 2f);
        int from = RATE;
        int to = lr.length / 2;
        Biquad[] lowL = {Biquad.lowpass(100f, 0.7071f, RATE), Biquad.lowpass(100f, 0.7071f, RATE)};
        Biquad[] lowR = {Biquad.lowpass(100f, 0.7071f, RATE), Biquad.lowpass(100f, 0.7071f, RATE)};
        double ll = 0;
        double rr = 0;
        double lrs = 0;
        double bl = 0;
        double br = 0;
        double blr = 0;
        for (int i = 0; i < to; i++) {
            float l = lr[2 * i];
            float r = lr[2 * i + 1];
            float a = lowL[1].process(lowL[0].process(l));
            float b = lowR[1].process(lowR[0].process(r));
            if (i >= from) {
                ll += l * l;
                rr += r * r;
                lrs += l * r;
                bl += a * a;
                br += b * b;
                blr += a * b;
            }
        }
        double c = lrs / Math.sqrt(ll * rr);
        double low = blr / Math.sqrt(bl * br);
        assertTrue("左右不是一样的: " + c, c < 0.999);
        assertTrue("低频左右一样: " + low, low > 0.99);
    }
}
