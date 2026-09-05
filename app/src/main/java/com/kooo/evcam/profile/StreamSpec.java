package com.kooo.evcam.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条流的参数。
 *
 * <h3>存的是意图，不是算出来的数</h3>
 *
 * <p>分辨率存 {@link #RESOLUTION_MAX} 这个<b>模式</b>，不存
 * {@code 3840x2160} 这个<b>数</b>；帧率存 {@link #FPS_UNLIMITED}，不存 30。</p>
 *
 * <p>一旦把当时算出来的值快照进配置，换个固件、换台车，存的就是个错的数，
 * 而界面还照着它显示 —— 这和「写死 25 帧」是同一类错误，只是换了个地方发作。
 * 具体是多少，永远在用的时候现问相机。</p>
 *
 * <h3>三条流不是都有这些字段</h3>
 *
 * <ul>
 *   <li><b>预览</b>：只有分辨率。帧率由捕获请求决定，是整个会话共享的，
 *       预览这一侧无从单独设定。</li>
 *   <li><b>录制</b>：分辨率、帧率、码率、编码、分段时长。帧率在这里的含义是
 *       <b>上限</b>：往下丢帧做得到，往上补帧做不到。</li>
 *   <li><b>拍照</b>：分辨率、JPEG 质量。没有帧率，也没有码率。</li>
 * </ul>
 */
public final class StreamSpec {

    /** 用这一路声明的最大尺寸。拍照默认走这个。 */
    public static final String RESOLUTION_MAX = "max";

    /** 跟随探测 / 跟随全局默认，具体由使用方决定。 */
    public static final String RESOLUTION_AUTO = "auto";

    /** 不限制帧率，视频流给多少录多少。 */
    public static final String FPS_UNLIMITED = "unlimited";

    /** 跟随画质等级算出来的码率。 */
    public static final String BITRATE_AUTO = "auto";

    /** {@link #RESOLUTION_MAX} / {@link #RESOLUTION_AUTO} / {@code "1280x5140"}。 */
    public String resolution = RESOLUTION_AUTO;

    /** {@link #FPS_UNLIMITED} 或具体数字的字符串。只有录制流用得上。 */
    public String fps = FPS_UNLIMITED;

    /** {@link #BITRATE_AUTO} 或码率等级（low / medium / high）。只有录制流用得上。 */
    public String bitrate = BITRATE_AUTO;

    /** auto / h264 / hevc。只有录制流用得上。 */
    public String codec = "auto";

    /** 分段时长（分钟）。只有录制流用得上；0 表示不分段。 */
    public int segmentMinutes;

    /**
     * 拆出来的四格，落盘时拼成 2×2 还是保持原样的长条。只有会拆的那一路用得上。
     *
     * <p>默认拼成 2×2：长条那一版 1280×5140 超过编码器 4096 的上限，会被整体缩小，
     * 而且回放放大是按 2×2 取景的。留着长条这一档是因为它走的是另一条录制路径
     * （MediaRecorder 直接吃相机输出，不需要 GL），出问题时还有条退路。</p>
     */
    public boolean grid = true;

    /** JPEG 质量 1–100。只有拍照流用得上。 */
    public int jpegQuality = 95;

    public static StreamSpec preview(String resolution) {
        StreamSpec spec = new StreamSpec();
        spec.resolution = resolution;
        return spec;
    }

    public static StreamSpec record(String resolution, String fps, String bitrate,
                                    String codec, int segmentMinutes) {
        StreamSpec spec = new StreamSpec();
        spec.resolution = resolution;
        spec.fps = fps;
        spec.bitrate = bitrate;
        spec.codec = codec;
        spec.segmentMinutes = segmentMinutes;
        return spec;
    }

    public static StreamSpec photo(String resolution, int jpegQuality) {
        StreamSpec spec = new StreamSpec();
        spec.resolution = resolution;
        spec.jpegQuality = jpegQuality;
        return spec;
    }

    /** 摊平成键值对，键前面会被调用方再加上「哪一路的哪条流」。 */
    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("resolution", resolution);
        out.put("fps", fps);
        out.put("bitrate", bitrate);
        out.put("codec", codec);
        out.put("segmentMinutes", String.valueOf(segmentMinutes));
        out.put("grid", String.valueOf(grid));
        out.put("jpegQuality", String.valueOf(jpegQuality));
        return out;
    }

    public static StreamSpec fromMap(Map<String, String> values) {
        StreamSpec spec = new StreamSpec();
        if (values == null) {
            return spec;
        }
        spec.resolution = text(values, "resolution", RESOLUTION_AUTO);
        spec.fps = text(values, "fps", FPS_UNLIMITED);
        spec.bitrate = text(values, "bitrate", BITRATE_AUTO);
        spec.codec = text(values, "codec", "auto");
        spec.segmentMinutes = number(values, "segmentMinutes", 0);
        // 缺这个键时按 2×2 处理：这一项是后加的，早先存下的配置里没有它
        spec.grid = !"false".equals(values.get("grid"));
        spec.jpegQuality = number(values, "jpegQuality", 95);
        return spec;
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static int number(Map<String, String> values, String key, int fallback) {
        try {
            String value = values.get(key);
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return "分辨率=" + resolution + " 帧率=" + fps + " 码率=" + bitrate
                + " 编码=" + codec + " 分段=" + segmentMinutes + "分钟"
                + " 排列=" + (grid ? "四宫格" : "原始长条");
    }
}
