package com.kooo.evcam.sound;

/**
 * 车外喇叭那一路（项目所有者 2026-10-24 要加的外放测试，{@link CarSpeakers#OUTSIDE_NOTIFY}）：车外只有一个单声道的小喇叭 ——
 * 左右合成一路（两个声道写一样的，车机按它的一个声道放）；过二阶高通（{@link #HIGHPASS_HZ}：小喇叭放不出低音，硬推只会破），
 * 再低 {@link #TRIM_DB}（系统的音量不一定管得到车外，先保守一点，上车听了再定）。
 * 纯计算（{@code ExteriorMixTest}），声音线程上用，不分配内存。
 */
final class ExteriorMix {

    static final float HIGHPASS_HZ = 150f;
    static final float TRIM_DB = -6f;

    private final Biquad highpass;
    private final float trim;

    ExteriorMix(float sampleRate) {
        highpass = Biquad.highpass(HIGHPASS_HZ, 0.7071f, sampleRate);
        trim = EngineVoice.dbToLin(TRIM_DB);
    }

    /** 把立体声 {@code in}（左右交错，{@code frames} 帧）合成车外那一路写进 {@code out}（左右交错，两个声道一样）。 */
    void process(float[] in, float[] out, int frames) {
        for (int i = 0; i < frames; i++) {
            float m = highpass.process(0.5f * (in[2 * i] + in[2 * i + 1])) * trim;
            out[2 * i] = m;
            out[2 * i + 1] = m;
        }
    }
}
