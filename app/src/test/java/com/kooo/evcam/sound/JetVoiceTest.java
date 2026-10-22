package com.kooo.evcam.sound;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「超音速」（试制第二版）：峰值永远不超过 −3 dBFS、静音就是真的 0、同样的输入出同样的声音；主音跟着转子
 * （怠速约 533 Hz、转满约 1190 Hz；停着时跟油门，一松半秒多就回到怠速）；整扇哨声跟着转子往上走；一踩油门「轰」一下；升挡「音爆」。
 * 参考量出来的数在 docs/local/drive-sound-plan.md §13，阈值的原型数在 docs/local/drive-sound/check_jet2.py。
 */
public class JetVoiceTest {

    private static final int RATE = 48_000;
    private static final int BLOCK = 256;
    /** P / N 里虚拟发动机最高转到这里。 */
    private static final float FREE_MAX_RPM = 4500f;

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

    /** 从 {@code from} 起 {@code n} 个样本（汉宁窗）在 {@code lo}–{@code hi} Hz 里的频谱重心。 */
    private static double centroid(float[] x, int from, int n, double lo, double hi) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (x[from + i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)));
        }
        double sum = 0;
        double weighted = 0;
        for (int k = (int) Math.ceil(lo * n / RATE); k <= hi * n / RATE; k++) {
            double hz = (double) k * RATE / n;
            double p = power(w, 0, n, hz);
            sum += p;
            weighted += p * hz;
        }
        return weighted / sum;
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
            loud.load = (b / 20) % 2 == 0 ? 1f : 0f;
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
        assertEquals("P 挡踩到底", JetVoice.TOP_HZ, settled(FREE_MAX_RPM, 1f, false), JetVoice.TOP_HZ * 0.03f);
        // 停着踩一半：转子 = 0.5²，比转速给的低得多；主音和转子转速成正比
        double half = JetVoice.IDLE_HZ + (JetVoice.TOP_HZ - JetVoice.IDLE_HZ) * Math.pow(0.5, JetVoice.FREE_CURVE);
        assertEquals("P 挡踩一半", half, settled(FREE_MAX_RPM, 0.5f, false), half * 0.03);
    }

    @Test
    public void inGearTheWhineIsTheVirtualRpm() {
        // 项目所有者 2026-10-22 挑的参考：挂挡加速每一挡主音从低爬到高、升挡掉下来 —— 主音就是虚拟转速的 10 阶。
        // 全油门 3000 rpm 时就是 3000 rpm 的音（第二版先顶到转满的七成，几乎是平的）
        double hz3000 = JetVoice.MAIN_ORDER * 3000 / 60.0;
        assertEquals("D 挡全油门 3000 rpm", hz3000, settled(3000f, 1f, true), hz3000 * 0.03);
        double hz4500 = JetVoice.MAIN_ORDER * 4500 / 60.0;
        assertEquals("D 挡小油门 4500 rpm", hz4500, settled(4500f, 0.2f, true), hz4500 * 0.03);
    }

    private static float settled(float rpm, float load, boolean drive) {
        JetVoice v = new JetVoice(RATE);
        render(v, params(rpm, load, drive), 1f, -1, 0f, 0, true);
        return v.mainHz();
    }

    @Test
    public void parkedItFallsBackSoonAfterALift() {
        // 停着踩满，虚拟转速还挂在高处（它落得慢），主音只跟转子：一松 0.7 s 落到顶上的三到六成（参考 1026 → 450 Hz），
        // 3 s 回到怠速附近
        JetVoice v = new JetVoice(RATE);
        int at = 100;
        render(v, params(FREE_MAX_RPM, 1f, false), (at * BLOCK + 0.7f * RATE) / RATE, at, 0f, 0, true);
        float soon = v.mainHz();
        assertTrue("0.7 s: " + soon, soon > 0.3f * JetVoice.TOP_HZ && soon < 0.6f * JetVoice.TOP_HZ);
        render(v, params(FREE_MAX_RPM, 0f, false), 2.3f, -1, 0f, 0, true);
        assertTrue("3 s: " + v.mainHz(), v.mainHz() < JetVoice.IDLE_HZ * 1.15f);
    }

    @Test
    public void theWholeFanRisesWithTheSpool() {
        // 60–2500 Hz 的频谱重心：转满比怠速高得多（整扇线一起往上走，不只是主音；2026-10-22 起主音和转速成正比，
        // 怠速时整扇线落在 60–250 Hz）
        float[] idle = render(params(900f, 0f, true), 1f);
        float[] full = render(params(JetVoice.FULL_RPM, 1f, true), 1f);
        int from = Math.round(0.6f * RATE);
        int n = 16384;
        double ratio = centroid(full, from, n, 60, 2500) / centroid(idle, from, n, 60, 2500);
        assertTrue("重心: " + ratio, ratio > 2);
    }

    @Test
    public void atFullSpoolTheWhineIsAFullRowOfHarmonics() {
        // 项目所有者 2026-10-22：电音多了，原厂那种「多频率的齐鸣」、厚度不够。参考停着踩到顶那一刻（85 ms 一帧，200–2000 Hz）：
        // 20 dB 以内 13–22 根线，线和线之间是满的（中间那一格只比最响的低 19–27 dB）；第二版只有 9–12 根干净的线，中间低 35 dB
        JetVoice v = new JetVoice(RATE);
        VoiceParams p = params(FREE_MAX_RPM, 1f, false);
        p.gain = 0.1f;
        float[] x = render(v, p, 1.7f, -1, 0f, 0, true);
        int n = 4096;
        for (float t : new float[] {1.2f, 1.4f, 1.6f}) {
            int from = Math.round(t * RATE) - n / 2;
            float[] w = new float[n];
            for (int i = 0; i < n; i++) {
                w[i] = (float) (x[from + i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)));
            }
            int k0 = (int) Math.ceil(200.0 * n / RATE);
            int k1 = (int) (2000.0 * n / RATE);
            double[] pw = new double[k1 - k0 + 1];
            double max = 0;
            for (int k = k0; k <= k1; k++) {
                pw[k - k0] = power(w, 0, n, (double) k * RATE / n);
                max = Math.max(max, pw[k - k0]);
            }
            int lines = 0;
            for (int i = 2; i < pw.length - 2; i++) {
                if (pw[i] >= pw[i - 1] && pw[i] >= pw[i + 1] && pw[i] > 0.01 * max && pw[i] > 4 * Math.min(pw[i - 2], pw[i + 2])) {
                    lines++;
                }
            }
            double[] sorted = pw.clone();
            java.util.Arrays.sort(sorted);
            double median = 10 * Math.log10(sorted[sorted.length / 2] / max);
            assertTrue(t + " s 20 dB 以内的线: " + lines, lines >= 12);
            assertTrue(t + " s 中间那一格比最响的低: " + median, median > -28);
        }
    }

    @Test
    public void theDroneHasNoRhythm() {
        // 项目所有者 2026-10-22：上一版一串一串有节奏、像活塞发动机；飞机应该是嗡嗡的，没有明显的节奏。300–2000 Hz 的包络
        // （去掉 0.7 s 以上的慢变）在每秒 2–20 下这一段里，最突出的那个比 2–60 下的中位数高不到 15 dB（参考 8–12 dB，散在各处；
        // 一整排谐波 + 成对的那一版 21–26 dB，在 10–13 Hz）。停着踩到顶、D 挡 4800 rpm 小油门各看一次
        for (VoiceParams p : new VoiceParams[] {params(FREE_MAX_RPM, 1f, false), params(4800f, 0.4f, true)}) {
            p.gain = 0.1f;
            float[] x = band(render(new JetVoice(RATE), p, 6.5f, -1, p.load, 0, true), 300f, 2000f);
            int from = Math.round(1.5f * RATE);
            int hop = 128;
            // 整流、一阶低通（80 Hz）、每 hop 个样本取一点
            double k = 1.0 - Math.exp(-2.0 * Math.PI * 80.0 / RATE);
            double lp = 0;
            int n = (x.length - from) / hop;
            double[] env = new double[n];
            for (int i = from; i < from + n * hop; i++) {
                lp += k * (Math.abs(x[i]) - lp);
                if ((i - from) % hop == hop - 1) {
                    env[(i - from) / hop] = lp;
                }
            }
            double fs = (double) RATE / hop;
            int h = (int) (0.35 * fs);
            float[] d = new float[n];
            for (int i = 0; i < n; i++) {
                double s = 0;
                int c = 0;
                for (int j = Math.max(0, i - h); j < Math.min(n, i + h); j++) {
                    s += env[j];
                    c++;
                }
                d[i] = (float) ((env[i] - s / c) * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / n)));
            }
            java.util.List<Double> all = new java.util.ArrayList<>();
            double max = 0;
            double maxHz = 0;
            for (double f = 2.0; f <= 60.0; f += 0.25) {
                double pw = goertzelAt(d, fs, f);
                all.add(pw);
                if (f <= 20.0 && pw > max) {
                    max = pw;
                    maxHz = f;
                }
            }
            java.util.Collections.sort(all);
            double over = 10 * Math.log10(max / all.get(all.size() / 2));
            assertTrue((p.drive ? "D 挡" : "停着踩到顶") + " 包络在 " + maxHz + " Hz 高出 " + over + " dB", over < 15);
        }
    }

    /** {@code x}（采样率 {@code fs}）在 {@code hz} 上的功率（Goertzel）。 */
    private static double goertzelAt(float[] x, double fs, double hz) {
        double c = 2.0 * Math.cos(2.0 * Math.PI * hz / fs);
        double s1 = 0;
        double s2 = 0;
        for (float v : x) {
            double s0 = v + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2;
    }

    @Test
    public void theWhineLeavesNoHissFloor() {
        // 项目所有者 2026-10-22：超音速有很严重的噪音，耳机、电脑音箱、车上喇叭都听得到。是哨声线的相位抖动：每 16 个样本一下跳过去
        // （每秒 3000 个小台阶），又只有一级低通（每根线两边拖着长长的一片）。10–20 kHz 比全部低 48 dB 以上（第三版怠速 −56、
        // D 挡 2500 rpm −53，第五版都是 −38）；怠速时 700–1400 Hz（线都在 300 Hz 以下）低 38 dB 以上（第三版 −49，第五版 −29）
        for (VoiceParams p : new VoiceParams[] {params(900f, 0f, false), params(2500f, 0.3f, true)}) {
            p.gain = 0.1f;
            float[] x = render(p, 3.5f);
            int from = Math.round(1.5f * RATE);
            double all = energy(x, from, x.length);
            double high = 10 * Math.log10(energy(band(x, 10000f, 20000f), from, x.length) / all);
            String what = p.drive ? "D 挡 2500 rpm" : "怠速";
            assertTrue(what + " 10–20 kHz 比全部低 " + -high + " dB", high < -48);
            if (!p.drive) {
                double skirt = 10 * Math.log10(energy(band(x, 700f, 1400f), from, x.length) / all);
                assertTrue(what + " 700–1400 Hz 比全部低 " + -skirt + " dB", skirt < -38);
            }
        }
    }

    // ================================================================= 喷流、换挡

    @Test
    public void aStabWhoomps() {
        // 3000 rpm 不动，第 40 块负荷 0 → 1：头 150 ms 的 40–100 Hz 比 0.7 s 之后同样长的一段大得多（原型十个种子最差 5.35 倍）。
        // 音量压低，不让限幅把「轰」那一下压扁（2026-10-22 起哨声是一整排谐波，满音量时限幅压得多）
        VoiceParams p = params(3000f, 0f, true);
        p.gain = 0.1f;
        float[] x = band(render(new JetVoice(RATE), p, 1.4f, 40, 1f, 0, true), 40f, 100f);
        int at = 40 * BLOCK;
        int n = Math.round(0.15f * RATE);
        int later = at + Math.round(0.7f * RATE);
        double ratio = energy(x, at, at + n) / energy(x, later, later + n);
        assertTrue("「轰」: " + ratio, ratio > 2.5);
    }

    @Test
    public void aStopSpoolsDownToSilence() {
        // 熄火：虚拟转速 0.7 秒从怠速落到 0，转子跟着落下去，音调往下走、越来越轻
        JetVoice v = new JetVoice(RATE);
        VoiceParams p = params(900f, 0f, false);
        render(v, p, 1f, -1, 0f, 0, true);
        float before = v.mainHz();
        p.fire = 0f;
        int blocks = Math.round(0.7f * RATE / BLOCK);
        float[] block = new float[2 * BLOCK];
        for (int b = 0; b < blocks; b++) {
            p.rpm = 900f * (1f - (b + 1f) / blocks);
            v.renderStereo(block, BLOCK, p);
        }
        assertTrue("音调往下走: " + before + " → " + v.mainHz(), v.mainHz() < 0.6f * before);
        p.rpm = 0f;
        float[] after = render(v, p, 0.5f, -1, 0f, 0, true);
        float[] idle = render(params(900f, 0f, false), 1f);
        int n = Math.round(0.2f * RATE);
        assertTrue("停住以后没声了",
                energy(after, after.length - n, after.length) < 1e-4 * energy(idle, idle.length - n, idle.length));
    }

    @Test
    public void anUpshiftBooms() {
        // 同一套随机数、只差换挡记号（第 300 块，起步那一下「轰」已经过去）：记号变之前一点不差；之后差出来的
        // （「音爆」、一捶、「轰」）不小（原型比发动机低 4.9 dB），大半在 200 Hz 以下（原型 79 %）；
        // 降挡没有「音爆」，只「轰」一点（原型是升挡的 31 %）
        int at = 300;
        float[] same = render(new JetVoice(RATE), params(5000f, 1f, true), 2f, at, 1f, 0, true);
        float[] up = render(new JetVoice(RATE), params(5000f, 1f, true), 2f, at, 1f, 1, true);
        float[] down = render(new JetVoice(RATE), params(5000f, 1f, true), 2f, at, 1f, 1, false);
        int from = at * BLOCK;
        for (int i = 0; i < from; i++) {
            assertEquals(same[i], up[i], 0f);
        }
        int n = Math.round(0.15f * RATE);
        float[] dUp = new float[same.length];
        float[] dDown = new float[same.length];
        for (int i = 0; i < same.length; i++) {
            dUp[i] = up[i] - same[i];
            dDown[i] = down[i] - same[i];
        }
        double diff = energy(dUp, from, from + n);
        assertTrue("差得不小", diff > 0.1 * energy(same, from, from + n));
        assertTrue("大半在低频", energy(band(dUp, 20f, 200f), from, from + n) > 0.5 * diff);
        double downDiff = energy(dDown, from, from + n);
        assertTrue("降挡差得少: " + downDiff / diff, downDiff < 0.6 * diff && downDiff > 0.05 * diff);
    }
}
