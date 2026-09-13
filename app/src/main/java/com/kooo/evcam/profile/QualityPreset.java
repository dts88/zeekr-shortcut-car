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

    /** 省空间：看得清发生了什么，看不清对面车牌。 */
    SAVE_SPACE("space", "15", StreamSpec.BITRATE_LOW),

    /** 均衡：日常行车够用，出事时看得清。默认。 */
    BALANCED("balanced", StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_MEDIUM),

    /** 最清晰：看得清对面车牌，编码器接近满负荷。 */
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

    /** 这一路的录制参数是不是正好是这一档。不是就该标「已细调」。 */
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

    /** 把这一档写进整份配置里每一路。 */
    public void applyTo(Profile profile) {
        if (profile == null) {
            return;
        }
        for (CameraProfile camera : profile.cameras) {
            applyTo(camera.record);
        }
    }

    /**
     * 这份配置现在整体上是哪一档 —— 每一路都正好是那一档时才算。
     *
     * @return 对得上的那一档；哪一路都对不上，或者几路各不相同，返回 null
     */
    public static QualityPreset of(Profile profile) {
        if (profile == null || profile.cameras.isEmpty()) {
            return null;
        }
        for (QualityPreset preset : values()) {
            boolean all = true;
            for (CameraProfile camera : profile.cameras) {
                if (!preset.matches(camera.record)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return preset;
            }
        }
        return null;
    }
}
