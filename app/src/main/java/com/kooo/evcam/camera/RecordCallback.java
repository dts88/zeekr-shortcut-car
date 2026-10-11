package com.kooo.evcam.camera;

import java.io.File;
import java.util.List;

/**
 * 录制回调接口
 */
public interface RecordCallback {
    /**
     * 录制开始
     */
    void onRecordStart(String cameraId);

    /**
     * 录制停止
     */
    void onRecordStop(String cameraId);

    /**
     * 录制错误
     */
    void onRecordError(String cameraId, String error);

    /**
     * 分段切换：上一段收好了（录制器自己的线程上报）。录像这边不为它动相机 —— MediaRecorder 换段时新的录像输出由
     * 宿主声明、调度去改会话（{@link VideoRecorder.SegmentOutputs}）。
     * @param cameraId 相机ID
     * @param newSegmentIndex 新的分段索引
     * @param completedFilePath 已完成的文件路径（可用于传输到最终目录）；空壳删了是 null
     */
    void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath);

    /**
     * 损坏文件被删除
     * @param cameraId 相机ID
     * @param deletedFiles 被删除的文件名列表
     */
    void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles);

    /**
     * 写不进文件：从开录（或最后一次写进文件）起 {@code stalledMs} 毫秒没有新数据写进文件，
     * 录制器自己的修复（换盘、重建编码器、快速恢复）都没救回来（MediaRecorder 没有这些修复：9 秒没再长、
     * 开录 10 秒还没写进第一笔、换段时新的一段开不起来，见 {@link VideoRecorder}）。
     * 这是「录像健不健康」唯一的裁判（项目所有者 2026-09-27）。收到后宿主先看是不是相机的事
     * （{@link RecordingLanes#stallIsCamera}）：是就只这一路退出；不是才按打断处理，接不接由 RecordingCoordinator 判。
     *
     * @param everWrote false：这次录制一个字节都没写出过（对应「没收到画面」）
     */
    default void onWriteStalled(String cameraId, long stalledMs, boolean everWrote) {
    }

    /**
     * 写盘跟不上：写入排队满了（约 3 秒的画面），编码线程开始在相机这一侧丢帧。录像照常在录，
     * 不算打断；每满一段报一次（那一段刚开始时）。提示不提示用户、多久提示一次由 RecordingCoordinator 定。
     */
    default void onWriteBacklog(String cameraId) {
    }

    /**
     * 录像换了盘：原来的盘写不进了，已经改写到别的盘接着录
     * @param cameraId 相机ID
     * @param dir 现在写进的目录
     * @param why 哪一步发现的（write / fsync / open / start）
     * @param rescuedMs 从内存里补写进新文件的时长（毫秒）
     */
    void onRecordingRelocated(String cameraId, File dir, String why, long rescuedMs);

    /**
     * 首次数据写入成功
     * 当检测到录制器首次成功写入数据时调用
     * 用于通知外部录制已真正开始，可以开始计时（分段计时、钉钉录制计时等）
     * 
     * @param cameraId 相机ID
     */
    void onFirstDataWritten(String cameraId);
}
