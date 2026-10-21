package com.kooo.evcam.sound;

import com.kooo.evcam.AppConfig;

/**
 * 声音风格（开发者选项「声音风格」）。项目所有者 2026-10-21：第一版太单薄，按发动机的结构重做（{@link PulseVoice}），
 * 「美式 V8」（机械增压交叉平面 V8）这一种取向；第一版留着，叫「试制 V8」；另做一种纯电车主动声浪的「超音速」（{@link JetVoice}，试制）。
 * 虚拟发动机（转速、负荷怎么跟车走）各风格都一样，只换合成器。
 */
enum SoundStyle {
    /** 美式 V8：机械增压交叉平面 V8（默认）。 */
    AMERICAN(AppConfig.DRIVE_SOUND_STYLE_AMERICAN),
    /** 试制 V8：第一版，阶次正弦加噪声，左右一样。 */
    PROTOTYPE(AppConfig.DRIVE_SOUND_STYLE_PROTOTYPE),
    /** 超音速：双发喷气机的取向（试制）。 */
    JET(AppConfig.DRIVE_SOUND_STYLE_JET);

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
        return AMERICAN;
    }

    /**
     * 启动时带不带起动机那一段（{@link VirtualEngine}）：只有「试制 V8」带；「美式 V8」直接点着（项目所有者 2026-10-21：
     * 不要起动机那几声啸叫），「超音速」直接起转。
     */
    boolean cranks() {
        return this == PROTOTYPE;
    }

    /** 这种风格的合成器；{@code cabin}：车内听感（只有「美式 V8」分车内、车外，另两种本来就是按车内做的）。 */
    Voice voice(float sampleRate, boolean cabin) {
        switch (this) {
            case PROTOTYPE:
                return new EngineVoice(SoundProfile.V8, sampleRate);
            case JET:
                return new JetVoice(sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate, cabin);
        }
    }
}
