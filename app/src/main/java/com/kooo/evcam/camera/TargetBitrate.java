package com.kooo.evcam.camera;

/**
 * 编码器的目标码率怎么算出来的。
 *
 * <h3>为什么从录制器里抽出来</h3>
 *
 * <p>配置编辑要在「码率」那一项下面写出这个数。如果界面自己再算一遍，
 * 两段公式迟早会走散 —— 那就又是一次「界面写一个数、实际用另一个数」。
 * 抽成一个纯函数，两边调同一个。</p>
 *
 * <p>不碰 Android，可以单独测。</p>
 *
 * <h3>0.44.0 为什么把整张表抬上去了</h3>
 *
 * <p>上游那张表是给小得多的画面定的。环视四宫格在这台车机上编码尺寸是
 * 2560×2570 —— 658 万像素 —— 而原来「中」档算出来只有 5.4 Mbps，合 0.033 bpp，
 * 大约是普通行车记录仪 1080p 画质密度的七分之一。细节是记录下来了，
 * 然后被压缩掉了：看起来不像分辨率不够，像整幅画蒙了一层。</p>
 *
 * <p>更要紧的是原来 8 Mbps 那条天花板：「高」档算出来 7.7 Mbps 紧贴着它，
 * 于是高和中之间只差 2.3 Mbps，再往上一步都没有。</p>
 */
public final class TargetBitrate {

    /**
     * 车机硬件编码器扛得住的上限。
     *
     * <p>两种编码共用一条 —— 这是<b>硬件</b>的限制，不是编码格式的。超过这个数
     * 编码器开始掉帧，画质反而更差。</p>
     */
    public static final int MAX = 24_000_000;

    /** 下限：再低就不是「省空间」，是「看不清」。 */
    public static final int MIN_H264 = 1_500_000;
    public static final int MIN_HEVC = 1_000_000;

    private TargetBitrate() {
    }

    /**
     * 目标码率（bps）。
     *
     * @param qualityLevel 画质档 0–3，来自这一路配置里的码率等级
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
        bitrate = Math.min(bitrate, MAX);
        bitrate = Math.max(bitrate, hevc ? MIN_HEVC : MIN_H264);
        // 取整到 100Kbps，日志和界面都好读
        return (int) (((bitrate + 50_000) / 100_000) * 100_000);
    }

    /**
     * 四档的每像素比特数（H.264 基准，HEVC 在这上面再打 55% 折）。
     *
     * <h3>这几个数是倒推出来的</h3>
     *
     * <p>环视四宫格 2560×2570、25fps、HEVC，每一档要落在这里 ——</p>
     *
     * <pre>
     *   0 极低   2.7 Mbps   0.016 bpp   只求留下「发生了什么」
     *   1 低     5.4 Mbps   0.033 bpp   0.43 及以前的「中」就是这个数
     *   2 中    10.0 Mbps   0.061 bpp   默认档
     *   3 高    20.0 Mbps   0.122 bpp   普通行车记录仪 1080p 的画质密度
     * </pre>
     *
     * <p>每档翻一倍。再往上没有意义：画面只有 658 万像素，20 Mbps 已经过了这个
     * 尺寸下 HEVC 的收益拐点，而 U 盘的写入速度和编码器都还要留余量。</p>
     *
     * <p>座舱那两路不拆四宫格，画面小得多，同一档算出来的码率自然低 ——
     * 这是对的：档位是「画质」，不是「码率数字」。</p>
     */
    private static double baseBitsPerPixel(int qualityLevel) {
        switch (qualityLevel) {
            case 0:
                return 0.035;
            case 1:
                return 0.070;
            case 3:
                return 0.260;
            case 2:
            default:
                return 0.130;
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
