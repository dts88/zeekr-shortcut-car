package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.config.BlindSpotConfig;
import com.kooo.evcam.zeekr.FisheyeProjection;
import com.kooo.evcam.settings.SettingSpec;
import com.kooo.evcam.settings.LicensePlate;
import com.kooo.evcam.settings.SettingsRegistry;

/**
 * 应用配置管理类
 * 管理应用级别的配置项
 */
public class AppConfig {
    private static final String TAG = "AppConfig";
    private static final String PREF_NAME = "app_config";
    
    // 配置项键名
    private static final String KEY_FIRST_LAUNCH = "first_launch";  // 首次启动标记
    private static final String KEY_LANGUAGE_CHOSEN = "language_chosen";  // 首次启动的语言选择是否已完成
    private static final String KEY_DEVICE_NICKNAME = "device_nickname";  // 设备识别名称（用于日志上传）
    private static final String KEY_AUTO_START_ON_BOOT = "auto_start_on_boot";  // 开机自启动
    private static final String KEY_AUTO_START_RECORDING = "auto_start_recording";  // 启动自动录制
    private static final String KEY_SCREEN_OFF_RECORDING = "screen_off_recording";  // 息屏录制（锁车录制）
    private static final String KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled";  // 保活服务
    private static final String KEY_PREVENT_SLEEP_ENABLED = "prevent_sleep_enabled";  // 防止休眠（持续WakeLock）
    private static final String KEY_RECORDING_MODE = "recording_mode";  // 录制模式
    
    // 存储位置配置
    private static final String KEY_STORAGE_LOCATION = "storage_location";  // 存储位置
    private static final String KEY_CUSTOM_SD_CARD_PATH = "custom_sd_card_path";  // 手动设置的U盘路径
    private static final String KEY_LAST_DETECTED_SD_PATH = "last_detected_sd_path";  // 上次自动检测到的U盘路径（缓存）
    
    // 存储位置常量
    public static final String STORAGE_INTERNAL = "internal";  // 内部存储
    public static final String STORAGE_EXTERNAL_SD = "external_sd";  // U盘（默认）
    
    // U盘回退提示标志（每次冷启动后重置）
    private static boolean sdFallbackShownThisSession = false;
    
    // 悬浮窗配置
    private static final String KEY_FLOATING_WINDOW_ENABLED = "floating_window_enabled";  // 悬浮窗开关
    private static final String KEY_FLOATING_WINDOW_SIZE = "floating_window_size";  // 悬浮窗大小
    private static final String KEY_FLOATING_WINDOW_ALPHA = "floating_window_alpha";  // 悬浮窗透明度
    private static final String KEY_FLOATING_WINDOW_X = "floating_window_x";  // 悬浮窗X位置
    private static final String KEY_FLOATING_WINDOW_Y = "floating_window_y";  // 悬浮窗Y位置
    
    // 存储清理配置
    private static final String KEY_VIDEO_STORAGE_LIMIT_GB = "video_storage_limit_gb";  // 视频存储限制（GB）
    private static final String KEY_PHOTO_STORAGE_LIMIT_GB = "photo_storage_limit_gb";  // 图片存储限制（GB）
    
    // 分段录制配置
    
    // 录制状态显示配置
    private static final String KEY_RECORDING_STATS_ENABLED = "recording_stats_enabled";  // 录制状态显示开关
    
    // 补盲功能全局开关
    private static final String KEY_BLIND_SPOT_GLOBAL_ENABLED = "blind_spot_global_enabled";  // 补盲功能总开关
    
    // 超视模式配置
    private static final String KEY_SUPERVISION_MODE_ENABLED = "supervision_mode_enabled";  // 超视模式开关
    private static final String KEY_SUPERVISION_LEFT_WIDTH = "supervision_left_width";      // 左视悬浮窗宽度
    private static final String KEY_SUPERVISION_LEFT_HEIGHT = "supervision_left_height";    // 左视悬浮窗高度
    private static final String KEY_SUPERVISION_LEFT_X = "supervision_left_x";              // 左视悬浮窗X位置
    private static final String KEY_SUPERVISION_LEFT_Y = "supervision_left_y";              // 左视悬浮窗Y位置
    private static final String KEY_SUPERVISION_RIGHT_WIDTH = "supervision_right_width";    // 右视悬浮窗宽度
    private static final String KEY_SUPERVISION_RIGHT_HEIGHT = "supervision_right_height";  // 右视悬浮窗高度
    private static final String KEY_SUPERVISION_RIGHT_X = "supervision_right_x";            // 右视悬浮窗X位置
    private static final String KEY_SUPERVISION_RIGHT_Y = "supervision_right_y";            // 右视悬浮窗Y位置
    
    // 长视模式配置（转向灯触发双画面模式）
    private static final String KEY_LONG_VIEW_MODE_ENABLED = "long_view_mode_enabled";  // 长视模式开关
    
    // 补盲选项配置 (原副屏显示)
    private static final String KEY_SECONDARY_DISPLAY_ENABLED = "secondary_display_enabled";  // 副屏显示开关
    private static final String KEY_SECONDARY_DISPLAY_CAMERA = "secondary_display_camera";    // 副屏显示的摄像头位置
    private static final String KEY_SECONDARY_DISPLAY_ID = "secondary_display_id";            // 副屏 Display ID
    private static final String KEY_SECONDARY_DISPLAY_X = "secondary_display_x";              // 副屏位置X
    private static final String KEY_SECONDARY_DISPLAY_Y = "secondary_display_y";              // 副屏位置Y
    private static final String KEY_SECONDARY_DISPLAY_WIDTH = "secondary_display_width";      // 副屏宽度
    private static final String KEY_SECONDARY_DISPLAY_HEIGHT = "secondary_display_height";    // 副屏高度
    private static final String KEY_SECONDARY_DISPLAY_ROTATION = "secondary_display_rotation"; // 副屏旋转角度
    private static final String KEY_SECONDARY_DISPLAY_BORDER = "secondary_display_border";    // 是否显示白边框
    private static final String KEY_SECONDARY_DISPLAY_ORIENTATION = "secondary_display_orientation"; // 屏幕方向（0/90/180/270）
    private static final String KEY_SECONDARY_DISPLAY_ALPHA = "secondary_display_alpha"; // 副屏补盲悬浮窗透明度（0-100）

    // 主屏悬浮窗配置 (补盲选项新增)
    private static final String KEY_MAIN_FLOATING_ENABLED = "main_floating_enabled";          // 主屏悬浮窗开关
    private static final String KEY_MAIN_FLOATING_CAMERA = "main_floating_camera";            // 主屏悬浮窗摄像头
    private static final String KEY_MAIN_FLOATING_X = "main_floating_x";                      // 主屏悬浮窗X位置
    private static final String KEY_MAIN_FLOATING_Y = "main_floating_y";                      // 主屏悬浮窗Y位置
    private static final String KEY_MAIN_FLOATING_WIDTH = "main_floating_width";              // 主屏悬浮窗宽度
    private static final String KEY_MAIN_FLOATING_HEIGHT = "main_floating_height";            // 主屏悬浮窗高度

    // 超级后视镜：把环视合成流里后方那一路单独放大显示
    private static final String KEY_REARVIEW_ENABLED = "rearview_enabled";        // 总开关
    private static final String KEY_REARVIEW_FISHEYE = "rearview_fisheye";        // 鱼眼校正开关
    private static final String KEY_REARVIEW_FOV = "rearview_fov";                // 目标视野（度）
    private static final String KEY_REARVIEW_WIDTH = "rearview_width";            // 窗口宽度（px）
    private static final String KEY_REARVIEW_HEIGHT = "rearview_height";          // 窗口高度（px）
    private static final String KEY_REARVIEW_PAN = "rearview_pan";                // 上下平移，0..1
    private static final String KEY_REARVIEW_LANE = "rearview_lane";              // 当前显示哪一路
    private static final String KEY_REARVIEW_FRONT_REAR = "rearview_front_rear";  // 只在前后之间切换
    private static final String KEY_REARVIEW_X = "rearview_x";                    // 窗口位置
    private static final String KEY_REARVIEW_Y = "rearview_y";


    // 转向灯联动配置 (补盲选项新增)
    private static final String KEY_TURN_SIGNAL_LINKAGE_ENABLED = "turn_signal_linkage_enabled"; // 转向灯联动开关
    private static final String KEY_TURN_SIGNAL_TIMEOUT = "turn_signal_timeout";               // 转向灯熄灭后延迟消失时间 (秒)
    
    // 车门联动配置
    private static final String KEY_DOOR_LINKAGE_ENABLED = "door_linkage_enabled";             // 车门联动开关
    private static final String KEY_DOOR_TIMEOUT = "door_timeout";                             // 车门关闭后延迟消失时间 (秒)
    private static final String KEY_DOOR_PRESET_SELECTION = "door_preset_selection";           // 车门联动车型选择 (l6l7/boyue_l)
    private static final String KEY_DOOR_REUSE_MAIN_FLOATING = "door_reuse_main_floating";     // 车门联动是否复用主屏悬浮窗
    private static final String KEY_DOOR_SECONDARY_DISPLAY_ENABLED = "door_secondary_display_enabled"; // 车门联动副屏显示开关
    
    private static final String KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING = "turn_signal_reuse_main_floating"; // 是否复用主屏悬浮窗
    private static final String KEY_TURN_SIGNAL_FLOATING_X = "turn_signal_floating_x";          // 独立补盲悬浮窗X
    private static final String KEY_TURN_SIGNAL_FLOATING_Y = "turn_signal_floating_y";          // 独立补盲悬浮窗Y
    private static final String KEY_TURN_SIGNAL_FLOATING_WIDTH = "turn_signal_floating_width";  // 独立补盲悬浮窗宽度
    private static final String KEY_TURN_SIGNAL_FLOATING_HEIGHT = "turn_signal_floating_height"; // 独立补盲悬浮窗高度
    private static final String KEY_TURN_SIGNAL_FLOATING_ROTATION = "turn_signal_floating_rotation"; // 独立补盲悬浮窗旋转
    private static final String KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG = "turn_signal_custom_left_trigger_log"; // 左转向灯触发log关键字
    private static final String KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG = "turn_signal_custom_right_trigger_log"; // 右转向灯触发log关键字
    private static final String KEY_TURN_SIGNAL_TRIGGER_MODE = "turn_signal_trigger_mode"; // 转向灯触发模式
    private static final String KEY_TURN_SIGNAL_PRESET_SELECTION = "turn_signal_preset_selection"; // 用户选择的预设选项（博越L/L6L7等）

    // 转向灯触发模式常量
    public static final String TRIGGER_MODE_LOGCAT = "logcat";            // Logcat 日志触发
    public static final String TRIGGER_MODE_VHAL_GRPC = "vhal_grpc";      // 车辆API 触发（银河E5/26款星舰7，默认）
    public static final String TRIGGER_MODE_CAR_SIGNAL_MANAGER = "car_signal_manager"; // CarSignalManager API 触发（银河L6/L7）
    
    // 兼容性别名（保持向后兼容）
    public static final String TRIGGER_MODE_CAR_API = TRIGGER_MODE_VHAL_GRPC;

    // 全景影像避让配置
    private static final String KEY_AVM_AVOIDANCE_ENABLED = "avm_avoidance_enabled";  // 全景影像避让开关
    private static final String KEY_AVM_AVOIDANCE_ACTIVITY = "avm_avoidance_activity"; // 全景影像避让的Activity名
    
    // 中转写入配置
    private static final String KEY_RELAY_WRITE_ENABLED = "relay_write_enabled";  // 中转写入开关（U盘存储时）

    // 定制键唤醒配置
    private static final String KEY_CUSTOM_KEY_WAKEUP_ENABLED = "custom_key_wakeup_enabled"; // 定制键唤醒开关
    private static final String KEY_CUSTOM_KEY_SPEED_THRESHOLD = "custom_key_speed_threshold"; // 速度阈值（秒速 m/s）
    private static final String KEY_CUSTOM_KEY_SPEED_PROP_ID = "custom_key_speed_prop_id"; // 速度属性ID
    private static final String KEY_CUSTOM_KEY_BUTTON_PROP_ID = "custom_key_button_prop_id"; // 按钮属性ID

    // 桌面悬浮模拟按钮 (补盲选项新增)
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED = "mock_turn_signal_floating_enabled"; // 悬浮模拟按钮开关
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_X = "mock_turn_signal_floating_x";             // 悬浮模拟按钮X
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_Y = "mock_turn_signal_floating_y";             // 悬浮模拟按钮Y

    // 补盲悬浮窗动效
    private static final String KEY_FLOATING_WINDOW_ANIMATION_ENABLED = "floating_window_animation_enabled"; // 悬浮窗开启/关闭动效
    private static final String KEY_BLIND_SPOT_STATUS_BAR_STYLE = "blind_spot_status_bar_style";             // 状态栏动效样式 (0=关, 1-5=五种动效)
    private static final String KEY_BLIND_SPOT_STATUS_BAR_COLOR = "blind_spot_status_bar_color";             // 状态栏动效颜色 (ARGB int)
    private static final String KEY_BLIND_SPOT_STATUS_BAR_BG_OPACITY = "blind_spot_status_bar_bg_opacity";   // 状态栏底色不透明度 0-100

    // 主屏悬浮窗比例锁定
    private static final String KEY_MAIN_FLOATING_ASPECT_RATIO_LOCKED = "main_floating_aspect_ratio_locked";

    // 主屏悬浮窗长按拖动
    private static final String KEY_MAIN_FLOATING_LONG_PRESS_DRAG = "main_floating_long_press_drag";

    // 补盲画面矫正 (Matrix)
    private static final String KEY_BLIND_SPOT_CORRECTION_ENABLED = "blind_spot_correction_enabled";
    private static final String KEY_BLIND_SPOT_CORRECTION_PREFIX = "blind_spot_correction_";
    private static final String KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED = "blind_spot_disclaimer_accepted";
    
    // 预览画面矫正配置
    private static final String KEY_PREVIEW_CORRECTION_ENABLED = "preview_correction_enabled";
    private static final String KEY_PREVIEW_CORRECTION_PREFIX = "preview_correction_";

    // 鱼眼矫正配置
    private static final String KEY_FISHEYE_CORRECTION_ENABLED = "fisheye_correction_enabled";
    private static final String KEY_FISHEYE_CORRECTION_PREFIX = "fisheye_correction_";

    // 时间角标配置
    private static final String KEY_TIMESTAMP_WATERMARK_ENABLED = "timestamp_watermark_enabled";  // 时间角标开关
    private static final String KEY_WATERMARK_SPEC_ENABLED = "watermark_spec_enabled";  // 角标附带录制规格
    private static final String KEY_PHOTO_VIA_JPEG = "photo_via_jpeg";
    private static final String KEY_FORCE_H264_ENCODING = "force_h264_encoding";  // 拍照走相机 JPEG 通道
    private static final String KEY_LICENSE_PLATE = "license_plate";  // 车牌号（可选）
    private static final String KEY_LICENSE_PLATE_ENABLED = "license_plate_enabled";

    // 视频编码器配置
    
    // 录制摄像头选择配置
    
    // 摄像头画面显示配置（与录制开关分离）
    private static final String KEY_CAMERA_FRONT_VISIBLE = "camera_front_visible";  // 前摄像头画面显示
    private static final String KEY_CAMERA_BACK_VISIBLE = "camera_back_visible";    // 后摄像头画面显示
    private static final String KEY_CAMERA_LEFT_VISIBLE = "camera_left_visible";    // 左摄像头画面显示
    private static final String KEY_CAMERA_RIGHT_VISIBLE = "camera_right_visible";  // 右摄像头画面显示
    
    // 亮度/降噪调节配置
    private static final String KEY_IMAGE_ADJUST_ENABLED = "image_adjust_enabled";  // 是否启用亮度/降噪调节
    private static final String KEY_EXPOSURE_COMPENSATION = "exposure_compensation";  // 曝光补偿值
    private static final String KEY_AWB_MODE = "awb_mode";  // 白平衡模式
    private static final String KEY_TONEMAP_MODE = "tonemap_mode";  // 色调映射模式
    private static final String KEY_EDGE_MODE = "edge_mode";  // 边缘增强模式
    private static final String KEY_NOISE_REDUCTION_MODE = "noise_reduction_mode";  // 降噪模式
    private static final String KEY_EFFECT_MODE = "effect_mode";  // 特效模式
    private static final String KEY_SCENE_MODE = "scene_mode";  // 场景模式
    
    // 白平衡模式常量（对应 CameraMetadata.CONTROL_AWB_MODE_*）
    public static final int AWB_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int AWB_MODE_AUTO = 1;  // 自动
    public static final int AWB_MODE_INCANDESCENT = 2;  // 白炽灯
    public static final int AWB_MODE_FLUORESCENT = 3;  // 荧光灯
    public static final int AWB_MODE_WARM_FLUORESCENT = 4;  // 暖荧光灯
    public static final int AWB_MODE_DAYLIGHT = 5;  // 日光
    public static final int AWB_MODE_CLOUDY_DAYLIGHT = 6;  // 阴天
    public static final int AWB_MODE_TWILIGHT = 7;  // 黄昏
    public static final int AWB_MODE_SHADE = 8;  // 阴影
    
    // 色调映射模式常量（对应 CameraMetadata.TONEMAP_MODE_*）
    public static final int TONEMAP_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int TONEMAP_MODE_CONTRAST_CURVE = 0;  // 对比度曲线
    public static final int TONEMAP_MODE_FAST = 1;  // 快速
    public static final int TONEMAP_MODE_HIGH_QUALITY = 2;  // 高质量
    
    // 边缘增强模式常量（对应 CameraMetadata.EDGE_MODE_*）
    public static final int EDGE_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int EDGE_MODE_OFF = 0;  // 关闭
    public static final int EDGE_MODE_FAST = 1;  // 快速
    public static final int EDGE_MODE_HIGH_QUALITY = 2;  // 高质量
    
    // 降噪模式常量（对应 CameraMetadata.NOISE_REDUCTION_MODE_*）
    public static final int NOISE_REDUCTION_DEFAULT = -1;  // 默认（不设置）
    public static final int NOISE_REDUCTION_OFF = 0;  // 关闭
    public static final int NOISE_REDUCTION_FAST = 1;  // 快速
    public static final int NOISE_REDUCTION_HIGH_QUALITY = 2;  // 高质量
    
    // 特效模式常量（对应 CameraMetadata.CONTROL_EFFECT_MODE_*）
    public static final int EFFECT_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int EFFECT_MODE_OFF = 0;  // 关闭
    public static final int EFFECT_MODE_MONO = 1;  // 黑白
    public static final int EFFECT_MODE_NEGATIVE = 2;  // 负片
    public static final int EFFECT_MODE_SOLARIZE = 3;  // 曝光过度
    public static final int EFFECT_MODE_SEPIA = 4;  // 怀旧
    public static final int EFFECT_MODE_AQUA = 6;  // 水蓝
    
    
    // 悬浮窗大小常量
    public static final int FLOATING_SIZE_TINY = 32;        // 超小
    public static final int FLOATING_SIZE_EXTRA_SMALL = 40; // 特小
    public static final int FLOATING_SIZE_SMALL = 48;       // 小
    public static final int FLOATING_SIZE_MEDIUM = 64;      // 中
    public static final int FLOATING_SIZE_LARGE = 80;       // 大
    public static final int FLOATING_SIZE_EXTRA_LARGE = 96; // 超大
    public static final int FLOATING_SIZE_HUGE = 112;       // 特大
    public static final int FLOATING_SIZE_GIANT = 128;      // 特特大
    public static final int FLOATING_SIZE_PLUS = 144;       // PLUS大
    public static final int FLOATING_SIZE_MAX = 160;        // MAX大

    /** 悬浮窗按钮的默认大小（dp）。 */
    public static final int FLOATING_SIZE_DEFAULT = 85;
    /** 悬浮窗按钮的默认不透明度（%）。 */
    public static final int FLOATING_ALPHA_DEFAULT = 75;

    // ---- 悬浮元素的默认位置（2026-08-29 实车调好后测得，见诊断报告第 7 节）----
    //
    // 位置按下面这块参照屏幕记录，使用时再按实际屏幕等比换算。
    // 直接存像素值的话，换一块尺寸不同的屏就会跑到画面外面去。

    /** 记录这些默认位置时的屏幕尺寸（极氪 7X 内置屏，整屏）。 */
    public static final int REFERENCE_SCREEN_WIDTH = 3200;
    public static final int REFERENCE_SCREEN_HEIGHT = 2000;

    /** 录制悬浮按钮的默认位置。 */
    public static final int DEFAULT_RECORDING_FLOATING_X = 2943;
    public static final int DEFAULT_RECORDING_FLOATING_Y = 205;

    /** 悬浮按钮（打开应用/状态指示）的默认位置。 */
    public static final int DEFAULT_FLOATING_WINDOW_X = 2890;
    public static final int DEFAULT_FLOATING_WINDOW_Y = 33;

    /**
     * 把参照屏幕上的横坐标换算到当前屏幕。
     *
     * @param referenceX  参照屏幕上的 X
     * @param screenWidth 当前屏幕宽度；&lt;= 0 时原样返回
     */
    public static int scaleDefaultX(int referenceX, int screenWidth) {
        if (screenWidth <= 0) {
            return referenceX;
        }
        return Math.round(referenceX * (float) screenWidth / REFERENCE_SCREEN_WIDTH);
    }

    /** @see #scaleDefaultX(int, int) */
    public static int scaleDefaultY(int referenceY, int screenHeight) {
        if (screenHeight <= 0) {
            return referenceY;
        }
        return Math.round(referenceY * (float) screenHeight / REFERENCE_SCREEN_HEIGHT);
    }
    
    // 录制模式常量
    public static final String RECORDING_MODE_AUTO = "auto";  // 自动（根据车型决定）
    public static final String RECORDING_MODE_MEDIA_RECORDER = "media_recorder";  // MediaRecorder（硬件编码）
    public static final String RECORDING_MODE_CODEC = "codec";  // MediaCodec（软编码）
    
    // 码率配置相关键名
    
    // 码率等级常量
    public static final String BITRATE_LOW = "low";        // 低码率（计算值的50%）
    public static final String BITRATE_MEDIUM = "medium";  // 中码率（计算值，默认）
    public static final String BITRATE_HIGH = "high";      // 高码率（计算值的150%）
    
    private static final String KEY_CAMERA_OVERRIDE_PREFIX = "zeekr_camera_override_";  // 手动指定的相机映射
    


    
    // 车型配置相关键名
    private static final String KEY_CAR_MODEL = "car_model";  // 车型（galaxy_e5 / custom）
    private static final String KEY_CAMERA_COUNT = "camera_count";  // 摄像头数量（4/2/1）
    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation";  // 屏幕方向（landscape/portrait，仅4摄像头时有效）
    private static final String KEY_CAMERA_FRONT_ID = "camera_front_id";  // 前摄像头编号
    private static final String KEY_CAMERA_BACK_ID = "camera_back_id";  // 后摄像头编号
    private static final String KEY_CAMERA_LEFT_ID = "camera_left_id";  // 左摄像头编号
    private static final String KEY_CAMERA_RIGHT_ID = "camera_right_id";  // 右摄像头编号
    private static final String KEY_CAMERA_FRONT_NAME = "camera_front_name";  // 前摄像头名称
    private static final String KEY_CAMERA_BACK_NAME = "camera_back_name";  // 后摄像头名称
    private static final String KEY_CAMERA_LEFT_NAME = "camera_left_name";  // 左摄像头名称
    private static final String KEY_CAMERA_RIGHT_NAME = "camera_right_name";  // 右摄像头名称
    private static final String KEY_CAMERA_FRONT_ROTATION = "camera_front_rotation";  // 前摄像头旋转角度
    private static final String KEY_CAMERA_BACK_ROTATION = "camera_back_rotation";  // 后摄像头旋转角度
    private static final String KEY_CAMERA_LEFT_ROTATION = "camera_left_rotation";  // 左摄像头旋转角度
    private static final String KEY_CAMERA_RIGHT_ROTATION = "camera_right_rotation";  // 右摄像头旋转角度
    private static final String KEY_CAMERA_FRONT_MIRROR = "camera_front_mirror";  // 前摄像头镜像
    private static final String KEY_CAMERA_BACK_MIRROR = "camera_back_mirror";  // 后摄像头镜像
    private static final String KEY_CAMERA_LEFT_MIRROR = "camera_left_mirror";  // 左摄像头镜像
    private static final String KEY_CAMERA_RIGHT_MIRROR = "camera_right_mirror";  // 右摄像头镜像
    
    // 摄像头裁剪配置（每个方向的裁剪像素值）
    private static final String KEY_CAMERA_CROP_PREFIX = "camera_crop_";  // 裁剪配置前缀
    
    // 自定义车型自由操控配置
    private static final String KEY_CUSTOM_FREE_CONTROL_ENABLED = "custom_free_control_enabled";  // 自由操控开关
    private static final String KEY_CUSTOM_BUTTON_STYLE = "custom_button_style";  // 按钮样式（standard/multi）
    private static final String KEY_CUSTOM_BUTTON_ORIENTATION = "custom_button_orientation";  // 按钮布局方向（horizontal/vertical）
    private static final String KEY_CUSTOM_LAYOUT_DATA = "custom_layout_data";  // 布局位置数据（JSON格式）
    private static final String KEY_CUSTOM_LAYOUT_VERSION = "custom_layout_version";

    // 前轮/后轮模式视图配置（用于自定义车型）
    private static final String KEY_FRONT_WHEEL_LEFT_WIDTH = "front_wheel_left_width";
    private static final String KEY_FRONT_WHEEL_LEFT_HEIGHT = "front_wheel_left_height";
    private static final String KEY_FRONT_WHEEL_LEFT_X = "front_wheel_left_x";
    private static final String KEY_FRONT_WHEEL_LEFT_Y = "front_wheel_left_y";
    private static final String KEY_FRONT_WHEEL_LEFT_ROTATION = "front_wheel_left_rotation";
    private static final String KEY_FRONT_WHEEL_RIGHT_WIDTH = "front_wheel_right_width";
    private static final String KEY_FRONT_WHEEL_RIGHT_HEIGHT = "front_wheel_right_height";
    private static final String KEY_FRONT_WHEEL_RIGHT_X = "front_wheel_right_x";
    private static final String KEY_FRONT_WHEEL_RIGHT_Y = "front_wheel_right_y";
    private static final String KEY_FRONT_WHEEL_RIGHT_ROTATION = "front_wheel_right_rotation";

    private static final String KEY_REAR_WHEEL_LEFT_WIDTH = "rear_wheel_left_width";
    private static final String KEY_REAR_WHEEL_LEFT_HEIGHT = "rear_wheel_left_height";
    private static final String KEY_REAR_WHEEL_LEFT_X = "rear_wheel_left_x";
    private static final String KEY_REAR_WHEEL_LEFT_Y = "rear_wheel_left_y";
    private static final String KEY_REAR_WHEEL_LEFT_ROTATION = "rear_wheel_left_rotation";
    private static final String KEY_REAR_WHEEL_RIGHT_WIDTH = "rear_wheel_right_width";
    private static final String KEY_REAR_WHEEL_RIGHT_HEIGHT = "rear_wheel_right_height";
    private static final String KEY_REAR_WHEEL_RIGHT_X = "rear_wheel_right_x";
    private static final String KEY_REAR_WHEEL_RIGHT_Y = "rear_wheel_right_y";
    private static final String KEY_REAR_WHEEL_RIGHT_ROTATION = "rear_wheel_right_rotation";

    // 普通模式（默认模式）的视图配置
    private static final String KEY_NORMAL_LEFT_WIDTH = "normal_left_width";
    private static final String KEY_NORMAL_LEFT_HEIGHT = "normal_left_height";
    private static final String KEY_NORMAL_LEFT_X = "normal_left_x";
    private static final String KEY_NORMAL_LEFT_Y = "normal_left_y";
    private static final String KEY_NORMAL_LEFT_ROTATION = "normal_left_rotation";
    private static final String KEY_NORMAL_RIGHT_WIDTH = "normal_right_width";
    private static final String KEY_NORMAL_RIGHT_HEIGHT = "normal_right_height";
    private static final String KEY_NORMAL_RIGHT_X = "normal_right_x";
    private static final String KEY_NORMAL_RIGHT_Y = "normal_right_y";
    private static final String KEY_NORMAL_RIGHT_ROTATION = "normal_right_rotation";
    
    // 按钮样式常量
    public static final String BUTTON_STYLE_STANDARD = "standard";  // 标准按钮（E5风格）
    public static final String BUTTON_STYLE_MULTI = "multi";        // 多按钮（L7-多按钮风格）
    
    // 按钮方向常量
    public static final String BUTTON_ORIENTATION_HORIZONTAL = "horizontal";  // 横版
    public static final String BUTTON_ORIENTATION_VERTICAL = "vertical";      // 竖版
    
    // 版本更新配置
    
    // 车型常量
    public static final String CAR_MODEL_GALAXY_E5 = "galaxy_e5";  // 银河E5
    public static final String CAR_MODEL_GALAXY_A7 = "galaxy_a7";  // 银河A7（沿用E5配置）
    public static final String CAR_MODEL_E5_MULTI = "galaxy_e5_multi";  // 银河E5-多按钮
    public static final String CAR_MODEL_L7 = "galaxy_l7";  // 银河L6/L7
    public static final String CAR_MODEL_L7_MULTI = "galaxy_l7_multi";  // 银河L7-多按钮
    public static final String CAR_MODEL_PHONE = "phone";  // 手机
    public static final String CAR_MODEL_CUSTOM = "custom";  // 自定义车型
    public static final String CAR_MODEL_XINGHAN_7 = "xinghan_7";  // 26款星舰7
    public static final String CAR_MODEL_MULTIVIEW = "multiview";  // 多视角布局
    public static final String CAR_MODEL_ZEEKR_7X = "zeekr_7x";  // 极氪7X（一路四联合成环视流）
    public static final String CAR_MODEL_ZEEKR_7X_MULTI = "zeekr_7x_multi";  // 极氪7X（环视合成流 + 两路座舱）
    
    private final SharedPreferences prefs;
    private final Context context;

    public AppConfig(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        repairInvalidSettings();
    }
    
    // ==================== 首次启动相关方法 ====================
    
    /**
     * 检查是否为首次启动
     * @return true 表示首次启动（新安装后第一次打开）
     */
    public boolean isFirstLaunch() {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }
    
    /**
     * 标记首次启动已完成
     */
    public void setFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        AppLog.d(TAG, "首次启动标记已设置为完成");
    }
    
    // ==================== 设备识别名称相关方法 ====================
    
    /**
     * 获取设备识别名称（用于日志上传）
     * @return 设备名称，如果未设置返回 null
     */
    public String getDeviceNickname() {
        return prefs.getString(KEY_DEVICE_NICKNAME, null);
    }
    
    /**
     * 设置设备识别名称
     * @param nickname 设备名称
     */
    public void setDeviceNickname(String nickname) {
        prefs.edit().putString(KEY_DEVICE_NICKNAME, nickname).apply();
        AppLog.d(TAG, "设备识别名称已设置: " + nickname);
    }
    
    /**
     * 检查是否已设置设备识别名称
     * @return true 表示已设置
     */
    public boolean hasDeviceNickname() {
        String nickname = getDeviceNickname();
        return nickname != null && !nickname.trim().isEmpty();
    }
    
    // ==================== 开机自启动相关方法 ====================
    
    /**
     * 设置开机自启动
     * @param enabled true 表示启用开机自启动
     */
    public void setAutoStartOnBoot(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply();
        AppLog.d(TAG, "开机自启动设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取开机自启动设置
     * @return true 表示启用开机自启动
     */
    public boolean isAutoStartOnBoot() {
        // 默认启用开机自启动（车机系统场景）
        // 默认关：开机就自己起来是件挺重的事，该由用户明确开启
        return prefs.getBoolean(KEY_AUTO_START_ON_BOOT, false);
    }
    
    /**
     * 设置启动自动录制
     * @param enabled true 表示启用启动自动录制
     */
    public void setAutoStartRecording(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START_RECORDING, enabled).apply();
        AppLog.d(TAG, "启动自动录制设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取启动自动录制设置
     * @return true 表示启用启动自动录制
     */
    public boolean isAutoStartRecording() {
        // 默认禁用启动自动录制（需要用户主动开启）
        return prefs.getBoolean(KEY_AUTO_START_RECORDING, false);
    }
    
    /**
     * 设置息屏录制（锁车录制）
     * @param enabled true 表示息屏时继续录制
     */
    public void setScreenOffRecordingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_OFF_RECORDING, enabled).apply();
        AppLog.d(TAG, "息屏录制设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取息屏录制设置
     * @return true 表示息屏时继续录制
     */
    public boolean isScreenOffRecordingEnabled() {
        // 默认禁用息屏录制
        return prefs.getBoolean(KEY_SCREEN_OFF_RECORDING, false);
    }
    
    /**
     * 设置保活服务
     * @param enabled true 表示启用保活服务
     */
    public void setKeepAliveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply();
        AppLog.d(TAG, "保活服务设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取保活服务设置
     * @return true 表示启用保活服务
     */
    public boolean isKeepAliveEnabled() {
        // 默认启用保活服务
        return prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, true);
    }
    
    /**
     * 设置防止休眠（持续WakeLock）
     * @param enabled true 表示启用防止休眠
     */
    public void setPreventSleepEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PREVENT_SLEEP_ENABLED, enabled).apply();
        AppLog.d(TAG, "防止休眠设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取防止休眠设置
     * @return true 表示启用防止休眠
     */
    public boolean isPreventSleepEnabled() {
        // 车机应用默认启用防止休眠
        // 原因：1. 车机使用车辆供电，不影响电池
        //       2. 摄像头应用需要在息屏时继续录制
        //       3. 远程控制需要后台运行
        return prefs.getBoolean(KEY_PREVENT_SLEEP_ENABLED, true);
    }
    
    /**
     * 设置录制模式
     * @param mode 录制模式（auto/media_recorder/codec）
     */
    public void setRecordingMode(String mode) {
        writeEnum(SettingsRegistry.RECORDING_MODE, mode);
        AppLog.d(TAG, "录制模式设置: " + mode);
    }
    
    /**
     * 获取录制模式
     * @return 录制模式，默认为自动
     */
    public String getRecordingMode() {
        return readEnum(SettingsRegistry.RECORDING_MODE);
    }
    
    /**
     * 判断是否应该使用 Codec 录制模式
     * @return true 表示应该使用 CodecVideoRecorder
     */
    public boolean shouldUseCodecRecording() {
        String mode = getRecordingMode();
        if (com.kooo.evcam.profile.RecordSpecs.anyGridEnabled(context)) {
            // 四宫格要在编码前用 GL 重排画面，只有 MediaCodec 路径能做到；
            // MediaRecorder 直接吃相机原始输出，没有插手的余地。
            if (RECORDING_MODE_MEDIA_RECORDER.equals(mode)) {
                AppLog.i(TAG, "已选四宫格录制，忽略 MediaRecorder 模式设置，改用 MediaCodec");
            }
            return true;
        }
        if (RECORDING_MODE_CODEC.equals(mode)) {
            // 强制使用 Codec 模式
            return true;
        } else if (RECORDING_MODE_MEDIA_RECORDER.equals(mode)) {
            // 强制使用 MediaRecorder 模式
            return false;
        } else {
            // 自动模式：所有车型默认使用 MediaCodec 模式
            return true;
        }
    }
    
    /**
     * 重置所有配置为默认值
     */
    public void resetToDefault() {
        prefs.edit().clear().apply();
        AppLog.d(TAG, "配置已重置为默认值");
    }
    
    // ==================== 分辨率配置相关方法 ====================
    
    
    
    
    
    // ==================== 码率配置相关方法 ====================
    
    
    
    /**
     * 根据分辨率和帧率计算码率（bps）
     * 公式：像素数 × 帧率 × 0.1
     * @param width 宽度
     * @param height 高度
     * @param frameRate 帧率
     * @return 计算出的码率（bps）
     */
    public static int calculateBitrate(int width, int height, int frameRate) {
        // 像素数 × 帧率 × 0.1
        long bitrate = (long) width * height * frameRate / 10;
        return (int) bitrate;
    }
    
    /**
     * 同上，但码率等级由调用方给。
     *
     * <p>等级现在写在每一路自己的配置里，而不是一个全局键 —— 取值的地方变了，
     * 这条公式没变。</p>
     */
    public static int actualBitrate(String level, int width, int height, int frameRate) {
        int baseBitrate = calculateBitrate(width, height, frameRate);
        switch (level == null ? "" : level) {
            case BITRATE_LOW:
                // 50%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate / 2);
            case BITRATE_HIGH:
                // 150%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate * 3 / 2);
            case BITRATE_MEDIUM:
            default:
                // 100%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate);
        }
    }
    
    /**
     * 将码率四舍五入到最接近的 0.5Mbps
     * @param bitrate 原始码率（bps）
     * @return 四舍五入后的码率（bps）
     */
    private static int roundToHalfMbps(int bitrate) {
        // 转换为 0.5Mbps 的倍数
        int halfMbps = 500000;
        int rounded = ((bitrate + halfMbps / 2) / halfMbps) * halfMbps;
        // 最小 0.5Mbps，最大 20Mbps
        return Math.max(halfMbps, Math.min(rounded, 20000000));
    }
    
    
    /**
     * 格式化码率为可读字符串
     * @param bitrate 码率（bps）
     * @return 格式化字符串，如 "3.0 Mbps"
     */
    public static String formatBitrate(int bitrate) {
        float mbps = bitrate / 1000000f;
        if (mbps >= 1) {
            return String.format(java.util.Locale.getDefault(), "%.1f Mbps", mbps);
        } else {
            return String.format(java.util.Locale.getDefault(), "%d Kbps", bitrate / 1000);
        }
    }
    
    
    // ==================== 帧率配置相关方法 ====================
    
    
    
    /**
     * 实际使用的录制帧率。
     *
     * <p>只由「录制帧率」一个设置决定：选「原始帧率」就跟随硬件，否则用选中的数值。
     * 旧的「标准/低」等级已整体删除 —— 它的界面在 0.6.1 合并两个帧率控件时就撤掉了，
     * 此后只是为了被迁移而存在，留着只会让「我到底在用多少帧录」多一个变量。</p>
     *
     * @param hardwareMaxFps 硬件支持的最大帧率
     * @return 实际使用的帧率
     */
    /**
     * 读不到相机声明时，录制链路当作「硬件最大帧率」用的兜底值。
     *
     * <p>只在 {@code CameraCapabilities.declaredMaxFps()} 拿不到数时才会用到 ——
     * 相机说得出话就听相机的，这个数不参与决策。</p>
     */
    public static final int RECORDER_MAX_FPS = 25;

    /**
     * 「不限制帧率」。
     *
     * <p>0 而不是某个很大的数：下游拿到 0 就知道「不要设任何门槛」，
     * 拿到 999 还得先判断这是不是一个真实的目标值。</p>
     */

    // ==================== 录制帧率（显式选择） ====================

    // ==================== 预览/录制分辨率解耦 ====================

    // ==================== 手动指定相机映射 ====================

    /**
     * 覆盖某个槽位使用的相机 id。
     *
     * <p>自动分配是「合成流按能力找、其余按 id 顺序补」，这在多路配置里只是猜测 ——
     * Camera2 无法告诉我们哪一路是后排、哪一路是驾驶位。这里允许直接指定。</p>
     *
     * @param slot     槽位：front / back / left
     * @param cameraId 相机 id；传 null 或空表示恢复自动
     */
    public void setCameraOverride(String slot, String cameraId) {
        String key = KEY_CAMERA_OVERRIDE_PREFIX + slot;
        if (cameraId == null || cameraId.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, cameraId).apply();
        }
        AppLog.d(TAG, "相机映射覆盖 " + slot + " = " + cameraId);
    }

    /** 返回 null 表示该槽位走自动分配。 */
    public String getCameraOverride(String slot) {
        String value = prefs.getString(KEY_CAMERA_OVERRIDE_PREFIX + slot, null);
        return (value == null || value.trim().isEmpty()) ? null : value;
    }

    /** 是否有任何槽位被手动指定过。 */
    public boolean hasCameraOverride() {
        return getCameraOverride("front") != null
                || getCameraOverride("back") != null
                || getCameraOverride("left") != null;
    }

    /** 清掉所有手动指定，全部恢复自动。 */
    public void clearCameraOverrides() {
        prefs.edit()
                .remove(KEY_CAMERA_OVERRIDE_PREFIX + "front")
                .remove(KEY_CAMERA_OVERRIDE_PREFIX + "back")
                .remove(KEY_CAMERA_OVERRIDE_PREFIX + "left")
                .apply();
        AppLog.i(TAG, "相机映射已全部恢复自动");
    }


    
    
    // ==================== 设置项自检 ====================

    /** 每个进程只自检一次，AppConfig 到处都在 new，不必每次都查。 */
    private static volatile boolean settingsRepaired = false;

    /**
     * 校验枚举型设置项，把不在合法取值内的值修回默认值。
     *
     * <p>起因是一个真实故障：设置页显示「四宫格」，录出来却是原始长条，
     * 把选项切走再切回来就好了。这正是<b>存的值和显示的值对不上</b>的表现。</p>
     *
     * <p>设置页每个下拉框都是这么读的：</p>
     *
     * <pre>
     *   int selectedIndex = 0;
     *   for (...) if (VALUES[i].equals(current)) { selectedIndex = i; break; }
     *   spinner.setSelection(selectedIndex);
     * </pre>
     *
     * <p>存的字符串一个都没匹配上时，界面<b>默默</b>显示第 0 项，而配置里还是那个
     * 对不上的值 —— 于是屏幕上写着一回事，程序按另一回事跑；切换选项之所以能「修好」，
     * 只是因为那一下终于写进了一个两边一致的值。八个下拉框都是这个形状。</p>
     *
     * <p>与其在每个读取点各打一个补丁，不如让配置自己兜底：凡是取值不在合法集合里的
     * 枚举项，一律修回文档写明的默认值并记日志。这样无论坏值是怎么来的 ——
     * 写入被打断、版本之间合法取值变了、还是装了旧版本回去 —— 界面都不会再显示一个
     * 程序不会遵守的设置。</p>
     */
    private void repairInvalidSettings() {
        if (settingsRepaired) {
            return;
        }
        settingsRepaired = true;

        // 遍历注册表，新增设置项自动纳入自检，不必记得回来加一行
        for (SettingSpec spec : SettingsRegistry.ALL) {
            repairEnum(spec);
        }
    }

    /** 一个枚举项的自检：存的值不合法就修回默认值。 */
    private void repairEnum(SettingSpec spec) {
        if (!prefs.contains(spec.key)) {
            return;  // 没存过就走默认，没什么可修的
        }
        String current = prefs.getString(spec.key, null);
        if (spec.isValid(current)) {
            return;
        }
        AppLog.w(TAG, spec.label + " 的存值「" + current + "」不在合法取值内，已修回默认「"
                + spec.defaultValue + "」");
        prefs.edit().putString(spec.key, spec.defaultValue).apply();
    }

    /**
     * 读一个枚举型设置。
     *
     * <p>一律过一遍 {@link SettingSpec#sanitize(String)} —— 不合法的值<b>根本不可能被
     * 读出来</b>，启动自检只是顺手把坏值从存储里清掉，不是唯一的防线。</p>
     */
    private String readEnum(SettingSpec spec) {
        return spec.sanitize(prefs.getString(spec.key, spec.defaultValue));
    }

    /** 写一个枚举型设置；不合法的值直接拒绝，避免把坏值写进存储。 */
    private void writeEnum(SettingSpec spec, String value) {
        if (!spec.isValid(value)) {
            AppLog.w(TAG, "拒绝把不合法的值「" + value + "」写入 " + spec.label);
            return;
        }
        prefs.edit().putString(spec.key, value).apply();
    }

    // ==================== 超级后视镜 ====================

    /**
     * 后视镜窗口的默认尺寸（px）。
     *
     * <p>宽高分开，而不是正方形：后视镜天然是横向的，喂给它的取景也是该路画面里
     * 一条横向的带 —— 硬做成正方形，要么上下留黑边，要么把两侧裁掉。</p>
     */
    public static final int REARVIEW_DEFAULT_WIDTH = 900;
    public static final int REARVIEW_DEFAULT_HEIGHT = 340;
    /** 允许的窗口尺寸范围。 */
    /**
     * 窗口尺寸下限。
     *
     * <p>不是 0：小到抓不住的话，就再也没法把它拖出来改大了 ——
     * 一个不可恢复的状态不该让人一滑就滑进去。</p>
     */
    public static final int REARVIEW_MIN_SIZE = 120;

    public boolean isRearViewEnabled() {
        return prefs.getBoolean(KEY_REARVIEW_ENABLED, false);
    }

    public void setRearViewEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REARVIEW_ENABLED, enabled).apply();
        AppLog.d(TAG, "超级后视镜: " + (enabled ? "开" : "关"));
    }

    /** 窗口宽度（px），上限为屏幕宽。 */
    public int getRearViewWidth(int screenWidth) {
        return clampRearViewSize(
                prefs.getInt(KEY_REARVIEW_WIDTH, REARVIEW_DEFAULT_WIDTH), screenWidth);
    }

    /** 窗口高度（px），上限为屏幕高。 */
    public int getRearViewHeight(int screenHeight) {
        return clampRearViewSize(
                prefs.getInt(KEY_REARVIEW_HEIGHT, REARVIEW_DEFAULT_HEIGHT), screenHeight);
    }

    public void setRearViewSize(int widthPx, int heightPx, int screenWidth, int screenHeight) {
        prefs.edit()
                .putInt(KEY_REARVIEW_WIDTH, clampRearViewSize(widthPx, screenWidth))
                .putInt(KEY_REARVIEW_HEIGHT, clampRearViewSize(heightPx, screenHeight))
                .apply();
    }

    /** 是否对后视镜画面做鱼眼校正。只影响显示，录制的原始画面不动。 */
    public boolean isRearViewFisheyeCorrection() {
        // 默认开：这几路都是鱼眼镜头，不校正的画面本来就不该是「正常」状态
        return prefs.getBoolean(KEY_REARVIEW_FISHEYE, true);
    }

    public void setRearViewFisheyeCorrection(boolean on) {
        prefs.edit().putBoolean(KEY_REARVIEW_FISHEYE, on).apply();
    }

    /** 校正的目标视野角度（度）。 */
    public float getRearViewFov() {
        return FisheyeProjection.clampFov(
                prefs.getFloat(KEY_REARVIEW_FOV, FisheyeProjection.DEFAULT_FOV_DEGREES));
    }

    public void setRearViewFov(float degrees) {
        prefs.edit().putFloat(KEY_REARVIEW_FOV, FisheyeProjection.clampFov(degrees)).apply();
    }

    /**
     * 夹住窗口尺寸。
     *
     * <p>上限就是屏幕本身 —— 想铺满整块屏就该允许，没有理由替用户设一个更小的天花板。</p>
     */
    public static int clampRearViewSize(int px, int screenLimit) {
        int max = Math.max(REARVIEW_MIN_SIZE, screenLimit);
        return Math.max(REARVIEW_MIN_SIZE, Math.min(max, px));
    }

    /**
     * 上下平移量，0..1。
     *
     * <p>以前这里存的是一个四值蒙版矩形。现在画面比例锁死了，
     * 看得到多宽由窗口形状和视野角度决定、看得到多高由比例决定 ——
     * <b>可调的只剩「这一条落在画面的哪个高度」</b>，一个数就够了。</p>
     */
    public float getRearViewPan() {
        return com.kooo.evcam.zeekr.RearViewGeometry.clampPan(
                prefs.getFloat(KEY_REARVIEW_PAN, com.kooo.evcam.zeekr.RearViewGeometry.DEFAULT_PAN));
    }

    /**
     * 后视镜当前显示哪一路。
     *
     * <p>记下来是因为它是用手势改的 —— 关掉再打开还停在原处，
     * 比每次都跳回后视更符合预期。</p>
     */
    public int getRearViewLane() {
        int lane = prefs.getInt(KEY_REARVIEW_LANE, com.kooo.evcam.zeekr.LaneCycle.REAR);
        boolean frontRearOnly = isRearViewFrontRearOnly();
        // 存的那一路可能已经不在当前模式的环上了（比如停在「左」时打开了只看前后）
        return com.kooo.evcam.zeekr.LaneCycle.isOnRing(lane, frontRearOnly)
                ? lane : com.kooo.evcam.zeekr.LaneCycle.REAR;
    }

    public void setRearViewLane(int lane) {
        prefs.edit().putInt(KEY_REARVIEW_LANE, lane).apply();
    }

    /** 只在前后之间切换，不去侧视。 */
    public boolean isRearViewFrontRearOnly() {
        return prefs.getBoolean(KEY_REARVIEW_FRONT_REAR, false);
    }

    public void setRearViewFrontRearOnly(boolean on) {
        prefs.edit().putBoolean(KEY_REARVIEW_FRONT_REAR, on).apply();
    }

    public void setRearViewPan(float pan) {
        prefs.edit().putFloat(KEY_REARVIEW_PAN,
                com.kooo.evcam.zeekr.RearViewGeometry.clampPan(pan)).apply();
    }

    /** 窗口位置；返回 -1 表示还没拖过，由调用方决定初始位置。 */
    public int getRearViewX() {
        return prefs.getInt(KEY_REARVIEW_X, -1);
    }

    public int getRearViewY() {
        return prefs.getInt(KEY_REARVIEW_Y, -1);
    }

    public void setRearViewPosition(int x, int y) {
        prefs.edit().putInt(KEY_REARVIEW_X, x).putInt(KEY_REARVIEW_Y, y).apply();
    }

    /** 恢复出厂：位置、大小、取景范围一并复位。 */
    public void resetRearViewLayout() {
        prefs.edit()
                .remove(KEY_REARVIEW_X)
                .remove(KEY_REARVIEW_Y)
                .remove(KEY_REARVIEW_FISHEYE)
                .remove(KEY_REARVIEW_FOV)
                .remove(KEY_REARVIEW_WIDTH)
                .remove(KEY_REARVIEW_HEIGHT)
                .remove(KEY_REARVIEW_PAN)
                .remove(KEY_REARVIEW_LANE)
                .remove(KEY_REARVIEW_FRONT_REAR)
                .apply();
        AppLog.i(TAG, "超级后视镜布局已恢复默认");
    }

    // ==================== 车型配置相关方法 ====================
    
    /**
     * 设置车型
     * @param carModel 车型标识（galaxy_e5 或 custom）
     */
    public void setCarModel(String carModel) {
        writeEnum(SettingsRegistry.CAR_MODEL, carModel);
        AppLog.d(TAG, "车型设置: " + carModel);
    }
    
    /**
     * 获取车型
     * @return 车型标识，默认为银河E5
     */
    /** 界面语言：auto / zh / en。 */
    public String getLanguageMode() {
        return readEnum(SettingsRegistry.LANGUAGE);
    }

    public void setLanguageMode(String mode) {
        writeEnum(SettingsRegistry.LANGUAGE, mode);
    }

    /**
     * 首次启动时的语言选择做过没有。
     *
     * <p>和「首次启动」分开记：语言一选中就可能触发界面重建，
     * 重建之后引导弹窗还得照常出现，所以两件事各有各的标记。</p>
     */
    public boolean isLanguageChosen() {
        return prefs.getBoolean(KEY_LANGUAGE_CHOSEN, false);
    }

    public void setLanguageChosen() {
        prefs.edit().putBoolean(KEY_LANGUAGE_CHOSEN, true).apply();
    }

    public String getCarModel() {
        // 默认极氪7X：本项目就是为极氪合成流做的，其余车型不再提供
        return readEnum(SettingsRegistry.CAR_MODEL);
    }
    
    /**
     * 是否为自定义车型
     */
    public boolean isCustomCarModel() {
        return CAR_MODEL_CUSTOM.equals(getCarModel());
    }
    
    /**
     * 是否为多视角布局
     */
    public boolean isMultiviewCarModel() {
        return CAR_MODEL_MULTIVIEW.equals(getCarModel());
    }
    
    /**
     * 是否为极氪合成流车型。
     * 该模式下车机只提供一路四联合成流，预览由 FourLaneContainer 拆成四宫格。
     */
    public boolean isZeekrCompositeModel() {
        String model = getCarModel();
        return CAR_MODEL_ZEEKR_7X.equals(model) || CAR_MODEL_ZEEKR_7X_MULTI.equals(model);
    }

    /** 是否为「环视 + 两路座舱」的多路极氪配置。 */
    public boolean isZeekrMultiModel() {
        return CAR_MODEL_ZEEKR_7X_MULTI.equals(getCarModel());
    }
    
    /**
     * 是否需要自定义布局管理器（自定义车型和多视角都需要）
     */
    public boolean needsCustomLayoutManager() {
        return isCustomCarModel() || isMultiviewCarModel();
    }
    
    /**
     * 设置摄像头数量
     * @param count 摄像头数量（4/2/1）
     */
    public void setCameraCount(int count) {
        prefs.edit().putInt(KEY_CAMERA_COUNT, count).apply();
        AppLog.d(TAG, "摄像头数量设置: " + count);
    }
    
    /**
     * 获取摄像头数量
     * 对于预设车型返回固定数量，对于自定义车型返回用户设置的数量
     * @return 摄像头数量
     */
    public int getCameraCount() {
        String carModel = getCarModel();
        // 预设车型返回固定的摄像头数量
        switch (carModel) {
            case CAR_MODEL_PHONE:
                return 2;  // 手机：2摄
            case CAR_MODEL_GALAXY_E5:
            case CAR_MODEL_GALAXY_A7:
            case CAR_MODEL_E5_MULTI:
            case CAR_MODEL_L7:
            case CAR_MODEL_L7_MULTI:
            case CAR_MODEL_XINGHAN_7:
                return 4;  // 银河E5/L7/26款星舰7：4摄
            case CAR_MODEL_ZEEKR_7X:
                return 1;  // 极氪7X：车机只给一路合成流，四个画面在同一帧里
            case CAR_MODEL_ZEEKR_7X_MULTI:
                return 3;  // 极氪7X 多路：环视合成流 + 两路座舱摄像头
            case CAR_MODEL_MULTIVIEW:
            case CAR_MODEL_CUSTOM:
            default:
                // 自定义车型使用用户设置的数量
                return prefs.getInt(KEY_CAMERA_COUNT, 4);
        }
    }
    
    /**
     * 获取用户设置的摄像头数量（仅用于自定义车型）
     * @return 用户设置的摄像头数量，默认为4
     */
    public int getCustomCameraCount() {
        return prefs.getInt(KEY_CAMERA_COUNT, 4);
    }
    
    /**
     * 设置屏幕方向（仅4摄像头时有效）
     * @param orientation 屏幕方向（landscape/portrait）
     */
    public void setScreenOrientation(String orientation) {
        prefs.edit().putString(KEY_SCREEN_ORIENTATION, orientation).apply();
        AppLog.d(TAG, "屏幕方向设置: " + orientation);
    }
    
    /**
     * 获取屏幕方向（仅4摄像头时有效）
     * @return 屏幕方向，默认为横屏
     */
    public String getScreenOrientation() {
        return prefs.getString(KEY_SCREEN_ORIENTATION, "landscape");
    }
    
    /**
     * 设置摄像头编号
     * @param position 位置（front/back/left/right）
     * @param cameraId 摄像头编号
     */
    public void setCameraId(String position, String cameraId) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ID;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ID;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ID;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ID;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putString(key, cameraId).apply();
        AppLog.d(TAG, "摄像头编号设置: " + position + " = " + cameraId);
    }
    
    /**
     * 获取摄像头编号
     * @param position 位置（front/back/left/right）
     * @return 摄像头编号，默认为 -1 表示自动检测
     */
    public String getCameraId(String position) {
        String key;
        String defaultValue;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ID;
                defaultValue = "2";  // 银河E5默认：前=2
                break;
            case "back":
                key = KEY_CAMERA_BACK_ID;
                defaultValue = "1";  // 银河E5默认：后=1
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ID;
                defaultValue = "3";  // 银河E5默认：左=3
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ID;
                defaultValue = "0";  // 银河E5默认：右=0
                break;
            default:
                return "-1";
        }
        return prefs.getString(key, defaultValue);
    }
    
    /**
     * 设置摄像头名称
     * @param position 位置（front/back/left/right）
     * @param name 摄像头名称；传 {@code null} 是清掉这一项，
     *             之后取到的就是跟着界面语言走的默认名
     */
    public void setCameraName(String position, String name) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_NAME;
                break;
            case "back":
                key = KEY_CAMERA_BACK_NAME;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_NAME;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_NAME;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putString(key, name).apply();
        AppLog.d(TAG, "摄像头名称设置: " + position + " = " + name);
    }
    
    /**
     * 获取摄像头名称
     * 对于预设车型返回默认名称，对于自定义车型返回用户设置的名称
     *
     * <p>要传<b>界面</b>的 Context（Activity / Fragment）：默认名字是从资源表里取的，
     * 理由见 {@link CameraNames}。</p>
     *
     * @param uiContext 要显示这个名字的界面
     * @param position 位置（front/back/left/right）
     * @return 摄像头名称
     */
    public String getCameraName(Context uiContext, String position) {
        String defaultValue = CameraNames.of(uiContext, position);
        // 预设车型返回默认名称
        if (!isCustomCarModel()) {
            return defaultValue;
        }

        // 自定义车型返回用户设置的名称
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_NAME;
                break;
            case "back":
                key = KEY_CAMERA_BACK_NAME;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_NAME;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_NAME;
                break;
            default:
                return defaultValue;
        }
        return prefs.getString(key, defaultValue);
    }

    /**
     * 设置摄像头旋转角度（仅用于自定义车型）
     * @param position 位置（front/back/left/right）
     * @param rotation 旋转角度（0/90/180/270）
     */
    public void setCameraRotation(String position, int rotation) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ROTATION;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ROTATION;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ROTATION;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ROTATION;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putInt(key, rotation).apply();
        AppLog.d(TAG, "摄像头旋转角度设置: " + position + " = " + rotation + "°");
    }

    /**
     * 获取摄像头旋转角度（仅用于自定义车型）
     * @param position 位置（front/back/left/right）
     * @return 旋转角度，默认为0（不旋转）
     */
    public int getCameraRotation(String position) {
        // 如果不是自定义车型，返回0（E5使用代码中的固定旋转）
        if (!isCustomCarModel()) {
            return 0;
        }

        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ROTATION;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ROTATION;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ROTATION;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ROTATION;
                break;
            default:
                return 0;
        }
        return prefs.getInt(key, 0);
    }
    
    /**
     * 设置摄像头镜像
     * @param position 摄像头位置（front/back/left/right）
     * @param mirror 是否镜像
     */
    public void setCameraMirror(String position, boolean mirror) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_MIRROR;
                break;
            case "back":
                key = KEY_CAMERA_BACK_MIRROR;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_MIRROR;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_MIRROR;
                break;
            default:
                return;
        }
        prefs.edit().putBoolean(key, mirror).apply();
        AppLog.d(TAG, position + " 摄像头镜像设置: " + mirror);
    }

    /**
     * 获取摄像头镜像设置
     * @param position 摄像头位置（front/back/left/right）
     * @return 是否镜像，默认为false（不镜像）
     */
    public boolean getCameraMirror(String position) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_MIRROR;
                break;
            case "back":
                key = KEY_CAMERA_BACK_MIRROR;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_MIRROR;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_MIRROR;
                break;
            default:
                return false;
        }
        return prefs.getBoolean(key, false);
    }

    /**
     * 设置摄像头裁剪值
     * @param position 位置（front/back/left/right）
     * @param direction 方向（top/bottom/left/right）
     * @param pixels 裁剪像素值
     */
    public void setCameraCrop(String position, String direction, int pixels) {
        String key = KEY_CAMERA_CROP_PREFIX + position + "_" + direction;
        prefs.edit().putInt(key, Math.max(0, pixels)).apply();
    }

    /**
     * 获取摄像头裁剪值
     * @param position 位置（front/back/left/right）
     * @param direction 方向（top/bottom/left/right）
     * @return 裁剪像素值，默认为0
     */
    public int getCameraCrop(String position, String direction) {
        String key = KEY_CAMERA_CROP_PREFIX + position + "_" + direction;
        return prefs.getInt(key, 0);
    }

    /**
     * 重置摄像头的所有裁剪值
     * @param position 位置（front/back/left/right）
     */
    public void resetCameraCrop(String position) {
        setCameraCrop(position, "top", 0);
        setCameraCrop(position, "bottom", 0);
        setCameraCrop(position, "left", 0);
        setCameraCrop(position, "right", 0);
    }

    // ==================== 存储位置配置相关方法 ====================
    
    /**
     * 设置存储位置
     * @param location 存储位置（internal 或 external_sd）
     */
    public void setStorageLocation(String location) {
        prefs.edit().putString(KEY_STORAGE_LOCATION, location).apply();
        AppLog.d(TAG, "存储位置设置: " + location);
    }
    
    /**
     * 获取存储位置
     * @return 存储位置，默认为内部存储
     */
    public String getStorageLocation() {
        // 默认 U 盘：行车记录是持续大量写入，默认写内置闪存会消耗它的寿命
        return prefs.getString(KEY_STORAGE_LOCATION, STORAGE_EXTERNAL_SD);
    }
    
    /**
     * 是否使用U盘存储
     * @return true 表示使用U盘
     */
    public boolean isUsingExternalSdCard() {
        return STORAGE_EXTERNAL_SD.equals(getStorageLocation());
    }
    
    /**
     * 设置自定义U盘路径
     * @param path U盘路径，设为null或空字符串表示使用自动检测
     */
    public void setCustomSdCardPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            prefs.edit().remove(KEY_CUSTOM_SD_CARD_PATH).apply();
            AppLog.d(TAG, "清除自定义U盘路径，使用自动检测");
        } else {
            prefs.edit().putString(KEY_CUSTOM_SD_CARD_PATH, path.trim()).apply();
            AppLog.d(TAG, "设置自定义U盘路径: " + path.trim());
        }
    }
    
    /**
     * 获取自定义U盘路径
     * @return 自定义路径，如果未设置返回null
     */
    public String getCustomSdCardPath() {
        String path = prefs.getString(KEY_CUSTOM_SD_CARD_PATH, null);
        if (path != null && path.trim().isEmpty()) {
            return null;
        }
        return path;
    }
    
    /**
     * 是否使用自定义U盘路径
     */
    public boolean hasCustomSdCardPath() {
        return getCustomSdCardPath() != null;
    }
    
    /**
     * 设置上次自动检测到的U盘路径（缓存）
     * @param path U盘路径
     */
    public void setLastDetectedSdPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            prefs.edit().remove(KEY_LAST_DETECTED_SD_PATH).apply();
        } else {
            prefs.edit().putString(KEY_LAST_DETECTED_SD_PATH, path.trim()).apply();
            AppLog.d(TAG, "缓存U盘路径: " + path.trim());
        }
    }
    
    /**
     * 获取上次自动检测到的U盘路径（缓存）
     * @return 缓存的路径，如果未设置返回null
     */
    public String getLastDetectedSdPath() {
        return prefs.getString(KEY_LAST_DETECTED_SD_PATH, null);
    }
    
    /**
     * 检查本次启动是否已显示过U盘回退提示
     */
    public static boolean isSdFallbackShownThisSession() {
        return sdFallbackShownThisSession;
    }
    
    /**
     * 标记本次启动已显示过U盘回退提示
     */
    public static void setSdFallbackShownThisSession(boolean shown) {
        sdFallbackShownThisSession = shown;
    }
    
    /**
     * 重置U盘回退提示标志（应用启动时调用）
     */
    public static void resetSdFallbackFlag() {
        sdFallbackShownThisSession = false;
    }
    
    /**
     * 检查当前是否应该使用中转写入
     * 当选择U盘存储且启用了中转写入时，使用中转写入以避免U盘慢速写入导致录制卡顿
     * @return true 表示应该使用中转写入
     */
    public boolean shouldUseRelayWrite() {
        // 只有使用U盘存储时才考虑中转写入
        if (!isUsingExternalSdCard()) {
            return false;
        }
        // 走同一个入口，不要在这里另外写一个默认值——
        // 之前这里写死为 true，而 isRelayWriteEnabled() 已改为默认 false，
        // 结果开关显示关闭、实际却仍在走内部存储中转。
        return isRelayWriteEnabled();
    }
    
    /**
     * 设置中转写入开关
     * @param enabled true 表示启用中转写入
     */
    public void setRelayWriteEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RELAY_WRITE_ENABLED, enabled).apply();
        AppLog.d(TAG, "中转写入设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取中转写入开关状态
     * @return true 表示中转写入已启用
     */
    public boolean isRelayWriteEnabled() {
        // 默认关闭：中转写入会先写内部存储再搬到 U 盘，闪存写入量翻倍。
        // 只有在 U 盘写入速度确实跟不上、录制出现卡顿时才值得开。
        return prefs.getBoolean(KEY_RELAY_WRITE_ENABLED, false);
    }
    
    // ==================== 悬浮窗配置相关方法 ====================
    
    /**
     * 设置悬浮窗开关
     * @param enabled true 表示启用悬浮窗
     */
    public void setFloatingWindowEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLOATING_WINDOW_ENABLED, enabled).apply();
        AppLog.d(TAG, "悬浮窗设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取悬浮窗开关状态
     * @return true 表示启用悬浮窗
     */
    public boolean isFloatingWindowEnabled() {
        return prefs.getBoolean(KEY_FLOATING_WINDOW_ENABLED, false);
    }
    
    /**
     * 设置悬浮窗大小（dp）
     * @param sizeDp 悬浮窗大小，单位dp
     */
    public void setFloatingWindowSize(int sizeDp) {
        prefs.edit().putInt(KEY_FLOATING_WINDOW_SIZE, sizeDp).apply();
        AppLog.d(TAG, "悬浮窗大小设置: " + sizeDp + "dp");
    }
    
    /**
     * 获取悬浮窗大小（dp）
     * @return 悬浮窗大小，默认为中等大小
     */
    public int getFloatingWindowSize() {
        return prefs.getInt(KEY_FLOATING_WINDOW_SIZE, FLOATING_SIZE_DEFAULT);
    }
    
    /**
     * 设置悬浮窗透明度（0-100）
     * @param alpha 透明度百分比，0为完全透明，100为完全不透明
     */
    public void setFloatingWindowAlpha(int alpha) {
        prefs.edit().putInt(KEY_FLOATING_WINDOW_ALPHA, alpha).apply();
        AppLog.d(TAG, "悬浮窗透明度设置: " + alpha + "%");
    }
    
    /**
     * 获取悬浮窗透明度（0-100）
     * @return 透明度百分比，默认为100（完全不透明）
     */
    public int getFloatingWindowAlpha() {
        return prefs.getInt(KEY_FLOATING_WINDOW_ALPHA, FLOATING_ALPHA_DEFAULT);
    }
    
    /**
     * 保存悬浮窗位置
     * @param x X坐标
     * @param y Y坐标
     */
    public void setFloatingWindowPosition(int x, int y) {
        prefs.edit()
            .putInt(KEY_FLOATING_WINDOW_X, x)
            .putInt(KEY_FLOATING_WINDOW_Y, y)
            .apply();
    }
    
    /**
     * 获取悬浮窗X位置
     * @return X坐标，默认-1表示未设置
     */
    public int getFloatingWindowX() {
        return prefs.getInt(KEY_FLOATING_WINDOW_X, -1);
    }
    
    /**
     * 获取悬浮窗Y位置
     * @return Y坐标，默认-1表示未设置
     */
    public int getFloatingWindowY() {
        return prefs.getInt(KEY_FLOATING_WINDOW_Y, -1);
    }
    
    // ==================== 存储清理配置相关方法 ====================
    
    /**
     * 设置视频存储限制（GB）
     * @param limitGb 存储限制，单位GB，0表示不限制
     */
    public void setVideoStorageLimitGb(int limitGb) {
        prefs.edit().putInt(KEY_VIDEO_STORAGE_LIMIT_GB, limitGb).apply();
        AppLog.d(TAG, "视频存储限制设置: " + limitGb + " GB");
    }
    
    /**
     * 获取视频存储限制（GB）
     * @return 存储限制，单位GB，0表示不限制，默认10GB
     */
    public int getVideoStorageLimitGb() {
        return prefs.getInt(KEY_VIDEO_STORAGE_LIMIT_GB, 10);
    }
    
    /**
     * 设置图片存储限制（GB）
     * @param limitGb 存储限制，单位GB，0表示不限制
     */
    public void setPhotoStorageLimitGb(int limitGb) {
        prefs.edit().putInt(KEY_PHOTO_STORAGE_LIMIT_GB, limitGb).apply();
        AppLog.d(TAG, "图片存储限制设置: " + limitGb + " GB");
    }
    
    /**
     * 获取图片存储限制（GB）
     * @return 存储限制，单位GB，0表示不限制，默认10GB
     */
    public int getPhotoStorageLimitGb() {
        return prefs.getInt(KEY_PHOTO_STORAGE_LIMIT_GB, 10);
    }
    
    /**
     * 检查是否启用了存储清理功能
     * @return true 如果至少有一项存储限制设置大于0
     */
    public boolean isStorageCleanupEnabled() {
        return getVideoStorageLimitGb() > 0 || getPhotoStorageLimitGb() > 0;
    }
    
    // ==================== 分段录制配置相关方法 ====================
    
    
    
    
    // ==================== 录制状态显示配置相关方法 ====================
    
    /**
     * 设置录制状态显示开关
     * @param enabled true 表示显示录制时间和分段数
     */
    public void setRecordingStatsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECORDING_STATS_ENABLED, enabled).apply();
        AppLog.d(TAG, "录制状态显示设置: " + (enabled ? "显示" : "隐藏"));
    }
    
    /**
     * 获取录制状态显示开关状态
     * @return true 表示显示录制时间和分段数
     */
    public boolean isRecordingStatsEnabled() {
        // 默认开启录制状态显示
        return prefs.getBoolean(KEY_RECORDING_STATS_ENABLED, true);
    }
    
    // ==================== 补盲功能全局开关 ====================
    
    /**
     * 设置补盲功能全局开关
     * 关闭时，所有补盲子功能（转向灯联动、主屏悬浮窗、副屏显示、模拟按钮、画面矫正）均不生效
     * @param enabled true 表示启用补盲功能
     */
    public void setBlindSpotGlobalEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLIND_SPOT_GLOBAL_ENABLED, enabled).apply();
        AppLog.d(TAG, "补盲功能全局开关: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取补盲功能全局开关状态
     * @return true 表示补盲功能已启用
     */
    public boolean isBlindSpotGlobalEnabled() {
        return prefs.getBoolean(KEY_BLIND_SPOT_GLOBAL_ENABLED, false);
    }
    
    // ==================== 超视模式配置相关方法 ====================
    
    /**
     * 设置超视模式开关
     * @param enabled true 表示启用超视模式
     */
    public void setSupervisionModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SUPERVISION_MODE_ENABLED, enabled).apply();
        AppLog.d(TAG, "超视模式设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取超视模式开关状态
     * @return true 表示超视模式已启用
     */
    public boolean isSupervisionModeEnabled() {
        return prefs.getBoolean(KEY_SUPERVISION_MODE_ENABLED, false);
    }
    
    /**
     * 设置左视悬浮窗位置和大小
     */
    public void setSupervisionLeftBounds(int x, int y, int width, int height) {
        AppLog.i(TAG, "💾 保存左视窗: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height);
        prefs.edit()
            .putInt(KEY_SUPERVISION_LEFT_X, x)
            .putInt(KEY_SUPERVISION_LEFT_Y, y)
            .putInt(KEY_SUPERVISION_LEFT_WIDTH, width)
            .putInt(KEY_SUPERVISION_LEFT_HEIGHT, height)
            .commit(); // 使用commit()同步保存
        AppLog.i(TAG, "💾 保存左视窗完成，当前读取: x=" + getSupervisionLeftX());
    }
    
    public int getSupervisionLeftX() {
        int val = prefs.getInt(KEY_SUPERVISION_LEFT_X, 100);
        AppLog.d(TAG, "📍 读取左视窗X: " + val + " (key=" + KEY_SUPERVISION_LEFT_X + ")");
        return val;
    }

    public int getSupervisionLeftY() {
        int val = prefs.getInt(KEY_SUPERVISION_LEFT_Y, 100);
        AppLog.d(TAG, "📍 读取左视窗Y: " + val);
        return val;
    }

    public int getSupervisionLeftWidth() {
        int val = prefs.getInt(KEY_SUPERVISION_LEFT_WIDTH, 800);
        AppLog.d(TAG, "📍 读取左视窗Width: " + val);
        return val;
    }

    public int getSupervisionLeftHeight() {
        int val = prefs.getInt(KEY_SUPERVISION_LEFT_HEIGHT, 600);
        AppLog.d(TAG, "📍 读取左视窗Height: " + val);
        return val;
    }
    
    /**
     * 设置右视悬浮窗位置和大小
     */
    public void setSupervisionRightBounds(int x, int y, int width, int height) {
        AppLog.i(TAG, "💾 保存右视窗: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height);
        prefs.edit()
            .putInt(KEY_SUPERVISION_RIGHT_X, x)
            .putInt(KEY_SUPERVISION_RIGHT_Y, y)
            .putInt(KEY_SUPERVISION_RIGHT_WIDTH, width)
            .putInt(KEY_SUPERVISION_RIGHT_HEIGHT, height)
            .commit(); // 使用commit()同步保存
        AppLog.i(TAG, "💾 保存右视窗完成，当前读取: x=" + getSupervisionRightX());
    }

    public int getSupervisionRightX() {
        // 右视窗X默认值：左视窗默认X(100) + 默认宽度(800) + 10 = 910
        int val = prefs.getInt(KEY_SUPERVISION_RIGHT_X, 910);
        AppLog.d(TAG, "📍 读取右视窗X: " + val + " (key=" + KEY_SUPERVISION_RIGHT_X + ")");
        return val;
    }
    
    public int getSupervisionRightY() {
        // 右视窗Y默认值：与左视窗默认Y相同
        return prefs.getInt(KEY_SUPERVISION_RIGHT_Y, 100);
    }
    
    public int getSupervisionRightWidth() {
        return prefs.getInt(KEY_SUPERVISION_RIGHT_WIDTH, 800);
    }
    
    public int getSupervisionRightHeight() {
        return prefs.getInt(KEY_SUPERVISION_RIGHT_HEIGHT, 600);
    }
    
    /**
     * 设置长视模式开关
     * @param enabled true 表示启用长视模式
     */
    public void setLongViewModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LONG_VIEW_MODE_ENABLED, enabled).apply();
        AppLog.d(TAG, "长视模式设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取长视模式开关状态
     * @return true 表示长视模式已启用
     */
    public boolean isLongViewModeEnabled() {
        return prefs.getBoolean(KEY_LONG_VIEW_MODE_ENABLED, false);
    }
    
    /**
     * 重置超视模式悬浮窗位置和大小为默认值
     * 左右两个画面在同一水平线上，相距10dp
     */
    public void resetSupervisionBounds() {
        // 使用补盲画面尺寸作为默认尺寸
        int width = getTurnSignalFloatingWidth();
        int height = getTurnSignalFloatingHeight();
        // 使用补盲画面位置作为右窗口默认位置
        int rightX = getTurnSignalFloatingX();
        int rightY = getTurnSignalFloatingY();
        int gap = 10; // 10dp间距
        // 左窗口在右窗口左侧，水平对齐（Y坐标相同）
        int leftX = rightX - width - gap;
        int leftY = rightY;
        
        prefs.edit()
            .putInt(KEY_SUPERVISION_LEFT_X, leftX)
            .putInt(KEY_SUPERVISION_LEFT_Y, leftY)
            .putInt(KEY_SUPERVISION_LEFT_WIDTH, width)
            .putInt(KEY_SUPERVISION_LEFT_HEIGHT, height)
            .putInt(KEY_SUPERVISION_RIGHT_X, rightX)
            .putInt(KEY_SUPERVISION_RIGHT_Y, rightY)
            .putInt(KEY_SUPERVISION_RIGHT_WIDTH, width)
            .putInt(KEY_SUPERVISION_RIGHT_HEIGHT, height)
            .apply();
        
        AppLog.i(TAG, "🎯 重置超视模式默认位置: 左窗口(" + leftX + "," + leftY + "), 右窗口(" + rightX + "," + rightY + "), 尺寸=" + width + "x" + height);
    }
    
    /**
     * 强制将所有待处理的SharedPreferences写入磁盘
     * 用于确保保存操作立即生效
     */
    public void forceSave() {
        // 调用apply()已经将数据写入内存，等待异步写入磁盘
        // 这里可以添加日志确认
        AppLog.i(TAG, "forceSave() called - 数据应该已保存");
    }
    
    // ==================== 补盲选项配置相关方法 (原副屏显示) ====================
    
    /**
     * 设置副屏显示开关
     */
    public void setSecondaryDisplayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SECONDARY_DISPLAY_ENABLED, enabled).apply();
        AppLog.d(TAG, "副屏显示设置: " + (enabled ? "启用" : "禁用"));
    }
    
    public boolean isSecondaryDisplayEnabled() {
        return prefs.getBoolean(KEY_SECONDARY_DISPLAY_ENABLED, false);
    }
    
    /**
     * 设置副屏显示的摄像头位置
     */
    public void setSecondaryDisplayCamera(String position) {
        prefs.edit().putString(KEY_SECONDARY_DISPLAY_CAMERA, position).apply();
    }
    
    public String getSecondaryDisplayCamera() {
        return prefs.getString(KEY_SECONDARY_DISPLAY_CAMERA, "front");
    }
    
    /**
     * 设置副屏 Display ID
     */
    public void setSecondaryDisplayId(int displayId) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ID, displayId).apply();
    }
    
    public int getSecondaryDisplayId() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ID, 0); // 0 为默认主屏，通常副屏从1开始
    }
    
    /**
     * 设置副屏位置和大小
     */
    public void setSecondaryDisplayBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_SECONDARY_DISPLAY_X, x)
            .putInt(KEY_SECONDARY_DISPLAY_Y, y)
            .putInt(KEY_SECONDARY_DISPLAY_WIDTH, width)
            .putInt(KEY_SECONDARY_DISPLAY_HEIGHT, height)
            .apply();
    }
    
    public int getSecondaryDisplayX() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_X, 0);
    }
    
    public int getSecondaryDisplayY() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_Y, 139);
    }
    
    public int getSecondaryDisplayWidth() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_WIDTH, 318);
    }
    
    public int getSecondaryDisplayHeight() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_HEIGHT, 236);
    }
    
    /**
     * 设置副屏旋转角度
     */
    public void setSecondaryDisplayRotation(int rotation) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ROTATION, rotation).apply();
    }
    
    public int getSecondaryDisplayRotation() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ROTATION, 0);
    }
    
    /**
     * 设置是否显示白边框
     */
    public void setSecondaryDisplayBorderEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SECONDARY_DISPLAY_BORDER, enabled).apply();
    }
    
    public boolean isSecondaryDisplayBorderEnabled() {
        return prefs.getBoolean(KEY_SECONDARY_DISPLAY_BORDER, false);
    }
    
    /**
     * 设置屏幕方向
     */
    public void setSecondaryDisplayOrientation(int orientation) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ORIENTATION, orientation).apply();
    }
    
    public int getSecondaryDisplayOrientation() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ORIENTATION, 180);
    }

    /**
     * 设置副屏补盲悬浮窗透明度（0-100）
     * @param alpha 透明度百分比，0为完全透明，100为完全不透明
     */
    public void setSecondaryDisplayAlpha(int alpha) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ALPHA, Math.max(0, Math.min(100, alpha))).apply();
        AppLog.d(TAG, "副屏补盲悬浮窗透明度设置: " + alpha + "%");
    }

    /**
     * 获取副屏补盲悬浮窗透明度（0-100）
     * @return 透明度百分比，默认为100（完全不透明）
     */
    public int getSecondaryDisplayAlpha() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ALPHA, 100);
    }

    public void setMainFloatingAspectRatioLocked(boolean locked) {
        prefs.edit().putBoolean(KEY_MAIN_FLOATING_ASPECT_RATIO_LOCKED, locked).apply();
    }

    public boolean isMainFloatingAspectRatioLocked() {
        return prefs.getBoolean(KEY_MAIN_FLOATING_ASPECT_RATIO_LOCKED, false);
    }

    public void setMainFloatingLongPressDragEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MAIN_FLOATING_LONG_PRESS_DRAG, enabled).apply();
    }

    public boolean isMainFloatingLongPressDragEnabled() {
        return prefs.getBoolean(KEY_MAIN_FLOATING_LONG_PRESS_DRAG, false);
    }

    public void setBlindSpotCorrectionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLIND_SPOT_CORRECTION_ENABLED, enabled).apply();
    }

    public boolean isBlindSpotCorrectionEnabled() {
        return prefs.getBoolean(KEY_BLIND_SPOT_CORRECTION_ENABLED, false);
    }

    public void setBlindSpotDisclaimerAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED, accepted).apply();
    }

    public boolean isBlindSpotDisclaimerAccepted() {
        return prefs.getBoolean(KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED, false);
    }

    private String getBlindSpotCorrectionKey(String cameraPos, String suffix) {
        return KEY_BLIND_SPOT_CORRECTION_PREFIX + cameraPos + "_" + suffix;
    }

    public void setBlindSpotCorrectionScaleX(String cameraPos, float scaleX) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), scaleX).apply();
    }

    public void setBlindSpotCorrectionScaleY(String cameraPos, float scaleY) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), scaleY).apply();
    }

    public void setBlindSpotCorrectionTranslateX(String cameraPos, float translateX) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), translateX).apply();
    }

    public void setBlindSpotCorrectionTranslateY(String cameraPos, float translateY) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), translateY).apply();
    }

    public float getBlindSpotCorrectionScaleX(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), 1.0f);
    }

    public float getBlindSpotCorrectionScaleY(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), 1.0f);
    }

    public float getBlindSpotCorrectionTranslateX(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), 0.0f);
    }

    public float getBlindSpotCorrectionTranslateY(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), 0.0f);
    }

    public void setBlindSpotCorrectionRotation(String cameraPos, int rotation) {
        prefs.edit().putInt(getBlindSpotCorrectionKey(cameraPos, "rotation"), rotation).apply();
    }

    public int getBlindSpotCorrectionRotation(String cameraPos) {
        // 兼容旧的 float 存储，读取后转换
        try {
            return prefs.getInt(getBlindSpotCorrectionKey(cameraPos, "rotation"), 0);
        } catch (ClassCastException e) {
            // 旧版本存的是 float，读取并转换
            float old = prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "rotation"), 0.0f);
            int rounded = Math.round(old);
            // 规整到 0/90/180/270
            if (rounded != 0 && rounded != 90 && rounded != 180 && rounded != 270) rounded = 0;
            setBlindSpotCorrectionRotation(cameraPos, rounded);
            return rounded;
        }
    }

    public void setBlindSpotCorrectionMirrorH(String cameraPos, boolean mirror) {
        prefs.edit().putBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_h"), mirror).apply();
    }

    public boolean getBlindSpotCorrectionMirrorH(String cameraPos) {
        return prefs.getBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_h"), false);
    }

    public void setBlindSpotCorrectionMirrorV(String cameraPos, boolean mirror) {
        prefs.edit().putBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_v"), mirror).apply();
    }

    public boolean getBlindSpotCorrectionMirrorV(String cameraPos) {
        return prefs.getBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_v"), false);
    }

    public void resetBlindSpotCorrection(String cameraPos) {
        prefs.edit()
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), 1.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), 1.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), 0.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), 0.0f)
                .putInt(getBlindSpotCorrectionKey(cameraPos, "rotation"), 0)
                .putBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_h"), false)
                .putBoolean(getBlindSpotCorrectionKey(cameraPos, "mirror_v"), false)
                .apply();
    }

    // ==================== 预览画面矫正配置相关方法 ====================

    /**
     * 设置预览画面矫正开关
     */
    public void setPreviewCorrectionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PREVIEW_CORRECTION_ENABLED, enabled).apply();
        AppLog.d(TAG, "预览画面矫正设置: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取预览画面矫正开关
     */
    public boolean isPreviewCorrectionEnabled() {
        return prefs.getBoolean(KEY_PREVIEW_CORRECTION_ENABLED, false);
    }

    private String getPreviewCorrectionKey(String cameraPos, String suffix) {
        return KEY_PREVIEW_CORRECTION_PREFIX + cameraPos + "_" + suffix;
    }

    public void setPreviewCorrectionScaleX(String cameraPos, float scaleX) {
        prefs.edit().putFloat(getPreviewCorrectionKey(cameraPos, "scale_x"), scaleX).apply();
    }

    public void setPreviewCorrectionScaleY(String cameraPos, float scaleY) {
        prefs.edit().putFloat(getPreviewCorrectionKey(cameraPos, "scale_y"), scaleY).apply();
    }

    public void setPreviewCorrectionTranslateX(String cameraPos, float translateX) {
        prefs.edit().putFloat(getPreviewCorrectionKey(cameraPos, "translate_x"), translateX).apply();
    }

    public void setPreviewCorrectionTranslateY(String cameraPos, float translateY) {
        prefs.edit().putFloat(getPreviewCorrectionKey(cameraPos, "translate_y"), translateY).apply();
    }

    public float getPreviewCorrectionScaleX(String cameraPos) {
        return prefs.getFloat(getPreviewCorrectionKey(cameraPos, "scale_x"), 1.0f);
    }

    public float getPreviewCorrectionScaleY(String cameraPos) {
        return prefs.getFloat(getPreviewCorrectionKey(cameraPos, "scale_y"), 1.0f);
    }

    public float getPreviewCorrectionTranslateX(String cameraPos) {
        return prefs.getFloat(getPreviewCorrectionKey(cameraPos, "translate_x"), 0.0f);
    }

    public float getPreviewCorrectionTranslateY(String cameraPos) {
        return prefs.getFloat(getPreviewCorrectionKey(cameraPos, "translate_y"), 0.0f);
    }

    /**
     * 重置单路摄像头的预览矫正参数
     */
    public void resetPreviewCorrection(String cameraPos) {
        prefs.edit()
                .putFloat(getPreviewCorrectionKey(cameraPos, "scale_x"), 1.0f)
                .putFloat(getPreviewCorrectionKey(cameraPos, "scale_y"), 1.0f)
                .putFloat(getPreviewCorrectionKey(cameraPos, "translate_x"), 0.0f)
                .putFloat(getPreviewCorrectionKey(cameraPos, "translate_y"), 0.0f)
                .apply();
    }

    /**
     * 重置所有摄像头的预览矫正参数
     */
    public void resetAllPreviewCorrection() {
        resetPreviewCorrection("front");
        resetPreviewCorrection("back");
        resetPreviewCorrection("left");
        resetPreviewCorrection("right");
        AppLog.d(TAG, "所有预览画面矫正参数已重置");
    }

    // ==================== 鱼眼矫正配置相关方法 ====================

    /**
     * 设置鱼眼矫正开关
     */
    public void setFisheyeCorrectionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FISHEYE_CORRECTION_ENABLED, enabled).apply();
        AppLog.d(TAG, "鱼眼矫正设置: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取鱼眼矫正开关
     */
    public boolean isFisheyeCorrectionEnabled() {
        return prefs.getBoolean(KEY_FISHEYE_CORRECTION_ENABLED, false);
    }

    private String getFisheyeCorrectionKey(String cameraPos, String suffix) {
        return KEY_FISHEYE_CORRECTION_PREFIX + cameraPos + "_" + suffix;
    }

    // --- K1 (主畸变系数) ---
    public void setFisheyeCorrectionK1(String cameraPos, float k1) {
        prefs.edit().putFloat(getFisheyeCorrectionKey(cameraPos, "k1"), k1).apply();
    }

    public float getFisheyeCorrectionK1(String cameraPos) {
        return prefs.getFloat(getFisheyeCorrectionKey(cameraPos, "k1"), 0.0f);
    }

    // --- K2 (二次畸变系数) ---
    public void setFisheyeCorrectionK2(String cameraPos, float k2) {
        prefs.edit().putFloat(getFisheyeCorrectionKey(cameraPos, "k2"), k2).apply();
    }

    public float getFisheyeCorrectionK2(String cameraPos) {
        return prefs.getFloat(getFisheyeCorrectionKey(cameraPos, "k2"), 0.0f);
    }

    // --- Zoom (矫正后缩放) ---
    public void setFisheyeCorrectionZoom(String cameraPos, float zoom) {
        prefs.edit().putFloat(getFisheyeCorrectionKey(cameraPos, "zoom"), zoom).apply();
    }

    public float getFisheyeCorrectionZoom(String cameraPos) {
        return prefs.getFloat(getFisheyeCorrectionKey(cameraPos, "zoom"), 1.0f);
    }

    // --- CenterX (畸变中心X偏移) ---
    public void setFisheyeCorrectionCenterX(String cameraPos, float cx) {
        prefs.edit().putFloat(getFisheyeCorrectionKey(cameraPos, "center_x"), cx).apply();
    }

    public float getFisheyeCorrectionCenterX(String cameraPos) {
        return prefs.getFloat(getFisheyeCorrectionKey(cameraPos, "center_x"), 0.5f);
    }

    // --- CenterY (畸变中心Y偏移) ---
    public void setFisheyeCorrectionCenterY(String cameraPos, float cy) {
        prefs.edit().putFloat(getFisheyeCorrectionKey(cameraPos, "center_y"), cy).apply();
    }

    public float getFisheyeCorrectionCenterY(String cameraPos) {
        return prefs.getFloat(getFisheyeCorrectionKey(cameraPos, "center_y"), 0.5f);
    }

    // --- Rotation (旋转角度) ---
    public void setFisheyeCorrectionRotation(String cameraPos, int rotation) {
        prefs.edit().putInt(getFisheyeCorrectionKey(cameraPos, "rotation"), rotation).apply();
    }

    public int getFisheyeCorrectionRotation(String cameraPos) {
        return prefs.getInt(getFisheyeCorrectionKey(cameraPos, "rotation"), 0);
    }

    /**
     * 重置单路摄像头的鱼眼矫正参数
     */
    public void resetFisheyeCorrection(String cameraPos) {
        prefs.edit()
                .putFloat(getFisheyeCorrectionKey(cameraPos, "k1"), 0.0f)
                .putFloat(getFisheyeCorrectionKey(cameraPos, "k2"), 0.0f)
                .putFloat(getFisheyeCorrectionKey(cameraPos, "zoom"), 1.0f)
                .putFloat(getFisheyeCorrectionKey(cameraPos, "center_x"), 0.5f)
                .putFloat(getFisheyeCorrectionKey(cameraPos, "center_y"), 0.5f)
                .putInt(getFisheyeCorrectionKey(cameraPos, "rotation"), 0)
                .apply();
    }

    /**
     * 重置所有摄像头的鱼眼矫正参数
     */
    public void resetAllFisheyeCorrection() {
        resetFisheyeCorrection("front");
        resetFisheyeCorrection("back");
        resetFisheyeCorrection("left");
        resetFisheyeCorrection("right");
        AppLog.d(TAG, "所有鱼眼矫正参数已重置");
    }

    // ==================== 全屏预览窗口位置和大小配置 ====================

    private static final String KEY_FULLSCREEN_WINDOW_X = "fullscreen_window_x";
    private static final String KEY_FULLSCREEN_WINDOW_Y = "fullscreen_window_y";
    private static final String KEY_FULLSCREEN_WINDOW_WIDTH = "fullscreen_window_width";
    private static final String KEY_FULLSCREEN_WINDOW_HEIGHT = "fullscreen_window_height";

    public void setFullscreenWindowX(String cameraPos, int x) {
        prefs.edit().putInt(KEY_FULLSCREEN_WINDOW_X + "_" + cameraPos, x).apply();
    }

    public int getFullscreenWindowX(String cameraPos) {
        return prefs.getInt(KEY_FULLSCREEN_WINDOW_X + "_" + cameraPos, -1);
    }

    public void setFullscreenWindowY(String cameraPos, int y) {
        prefs.edit().putInt(KEY_FULLSCREEN_WINDOW_Y + "_" + cameraPos, y).apply();
    }

    public int getFullscreenWindowY(String cameraPos) {
        return prefs.getInt(KEY_FULLSCREEN_WINDOW_Y + "_" + cameraPos, -1);
    }

    public void setFullscreenWindowWidth(String cameraPos, int width) {
        prefs.edit().putInt(KEY_FULLSCREEN_WINDOW_WIDTH + "_" + cameraPos, width).apply();
    }

    public int getFullscreenWindowWidth(String cameraPos) {
        return prefs.getInt(KEY_FULLSCREEN_WINDOW_WIDTH + "_" + cameraPos, -1);
    }

    public void setFullscreenWindowHeight(String cameraPos, int height) {
        prefs.edit().putInt(KEY_FULLSCREEN_WINDOW_HEIGHT + "_" + cameraPos, height).apply();
    }

    public int getFullscreenWindowHeight(String cameraPos) {
        return prefs.getInt(KEY_FULLSCREEN_WINDOW_HEIGHT + "_" + cameraPos, -1);
    }

    public boolean hasFullscreenWindowParams(String cameraPos) {
        return getFullscreenWindowX(cameraPos) != -1 &&
               getFullscreenWindowY(cameraPos) != -1 &&
               getFullscreenWindowWidth(cameraPos) != -1 &&
               getFullscreenWindowHeight(cameraPos) != -1;
    }

    public void clearFullscreenWindowParams(String cameraPos) {
        prefs.edit()
                .remove(KEY_FULLSCREEN_WINDOW_X + "_" + cameraPos)
                .remove(KEY_FULLSCREEN_WINDOW_Y + "_" + cameraPos)
                .remove(KEY_FULLSCREEN_WINDOW_WIDTH + "_" + cameraPos)
                .remove(KEY_FULLSCREEN_WINDOW_HEIGHT + "_" + cameraPos)
                .apply();
    }

    // ==================== 主屏悬浮窗配置相关方法 ====================

    /**
     * 设置主屏悬浮窗开关
     */
    public void setMainFloatingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MAIN_FLOATING_ENABLED, enabled).apply();
        AppLog.d(TAG, "主屏悬浮窗设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isMainFloatingEnabled() {
        return prefs.getBoolean(KEY_MAIN_FLOATING_ENABLED, false);
    }

    /**
     * 设置主屏悬浮窗显示的摄像头位置
     */
    public void setMainFloatingCamera(String position) {
        prefs.edit().putString(KEY_MAIN_FLOATING_CAMERA, position).apply();
    }

    public String getMainFloatingCamera() {
        return prefs.getString(KEY_MAIN_FLOATING_CAMERA, "front");
    }

    /**
     * 设置主屏悬浮窗位置和大小
     */
    public void setMainFloatingBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_MAIN_FLOATING_X, x)
            .putInt(KEY_MAIN_FLOATING_Y, y)
            .putInt(KEY_MAIN_FLOATING_WIDTH, width)
            .putInt(KEY_MAIN_FLOATING_HEIGHT, height)
            .apply();
    }

    public int getMainFloatingX() {
        return prefs.getInt(KEY_MAIN_FLOATING_X, 100);
    }

    public int getMainFloatingY() {
        return prefs.getInt(KEY_MAIN_FLOATING_Y, 100);
    }

    public int getMainFloatingWidth() {
        return prefs.getInt(KEY_MAIN_FLOATING_WIDTH, 480);
    }

    public int getMainFloatingHeight() {
        return prefs.getInt(KEY_MAIN_FLOATING_HEIGHT, 320);
    }

    /**
     * 重置主屏悬浮窗位置和大小为默认值
     */
    public void resetMainFloatingBounds() {
        prefs.edit()
            .putInt(KEY_MAIN_FLOATING_X, 100)
            .putInt(KEY_MAIN_FLOATING_Y, 100)
            .putInt(KEY_MAIN_FLOATING_WIDTH, 480)
            .putInt(KEY_MAIN_FLOATING_HEIGHT, 320)
            .apply();
    }

    // ==================== 转向灯联动配置相关方法 ====================

    /**
     * 设置转向灯联动开关
     */
    public void setTurnSignalLinkageEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TURN_SIGNAL_LINKAGE_ENABLED, enabled).apply();
        AppLog.d(TAG, "转向灯联动设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isTurnSignalLinkageEnabled() {
        return prefs.getBoolean(KEY_TURN_SIGNAL_LINKAGE_ENABLED, false);
    }

    /**
     * 设置转向灯熄灭后的延迟消失时间（秒）
     */
    public void setTurnSignalTimeout(int seconds) {
        prefs.edit().putInt(KEY_TURN_SIGNAL_TIMEOUT, seconds).apply();
    }

   public int getTurnSignalTimeout() {
        return prefs.getInt(KEY_TURN_SIGNAL_TIMEOUT, 10);
    }

    /**
     * 设置是否复用主屏悬浮窗
     */
    public void setTurnSignalReuseMainFloating(boolean reuse) {
        prefs.edit().putBoolean(KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING, reuse).apply();
    }

    public boolean isTurnSignalReuseMainFloating() {
        return prefs.getBoolean(KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING, true);
    }

    public void setTurnSignalCustomLeftTriggerLog(String keyword) {
        prefs.edit().putString(KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG, keyword).apply();
    }

    public String getTurnSignalCustomLeftTriggerLog() {
        return prefs.getString(
                KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG,
                "left front turn signal:1"
        );
    }

    public void setTurnSignalCustomRightTriggerLog(String keyword) {
        prefs.edit().putString(KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG, keyword).apply();
    }

    public String getTurnSignalCustomRightTriggerLog() {
        return prefs.getString(
                KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG,
                "right front turn signal:1"
        );
    }

    public String getTurnSignalLeftTriggerLog() {
        return getTurnSignalCustomLeftTriggerLog();
    }

    public String getTurnSignalRightTriggerLog() {
        return getTurnSignalCustomRightTriggerLog();
    }

    /**
     * 设置转向灯触发模式
     * @param mode TRIGGER_MODE_LOGCAT 或 TRIGGER_MODE_CAR_API
     */
    public void setTurnSignalTriggerMode(String mode) {
        prefs.edit().putString(KEY_TURN_SIGNAL_TRIGGER_MODE, mode).apply();
        AppLog.d(TAG, "转向灯触发模式: " + mode);
    }

    /**
     * 获取转向灯触发模式
     */
    public String getTurnSignalTriggerMode() {
        return prefs.getString(KEY_TURN_SIGNAL_TRIGGER_MODE, TRIGGER_MODE_VHAL_GRPC);
    }

    /**
     * 是否使用 CarAPI 触发模式（兼容性方法）
     */
    public boolean isCarApiTriggerMode() {
        String mode = getTurnSignalTriggerMode();
        return TRIGGER_MODE_VHAL_GRPC.equals(mode) || TRIGGER_MODE_CAR_API.equals(mode);
    }

    /**
     * 是否使用车辆API 触发模式
     */
    public boolean isVhalGrpcTriggerMode() {
        return TRIGGER_MODE_VHAL_GRPC.equals(getTurnSignalTriggerMode());
    }

    /**
     * 是否使用 CarSignalManager API 触发模式
     */
    public boolean isCarSignalManagerTriggerMode() {
        return TRIGGER_MODE_CAR_SIGNAL_MANAGER.equals(getTurnSignalTriggerMode());
    }

    /**
     * 保存用户选择的转向灯预设选项（用于恢复具体的 RadioButton 选择）
     * @param presetName 预设名称，如 "l6l7" 或 "boyue_l"
     */
    public void setTurnSignalPresetSelection(String presetName) {
        prefs.edit().putString(KEY_TURN_SIGNAL_PRESET_SELECTION, presetName).apply();
        AppLog.d(TAG, "保存转向灯预设选择: " + presetName);
    }

    /**
     * 获取用户选择的转向灯预设选项
     * @return 预设名称，如 "l6l7" 或 "boyue_l"
     */
    public String getTurnSignalPresetSelection() {
        return prefs.getString(KEY_TURN_SIGNAL_PRESET_SELECTION, "l6l7"); // 默认返回 l6l7
    }

    // ==================== 车门联动配置 ====================
    
    /**
     * 设置车门联动开关（左右补盲）
     */
    public void setDoorLinkageEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOOR_LINKAGE_ENABLED, enabled).apply();
        AppLog.d(TAG, "车门联动设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isDoorLinkageEnabled() {
        return prefs.getBoolean(KEY_DOOR_LINKAGE_ENABLED, false);
    }

    /**
     * 设置车门关闭后延迟消失时间
     */
    public void setDoorTimeout(int seconds) {
        prefs.edit().putInt(KEY_DOOR_TIMEOUT, seconds).apply();
    }

    public int getDoorTimeout() {
        return prefs.getInt(KEY_DOOR_TIMEOUT, 10); // 默认10秒
    }

    /**
     * 保存用户选择的车门联动预设选项（用于恢复具体的 RadioButton 选择）
     * @param presetName 预设名称，如 "l6l7" 或 "boyue_l"
     */
    public void setDoorPresetSelection(String presetName) {
        prefs.edit().putString(KEY_DOOR_PRESET_SELECTION, presetName).apply();
        AppLog.d(TAG, "保存车门联动预设选择: " + presetName);
    }

    /**
     * 获取用户选择的车门联动预设选项
     * @return 预设名称，如 "l6l7" 或 "boyue_l"
     */
    public String getDoorPresetSelection() {
        return prefs.getString(KEY_DOOR_PRESET_SELECTION, "l6l7"); // 默认返回 l6l7
    }

    /**
     * 设置车门联动是否复用主屏悬浮窗
     */
    public void setDoorReuseMainFloating(boolean reuse) {
        prefs.edit().putBoolean(KEY_DOOR_REUSE_MAIN_FLOATING, reuse).apply();
    }

    public boolean isDoorReuseMainFloating() {
        return prefs.getBoolean(KEY_DOOR_REUSE_MAIN_FLOATING, true); // 默认复用
    }

    /**
     * 设置车门联动副屏显示开关
     */
    public void setDoorSecondaryDisplayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DOOR_SECONDARY_DISPLAY_ENABLED, enabled).apply();
    }

    public boolean isDoorSecondaryDisplayEnabled() {
        return prefs.getBoolean(KEY_DOOR_SECONDARY_DISPLAY_ENABLED, false); // 默认关闭
    }

    /**
     * 设置独立补盲悬浮窗位置和大小
     */
    public void setTurnSignalFloatingBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_TURN_SIGNAL_FLOATING_X, x)
            .putInt(KEY_TURN_SIGNAL_FLOATING_Y, y)
            .putInt(KEY_TURN_SIGNAL_FLOATING_WIDTH, width)
            .putInt(KEY_TURN_SIGNAL_FLOATING_HEIGHT, height)
            .apply();
    }

    public int getTurnSignalFloatingX() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_X, 200);
    }

    public int getTurnSignalFloatingY() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_Y, 200);
    }

    public int getTurnSignalFloatingWidth() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_WIDTH, 640);
    }

    public int getTurnSignalFloatingHeight() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_HEIGHT, 360);
    }

    /**
     * 设置独立补盲悬浮窗旋转
     */
    public void setTurnSignalFloatingRotation(int rotation) {
        prefs.edit().putInt(KEY_TURN_SIGNAL_FLOATING_ROTATION, rotation).apply();
    }

    public int getTurnSignalFloatingRotation() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_ROTATION, 0);
    }

    // ==================== 桌面悬浮模拟按钮配置相关方法 ====================

    public void setMockTurnSignalFloatingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED, enabled).apply();
    }

    public boolean isMockTurnSignalFloatingEnabled() {
        return prefs.getBoolean(KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED, false);
    }

    public void setMockTurnSignalFloatingPosition(int x, int y) {
        prefs.edit()
                .putInt(KEY_MOCK_TURN_SIGNAL_FLOATING_X, x)
                .putInt(KEY_MOCK_TURN_SIGNAL_FLOATING_Y, y)
                .apply();
    }

    public int getMockTurnSignalFloatingX() {
        return prefs.getInt(KEY_MOCK_TURN_SIGNAL_FLOATING_X, 200);
    }

    public int getMockTurnSignalFloatingY() {
        return prefs.getInt(KEY_MOCK_TURN_SIGNAL_FLOATING_Y, 200);
    }

    // ==================== 悬浮窗动效配置 ====================

    public void setFloatingWindowAnimationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLOATING_WINDOW_ANIMATION_ENABLED, enabled).apply();
    }

    public boolean isFloatingWindowAnimationEnabled() {
        return prefs.getBoolean(KEY_FLOATING_WINDOW_ANIMATION_ENABLED, false);
    }

    public void setBlindSpotStatusBarStyle(int style) {
        prefs.edit().putInt(KEY_BLIND_SPOT_STATUS_BAR_STYLE, style).apply();
    }

    /**
     * @return 0=关闭, 1=序贯灯段, 2=流光彗尾, 3=波纹扩散, 4=呼吸渐变填充, 5=箭头涟漪
     */
    public int getBlindSpotStatusBarStyle() {
        return prefs.getInt(KEY_BLIND_SPOT_STATUS_BAR_STYLE, 1);
    }

    public void setBlindSpotStatusBarColor(int color) {
        prefs.edit().putInt(KEY_BLIND_SPOT_STATUS_BAR_COLOR, color).apply();
    }

    /**
     * @return ARGB color for status bar effect. Default: amber 0xFFFFBF40
     */
    public int getBlindSpotStatusBarColor() {
        return prefs.getInt(KEY_BLIND_SPOT_STATUS_BAR_COLOR, 0xFFFFBF40);
    }

    /**
     * @param opacity 0-100, 0=完全透明, 100=完全不透明
     */
    public void setBlindSpotStatusBarBgOpacity(int opacity) {
        prefs.edit().putInt(KEY_BLIND_SPOT_STATUS_BAR_BG_OPACITY, opacity).apply();
    }

    /**
     * @return 0-100, default 31 (约 31% 不透明)
     */
    public int getBlindSpotStatusBarBgOpacity() {
        return prefs.getInt(KEY_BLIND_SPOT_STATUS_BAR_BG_OPACITY, 31);
    }

    // ==================== 时间角标配置相关方法 ====================
    
    /**
     * 设置时间角标开关
     * @param enabled true 表示在保存的视频和图片上添加时间角标
     */
    public void setTimestampWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "时间角标设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 角标是否附带录制规格（分辨率 / 帧率 / 编码 / 码率）。
     *
     * <p>默认开启：录像上带着自己的录制参数，事后回看时不必再去猜当时是什么设置。
     * 需要角标尽量小的场合可以关掉。</p>
     */
    public boolean isWatermarkSpecEnabled() {
        return prefs.getBoolean(KEY_WATERMARK_SPEC_ENABLED, true);
    }

    public void setWatermarkSpecEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WATERMARK_SPEC_ENABLED, enabled).apply();
        AppLog.d(TAG, "角标规格行: " + (enabled ? "显示" : "隐藏"));
    }

    /**
     * 获取时间角标开关状态
     * @return true 表示启用时间角标
     */
    /**
     * 要落进画面的车牌号；关掉开关或者没填时返回空串。
     *
     * <p>存进来的值一律先过 {@link LicensePlate#sanitize} —— 界面上显示的、
     * 存下来的、录进画面的必须是同一个串。</p>
     */
    public String getLicensePlate() {
        if (!prefs.getBoolean(KEY_LICENSE_PLATE_ENABLED, false)) {
            return "";
        }
        return LicensePlate.sanitize(prefs.getString(KEY_LICENSE_PLATE, ""));
    }

    /** 设置界面要看的原始值（不受开关影响）。 */
    public String getLicensePlateRaw() {
        return LicensePlate.sanitize(prefs.getString(KEY_LICENSE_PLATE, ""));
    }

    public void setLicensePlate(String value) {
        String clean = LicensePlate.sanitize(value);
        prefs.edit().putString(KEY_LICENSE_PLATE, clean).apply();
        AppLog.i(TAG, "车牌号设为 " + (clean.isEmpty() ? "（空）" : clean));
    }

    /**
     * 强制所有相机用 H.264 编码。
     *
     * <h3>它和配置里的「编码」是什么关系</h3>
     *
     * <p>配置里每一路可以各自选 auto / h264 —— 这个开关<b>盖过</b>它们：
     * 打开之后所有相机一律 H.264。它是一个排查用的总闸，
     * 用在「怀疑 H.265 有问题、想一次性排除掉」的时候。</p>
     *
     * <p>关着时（默认）各路按自己配置里的编码走。</p>
     */
    public boolean isForceH264Encoding() {
        return prefs.getBoolean(KEY_FORCE_H264_ENCODING, false);
    }

    public void setForceH264Encoding(boolean enabled) {
        prefs.edit().putBoolean(KEY_FORCE_H264_ENCODING, enabled).apply();
        AppLog.d(TAG, "强制 H.264 编码: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 拍照走相机自己的 JPEG 输出通道，而不是抓预览画面。
     *
     * <h3>为什么默认开着</h3>
     *
     * <p>关着时拍照是从预览画面上抓一张，尺寸只能是预览的尺寸 —— 配置里那个
     * 「拍照分辨率」完全不参与，设成 1600×900 也照样出预览那一份。
     * 一个设了不生效的选项比没有更糟。</p>
     *
     * <h3>它的风险，以及退路</h3>
     *
     * <p>它要在每一路的相机会话里常驻一条 JPEG 输出流。撑爆平台的流数量上限时，
     * 表现是<b>会话配置失败 = 没有画面</b> —— 比「照片糊一点」严重得多。所以
     * {@code SingleCamera} 在会话配不上时<b>第一个丢掉的就是它</b>：丢掉之后
     * 画面照旧，拍照退回抓预览。画面优先于照片清晰度。</p>
     *
     * <p>关掉它仍然可以（开发者选项），代价是拍照分辨率随之失效。</p>
     */
    public boolean isPhotoViaJpegEnabled() {
        return prefs.getBoolean(KEY_PHOTO_VIA_JPEG, true);
    }

    public void setPhotoViaJpegEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PHOTO_VIA_JPEG, enabled).apply();
        AppLog.i(TAG, "拍照通道: " + (enabled ? "相机 JPEG 输出" : "抓预览画面"));
    }

    public boolean isLicensePlateEnabled() {
        return prefs.getBoolean(KEY_LICENSE_PLATE_ENABLED, false);
    }

    public void setLicensePlateEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LICENSE_PLATE_ENABLED, enabled).apply();
    }

    public boolean isTimestampWatermarkEnabled() {
        // 默认关闭时间角标
        return prefs.getBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, false);
    }

    // ==================== 视频编码器配置相关方法 ====================


    // ==================== 录制摄像头选择配置相关方法 ====================
    
    
    
    
    
    /**
     * 获取某个摄像头画面是否显示（与录制开关分离）
     * @param position 位置（front/back/left/right）
     * @return true 表示显示画面，默认为 true
     */
    public boolean isCameraVisible(String position) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_VISIBLE;
                break;
            case "back":
                key = KEY_CAMERA_BACK_VISIBLE;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_VISIBLE;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_VISIBLE;
                break;
            default:
                return true;  // 未知位置默认显示
        }
        // 默认显示（开启）
        return prefs.getBoolean(key, true);
    }
    
    /**
     * 设置某个摄像头画面是否显示（与录制开关分离）
     * @param position 位置（front/back/left/right）
     * @param visible true 表示显示画面
     */
    public void setCameraVisible(String position, boolean visible) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_VISIBLE;
                break;
            case "back":
                key = KEY_CAMERA_BACK_VISIBLE;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_VISIBLE;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_VISIBLE;
                break;
            default:
                return;  // 未知位置不处理
        }
        prefs.edit().putBoolean(key, visible).apply();
    }
    
    
    // ==================== 亮度/降噪调节配置相关方法 ====================
    
    /**
     * 设置是否启用亮度/降噪调节
     * @param enabled true 表示启用
     */
    public void setImageAdjustEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_IMAGE_ADJUST_ENABLED, enabled).apply();
        AppLog.d(TAG, "亮度/降噪调节设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取是否启用亮度/降噪调节
     * @return true 表示启用
     */
    public boolean isImageAdjustEnabled() {
        return prefs.getBoolean(KEY_IMAGE_ADJUST_ENABLED, false);
    }
    
    /**
     * 设置曝光补偿值
     * @param value 曝光补偿值（范围取决于设备，通常 -12 到 +12）
     */
    public void setExposureCompensation(int value) {
        prefs.edit().putInt(KEY_EXPOSURE_COMPENSATION, value).apply();
        AppLog.d(TAG, "曝光补偿设置: " + value);
    }
    
    /**
     * 获取曝光补偿值
     * @return 曝光补偿值，默认为 0
     */
    public int getExposureCompensation() {
        return prefs.getInt(KEY_EXPOSURE_COMPENSATION, 0);
    }
    
    /**
     * 设置白平衡模式
     * @param mode 白平衡模式（AWB_MODE_* 常量）
     */
    public void setAwbMode(int mode) {
        prefs.edit().putInt(KEY_AWB_MODE, mode).apply();
        AppLog.d(TAG, "白平衡模式设置: " + mode);
    }
    
    /**
     * 获取白平衡模式
     * @return 白平衡模式，默认为 AWB_MODE_DEFAULT（不设置）
     */
    public int getAwbMode() {
        return prefs.getInt(KEY_AWB_MODE, AWB_MODE_DEFAULT);
    }
    
    /**
     * 设置色调映射模式
     * @param mode 色调映射模式（TONEMAP_MODE_* 常量）
     */
    public void setTonemapMode(int mode) {
        prefs.edit().putInt(KEY_TONEMAP_MODE, mode).apply();
        AppLog.d(TAG, "色调映射模式设置: " + mode);
    }
    
    /**
     * 获取色调映射模式
     * @return 色调映射模式，默认为 TONEMAP_MODE_DEFAULT（不设置）
     */
    public int getTonemapMode() {
        return prefs.getInt(KEY_TONEMAP_MODE, TONEMAP_MODE_DEFAULT);
    }
    
    /**
     * 设置边缘增强模式
     * @param mode 边缘增强模式（EDGE_MODE_* 常量）
     */
    public void setEdgeMode(int mode) {
        prefs.edit().putInt(KEY_EDGE_MODE, mode).apply();
        AppLog.d(TAG, "边缘增强模式设置: " + mode);
    }
    
    /**
     * 获取边缘增强模式
     * @return 边缘增强模式，默认为 EDGE_MODE_DEFAULT（不设置）
     */
    public int getEdgeMode() {
        return prefs.getInt(KEY_EDGE_MODE, EDGE_MODE_DEFAULT);
    }
    
    /**
     * 设置降噪模式
     * @param mode 降噪模式（NOISE_REDUCTION_* 常量）
     */
    public void setNoiseReductionMode(int mode) {
        prefs.edit().putInt(KEY_NOISE_REDUCTION_MODE, mode).apply();
        AppLog.d(TAG, "降噪模式设置: " + mode);
    }
    
    /**
     * 获取降噪模式
     * @return 降噪模式，默认为 NOISE_REDUCTION_DEFAULT（不设置）
     */
    public int getNoiseReductionMode() {
        return prefs.getInt(KEY_NOISE_REDUCTION_MODE, NOISE_REDUCTION_DEFAULT);
    }
    
    /**
     * 设置特效模式
     * @param mode 特效模式（EFFECT_MODE_* 常量）
     */
    public void setEffectMode(int mode) {
        prefs.edit().putInt(KEY_EFFECT_MODE, mode).apply();
        AppLog.d(TAG, "特效模式设置: " + mode);
    }
    
    /**
     * 获取特效模式
     * @return 特效模式，默认为 EFFECT_MODE_DEFAULT（不设置）
     */
    public int getEffectMode() {
        return prefs.getInt(KEY_EFFECT_MODE, EFFECT_MODE_DEFAULT);
    }
    
    /**
     * 设置场景模式
     * @param mode 场景模式
     */
    public void setSceneMode(int mode) {
        prefs.edit().putInt(KEY_SCENE_MODE, mode).apply();
        AppLog.d(TAG, "场景模式设置: " + mode);
    }
    
    /**
     * 获取场景模式
     * @return 场景模式，默认为 -1（不设置）
     */
    public int getSceneMode() {
        return prefs.getInt(KEY_SCENE_MODE, -1);
    }
    
    /**
     * 重置所有亮度/降噪调节参数为默认值
     */
    public void resetImageAdjustParams() {
        prefs.edit()
            .putInt(KEY_EXPOSURE_COMPENSATION, 0)
            .putInt(KEY_AWB_MODE, AWB_MODE_DEFAULT)
            .putInt(KEY_TONEMAP_MODE, TONEMAP_MODE_DEFAULT)
            .putInt(KEY_EDGE_MODE, EDGE_MODE_DEFAULT)
            .putInt(KEY_NOISE_REDUCTION_MODE, NOISE_REDUCTION_DEFAULT)
            .putInt(KEY_EFFECT_MODE, EFFECT_MODE_DEFAULT)
            .putInt(KEY_SCENE_MODE, -1)
            .apply();
        AppLog.d(TAG, "亮度/降噪调节参数已重置为默认值");
    }
    
    /**
     * 获取白平衡模式的显示名称
     */
    public static String getAwbModeDisplayName(int mode) {
        switch (mode) {
            case AWB_MODE_DEFAULT: return "默认";
            case AWB_MODE_AUTO: return "自动";
            case AWB_MODE_INCANDESCENT: return "白炽灯";
            case AWB_MODE_FLUORESCENT: return "荧光灯";
            case AWB_MODE_WARM_FLUORESCENT: return "暖荧光灯";
            case AWB_MODE_DAYLIGHT: return "日光";
            case AWB_MODE_CLOUDY_DAYLIGHT: return "阴天";
            case AWB_MODE_TWILIGHT: return "黄昏";
            case AWB_MODE_SHADE: return "阴影";
            default: return "未知";
        }
    }
    
    /**
     * 获取色调映射模式的显示名称
     */
    public static String getTonemapModeDisplayName(int mode) {
        switch (mode) {
            case TONEMAP_MODE_DEFAULT: return "默认";
            case TONEMAP_MODE_CONTRAST_CURVE: return "对比度曲线";
            case TONEMAP_MODE_FAST: return "快速";
            case TONEMAP_MODE_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取边缘增强模式的显示名称
     */
    public static String getEdgeModeDisplayName(int mode) {
        switch (mode) {
            case EDGE_MODE_DEFAULT: return "默认";
            case EDGE_MODE_OFF: return "关闭";
            case EDGE_MODE_FAST: return "快速";
            case EDGE_MODE_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取降噪模式的显示名称
     */
    public static String getNoiseReductionModeDisplayName(int mode) {
        switch (mode) {
            case NOISE_REDUCTION_DEFAULT: return "默认";
            case NOISE_REDUCTION_OFF: return "关闭";
            case NOISE_REDUCTION_FAST: return "快速";
            case NOISE_REDUCTION_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取特效模式的显示名称
     */
    public static String getEffectModeDisplayName(int mode) {
        switch (mode) {
            case EFFECT_MODE_DEFAULT: return "默认";
            case EFFECT_MODE_OFF: return "关闭";
            case EFFECT_MODE_MONO: return "黑白";
            case EFFECT_MODE_NEGATIVE: return "负片";
            case EFFECT_MODE_SOLARIZE: return "曝光过度";
            case EFFECT_MODE_SEPIA: return "怀旧";
            case EFFECT_MODE_AQUA: return "水蓝";
            default: return "未知";
        }
    }
    
    // ==================== 自定义车型自由操控配置相关方法 ====================
    
    /**
     * 设置自由操控开关
     * @param enabled true 表示启用自由操控
     */
    public void setCustomFreeControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CUSTOM_FREE_CONTROL_ENABLED, enabled).apply();
        AppLog.d(TAG, "自由操控设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取自由操控开关状态
     * @return true 表示启用自由操控
     */
    public boolean isCustomFreeControlEnabled() {
        return prefs.getBoolean(KEY_CUSTOM_FREE_CONTROL_ENABLED, false);
    }
    
    /**
     * 设置按钮样式
     * @param style 按钮样式（BUTTON_STYLE_STANDARD / BUTTON_STYLE_MULTI）
     */
    public void setCustomButtonStyle(String style) {
        prefs.edit().putString(KEY_CUSTOM_BUTTON_STYLE, style).apply();
        AppLog.d(TAG, "按钮样式设置: " + style);
    }
    
    /**
     * 获取按钮样式
     * @return 按钮样式，默认为标准按钮
     */
    public String getCustomButtonStyle() {
        return prefs.getString(KEY_CUSTOM_BUTTON_STYLE, BUTTON_STYLE_STANDARD);
    }
    
    /**
     * 设置按钮布局方向
     * @param orientation 方向（BUTTON_ORIENTATION_HORIZONTAL / BUTTON_ORIENTATION_VERTICAL）
     */
    public void setCustomButtonOrientation(String orientation) {
        prefs.edit().putString(KEY_CUSTOM_BUTTON_ORIENTATION, orientation).apply();
        AppLog.d(TAG, "按钮布局方向设置: " + orientation);
    }
    
    /**
     * 获取按钮布局方向
     * @return 布局方向，默认为横版
     */
    public String getCustomButtonOrientation() {
        return prefs.getString(KEY_CUSTOM_BUTTON_ORIENTATION, BUTTON_ORIENTATION_HORIZONTAL);
    }
    
    /**
     * 保存自定义布局数据（JSON格式）
     * @param layoutDataJson 布局数据JSON字符串
     */
    public void setCustomLayoutData(String layoutDataJson) {
        prefs.edit().putString(KEY_CUSTOM_LAYOUT_DATA, layoutDataJson).apply();
        AppLog.d(TAG, "自定义布局数据已保存");
    }
    
    /**
     * 获取自定义布局数据
     * @return 布局数据JSON字符串，如果未设置返回null
     */
    public String getCustomLayoutData() {
        return prefs.getString(KEY_CUSTOM_LAYOUT_DATA, null);
    }
    
    /**
     * 清除自定义布局数据
     */
    public void clearCustomLayoutData() {
        prefs.edit().remove(KEY_CUSTOM_LAYOUT_DATA).apply();
        AppLog.d(TAG, "自定义布局数据已清除");
    }

    public int getCustomLayoutVersion() {
        return prefs.getInt(KEY_CUSTOM_LAYOUT_VERSION, 0);
    }

    public void setCustomLayoutVersion(int version) {
        prefs.edit().putInt(KEY_CUSTOM_LAYOUT_VERSION, version).apply();
    }
    
    /**
     * 根据旋转角度计算实际显示比例
     * @param width 原始宽度
     * @param height 原始高度
     * @param rotation 旋转角度 (0/90/180/270)
     * @return [displayWidth, displayHeight]
     */
    public static int[] calculateDisplayRatio(int width, int height, int rotation) {
        if (rotation == 90 || rotation == 270) {
            // 旋转90°或270°时，宽高互换
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }
    
    /**
     * 获取按钮样式的显示名称
     */
    public static String getButtonStyleDisplayName(String style) {
        if (BUTTON_STYLE_MULTI.equals(style)) {
            return "多按钮";
        }
        return "标准";
    }
    
    /**
     * 获取按钮方向的显示名称
     */
    public static String getButtonOrientationDisplayName(String orientation) {
        if (BUTTON_ORIENTATION_VERTICAL.equals(orientation)) {
            return "竖版";
        }
        return "横版";
    }
    
    // ==================== 全景影像避让配置相关方法 ====================

    /**
     * 设置全景影像避让开关
     */
    public void setAvmAvoidanceEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AVM_AVOIDANCE_ENABLED, enabled).apply();
        AppLog.d(TAG, "全景影像避让设置: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取全景影像避让开关状态
     */
    public boolean isAvmAvoidanceEnabled() {
        return prefs.getBoolean(KEY_AVM_AVOIDANCE_ENABLED, false);
    }

    /**
     * 设置全景影像避让的Activity名称
     */
    public void setAvmAvoidanceActivity(String activityName) {
        prefs.edit().putString(KEY_AVM_AVOIDANCE_ACTIVITY, activityName).apply();
        AppLog.d(TAG, "全景影像避让Activity: " + activityName);
    }

    /**
     * 获取全景影像避让的Activity名称
     */
    public String getAvmAvoidanceActivity() {
        return prefs.getString(KEY_AVM_AVOIDANCE_ACTIVITY, "com.geely.avm_app.AvmRenderActivity");
    }

    // ==================== 定制键唤醒配置相关方法 ====================

    /**
     * 设置定制键唤醒开关
     */
    public void setCustomKeyWakeupEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CUSTOM_KEY_WAKEUP_ENABLED, enabled).apply();
        AppLog.d(TAG, "定制键唤醒设置: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取定制键唤醒开关状态
     */
    public boolean isCustomKeyWakeupEnabled() {
        return prefs.getBoolean(KEY_CUSTOM_KEY_WAKEUP_ENABLED, false);
    }

    /**
     * 设置速度阈值（秒速 m/s）
     */
    public void setCustomKeySpeedThreshold(float threshold) {
        prefs.edit().putFloat(KEY_CUSTOM_KEY_SPEED_THRESHOLD, threshold).apply();
        AppLog.d(TAG, "定制键速度阈值: " + threshold + " m/s");
    }

    /**
     * 获取速度阈值（秒速 m/s），默认8.34
     */
    public float getCustomKeySpeedThreshold() {
        return prefs.getFloat(KEY_CUSTOM_KEY_SPEED_THRESHOLD, 8.34f);
    }

    /**
     * 设置速度属性ID
     */
    public void setCustomKeySpeedPropId(int propId) {
        prefs.edit().putInt(KEY_CUSTOM_KEY_SPEED_PROP_ID, propId).apply();
    }

    /**
     * 获取速度属性ID，默认291504647
     */
    public int getCustomKeySpeedPropId() {
        return prefs.getInt(KEY_CUSTOM_KEY_SPEED_PROP_ID, 291504647);
    }

    /**
     * 设置按钮属性ID
     */
    public void setCustomKeyButtonPropId(int propId) {
        prefs.edit().putInt(KEY_CUSTOM_KEY_BUTTON_PROP_ID, propId).apply();
    }

    /**
     * 获取按钮属性ID，默认557872183
     */
    public int getCustomKeyButtonPropId() {
        return prefs.getInt(KEY_CUSTOM_KEY_BUTTON_PROP_ID, 557872183);
    }

    // ==================== 前轮/后轮模式视图配置相关方法 ====================

    /**
     * 设置前轮模式左视图参数
     */
    public void setFrontWheelLeftViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_FRONT_WHEEL_LEFT_WIDTH, width)
                .putInt(KEY_FRONT_WHEEL_LEFT_HEIGHT, height)
                .putInt(KEY_FRONT_WHEEL_LEFT_X, x)
                .putInt(KEY_FRONT_WHEEL_LEFT_Y, y)
                .putInt(KEY_FRONT_WHEEL_LEFT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "前轮模式左视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取前轮模式左视图宽度
     */
    public int getFrontWheelLeftWidth(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_LEFT_WIDTH, defaultValue);
    }

    /**
     * 获取前轮模式左视图高度
     */
    public int getFrontWheelLeftHeight(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_LEFT_HEIGHT, defaultValue);
    }

    /**
     * 获取前轮模式左视图X位置
     */
    public int getFrontWheelLeftX(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_LEFT_X, defaultValue);
    }

    /**
     * 获取前轮模式左视图Y位置
     */
    public int getFrontWheelLeftY(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_LEFT_Y, defaultValue);
    }

    /**
     * 获取前轮模式左视图旋转角度
     */
    public int getFrontWheelLeftRotation(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_LEFT_ROTATION, defaultValue);
    }

    /**
     * 设置前轮模式右视图参数
     */
    public void setFrontWheelRightViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_FRONT_WHEEL_RIGHT_WIDTH, width)
                .putInt(KEY_FRONT_WHEEL_RIGHT_HEIGHT, height)
                .putInt(KEY_FRONT_WHEEL_RIGHT_X, x)
                .putInt(KEY_FRONT_WHEEL_RIGHT_Y, y)
                .putInt(KEY_FRONT_WHEEL_RIGHT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "前轮模式右视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取前轮模式右视图宽度
     */
    public int getFrontWheelRightWidth(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_RIGHT_WIDTH, defaultValue);
    }

    /**
     * 获取前轮模式右视图高度
     */
    public int getFrontWheelRightHeight(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_RIGHT_HEIGHT, defaultValue);
    }

    /**
     * 获取前轮模式右视图X位置
     */
    public int getFrontWheelRightX(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_RIGHT_X, defaultValue);
    }

    /**
     * 获取前轮模式右视图Y位置
     */
    public int getFrontWheelRightY(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_RIGHT_Y, defaultValue);
    }

    /**
     * 获取前轮模式右视图旋转角度
     */
    public int getFrontWheelRightRotation(int defaultValue) {
        return prefs.getInt(KEY_FRONT_WHEEL_RIGHT_ROTATION, defaultValue);
    }

    /**
     * 设置后轮模式左视图参数
     */
    public void setRearWheelLeftViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_REAR_WHEEL_LEFT_WIDTH, width)
                .putInt(KEY_REAR_WHEEL_LEFT_HEIGHT, height)
                .putInt(KEY_REAR_WHEEL_LEFT_X, x)
                .putInt(KEY_REAR_WHEEL_LEFT_Y, y)
                .putInt(KEY_REAR_WHEEL_LEFT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "后轮模式左视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取后轮模式左视图宽度
     */
    public int getRearWheelLeftWidth(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_LEFT_WIDTH, defaultValue);
    }

    /**
     * 获取后轮模式左视图高度
     */
    public int getRearWheelLeftHeight(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_LEFT_HEIGHT, defaultValue);
    }

    /**
     * 获取后轮模式左视图X位置
     */
    public int getRearWheelLeftX(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_LEFT_X, defaultValue);
    }

    /**
     * 获取后轮模式左视图Y位置
     */
    public int getRearWheelLeftY(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_LEFT_Y, defaultValue);
    }

    /**
     * 获取后轮模式左视图旋转角度
     */
    public int getRearWheelLeftRotation(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_LEFT_ROTATION, defaultValue);
    }

    /**
     * 设置后轮模式右视图参数
     */
    public void setRearWheelRightViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_REAR_WHEEL_RIGHT_WIDTH, width)
                .putInt(KEY_REAR_WHEEL_RIGHT_HEIGHT, height)
                .putInt(KEY_REAR_WHEEL_RIGHT_X, x)
                .putInt(KEY_REAR_WHEEL_RIGHT_Y, y)
                .putInt(KEY_REAR_WHEEL_RIGHT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "后轮模式右视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取后轮模式右视图宽度
     */
    public int getRearWheelRightWidth(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_RIGHT_WIDTH, defaultValue);
    }

    /**
     * 获取后轮模式右视图高度
     */
    public int getRearWheelRightHeight(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_RIGHT_HEIGHT, defaultValue);
    }

    /**
     * 获取后轮模式右视图X位置
     */
    public int getRearWheelRightX(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_RIGHT_X, defaultValue);
    }

    /**
     * 获取后轮模式右视图Y位置
     */
    public int getRearWheelRightY(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_RIGHT_Y, defaultValue);
    }

    /**
     * 获取后轮模式右视图旋转角度
     */
    public int getRearWheelRightRotation(int defaultValue) {
        return prefs.getInt(KEY_REAR_WHEEL_RIGHT_ROTATION, defaultValue);
    }

    /**
     * 设置普通模式左视图参数
     */
    public void setNormalLeftViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_NORMAL_LEFT_WIDTH, width)
                .putInt(KEY_NORMAL_LEFT_HEIGHT, height)
                .putInt(KEY_NORMAL_LEFT_X, x)
                .putInt(KEY_NORMAL_LEFT_Y, y)
                .putInt(KEY_NORMAL_LEFT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "普通模式左视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取普通模式左视图宽度
     */
    public int getNormalLeftWidth(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_LEFT_WIDTH, defaultValue);
    }

    /**
     * 获取普通模式左视图高度
     */
    public int getNormalLeftHeight(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_LEFT_HEIGHT, defaultValue);
    }

    /**
     * 获取普通模式左视图X位置
     */
    public int getNormalLeftX(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_LEFT_X, defaultValue);
    }

    /**
     * 获取普通模式左视图Y位置
     */
    public int getNormalLeftY(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_LEFT_Y, defaultValue);
    }

    /**
     * 获取普通模式左视图旋转角度
     */
    public int getNormalLeftRotation(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_LEFT_ROTATION, defaultValue);
    }

    /**
     * 设置普通模式右视图参数
     */
    public void setNormalRightViewParams(int width, int height, int x, int y, int rotation) {
        prefs.edit()
                .putInt(KEY_NORMAL_RIGHT_WIDTH, width)
                .putInt(KEY_NORMAL_RIGHT_HEIGHT, height)
                .putInt(KEY_NORMAL_RIGHT_X, x)
                .putInt(KEY_NORMAL_RIGHT_Y, y)
                .putInt(KEY_NORMAL_RIGHT_ROTATION, rotation)
                .apply();
        AppLog.d(TAG, "普通模式右视图参数已保存: " + width + "x" + height + " @(" + x + "," + y + ") 旋转" + rotation + "°");
    }

    /**
     * 获取普通模式右视图宽度
     */
    public int getNormalRightWidth(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_RIGHT_WIDTH, defaultValue);
    }

    /**
     * 获取普通模式右视图高度
     */
    public int getNormalRightHeight(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_RIGHT_HEIGHT, defaultValue);
    }

    /**
     * 获取普通模式右视图X位置
     */
    public int getNormalRightX(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_RIGHT_X, defaultValue);
    }

    /**
     * 获取普通模式右视图Y位置
     */
    public int getNormalRightY(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_RIGHT_Y, defaultValue);
    }

    /**
     * 获取普通模式右视图旋转角度
     */
    public int getNormalRightRotation(int defaultValue) {
        return prefs.getInt(KEY_NORMAL_RIGHT_ROTATION, defaultValue);
    }

    /**
     * 重置前轮模式视图参数为默认值
     */
    public void resetFrontWheelViewParams() {
        setFrontWheelLeftViewParams(1120, 662, 10, 397, 270);
        setFrontWheelRightViewParams(1211, 662, -76, 502, 90);
        AppLog.d(TAG, "前轮模式视图参数已重置为默认值");
    }

    /**
     * 重置后轮模式视图参数为默认值
     */
    public void resetRearWheelViewParams() {
        setRearWheelLeftViewParams(1120, 662, 10, -624, 270);
        setRearWheelRightViewParams(1298, 662, -164, -702, 90);
        AppLog.d(TAG, "后轮模式视图参数已重置为默认值");
    }

    /**
     * 重置普通模式视图参数为默认值
     * 普通模式下保持车辆控制区域可见，左右视图宽度相等
     */
    public void resetNormalViewParams(int defaultLeftWidth, int defaultLeftHeight, int defaultRightWidth, int defaultRightHeight, int containerHeight) {
        int halfHeight = (containerHeight - 20) / 2;
        int padding = 10;
        int vehicleControlWidth = 280;
        
        // 计算左右视图宽度，使其相等且右视图右边缘与后视图对齐
        int totalViewWidth = defaultLeftWidth + defaultRightWidth + vehicleControlWidth + padding * 4;
        // 从传入的宽度反推容器宽度，这里简化处理
        int leftViewWidth = defaultLeftWidth;
        int rightViewWidth = defaultRightWidth;

        setNormalLeftViewParams(leftViewWidth, halfHeight, padding, padding * 2 + halfHeight, 0);
        setNormalRightViewParams(rightViewWidth, halfHeight, padding * 2 + leftViewWidth + vehicleControlWidth, padding * 2 + halfHeight, 0);
        AppLog.d(TAG, "普通模式视图参数已重置为默认值");
    }

    /**
     * 获取补盲配置实例
     */
    public BlindSpotConfig getBlindSpotConfig() {
        return new BlindSpotConfig(
                context.getSharedPreferences("blind_spot_config", Context.MODE_PRIVATE)
        );
    }

    /**
     * 检查是否使用新的录制架构（D:\yuan方案）
     */
    public boolean useNewRecordingArchitecture() {
        // 默认启用新的录制架构
        return prefs.getBoolean("use_new_recording_architecture", true);
    }

    /**
     * 设置是否使用新的录制架构
     */
    public void setUseNewRecordingArchitecture(boolean enabled) {
        prefs.edit().putBoolean("use_new_recording_architecture", enabled).apply();
        AppLog.d(TAG, "新录制架构设置: " + (enabled ? "启用" : "禁用"));
    }

    // ==================== 录制悬浮按钮配置 ====================

    private static final String KEY_RECORDING_FLOATING_ENABLED = "recording_floating_enabled";
    private static final String KEY_RECORDING_FLOATING_BUTTON_SIZE = "recording_floating_button_size";
    private static final String KEY_RECORDING_FLOATING_TIME_TEXT_SIZE = "recording_floating_time_text_size";
    // 录制悬浮按钮的位置。上游没有存过位置，所以每次启动都回到默认点。
    private static final String KEY_RECORDING_FLOATING_X = "recording_floating_x";
    private static final String KEY_RECORDING_FLOATING_Y = "recording_floating_y";
    private static final boolean DEFAULT_RECORDING_FLOATING_ENABLED = true;  // 默认开启
    private static final int DEFAULT_BUTTON_SIZE_DP = 65;  // 实车调好后测得
    private static final int DEFAULT_TIME_TEXT_SIZE_SP = 14;

    /**
     * 是否启用录制悬浮按钮
     */
    public boolean isRecordingFloatingEnabled() {
        return prefs.getBoolean(KEY_RECORDING_FLOATING_ENABLED, DEFAULT_RECORDING_FLOATING_ENABLED);
    }

    /**
     * 设置录制悬浮按钮启用状态
     */
    public void setRecordingFloatingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECORDING_FLOATING_ENABLED, enabled).apply();
        AppLog.d(TAG, "录制悬浮按钮设置: " + (enabled ? "启用" : "禁用"));
    }

    /**
     * 获取录制悬浮按钮大小（dp）
     */
    public int getRecordingFloatingButtonSizeDp() {
        return prefs.getInt(KEY_RECORDING_FLOATING_BUTTON_SIZE, DEFAULT_BUTTON_SIZE_DP);
    }

    /**
     * 设置录制悬浮按钮大小（dp）
     */
    public void setRecordingFloatingButtonSizeDp(int sizeDp) {
        prefs.edit().putInt(KEY_RECORDING_FLOATING_BUTTON_SIZE, sizeDp).apply();
        AppLog.d(TAG, "录制悬浮按钮大小设置: " + sizeDp + "dp");
    }

    /**
     * 获取录制悬浮按钮时间文字大小（sp）
     */
    public int getRecordingFloatingTimeTextSizeSp() {
        return prefs.getInt(KEY_RECORDING_FLOATING_TIME_TEXT_SIZE, DEFAULT_TIME_TEXT_SIZE_SP);
    }

    /**
     * 设置录制悬浮按钮时间文字大小（sp）
     */
    /**
     * 记住录制悬浮按钮被拖到哪里。
     *
     * <p>上游只在内存里改 layoutParams，从不落盘，所以每次重启都回到屏幕左侧中间。</p>
     */
    public void setRecordingFloatingPosition(int x, int y) {
        prefs.edit()
                .putInt(KEY_RECORDING_FLOATING_X, x)
                .putInt(KEY_RECORDING_FLOATING_Y, y)
                .apply();
    }

    /** 返回 -1 表示还没存过，调用方应使用默认位置。 */
    public int getRecordingFloatingX() {
        return prefs.getInt(KEY_RECORDING_FLOATING_X, -1);
    }

    public int getRecordingFloatingY() {
        return prefs.getInt(KEY_RECORDING_FLOATING_Y, -1);
    }

    /**
     * 把录制悬浮按钮恢复出厂：位置、按钮大小、时间字号。
     */
    public void resetRecordingFloatingLayout() {
        prefs.edit()
                .remove(KEY_RECORDING_FLOATING_X)
                .remove(KEY_RECORDING_FLOATING_Y)
                .remove(KEY_RECORDING_FLOATING_BUTTON_SIZE)
                .remove(KEY_RECORDING_FLOATING_TIME_TEXT_SIZE)
                .apply();
        AppLog.i(TAG, "录制悬浮按钮布局已恢复默认");
    }

    /**
     * 把主屏悬浮窗恢复出厂：位置、大小、透明度。
     */
    public void resetFloatingWindowLayout() {
        prefs.edit()
                .remove(KEY_FLOATING_WINDOW_X)
                .remove(KEY_FLOATING_WINDOW_Y)
                .remove(KEY_FLOATING_WINDOW_SIZE)
                .remove(KEY_FLOATING_WINDOW_ALPHA)
                .apply();
        AppLog.i(TAG, "悬浮窗布局已恢复默认");
    }

    public void setRecordingFloatingTimeTextSizeSp(int sizeSp) {
        prefs.edit().putInt(KEY_RECORDING_FLOATING_TIME_TEXT_SIZE, sizeSp).apply();
        AppLog.d(TAG, "录制悬浮按钮时间文字大小设置: " + sizeSp + "sp");
    }

}
