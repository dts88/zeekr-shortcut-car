package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「电动概念车」（试制）：峰值永远不超过 −3 dBFS、静音就是真的 0、同样的输入出同样的声音；主音跟着虚拟转速（2.6 阶）；几根音在各自的
 * 倍数上、中间是空的；扭矩越大高处的音越亮；底子一直到 8 kHz；回收时还有声；换挡「嗞」一声、断扭掉一下；左右宽。
 * 参考量出来的数在 docs/local/drive-sound-plan.md §13。
 */
public class ElectricVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;

    private static VoiceParams params(float rpm, float load) {
        VoiceParams p = new VoiceParams();
        p.rpm = rpm;
        p.load = load;
        p.level = 1f;
        p.gain = 0.3f;
        p.drive = true;
        return p;
    }

    private static float[] renderStereo(ElectricVoice voice, VoiceParams p, float seconds) {
        int blocks = Math.round(seconds * RATE / BLOCK);
        float[] out = new float[2 * blocks * BLOCK];
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            voice.renderStereo(block, BLOCK, p);
            System.arraycopy(block, 0, out, 2 * b * BLOCK, 2 * BLOCK);
        }
        return out;
    }

    private static float[] render(ElectricVoice voice, VoiceParams p, float seconds) {
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

    private static double db(double p) {
        return 10 * Math.log10(p + 1e-30);
    }

    private static double bandDb(float[] x, int from, int n, double lo, double hi) {
        double sum = 0;
        for (int k = (int) Math.ceil(lo * n / RATE); k <= hi * n / RATE; k++) {
            sum += power(x, from, n, (double) k * RATE / n);
        }
        return db(sum);
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
        ElectricVoice voice = new ElectricVoice(RATE);
        VoiceParams p = new VoiceParams();
        float[] block = new float[2 * BLOCK];
        long seed = 13L;
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
                p.brightDb = ((r >> 4) & 7) - 2f;
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
        for (float s : render(new ElectricVoice(RATE), p, 0.2f)) {
            assertEquals(0f, s, 0f);
        }
    }

    @Test
    public void theSameInputGivesTheSameSound() {
        assertArrayEquals(render(new ElectricVoice(RATE), params(3500f, 0.7f), 0.3f),
                render(new ElectricVoice(RATE), params(3500f, 0.7f), 0.3f), 0f);
    }

    // ================================================================= 音

    @Test
    public void theMainFollowsTheVirtualRpm() {
        // 参考全油门挂挡加速：主音 155–275 Hz、升挡掉回 0.7–0.8 倍 —— 和虚拟转速成正比，6000 rpm（全油门升挡点）约 260 Hz
        for (float rpm : new float[] {900f, 3000f, 6000f}) {
            ElectricVoice v = new ElectricVoice(RATE);
            render(v, params(rpm, 0.5f), 0.5f);
            double hz = ElectricVoice.MAIN_ORDER * rpm / 60.0;
            assertEquals(rpm + " rpm", hz, v.mainHz(), hz * 0.01);
        }
    }

    @Test
    public void theTonesSitOnTheirRatiosWithGapsBetween() {
        // 参考：下面八度、主音、2、4 倍，和一根 7.45 倍（全油门时都在主音 6 dB 以内）；中间没有线
        float[] x = render(new ElectricVoice(RATE), params(4000f, 1f), 2f);
        int n = 16384;
        int from = RATE;
        double f = ElectricVoice.MAIN_ORDER * 4000 / 60.0;
        double main = db(power(x, from, n, f));
        for (double r : new double[] {0.5, 2, 4, 7.45}) {
            double d = db(power(x, from, n, r * f)) - main;
            assertTrue(r + " 倍: " + d, d > -10);
        }
        for (double r : new double[] {1.25, 2.5, 5.5}) {
            double d = db(power(x, from, n, r * f)) - main;
            assertTrue(r + " 倍是空的: " + d, d < -15);
        }
    }

    @Test
    public void torqueBrightensTheHighTones() {
        // 参考：全油门时 4 倍只比主音低 1–2 dB、7.45 倍低 6 dB；轻踩时低 12–17 dB
        int n = 16384;
        int from = RATE;
        double f = ElectricVoice.MAIN_ORDER * 4000 / 60.0;
        float[] full = render(new ElectricVoice(RATE), params(4000f, 1f), 2f);
        float[] light = render(new ElectricVoice(RATE), params(4000f, 0.1f), 2f);
        for (double r : new double[] {4, 7.45}) {
            double a = db(power(full, from, n, r * f)) - db(power(full, from, n, f));
            double b = db(power(light, from, n, r * f)) - db(power(light, from, n, f));
            assertTrue(r + " 倍: 满 " + a + " 轻 " + b, a - b > 8);
        }
    }

    @Test
    public void theFloorReachesUpTo8kHzAndGrowsWithTorque() {
        // 参考：1–3 kHz 只比 300–1000 Hz 低 3–5 dB，3–8 kHz 低 8–16 dB
        int n = 8192;
        int from = RATE;
        float[] full = render(new ElectricVoice(RATE), params(5000f, 1f), 2f);
        float[] light = render(new ElectricVoice(RATE), params(5000f, 0.1f), 2f);
        double mid = bandDb(full, from, n, 300, 1000);
        double top = bandDb(full, from, n, 3000, 8000);
        assertTrue("3–8 kHz: " + (top - mid), top - mid > -16 && top - mid < -2);
        double topLight = bandDb(light, from, n, 3000, 8000);
        assertTrue("扭矩大底子响: " + (top - topLight), top - topLight > 6);
    }

    @Test
    public void regenKeepsItAlive() {
        // 松油门、刹车时电机在发电：音还在，比完全没有扭矩时响
        VoiceParams regen = params(3000f, 0f);
        regen.overrun = 1f;
        float[] a = render(new ElectricVoice(RATE), regen, 1f);
        float[] b = render(new ElectricVoice(RATE), params(3000f, 0f), 1f);
        double d = rmsDb(a, RATE / 2, a.length) - rmsDb(b, RATE / 2, b.length);
        assertTrue("回收比空转响 " + d + " dB", d > 1);
    }

    @Test
    public void anUpshiftZapsAndDips() {
        // 「嗞」不碰随机数：同样的输入、只差一次换挡，两份一减就是那一声；它很短，0.15 s 后没了
        ElectricVoice plain = new ElectricVoice(RATE);
        ElectricVoice shifted = new ElectricVoice(RATE);
        VoiceParams p = params(5000f, 1f);
        render(plain, p, 1f);
        render(shifted, p, 1f);
        VoiceParams s = params(5000f, 1f);
        s.shiftSerial = 1;
        float[] a = render(plain, p, 0.3f);
        float[] b = render(shifted, s, 0.3f);
        double early = 0;
        double late = 0;
        for (int i = 0; i < a.length; i++) {
            double d = b[i] - a[i];
            if (i < Math.round(0.08f * RATE)) {
                early += d * d;
            } else if (i > Math.round(0.15f * RATE)) {
                late += d * d;
            }
        }
        assertTrue("有一声", early > 1e-4);
        // 限幅器的增益跟着那一声动了一点点，之后只剩它的余波
        assertTrue("0.15 s 后没了: " + late / early, late < 1e-3 * early);
        // 断扭：电平掉 5–6 dB
        ElectricVoice v = new ElectricVoice(RATE);
        float[] before = render(v, p, 1f);
        VoiceParams c = params(5000f, 1f);
        c.cut = 1f;
        float[] cut = render(v, c, 0.12f);
        double dip = rmsDb(cut, Math.round(0.04f * RATE), cut.length) - rmsDb(before, before.length - Math.round(0.3f * RATE), before.length);
        assertTrue("掉了 " + dip + " dB", dip < -3 && dip > -9);
    }

    @Test
    public void theStereoIsWide() {
        // 参考左右的相关只有 0.3–0.5
        float[] lr = renderStereo(new ElectricVoice(RATE), params(5000f, 1f), 2f);
        double ll = 0;
        double rr = 0;
        double lrs = 0;
        for (int i = RATE; i < lr.length / 2; i++) {
            ll += lr[2 * i] * lr[2 * i];
            rr += lr[2 * i + 1] * lr[2 * i + 1];
            lrs += lr[2 * i] * lr[2 * i + 1];
        }
        double c = lrs / Math.sqrt(ll * rr);
        assertTrue("相关 " + c, c < 0.8 && c > 0.1);
    }
}
