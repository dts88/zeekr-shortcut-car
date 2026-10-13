package com.kooo.evcam.profile;

/**
 * 「录成什么样」的三档。
 *
 * <h3>为什么先问这个，再问参数</h3>
 *
 * <p>配置编辑原来摆的是参数本身：每一路九行，分辨率、帧率、码率、编码、分段……
 * 三路就是二十七行形状一样的东西。但决定这些数的人，心里想的不是「码率要中还是高」，
 * 而是「够不够清楚」和「能录多久」—— 参数是答案，不是问题。</p>
 *
 * <p>所以先选一档，一次定下三路的帧率和码率；想单独改某一路，再展开细调。
 * 细调过的那一路会被标出来（{@link #matches}），因为「我选了均衡，但后座舱不是」
 * 这件事必须看得见。</p>
 *
 * <p>选的是哪一档存在配置里（{@link Profile#quality}），标记拿它来比。
 * 不能从各路的参数倒推：单独改过一路之后几路各不相同，推出来的是「哪一档都不是」，
 * 于是三张卡一张都不亮，被改过的那一路也没有可比的对象。</p>
 *
 * <h3>帧率</h3>
 *
 * <p>10 / 20 / 不限。帧率和码率同方向走：帧数少了，同样的码率落在每一帧上就多，
 * 所以省空间那一档降帧率并不意味着每一帧更糊 —— 它省下的是总量。</p>
 *
 * <h3>为什么不动分辨率</h3>
 *
 * <p>三档只改帧率和码率。分辨率在这台车机上没有可省的余地：环视那一路的
 * {@code auto} 已经是「每格最清楚」的那个声明尺寸，往下调一档就等于把证据丢掉，
 * 而省下来的空间还不如把码率降一档多。省空间要降的是<b>每帧多少比特</b>，
 * 不是<b>多少像素</b>。</p>
 *
 * <p>纯数据，不碰 Android，可以单独测。</p>
 */
public enum QualityPreset {

    /** 省空间：10 fps + 低码率，继续记录全过程，细节有所压缩。 */
    SAVE_SPACE("space", "10", StreamSpec.BITRATE_LOW),

    /** 均衡：20 fps + 中码率。默认。 */
    BALANCED("balanced", "20", StreamSpec.BITRATE_MEDIUM),

    /** 最清晰：不限帧率 + 高码率，编码器接近满负荷。 */
    SHARPEST("sharp", StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_HIGH);

    /** 存进配置里的那个词。存词不存序号：序号会随枚举顺序变。 */
    public final String key;
    public final String fps;
    public final String bitrate;

    QualityPreset(String key, String fps, String bitrate) {
        this.key = key;
        this.fps = fps;
        this.bitrate = bitrate;
    }

    /** 认不出来的值一律当「均衡」—— 配置是可以被手改的。 */
    public static QualityPreset fromKey(String key) {
        for (QualityPreset preset : values()) {
            if (preset.key.equals(key)) {
                return preset;
            }
        }
        return BALANCED;
    }

    /** 这一路的录制参数是不是正好是这一档。不是就该标「自定义」。 */
    public boolean matches(StreamSpec record) {
        if (record == null) {
            return false;
        }
        return fps.equals(record.fps) && bitrate.equals(record.bitrate);
    }

    /** 把这一档写进一路的录制参数。其余的（分辨率、编码、分段）不动。 */
    public void applyTo(StreamSpec record) {
        if (record != null) {
            record.fps = fps;
            record.bitrate = bitrate;
        }
    }

    /** 把这一档写进整份配置里每一路，并记下选的是这一档。 */
    public void applyTo(Profile profile) {
        if (profile == null) {
            return;
        }
        profile.quality = this;
        for (CameraProfile camera : profile.cameras) {
            applyTo(camera.record);
        }
    }

    /**
     * 早先存下的配置里没记选的是哪一档，读的时候从开着的那几路倒推一个。
     *
     * <p>哪一档对得上的路最多就是哪一档：单独改过一路之后，其余几路还是当初选的那一档。
     * 关着的不算 —— 后来补上的那几路是默认值，不代表当初选了什么。
     * 一样多（包括一路都对不上）时算「均衡」，和认不出来的词一样（{@link #fromKey}）。</p>
     *
     * <p>只在读老配置时用一次：存过之后，选的那一档就记在配置里了。</p>
     */
    static QualityPreset inferredFrom(Profile profile) {
        QualityPreset best = BALANCED;
        int bestCount = enabledMatching(profile, BALANCED);
        for (QualityPreset preset : values()) {
            int count = enabledMatching(profile, preset);
            if (count > bestCount) {
                best = preset;
                bestCount = count;
            }
        }
        return best;
    }

    private static int enabledMatching(Profile profile, QualityPreset preset) {
        int count = 0;
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled && preset.matches(camera.record)) {
                count++;
            }
        }
        return count;
    }
}
