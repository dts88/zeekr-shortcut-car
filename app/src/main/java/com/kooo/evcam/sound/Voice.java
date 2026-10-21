package com.kooo.evcam.sound;

/** 一种声音风格的合成器（{@link SoundStyle}）：声音线程上一块一块地调，循环里不分配内存。 */
interface Voice {

    /** 合成 {@code frames} 帧立体声，左右交错写进 {@code lr[0 .. 2 × frames)}；峰值不超过 {@link EngineVoice#CEILING}。 */
    void renderStereo(float[] lr, int frames, VoiceParams params);
}
