package com.kooo.evcam.overlay;

/**
 * 悬浮按钮上单击、双击和长按各自做什么。
 *
 * <h3>为什么单击和长按默认都是「打开主界面」</h3>
 *
 * <p>这个按钮以前有两个：一个只管打开应用，一个只管开始停止录制。合成一个之后，
 * 默认必须是<b>不会误操作的那一个</b> —— 手指擦到按钮就停了录像，是行车记录仪
 * 最不该发生的事。想让它管录制，自己去设置里选。</p>
 * <p>双击默认不执行，避免改变原来单击的响应速度。</p>
 *
 * <p>存的是这里的 {@code key}，不是序号：序号会随枚举顺序变，
 * 而这几个字符串写进了用户的配置。</p>
 */
public enum FloatingAction {

    /** 把应用拉到前台。 */
    OPEN_APP("open_app"),
    /** 开始 / 停止录制。 */
    TOGGLE_RECORDING("toggle_recording"),
    /** 拍一张。 */
    TAKE_PHOTO("take_photo"),
    /** 开 / 关超级镜子。 */
    TOGGLE_MIRROR("toggle_mirror"),
    /** 不执行动作；双击默认关闭，保留原来单击的即时响应。 */
    NONE("none");

    public final String key;

    FloatingAction(String key) {
        this.key = key;
    }

    /** 认不出来的值一律回到默认 —— 配置是可以被手改的。 */
    public static FloatingAction fromKey(String key) {
        return fromKey(key, OPEN_APP);
    }

    /** Optional gestures must stay disabled when their stored value is unknown. */
    public static FloatingAction fromKey(String key, FloatingAction fallback) {
        if (key != null) {
            for (FloatingAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
        }
        return fallback;
    }
}
