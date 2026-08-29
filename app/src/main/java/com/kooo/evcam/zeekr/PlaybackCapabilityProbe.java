package com.kooo.evcam.zeekr;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 回放与解码能力探测。
 *
 * <p>连续回放里反复拖动会出现抽搐、卡死甚至乱码。播放器那边的状态机问题已经修了
 * （见 {@code TimelinePlayerActivity#seekTimelineTo}），但还有一个更底层的可能性
 * 需要用实测数据排除：<b>这台车机的硬件解码器根本吃不下我们录出来的尺寸</b>。</p>
 *
 * <p>录制用的合成流是 1280×5140。绝大多数 AVC 硬件解码器单边上限是 4096，
 * 5140 超了。真是这样的话，播放会退回软解（慢、抽搐）或者直接解错（乱码）——
 * 那就不是播放器代码能修的，得改录制尺寸或分格保存。</p>
 *
 * <p>所以这里报告两件事：录出来的文件<b>实际</b>是什么规格，
 * 以及这台车机的解码器<b>声明</b>能吃多大。两者一对照，结论就明确了。</p>
 */
public final class PlaybackCapabilityProbe {

    /** 最多检查几个最新的录像文件。 */
    private static final int MAX_FILES = 3;

    private PlaybackCapabilityProbe() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("## 8. 回放与解码能力").append('\n');
        sb.append("用途：确认车机的解码器能不能吃下我们录出来的尺寸。").append('\n');
        sb.append("连续回放拖动后抽搐/卡死/乱码，除了播放器状态机，").append('\n');
        sb.append("另一个可能就是尺寸超出硬件解码上限。").append('\n');
        sb.append('\n');

        List<int[]> recordedSizes = new ArrayList<>();
        appendRecordedFiles(sb, context, recordedSizes);
        appendDecoderLimits(sb, recordedSizes);

        sb.append('\n');
    }

    // ------------------------------------------------------------------

    private static void appendRecordedFiles(StringBuilder sb, Context context,
                                            List<int[]> sizesOut) {
        sb.append("### 8.1 最近的录像文件实际规格").append('\n');
        try {
            File dir = StorageHelper.getVideoDir(context);
            File[] files = dir != null ? dir.listFiles() : null;
            if (files == null || files.length == 0) {
                sb.append("没有找到录像文件").append('\n').append('\n');
                return;
            }

            List<File> videos = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                    videos.add(f);
                }
            }
            if (videos.isEmpty()) {
                sb.append("没有找到 mp4 文件").append('\n').append('\n');
                return;
            }
            // 最新的几个即可，读每个文件都要开 extractor，不必全扫
            videos.sort(new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });

            int shown = 0;
            for (File video : videos) {
                if (shown++ >= MAX_FILES) {
                    break;
                }
                sb.append("  ").append(video.getName())
                        .append("  ").append(StorageHelper.formatSize(video.length())).append('\n');
                describeTrack(sb, video, sizesOut);
            }
        } catch (Throwable t) {
            sb.append("!! 读取失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    /** 用 MediaExtractor 读视频轨的真实格式。 */
    private static void describeTrack(StringBuilder sb, File video, List<int[]> sizesOut) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                int width = getInt(format, MediaFormat.KEY_WIDTH, -1);
                int height = getInt(format, MediaFormat.KEY_HEIGHT, -1);
                sb.append("     编码: ").append(mime).append('\n');
                sb.append("     尺寸: ").append(width).append(" x ").append(height).append('\n');
                int fps = getInt(format, MediaFormat.KEY_FRAME_RATE, -1);
                if (fps > 0) {
                    sb.append("     帧率(容器声明): ").append(fps).append('\n');
                }
                long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : -1L;
                if (durationUs > 0) {
                    sb.append("     时长: ").append(durationUs / 1_000_000L).append(" 秒").append('\n');
                }
                if (width > 0 && height > 0) {
                    sizesOut.add(new int[]{width, height});
                }
            }
        } catch (Throwable t) {
            sb.append("     !! 解析失败: ").append(t).append('\n');
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
                // 释放失败不影响报告
            }
        }
    }

    private static int getInt(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------

    private static void appendDecoderLimits(StringBuilder sb, List<int[]> recordedSizes) {
        sb.append("### 8.2 本机解码器声明的尺寸上限").append('\n');
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            boolean anyReported = false;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!type.startsWith("video/")) {
                        continue;
                    }
                    if (!type.equals("video/avc") && !type.equals("video/hevc")) {
                        continue;  // 我们只录这两种
                    }
                    MediaCodecInfo.VideoCapabilities caps;
                    try {
                        caps = info.getCapabilitiesForType(type).getVideoCapabilities();
                    } catch (Throwable t) {
                        continue;
                    }
                    if (caps == null) {
                        continue;
                    }
                    anyReported = true;
                    sb.append("  ").append(info.getName())
                            .append("  (").append(type).append(")").append('\n');
                    sb.append("     宽: ").append(caps.getSupportedWidths())
                            .append("   高: ").append(caps.getSupportedHeights()).append('\n');

                    // 直接问它支不支持我们录出来的尺寸 —— 这是最有用的一行
                    for (int[] size : recordedSizes) {
                        boolean ok = false;
                        String reason = "";
                        try {
                            ok = caps.isSizeSupported(size[0], size[1]);
                        } catch (Throwable t) {
                            reason = "（判断失败: " + t + "）";
                        }
                        sb.append("     ").append(size[0]).append("x").append(size[1])
                                .append(": ").append(ok ? "支持" : ">> 不支持 <<")
                                .append(reason).append('\n');
                    }
                }
            }
            if (!anyReported) {
                sb.append("没有枚举到 AVC/HEVC 解码器").append('\n');
            }

            sb.append('\n');
            sb.append(">> 若上面出现「不支持」，说明录制尺寸超出了硬件解码上限：").append('\n');
            sb.append("   播放会退回软解（卡顿抽搐）或直接解错（乱码），").append('\n');
            sb.append("   这不是播放器代码能修的，得改录制尺寸或分格保存。").append('\n');
            sb.append(">> 若全部「支持」，那回放问题就在播放器一侧，").append('\n');
            sb.append("   0.7.4 已重写拖动时的 prepare/seek 状态机。").append('\n');
        } catch (Throwable t) {
            sb.append("!! 枚举解码器失败: ").append(t).append('\n');
        }
    }
}
