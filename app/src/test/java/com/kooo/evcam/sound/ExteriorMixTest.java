package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 车外喇叭那一路（项目所有者 2026-10-24 要加的外放测试）：左右合成一路、去掉小喇叭放不出的低音、压一点。
 *
 * <p>算错的表现：车外喇叭只响一边的声音（另一边丢了）、低音硬推进小喇叭（破音）、车外比车内还响。</p>
 */
public class ExteriorMixTest {

    private static final int RATE = 48_000;

    /** 立体声的正弦（左右交错）：左边 {@code left} 倍、右边 {@code right} 倍。 */
    private static float[] sine(double hz, float left, float right, int frames) {
        float[] x = new float[2 * frames];
        for (int i = 0; i < frames; i++) {
            float s = (float) Math.sin(2.0 * Math.PI * hz * i / RATE);
            x[2 * i] = left * s;
            x[2 * i + 1] = right * s;
        }
        return x;
    }

    /** 过车外那一路，后半段的 RMS（左声道）；顺带查左右一样。 */
    private static double rmsAfter(float[] in) {
        int frames = in.length / 2;
        float[] out = new float[in.length];
        new ExteriorMix(RATE).process(in, out, frames);
        double e = 0;
        for (int i = frames / 2; i < frames; i++) {
            assertEquals("左右一样（一个单声道的喇叭）", out[2 * i], out[2 * i + 1], 0f);
            e += (double) out[2 * i] * out[2 * i];
        }
        return Math.sqrt(e / (frames - frames / 2));
    }

    @Test
    public void bothSidesGoToTheOneSpeaker() {
        // 只有左边有声、只有右边有声：车外喇叭都听得到，各一半
        double left = rmsAfter(sine(1000, 1f, 0f, RATE));
        double right = rmsAfter(sine(1000, 0f, 1f, RATE));
        assertEquals(left, right, left * 1e-3);
        double both = rmsAfter(sine(1000, 1f, 1f, RATE));
        assertEquals("一半", 0.5, left / both, 0.01);
    }

    @Test
    public void midrangePassesAtTheTrim() {
        double level = rmsAfter(sine(1000, 1f, 1f, RATE)) / Math.sqrt(0.5);
        assertEquals("1 kHz 低 " + ExteriorMix.TRIM_DB + " dB", ExteriorMix.TRIM_DB, 20 * Math.log10(level), 0.5);
    }

    @Test
    public void theBassStaysOutOfTheSmallSpeaker() {
        double mid = rmsAfter(sine(1000, 1f, 1f, RATE));
        double bass = rmsAfter(sine(40, 1f, 1f, RATE));
        double db = 20 * Math.log10(bass / mid);
        assertTrue("40 Hz 比 1 kHz 低 " + db + " dB", db < -18);
    }
}
