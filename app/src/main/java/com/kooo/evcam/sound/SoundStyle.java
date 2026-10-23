package com.kooo.evcam.sound;

import com.kooo.evcam.AppConfig;

/**
 * 声音风格（开发者选项「声音风格」）。项目所有者 2026-10-21：第一版太单薄，按发动机的结构重做（{@link PulseVoice}），
 * 「美式 V8」（机械增压交叉平面 V8）这一种取向；第一版留着，叫「试制 V8」；另做一种纯电车主动声浪的「超音速」（{@link JetVoice}，试制）。
 * 「超音速（旧版）」（{@link JetOldVoice}）是 2026-10-22 的那一版，车主要和新的装车对比（2026-10-23）。
 * 虚拟发动机（转速、负荷怎么跟车走）各风格都一样，只换合成器。
 */
enum SoundStyle {
    /** 美式 V8：机械增压交叉平面 V8（默认）。 */
    AMERICAN(AppConfig.DRIVE_SOUND_STYLE_AMERICAN),
    /** 试制 V8：第一版，阶次正弦加噪声，左右一样。 */
    PROTOTYPE(AppConfig.DRIVE_SOUND_STYLE_PROTOTYPE),
    /** 超音速：双发喷气机的取向（试制）。 */
    JET(AppConfig.DRIVE_SOUND_STYLE_JET),
    /** 超音速（旧版）：2026-10-22 的那一版（{@link JetOldVoice}），和新的装车对比（项目所有者 2026-10-23）。 */
    JET_OLD(AppConfig.DRIVE_SOUND_STYLE_JET_OLD);

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

    /** 开发者选项「听感」对它有没有用：只有「美式 V8」分车外、车内。 */
    boolean hasViews() {
        return this == AMERICAN;
    }

    /**
     * 开发者选项「变速箱」对它有没有用：两种超音速没有 —— 喷气机本来没有变速箱，换挡是参考车的 N e-Shift（模拟 8 速双离合）
     * 做出来的，固定按 8 速走（视频里升挡掉回 0.65–0.8 倍，和 8 速的级差对得上）。项目所有者 2026-10-23。
     */
    boolean choosesGearbox() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    /** 这种风格实际用的变速箱：两种超音速固定 8 速，别的照开发者选项。 */
    Gearbox gearbox(Gearbox chosen) {
        return choosesGearbox() ? chosen : Gearbox.AUTO8;
    }

    /**
     * 「连续」走法里油门接近踩到底时走不走伪挡位（{@link VirtualEngine}）：两种 V8 走（项目所有者 2026-10-21）；两种超音速不走，
     * 一路平滑上去 —— 参考车关掉 e-Shift 时就是这样（项目所有者 2026-10-23）。
     */
    boolean pseudoGears() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    /** 这种风格的合成器；{@code cabin}：车内听感（只有「美式 V8」分车内、车外，别的本来就是按车内做的）。 */
    Voice voice(float sampleRate, boolean cabin) {
        switch (this) {
            case PROTOTYPE:
                return new EngineVoice(SoundProfile.V8, sampleRate);
            case JET:
                return new JetVoice(sampleRate);
            case JET_OLD:
                return new JetOldVoice(sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate, cabin);
        }
    }
}
