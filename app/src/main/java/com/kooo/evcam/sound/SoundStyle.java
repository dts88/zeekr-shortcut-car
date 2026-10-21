package com.kooo.evcam.sound;

import com.kooo.evcam.AppConfig;

/**
 * 声音风格（开发者选项「声音风格」）。项目所有者 2026-10-21：第一版太单薄，按发动机的结构重做（{@link PulseVoice}）；
 * 第一版留着，叫「经典 V8」。虚拟发动机（转速、负荷怎么跟车走）各风格都一样，只换合成器。
 */
enum SoundStyle {
    /** 机械增压交叉平面 V8（默认）。 */
    SUPERCHARGED(AppConfig.DRIVE_SOUND_STYLE_SUPERCHARGED),
    /** 双涡轮交叉平面 V8：起压、泄压、叶轮掉转速。 */
    TWIN_TURBO(AppConfig.DRIVE_SOUND_STYLE_TWIN_TURBO),
    /** 第一版：阶次正弦加噪声，左右一样。 */
    CLASSIC(AppConfig.DRIVE_SOUND_STYLE_CLASSIC);

    /** 设置里存的值。 */
    final String key;

    SoundStyle(String key) {
        this.key = key;
    }

    /** 设置里存的值 → 风格；认不出的按默认。 */
    static SoundStyle of(String key) {
        for (SoundStyle s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return SUPERCHARGED;
    }

    /** 这种风格的合成器。 */
    Voice voice(float sampleRate) {
        switch (this) {
            case TWIN_TURBO:
                return new PulseVoice(PulseProfile.TWIN_TURBO_V8, sampleRate);
            case CLASSIC:
                return new EngineVoice(SoundProfile.V8, sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate);
        }
    }
}
