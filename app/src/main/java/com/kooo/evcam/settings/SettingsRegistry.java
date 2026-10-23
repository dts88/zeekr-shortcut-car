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
 * 它们按路存在配置里，入口是设置 → 录制 →「配置编辑」。</p>
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
     * <p>只列出本项目实际提供的两项。上游那些银河/星舰/手机车型连同代码都删了；
     * 不提供的选项不该出现在合法取值里，否则自检会把它们放行。</p>
     */
    public static final SettingSpec CAR_MODEL = SettingSpec.of(
            "car_model", "车型", "zeekr_7x",
            entry("zeekr_7x", "极氪7X（环视合成流）", R.string.opt_model_zeekr),
            entry("zeekr_7x_multi", "极氪7X（环视 + 前后座舱）", R.string.opt_model_zeekr_multi));

    /**
     * 界面语言。
     *
     * <p>默认<b>跟随系统</b> —— 车机本来是什么语言，这个应用就该是什么语言。
     * 其余几档是明确指定，用于系统语言和使用者的偏好不一致的情况。</p>
     */
    public static final SettingSpec LANGUAGE = SettingSpec.of(
            "language", "语言", "auto",
            entry("auto", "跟随系统", R.string.lang_auto),
            entry("zh", "中文", R.string.lang_zh),
            entry("en", "English", R.string.lang_en),
            entry("ms", "Bahasa Melayu", R.string.lang_ms));

    /**
     * 鱼眼校正用哪种投影。
     *
     * <p>取值必须和 {@code FisheyeProjection.PROJECTION_*} 对得上 —— 那边是算法，
     * 这边是界面，中间只隔着这一个字符串。{@code FisheyeProjectionTest} 钉着这一条。</p>
     *
     * <p>默认直线投影：它把直线掰得笔直，是大多数人说「矫正」时想要的那个样子。
     * 主界面预览、图片回看、视频回看共用；超级镜子不用它。</p>
     */
    public static final SettingSpec FISHEYE_PROJECTION = SettingSpec.of(
            "fisheye_projection", "鱼眼校正方式", "rectilinear",
            entry("rectilinear", "直线投影", R.string.opt_projection_rectilinear),
            entry("cylindrical", "柱面投影", R.string.opt_projection_cylindrical),
            entry("stereographic", "立体投影", R.string.opt_projection_stereographic));

    /**
     * 动作栏（录制、拍照那一列）放在屏幕哪一侧。
     *
     * <p>左舵车和右舵车伸手够屏幕的方向正好相反。只挪这一列，别的区不动 ——
     * 标题、内容、状态条的位置不因舵位而变，换一台车也不用重新认界面。</p>
     */
    public static final SettingSpec ACTION_RAIL_SIDE = SettingSpec.of(
            "action_rail_side", "操作按钮位置", "right",
            entry("right", "右侧", R.string.opt_rail_right),
            entry("left", "左侧", R.string.opt_rail_left));

    /** 超级镜子的切换范围（{@link com.kooo.evcam.zeekr.MirrorViews}）：分段按钮，三档（项目所有者 2026-10-19）。 */
    public static final SettingSpec REARVIEW_VIEWS = SettingSpec.of(
            "rearview_views", "切换范围", "four",
            entry("four", "四向", R.string.opt_views_four),
            entry("front_rear", "前后", R.string.opt_views_front_rear),
            entry("rear_cabin", "后视 + 后座舱", R.string.opt_views_rear_cabin));

    /**
     * 录制声音用哪个来源（开发者选项，试验；{@code camera.FootageAudio}）。项目所有者 2026-10-20：两种都做出来、上车比。
     * 名字按来源叫，不叫「机身麦克风」：Lab 怀疑那一路其实在车外，测清之前不下结论。
     */
    public static final SettingSpec FOOTAGE_AUDIO_SOURCE = SettingSpec.of(
            "footage_audio_source", "麦克风来源", com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_RAW,
            entry(com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_RAW, "原始（VOICE_RECOGNITION）",
                    R.string.dev_footage_audio_source_raw),
            entry(com.kooo.evcam.AppConfig.FOOTAGE_AUDIO_CALL, "通话（VOICE_COMMUNICATION）",
                    R.string.dev_footage_audio_source_call));

    /**
     * 模拟声浪自己的音量（开发者选项，试验；{@code sound.DriveSound}）：在车机媒体音量之上再乘这么多。
     * 存的就是 dB 数，界面上显示的也是它 —— 显示的就是实际用的增益。项目所有者 2026-10-21 实车：−12 dB 太小，平时用 0 dB，
     * 最低 −6 dB 就够；默认 0 dB（以前存的 −18、−12 不在单子里，读出来按默认）。
     */
    public static final SettingSpec DRIVE_SOUND_VOLUME = SettingSpec.of(
            "drive_sound_volume", "声浪音量", "0",
            entry("-6", "−6 dB"),
            entry("-3", "−3 dB"),
            entry("0", "0 dB"));

    /** 模拟声浪的转速变化方式（开发者选项，试验）。项目所有者 2026-10-21：两种都做，上车听了再定。 */
    public static final SettingSpec DRIVE_SOUND_RPM = SettingSpec.of(
            "drive_sound_rpm", "转速变化方式", com.kooo.evcam.AppConfig.DRIVE_SOUND_RPM_CONTINUOUS,
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_RPM_CONTINUOUS, "连续", R.string.dev_drive_sound_rpm_continuous),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_RPM_SHIFTS, "模拟换挡", R.string.dev_drive_sound_rpm_shifts));

    /**
     * 模拟声浪的变速箱（开发者选项，试验；{@code sound.Gearbox}）：模拟换挡（和连续走法地板油时的伪挡位）按哪一种的齿比走。
     * 项目所有者 2026-10-22：照车主偏好的那台的 8 速自动（默认）；它的 6 速手动也做成可选。不用车型名（商标）。
     */
    public static final SettingSpec DRIVE_SOUND_GEARBOX = SettingSpec.of(
            "drive_sound_gearbox", "变速箱", com.kooo.evcam.AppConfig.DRIVE_SOUND_GEARBOX_AUTO8,
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_GEARBOX_AUTO8, "8 速自动", R.string.dev_drive_sound_gearbox_auto8),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_GEARBOX_MANUAL6, "6 速手动", R.string.dev_drive_sound_gearbox_manual6));

    /**
     * 模拟声浪的声音风格（开发者选项，试验；{@code sound.SoundStyle}）。项目所有者 2026-10-21：第一版太单薄，
     * 按发动机结构重做「美式 V8」这一种取向；第一版留着，叫「试制 V8」（两个名字车主定的）；另试制一种纯电车主动声浪的
     * 「超音速」（车主 2026-10-21 批的名字）。都不用车型名（商标）。「超音速（旧版）」是 2026-10-22 的那一版，车主要和新的
     * 装车对比（2026-10-23）。「涡轮四缸」「电动概念车」照参考车另外两种主题仿制（2026-10-23，名字车主批了）。
     */
    public static final SettingSpec DRIVE_SOUND_STYLE = SettingSpec.of(
            "drive_sound_style", "声音风格", com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_AMERICAN,
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_AMERICAN, "美式 V8", R.string.dev_drive_sound_style_american),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_PROTOTYPE, "试制 V8", R.string.dev_drive_sound_style_prototype),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_JET, "超音速", R.string.dev_drive_sound_style_jet),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_JET_OLD, "超音速（旧版）", R.string.dev_drive_sound_style_jet_old),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_TURBO4, "涡轮四缸", R.string.dev_drive_sound_style_turbo4),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_STYLE_ELECTRIC, "电动概念车", R.string.dev_drive_sound_style_electric));

    /**
     * 模拟声浪的听感（开发者选项，试验）：车外（站在车旁）或车内（隔着防火墙、车身）。项目所有者 2026-10-21：车内的可以试一下；
     * 默认还是车外。只有「美式 V8」分两种，另两种本来就按车内做的。
     */
    public static final SettingSpec DRIVE_SOUND_VIEW = SettingSpec.of(
            "drive_sound_view", "听感", com.kooo.evcam.AppConfig.DRIVE_SOUND_VIEW_OUTSIDE,
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_VIEW_OUTSIDE, "车外", R.string.dev_drive_sound_view_outside),
            entry(com.kooo.evcam.AppConfig.DRIVE_SOUND_VIEW_CABIN, "车内", R.string.dev_drive_sound_view_cabin));

    /** 全部枚举型设置项，启动自检会逐个走一遍。 */
    public static final List<SettingSpec> ALL = Collections.unmodifiableList(Arrays.asList(
            RECORDING_MODE,
            CAR_MODEL,
            LANGUAGE,
            FISHEYE_PROJECTION,
            ACTION_RAIL_SIDE,
            REARVIEW_VIEWS,
            FOOTAGE_AUDIO_SOURCE,
            DRIVE_SOUND_VOLUME,
            DRIVE_SOUND_RPM,
            DRIVE_SOUND_GEARBOX,
            DRIVE_SOUND_STYLE,
            DRIVE_SOUND_VIEW));
}
