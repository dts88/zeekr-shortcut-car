package com.kooo.evcam.overlay;

/**
 * 悬浮按钮上单击和长按各自做什么。
 *
 * <h3>为什么默认两个都是「打开主界面」</h3>
 *
 * <p>这个按钮以前有两个：一个只管打开应用，一个只管开始停止录制。合成一个之后，
 * 默认必须是<b>不会误操作的那一个</b> —— 手指擦到按钮就停了录像，是行车记录仪
 * 最不该发生的事。想让它管录制，自己去设置里选。</p>
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
    /** 锁定此刻前后 10 秒所在的录像：和闪远光自动锁定同一套（{@code AutoLock#lockNow}）。 */
    LOCK_FOOTAGE("lock_footage"),
    /** 开 / 关模拟声浪（设置 → 系统「模拟声浪（试验性）」）：开车时一键关的地方。 */
    TOGGLE_DRIVE_SOUND("toggle_drive_sound");

    public final String key;

    FloatingAction(String key) {
        this.key = key;
    }

    /**
     * 在界面上叫什么（悬浮按钮的「点击」「长按」、灵动贴的「单击」「双击」都用它）。
     *
     * <p>新增一个动作时这里忘了加分支，它会显示成「打开主界面」—— 不会崩，但会说谎，所以新增时两处一起改。</p>
     */
    public int labelRes() {
        switch (this) {
            case TOGGLE_RECORDING:
                return com.kooo.evcam.R.string.floating_action_toggle_recording;
            case TAKE_PHOTO:
                return com.kooo.evcam.R.string.floating_action_take_photo;
            case TOGGLE_MIRROR:
                return com.kooo.evcam.R.string.floating_action_toggle_mirror;
            case LOCK_FOOTAGE:
                return com.kooo.evcam.R.string.floating_action_lock_footage;
            case TOGGLE_DRIVE_SOUND:
                return com.kooo.evcam.R.string.floating_action_toggle_drive_sound;
            case OPEN_APP:
            default:
                return com.kooo.evcam.R.string.floating_action_open_app;
        }
    }

    /** 认不出来的值一律回到默认 —— 配置是可以被手改的。 */
    public static FloatingAction fromKey(String key) {
        if (key != null) {
            for (FloatingAction action : values()) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
        }
        return OPEN_APP;
    }
}
