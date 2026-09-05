package com.kooo.evcam.settings;

import static com.kooo.evcam.settings.SettingSpec.entry;

import com.kooo.evcam.R;

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
 *
 * <p>相机与视频流的参数（排列、帧率、码率、编码、分段、每一路的尺寸）不在这里 ——
 * 它们按路存在配置里，入口是开发者选项 →「配置编辑」。</p>
 */
public final class SettingsRegistry {

    private SettingsRegistry() {
    }

    /** 录制模式。 */
    public static final SettingSpec RECORDING_MODE = SettingSpec.of(
            "recording_mode", "录制模式", "auto",
            entry("auto", "自动（推荐）", R.string.opt_mode_auto),
            entry("media_recorder", "MediaRecorder"),
            entry("codec", "MediaCodec"));

    /**
     * 车型（视频流配置）。
     *
     * <p>只列出本项目实际提供的三项。上游那些银河/星舰/手机车型的常量与分支代码都还在，
     * 但不出现在这里 —— 不提供的选项就不该出现在合法取值里，否则自检会把它们放行。</p>
     */
    public static final SettingSpec CAR_MODEL = SettingSpec.of(
            "car_model", "车型", "zeekr_7x",
            entry("zeekr_7x", "极氪7X（环视合成流）", R.string.opt_model_zeekr),
            entry("zeekr_7x_multi", "极氪7X（环视+座舱3路）", R.string.opt_model_zeekr_multi),
            entry("custom", "自定义（排查用）", R.string.opt_model_custom));

    /**
     * 界面语言。
     *
     * <p>默认<b>跟随系统</b> —— 车机本来是什么语言，这个应用就该是什么语言。
     * 另外两档是明确指定，用于系统语言和使用者的偏好不一致的情况。</p>
     */
    public static final SettingSpec LANGUAGE = SettingSpec.of(
            "language", "语言", "auto",
            entry("auto", "跟随系统", R.string.lang_auto),
            entry("zh", "中文", R.string.lang_zh),
            entry("en", "English", R.string.lang_en));

    /** 全部枚举型设置项，启动自检会逐个走一遍。 */
    public static final List<SettingSpec> ALL = Collections.unmodifiableList(Arrays.asList(
            RECORDING_MODE,
            CAR_MODEL,
            LANGUAGE));
}
