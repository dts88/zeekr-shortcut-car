package com.kooo.evcam.playback;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 视频分组模型
 * 将同一时间戳录制的多路视频组合在一起（前/后/左/右）
 * 文件命名格式：yyyyMMdd_HHmmss_{position}.mp4
 */
public class VideoGroup {
    
    /** 摄像头位置常量 */
    public static final String POSITION_FRONT = "front";
    public static final String POSITION_BACK = "back";
    public static final String POSITION_LEFT = "left";
    public static final String POSITION_RIGHT = "right";
    
    /** 时间戳前缀，如 "20260131_1254" */
    private final String timestampPrefix;
    
    /** 录制时间（解析自文件名） */
    private final Date recordTime;
    
    /** 各位置的视频文件 */
    private final Map<String, File> videoFiles;
    
    /** 总文件大小（所有位置之和） */
    private long totalSize;
    
    public VideoGroup(String timestampPrefix) {
        this.timestampPrefix = timestampPrefix;
        this.videoFiles = new HashMap<>();
        this.totalSize = 0;
        this.recordTime = parseTimestamp(timestampPrefix);
    }
    
    /**
     * 添加视频文件到分组
     * @param file 视频文件
     */
    public void addFile(File file) {
        String position = extractPosition(file.getName());
        if (position != null) {
            videoFiles.put(position, file);
            totalSize += file.length();
        }
    }
    
    /**
     * 从文件名提取时间戳前缀
     * @param fileName 文件名，如 "20260131_125430_front.mp4"
     * @return 时间戳前缀，如 "20260131_125430"
     */
    public static String extractTimestampPrefix(String fileName) {
        // 移除扩展名
        String nameWithoutExt = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameWithoutExt = fileName.substring(0, dotIndex);
        }
        
        // 找到最后一个下划线，它之前是时间戳
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');
        if (lastUnderscore > 0) {
            return nameWithoutExt.substring(0, lastUnderscore);
        }
        return nameWithoutExt;
    }
    
    /**
     * 从文件名提取摄像头位置
     * @param fileName 文件名，如 "20260131_125430_front.mp4"
     * @return 位置，如 "front"
     */
    public static String extractPosition(String fileName) {
        // 移除扩展名
        String nameWithoutExt = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameWithoutExt = fileName.substring(0, dotIndex);
        }
        
        // 找到最后一个下划线，它之后是位置
        int lastUnderscore = nameWithoutExt.lastIndexOf('_');
        if (lastUnderscore > 0 && lastUnderscore < nameWithoutExt.length() - 1) {
            return nameWithoutExt.substring(lastUnderscore + 1).toLowerCase();
        }
        return null;
    }
    
    /**
     * 解析时间戳为日期
     */
    private Date parseTimestamp(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            return sdf.parse(timestamp);
        } catch (ParseException e) {
            return new Date(0);
        }
    }
    
    // Getters
    
    public String getTimestampPrefix() {
        return timestampPrefix;
    }
    
    public Date getRecordTime() {
        return recordTime;
    }
    
    /**
     * 获取格式化的日期时间字符串
     */
    public String getFormattedDateTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取格式化的日期字符串
     */
    public String getFormattedDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取格式化的时间字符串
     */
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(recordTime);
    }
    
    /**
     * 获取指定位置的视频文件
     * @param position 位置（front/back/left/right）
     * @return 文件，可能为null
     */
    public File getVideoFile(String position) {
        return videoFiles.get(position);
    }
    
    /**
     * 获取前置摄像头视频
     */
    public File getFrontVideo() {
        return videoFiles.get(POSITION_FRONT);
    }
    
    /**
     * 获取后置摄像头视频
     */
    public File getBackVideo() {
        return videoFiles.get(POSITION_BACK);
    }
    
    /**
     * 获取左侧摄像头视频
     */
    public File getLeftVideo() {
        return videoFiles.get(POSITION_LEFT);
    }
    
    /**
     * 获取右侧摄像头视频
     */
    public File getRightVideo() {
        return videoFiles.get(POSITION_RIGHT);
    }
    
    /**
     * 获取所有视频文件
     */
    public Map<String, File> getAllVideoFiles() {
        return new HashMap<>(videoFiles);
    }
    
    /**
     * 获取第一个可用的缩略图文件（用于列表显示）
     * 优先级：front > back > left > right
     */
    public File getThumbnailFile() {
        if (videoFiles.containsKey(POSITION_FRONT)) {
            return videoFiles.get(POSITION_FRONT);
        } else if (videoFiles.containsKey(POSITION_BACK)) {
            return videoFiles.get(POSITION_BACK);
        } else if (videoFiles.containsKey(POSITION_LEFT)) {
            return videoFiles.get(POSITION_LEFT);
        } else if (videoFiles.containsKey(POSITION_RIGHT)) {
            return videoFiles.get(POSITION_RIGHT);
        }
        return null;
    }
    
    /**
     * 获取视频路数
     */
    public int getVideoCount() {
        return videoFiles.size();
    }
    
    /**
     * 获取总文件大小
     */
    public long getTotalSize() {
        return totalSize;
    }
    
    /**
     * 获取格式化的文件大小字符串
     */
    public String getFormattedSize() {
        if (totalSize < 1024) {
            return totalSize + " B";
        } else if (totalSize < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f KB", totalSize / 1024.0);
        } else if (totalSize < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f MB", totalSize / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * 检查是否有指定位置的视频
     */
    public boolean hasVideo(String position) {
        return videoFiles.containsKey(position);
    }
    
    /**
     * 删除所有视频文件
     * @return 成功删除的文件数
     */
    public int deleteAll() {
        int deleted = 0;
        for (File file : videoFiles.values()) {
            if (file.delete()) {
                deleted++;
            }
        }
        if (deleted > 0) {
            videoFiles.clear();
            totalSize = 0;
        }
        return deleted;
    }
}
