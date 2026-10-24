package com.kooo.evcam.sound;

import com.kooo.evcam.AppConfig;

/**
 * 声音风格（「模拟声浪」页的「声音风格」）。项目所有者 2026-10-21：第一版太单薄，按发动机的结构重做（{@link PulseVoice}），
 * 「美式 V8」（机械增压交叉平面 V8）这一种取向；第一版留着，叫「试制 V8」；另做一种纯电车主动声浪的「超音速」（{@link JetVoice}，试制）。
 * 「超音速（旧版）」（{@link JetOldVoice}）是 2026-10-22 的那一版，车主要和新的装车对比（2026-10-23）。
 * 「涡轮四缸」（{@link TurboFourVoice}）、「电动概念车」（{@link ElectricVoice}）照参考车另外两种主题、用同样的做法仿制（2026-10-23，试制）。
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
    JET_OLD(AppConfig.DRIVE_SOUND_STYLE_JET_OLD),
    /** 涡轮四缸：参考车模仿汽油机的那一种主题（{@link TurboFourVoice}，试制）。 */
    TURBO4(AppConfig.DRIVE_SOUND_STYLE_TURBO4),
    /** 电动概念车：参考车电动概念车声的那一种主题（{@link ElectricVoice}，试制）。 */
    ELECTRIC(AppConfig.DRIVE_SOUND_STYLE_ELECTRIC);

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
     * 启动时带不带起动机那一段（{@link VirtualEngine}）：只有「试制 V8」带；「美式 V8」「涡轮四缸」直接点着（项目所有者 2026-10-21：
     * 不要起动机那几声啸叫），「超音速」「电动概念车」直接起转。
     */
    boolean cranks() {
        return this == PROTOTYPE;
    }

    /** 「模拟声浪」页的「听感」对它有没有用：只有「美式 V8」分车外、车内。 */
    boolean hasViews() {
        return this == AMERICAN;
    }

    /**
     * 「模拟声浪」页的「变速箱」对它有没有用：两种超音速没有 —— 喷气机本来没有变速箱，换挡是参考车的 N e-Shift（模拟 8 速双离合）
     * 做出来的，固定按 8 速走（视频里升挡掉回 0.65–0.8 倍，和 8 速的级差对得上）。项目所有者 2026-10-23。
     * 「涡轮四缸」「电动概念车」也是参考车的主题，换挡同样是 e-Shift 的 8 速（参考升挡掉回 0.68–0.82 倍）。
     */
    boolean choosesGearbox() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    /** 这种风格实际用的变速箱：两种 V8 照「模拟声浪」页的选择，别的（参考车的主题）固定 8 速。 */
    Gearbox gearbox(Gearbox chosen) {
        return choosesGearbox() ? chosen : Gearbox.AUTO8;
    }

    /**
     * 「连续」走法里油门接近踩到底时走不走伪挡位（{@link VirtualEngine}）：两种 V8 走（项目所有者 2026-10-21）；参考车的主题
     * （两种超音速、涡轮四缸、电动概念车）不走，一路平滑上去 —— 参考车关掉 e-Shift 时就是这样（项目所有者 2026-10-23；
     * 评测说关掉 e-Shift 时涡轮四缸那一种像配了无级变速）。
     */
    boolean pseudoGears() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    /**
     * 空挡（P / N）照真车（{@link VirtualEngine}）：松油门后按摩擦、泵气落转速，低转恢复供油、低负荷地转着往下落，落到怠速时电控
     * 稳一下。只有「美式 V8」（项目所有者 2026-10-23 给的参考视频）；别的照原来平滑地落。
     */
    boolean realNeutral() {
        return this == AMERICAN;
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
            case TURBO4:
                return new TurboFourVoice(sampleRate);
            case ELECTRIC:
                return new ElectricVoice(sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate, cabin);
        }
    }
}
