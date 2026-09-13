package com.kooo.evcam.camera;

/**
 * 这一档要花多少空间、能录多久。
 *
 * <h3>为什么这几个数必须出现在界面上</h3>
 *
 * <p>分辨率、帧率、码率三个数一起决定了两件用户真正关心的事：每小时吃掉多少 GB，
 * 和这张 U 盘能留住多久的行车记录。以前这两件事一个都没写出来 —— 于是「码率选高
 * 一点」和「录像只剩三小时」之间那条因果链，只能在车上用一个礼拜撞出来。</p>
 *
 * <p>GB 按 1000 进制算，和 U 盘上印的一致：一张标称 128GB 的盘，这里算出来的
 * 小时数和用户心里那个数对得上。用 1024 进制会凭空少报 7%，而这几行字的全部
 * 意义就是让人敢照着它做决定。</p>
 *
 * <p>纯函数，不碰 Android，可以单独测。</p>
 */
public final class StorageBudget {

    /** 一小时有多少秒。 */
    private static final long SECONDS_PER_HOUR = 3600L;

    private StorageBudget() {
    }

    /**
     * 这些码率加起来，一小时落盘多少字节。
     *
     * @param bitsPerSecond 每一路的目标码率（bps），来自 {@link TargetBitrate}
     */
    public static long bytesPerHour(int... bitsPerSecond) {
        long total = 0;
        if (bitsPerSecond != null) {
            for (int bits : bitsPerSecond) {
                if (bits > 0) {
                    total += bits;
                }
            }
        }
        return total / 8L * SECONDS_PER_HOUR;
    }

    /** 一小时多少 GB（1000 进制，和 U 盘上印的一致）。 */
    public static float gigabytesPerHour(long bytesPerHour) {
        return bytesPerHour / 1_000_000_000f;
    }

    /**
     * 这点空间能录多久（小时）。
     *
     * @return 录不了或者算不出来时返回 0
     */
    public static float hours(long freeBytes, long bytesPerHour) {
        if (freeBytes <= 0 || bytesPerHour <= 0) {
            return 0f;
        }
        return (float) freeBytes / bytesPerHour;
    }
}
