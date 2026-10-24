package com.kooo.evcam.sound;

/**
 * 车外喇叭那一路（项目所有者 2026-10-24 要加的外放测试，{@link CarSpeakers#OUTSIDE_NOTIFY}）：车外只有一个单声道的小喇叭 ——
 * 左右合成一路（两个声道写一样的，车机按它的一个声道放）；过二阶高通（{@link #HIGHPASS_HZ}：小喇叭放不出低音，硬推只会破）；
 * 再乘车外的音量（「模拟声浪」页的「车外音量」，平滑地变），最后过限幅（{@link Limiter}，峰值不过 {@link EngineVoice#CEILING}）：
 * 车外喇叭在车机里是另一个音量组，音量键调不到它，只能在软件里加；加多了靠限幅压住，不削波。
 * 项目所有者 2026-10-24 实车：车外音量过低（原来还低 6 dB）。
 * 纯计算（{@code ExteriorMixTest}），声音线程上用，不分配内存。
 */
final class ExteriorMix {

    static final float HIGHPASS_HZ = 150f;

    private final Biquad highpass;
    private final Limiter limiter;
    private final float gainK;
    private float gain = -1f;

    ExteriorMix(float sampleRate) {
        highpass = Biquad.highpass(HIGHPASS_HZ, 0.7071f, sampleRate);
        limiter = new Limiter(sampleRate);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
    }

    /**
     * 把立体声 {@code in}（左右交错，{@code frames} 帧）合成车外那一路写进 {@code out}（左右交错，两个声道一样）；
     * {@code targetGain} 是车外音量（倍数），逐样本平滑地过去。
     */
    void process(float[] in, float[] out, int frames, float targetGain) {
        if (gain < 0f) {
            gain = targetGain;
        }
        for (int i = 0; i < frames; i++) {
            gain += (targetGain - gain) * gainK;
            float m = highpass.process(0.5f * (in[2 * i] + in[2 * i + 1])) * gain;
            limiter.push(m, m);
            out[2 * i] = limiter.left;
            out[2 * i + 1] = limiter.right;
        }
    }
}
