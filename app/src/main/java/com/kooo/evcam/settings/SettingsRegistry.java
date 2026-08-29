package com.kooo.evcam.settings;

import static com.kooo.evcam.settings.SettingSpec.entry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 全部枚举型设置项的唯一声明处。
 *
 * <p>这些设置以前被声明三遍：{@code AppConfig} 的 getter 里一份默认值、
 * {@code SettingsFragment} 里一份选项数组、布局 XML 里一份显示文字。三处靠人工同步，
 * 任何一处对不上，界面就会显示一个程序不会遵守的值 —— 「设置写着四宫格、录出来是长条」
 * 就是这么来的。</p>
 *
 * <p>现在选项列表、默认值、显示名、启动自检全部从这里派生。</p>
 *
 * <p><b>只收枚举型</b>：布尔开关、数值、路径这些没有「选项列表」，不存在这一类错位，
 * 保持原样即可。硬塞进来只会让这份声明变得难读。</p>
 */
public final class SettingsRegistry {

    private SettingsRegistry() {
    }

    /** 录制画面排列。 */
    public static final SettingSpec RECORD_LAYOUT = SettingSpec.of(
            "record_layout", "录制画面排列", "grid2x2",
            entry("grid2x2", "四宫格"),
            entry("raw", "原始长条"));

    /** 录制帧率。 */
    public static final SettingSpec RECORD_FPS = SettingSpec.of(
            "record_fps", "录制帧率", "auto",
            entry("auto", "原始帧率"),
            entry("30", "30 fps"),
            entry("24", "24 fps"),
            entry("20", "20 fps"),
            entry("15", "15 fps"),
            entry("10", "10 fps"));

    /** 「预览用低分辨率」开启后，预览缓冲区的目标尺寸。 */
    public static final SettingSpec PREVIEW_RESOLUTION = SettingSpec.of(
            "preview_resolution", "预览分辨率", "1280x720",
            entry("640x480", "640x480"),
            entry("1280x720", "1280x720"),
            entry("1600x900", "1600x900"),
            entry("1920x1080", "1920x1080"));

    /** 录制模式。 */
    public static final SettingSpec RECORDING_MODE = SettingSpec.of(
            "recording_mode", "录制模式", "auto",
            entry("auto", "自动（推荐）"),
            entry("media_recorder", "MediaRecorder"),
            entry("codec", "MediaCodec"));

    /** 码率等级。 */
    public static final SettingSpec BITRATE_LEVEL = SettingSpec.of(
            "bitrate_level", "码率等级", "medium",
            entry("low", "低"),
            entry("medium", "中"),
            entry("high", "高"));

    /**
     * 车型（视频流配置）。
     *
     * <p>只列出本项目实际提供的三项。上游那些银河/星舰/手机车型的常量与分支代码都还在，
     * 但不出现在这里 —— 不提供的选项就不该出现在合法取值里，否则自检会把它们放行。</p>
     */
    public static final SettingSpec CAR_MODEL = SettingSpec.of(
            "car_model", "车型", "zeekr_7x",
            entry("zeekr_7x", "极氪7X（环视合成流）"),
            entry("zeekr_7x_multi", "极氪7X（环视+座舱3路）"),
            entry("custom", "自定义（排查用）"));

    /** 全部枚举型设置项，启动自检会逐个走一遍。 */
    public static final List<SettingSpec> ALL = Collections.unmodifiableList(Arrays.asList(
            RECORD_LAYOUT,
            RECORD_FPS,
            PREVIEW_RESOLUTION,
            RECORDING_MODE,
            BITRATE_LEVEL,
            CAR_MODEL));
}
