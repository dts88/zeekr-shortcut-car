package com.kooo.evcam.camera;

/**
 * 编码器的目标码率怎么算出来的。
 *
 * <h3>为什么从录制器里抽出来</h3>
 *
 * <p>设置界面要在「码率」那一项下面写出这个数。如果界面自己再算一遍，
 * 两段公式迟早会走散 —— 那就又是一次「界面写一个数、实际用另一个数」。
 * 抽成一个纯函数，两边调同一个。</p>
 *
 * <p>不碰 Android，可以单独测。</p>
 */
public final class TargetBitrate {

    /** 编码器扛得住的上限。超过这个数只会让它卡顿，画质并不会更好。 */
    public static final int MAX_H264 = 12_000_000;
    public static final int MAX_HEVC = 8_000_000;

    /** 下限：再低就不是「省空间」，是「看不清」。 */
    public static final int MIN_H264 = 1_500_000;
    public static final int MIN_HEVC = 1_000_000;

    private TargetBitrate() {
    }

    /**
     * 目标码率（bps）。
     *
     * @param qualityLevel 画质档 0–3，来自设置里的码率等级
     * @param frameRate    标称帧率，必须是正数
     * @param hevc         是否 H.265
     */
    public static int compute(int qualityLevel, int width, int height,
                              int frameRate, boolean hevc) {
        if (width <= 0 || height <= 0 || frameRate <= 0) {
            return hevc ? MIN_HEVC : MIN_H264;
        }
        double bpp = baseBitsPerPixel(qualityLevel);

        // 分辨率越高，每像素需要的比特越少（编码效率随之提升）
        long pixels = (long) width * height;
        if (pixels > 2_073_600L) {          // 1080p 以上
            bpp *= 0.85;
        } else if (pixels > 921_600L) {     // 720p 以上
            bpp *= 0.90;
        }

        // 同画质下 HEVC 只要 55% 的码率
        if (hevc) {
            bpp *= 0.55;
        }

        long bitrate = (long) ((double) width * height * frameRate * bpp);
        bitrate = Math.min(bitrate, hevc ? MAX_HEVC : MAX_H264);
        bitrate = Math.max(bitrate, hevc ? MIN_HEVC : MIN_H264);
        // 取整到 100Kbps，日志和界面都好读
        return (int) (((bitrate + 50_000) / 100_000) * 100_000);
    }

    private static double baseBitsPerPixel(int qualityLevel) {
        switch (qualityLevel) {
            case 0:
                return 0.03;
            case 1:
                return 0.05;
            case 3:
                return 0.10;
            case 2:
            default:
                return 0.07;
        }
    }

    /** {@code "8.2 Mbps"} 这样的显示文字。 */
    public static String format(int bitrate) {
        if (bitrate >= 1_000_000) {
            return String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f);
        }
        return (bitrate / 1000) + " Kbps";
    }
}
