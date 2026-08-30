package com.kooo.evcam.settings;

/**
 * 录制帧率的取值规则。
 *
 * <h3>为什么单独一个类</h3>
 *
 * <p>这段规则有两个使用方：录制链路要用它算实际帧率，设置界面要用它<b>显示</b>那个数。
 * 如果放在 {@code AppConfig} 里，{@link SettingsRegistry} 就得反过来引用 AppConfig，
 * 而 AppConfig 的静态字段本来就在引用 SettingsRegistry —— 两边互相触发类初始化，
 * 谁先加载会决定另一边读到的是不是完整的值。这种问题不会报错，
 * 只会安静地读到 0 或 null，所以宁可多一个没有依赖的小类。</p>
 *
 * <h3>「原始帧率」到底是多少</h3>
 *
 * <p>是 {@link #RECORDER_MAX_FPS}，一个<b>写死的数</b>，不是从相机读出来的。
 * 当初定 25 是为了压低 CPU 占用。所以「原始」这个名字有点误导 ——
 * 它跟随的是录制链路的默认值，不是相机的原生帧率。</p>
 *
 * <p>界面上标的数由 {@link #autoLabel()} 算出来，和录制用的是同一段代码，
 * 不会出现界面写一个数、实际录另一个数。</p>
 */
public final class FrameRatePolicy {

    /**
     * 录制链路当作「硬件最大帧率」用的值。
     *
     * <p>写死的，见 {@code MultiCameraManager} 的三处调用。</p>
     */
    public static final int RECORDER_MAX_FPS = 25;

    private FrameRatePolicy() {
    }

    /**
     * 「跟随硬件」时实际使用的帧率。
     *
     * @param hardwareMaxFps 硬件支持的最大帧率
     */
    public static int standardFrameRate(int hardwareMaxFps) {
        if (hardwareMaxFps <= 0) {
            return 30;
        }
        // 本来就在 30 附近，直接用
        if (hardwareMaxFps >= 25 && hardwareMaxFps <= 35) {
            return hardwareMaxFps;
        }
        // 高于 30 的降到 30 以下：60 -> 30，120 -> 30
        if (hardwareMaxFps > 35) {
            int divisor = (hardwareMaxFps + 29) / 30;
            int result = hardwareMaxFps / divisor;
            return Math.max(15, Math.min(result, 30));
        }
        // 低于 25 的照用
        return hardwareMaxFps;
    }

    /** 「原始帧率」这一项显示的文字，带上它实际会用的数值。 */
    public static String autoLabel() {
        return "原始帧率（" + standardFrameRate(RECORDER_MAX_FPS) + " fps）";
    }
}
