package com.kooo.evcam.zeekr;

/**
 * 超级镜子的「切换范围」：在哪几路画面之间切换（项目所有者 2026-10-19，取代「只显示前后视」开关）。
 *
 * <ul>
 *   <li>{@link #FOUR} 四向（默认）：环视的前、后、左、右；</li>
 *   <li>{@link #FRONT_REAR} 前后：环视的前、后；</li>
 *   <li>{@link #REAR_CABIN} 后视 + 后座舱：环视的后视，和后座舱摄像头。超级镜子开着时后座舱也一直开着，
 *       切过去不用等 —— 登记表上超级镜子这一项除了环视还要后座舱（ChannelRules），配置里没启用后座舱也照样建它
 *       （CameraManagerHolder）。</li>
 * </ul>
 *
 * <p>存的是 {@link #key}，不是序号：序号会随枚举顺序变，而这几个字符串写进了用户的配置。</p>
 *
 * <p>纯 Java，不碰 Android。</p>
 */
public enum MirrorViews {

    FOUR("four"),
    FRONT_REAR("front_rear"),
    REAR_CABIN("rear_cabin");

    public final String key;

    MirrorViews(String key) {
        this.key = key;
    }

    /** 认不出来的值回到默认（四向）—— 配置是可以被手改的。 */
    public static MirrorViews fromKey(String key) {
        if (key != null) {
            for (MirrorViews views : values()) {
                if (views.key.equals(key)) {
                    return views;
                }
            }
        }
        return FOUR;
    }

    /** 只分上下两半的那两档（按键模式、点中时闪的形状）。 */
    public boolean twoViews() {
        return this != FOUR;
    }
}
