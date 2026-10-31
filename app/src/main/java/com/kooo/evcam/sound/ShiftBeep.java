package com.kooo.evcam.sound;

/**
 * 灵动贴换挡的提示音（项目所有者 2026-10-31）：短促的一声。人选的挡进了红线区、按键要的挡做不了时响（{@link VirtualEngine#beepSerial}）。
 * 只混进车内那一路（{@link DriveSound}）；响的时候发动机让开一点，加起来不超过合成器的上限（{@link EngineVoice#CEILING}）。
 * 纯计算（{@code ShiftBeepTest}），声音线程上调，不分配内存。
 */
final class ShiftBeep {

    /** 音高（Hz）：比发动机的主要能量高得多，听得出不是发动机。 */
    static final float HZ = 1760f;
    /** 一声多长（秒）。 */
    static final float SEC = 0.09f;
    /** 开头、结尾淡入淡出多久（秒）：不出「咔」。 */
    static final float EDGE_SEC = 0.005f;
    /** 这一声的峰值（声浪音量 0 dB 时）。 */
    static final float LEVEL = 0.3f;

    private final double radPerSample;
    private final int length;
    private final int edge;
    /** 这一声放到第几个样本；放完是 {@link #length}。 */
    private int pos;

    ShiftBeep(float sampleRate) {
        radPerSample = 2.0 * Math.PI * HZ / sampleRate;
        length = Math.round(SEC * sampleRate);
        edge = Math.max(1, Math.round(EDGE_SEC * sampleRate));
        pos = length;
    }

    /** 从头响一声。 */
    void start() {
        pos = 0;
    }

    /** 混进 {@code lr[0 .. 2 × frames)}（立体声交错）；{@code gain}：门控 × 声浪音量（线性）。没在响就不动它。 */
    void mix(float[] lr, int frames, float gain) {
        for (int i = 0; i < frames && pos < length; i++, pos++) {
            float env = Math.min(1f, (float) Math.min(pos, length - 1 - pos) / edge);
            float level = LEVEL * env * gain;
            float s = level * (float) Math.sin(radPerSample * pos);
            float keep = 1f - level / EngineVoice.CEILING;
            lr[2 * i] = lr[2 * i] * keep + s;
            lr[2 * i + 1] = lr[2 * i + 1] * keep + s;
        }
    }
}
