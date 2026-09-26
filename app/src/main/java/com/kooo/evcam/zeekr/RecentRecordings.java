package com.kooo.evcam.zeekr;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 诊断报告里「最近几个录像文件实际是什么规格」。
 *
 * <p>编码、尺寸、容器声明的帧率、时长，读的是文件本身，不是设置里填的值 ——
 * 两者不一样的时候，以这里为准。</p>
 *
 * <p>原来它是「回放与解码能力」的一半，另一半是解码器声明的尺寸上限。那一半的问题
 * 早有结论（平台笔记 §4.8：解码器吃得下，已排除），1.37.0 删了；这一半一直有用，留着。</p>
 */
public final class RecentRecordings {

    /** 最多看几个最新的录像文件。三路录制时正好是同一段的三个文件。 */
    private static final int MAX_FILES = 3;

    private RecentRecordings() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("## 8. 最近的录像文件实际规格").append('\n');
        try {
            File dir = StorageHelper.getVideoDir(context);
            File[] files = dir != null ? dir.listFiles() : null;
            List<File> videos = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".mp4")) {
                        videos.add(f);
                    }
                }
            }
            if (videos.isEmpty()) {
                sb.append("没有找到 mp4 文件").append('\n').append('\n');
                return;
            }
            // 最新的几个即可：读每个文件都要开一次 extractor，不必全扫
            videos.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 0; i < Math.min(MAX_FILES, videos.size()); i++) {
                File video = videos.get(i);
                sb.append("  ").append(video.getName())
                        .append("  ").append(StorageHelper.formatSize(video.length())).append('\n');
                describeTrack(sb, video);
            }
        } catch (Throwable t) {
            sb.append("!! 读取失败: ").append(t).append('\n');
        }
        sb.append('\n');
    }

    /** 用 MediaExtractor 读视频轨的真实格式。 */
    private static void describeTrack(StringBuilder sb, File video) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                sb.append("     编码: ").append(mime).append('\n');
                sb.append("     尺寸: ").append(getInt(format, MediaFormat.KEY_WIDTH, -1))
                        .append(" x ").append(getInt(format, MediaFormat.KEY_HEIGHT, -1)).append('\n');
                int fps = getInt(format, MediaFormat.KEY_FRAME_RATE, -1);
                if (fps > 0) {
                    sb.append("     帧率(容器声明): ").append(fps).append('\n');
                }
                long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : -1L;
                if (durationUs > 0) {
                    sb.append("     时长: ").append(durationUs / 1_000_000L).append(" 秒").append('\n');
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
}
