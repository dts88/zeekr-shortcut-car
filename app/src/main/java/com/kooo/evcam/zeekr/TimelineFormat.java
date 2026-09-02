package com.kooo.evcam.zeekr;

import java.util.Locale;

/**
 * 连续回放界面用到的两个格式化函数。
 *
 * <p>单独放一个类是为了能测：它们原本写在 {@code TimelineSessionAdapter} 里，
 * 而那个类继承 {@code RecyclerView.Adapter}，纯 JVM 单元测试要碰它就得把一整套
 * UI 类拖进来。这里不依赖任何 Android API。</p>
 */
public final class TimelineFormat {

    private TimelineFormat() {
    }

    /** 时长；不足一小时就不显示小时位，列表里越短越好读。 */
    public static String duration(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /**
     * 文件大小。
     *
     * <p>读不到大小时显示一个破折号而不是 0 B —— 后者看起来像个真实的测量结果。
     * 用符号而不是文字，是因为这个类是纯静态工具，没有 Context 可以取资源，
     * 而破折号在中英文界面下都读得通。</p>
     */
    public static String size(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
