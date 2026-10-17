package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件传输管理器
 * 负责将临时目录中的视频文件异步传输到目标存储（如U盘）
 * 
 * 工作原理：
 * 1. 录制时先写入内部存储的临时目录（高速）
 * 2. 分段完成后，将文件加入传输队列
 * 3. 后台线程负责将文件移动/复制到目标目录
 * 4. 传输完成后删除临时文件
 * 
 * 这样可以避免U盘慢速写入影响录制性能
 */
public class FileTransferManager {
    private static final String TAG = "FileTransferManager";
    
    // 中转目录名称（应用私有文件目录，旧缓存仅用于恢复）
    public static final String TEMP_VIDEO_DIR = "temp_video";
    
    // 传输任务
    private static class TransferTask {
        final File sourceFile;      // 源文件（临时目录中）
        final File targetFile;      // 目标文件（最终存储位置）
        final TransferCallback callback;
        int retryCount;             // 重试次数
        
        TransferTask(File source, File target, TransferCallback callback) {
            this.sourceFile = source;
            this.targetFile = target;
            this.callback = callback;
            this.retryCount = 0;
        }
    }
    
    // 传输回调
    public interface TransferCallback {
        void onTransferComplete(File sourceFile, File targetFile);
        void onTransferFailed(File sourceFile, File targetFile, String error);
    }
    
    // 单例
    private static FileTransferManager instance;
    
    private final Context context;
    private final ConcurrentLinkedQueue<TransferTask> transferQueue;
    private HandlerThread transferThread;
    private Handler transferHandler;
    private final AtomicBoolean isRunning;
    private final AtomicBoolean isProcessing;
    
    // 配置
    private static final int MAX_RETRY_COUNT = 5;           // 最大重试次数（增加到5次）
    private static final long RETRY_DELAY_MS = 3000;        // 重试延迟（毫秒）- 减少到3秒加快重试
    private static final long TRANSFER_CHECK_INTERVAL_MS = 500;  // 检查队列间隔（缩短到500ms）
    private static final long MIN_VALID_FILE_SIZE = 1;  // Preserve any nonempty file; size is not an MP4 validity test.
    
    // 统计
    private long totalTransferred = 0;      // 已传输文件数
    private long totalFailed = 0;           // 失败文件数
    private long totalBytesTransferred = 0; // 已传输字节数
    
    private FileTransferManager(Context context) {
        this.context = context.getApplicationContext();
        this.transferQueue = new ConcurrentLinkedQueue<>();
        this.isRunning = new AtomicBoolean(false);
        this.isProcessing = new AtomicBoolean(false);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized FileTransferManager getInstance(Context context) {
        if (instance == null) {
            instance = new FileTransferManager(context);
        }
        return instance;
    }
    
    /**
     * 启动传输服务
     */
    public void start() {
        if (isRunning.getAndSet(true)) {
            AppLog.d(TAG, "Transfer service already running");
            return;
        }
        
        transferThread = new HandlerThread("FileTransfer", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        transferThread.start();
        transferHandler = new Handler(transferThread.getLooper());
        
        // 启动定期检查队列
        scheduleNextCheck();
        
        // Untransferred footage is retained, never expired merely because USB is unavailable.
        
        AppLog.d(TAG, "File transfer service started");
    }
    
    /**
     * 停止传输服务
     */
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }
        
        if (transferHandler != null) {
            transferHandler.removeCallbacksAndMessages(null);
            transferHandler = null;
        }
        
        if (transferThread != null) {
            transferThread.quitSafely();
            try {
                transferThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            transferThread = null;
        }
        
        AppLog.d(TAG, "File transfer service stopped. Stats: transferred=" + totalTransferred + 
                ", failed=" + totalFailed + ", bytes=" + formatSize(totalBytesTransferred));
    }
    
    /**
     * 添加传输任务
     * @param sourceFile 源文件（临时目录中）
     * @param targetFile 目标文件（最终位置）
     * @param callback 回调（可为null）
     */
    public void addTransferTask(File sourceFile, File targetFile, TransferCallback callback) {
        if (sourceFile == null || targetFile == null || !sourceFile.exists()) {
            AppLog.w(TAG, "Source file does not exist: " + sourceFile);
            if (callback != null) {
                callback.onTransferFailed(sourceFile, targetFile, "Source file not found");
            }
            return;
        }
        
        TransferTask task = new TransferTask(sourceFile, targetFile, callback);
        transferQueue.offer(task);
        
        AppLog.d(TAG, "Added transfer task: " + sourceFile.getName() + " -> " + targetFile.getAbsolutePath());
        
        // 如果服务在运行，立即触发处理
        if (isRunning.get() && transferHandler != null) {
            transferHandler.post(this::processQueue);
        }
    }
    
    /**
     * 获取临时视频目录
     * @return 临时目录，如果创建失败返回null
     */
    public static File relayDirectory(Context context) {
        return new File(context.getFilesDir(), TEMP_VIDEO_DIR);
    }

    public static File legacyRelayDirectory(Context context) {
        return new File(context.getCacheDir(), TEMP_VIDEO_DIR);
    }

    public File getTempVideoDir() {
        File tempDir = relayDirectory(context);
        if (!tempDir.exists()) {
            if (!tempDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create temp video directory: " + tempDir.getAbsolutePath());
                return null;
            }
        }
        return tempDir;
    }
    
    /**
     * 获取临时目录占用的空间（字节）
     */
    public long getTempDirSize() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return 0;
        }
        
        long size = 0;
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }
    
    /**
     * 清理临时目录（删除所有文件）
     * 注意：仅在确认不需要这些文件时调用
     */
    public void clearTempDir() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return;
        }
        
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) {
                    AppLog.d(TAG, "Deleted temp file: " + file.getName());
                }
            }
        }
        
        AppLog.d(TAG, "Temp directory cleared");
    }
    
    /**
     * 获取队列中等待传输的任务数
     */
    public int getQueueSize() {
        return transferQueue.size();
    }
    
    // ===== 私有方法 =====
    
    /**
     * 调度下一次队列检查
     */
    private void scheduleNextCheck() {
        if (!isRunning.get() || transferHandler == null) {
            return;
        }
        
        transferHandler.postDelayed(() -> {
            if (isRunning.get()) {
                processQueue();
                scheduleNextCheck();
            }
        }, TRANSFER_CHECK_INTERVAL_MS);
    }
    
    /**
     * 处理传输队列
     */
    private void processQueue() {
        if (!isRunning.get() || isProcessing.getAndSet(true)) {
            return;
        }
        
        try {
            TransferTask task;
            while ((task = transferQueue.poll()) != null) {
                if (!isRunning.get()) {
                    // 服务停止，将任务放回队列
                    transferQueue.offer(task);
                    break;
                }
                
                processTask(task);
            }
        } finally {
            isProcessing.set(false);
        }
    }
    
    /**
     * 处理单个传输任务
     */
    private void processTask(TransferTask task) {
        if (!task.sourceFile.exists()) {
            AppLog.w(TAG, "Source file no longer exists: " + task.sourceFile.getName());
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, "Source file not found");
            }
            totalFailed++;
            return;
        }

        // 验证源文件大小
        long sourceSize = task.sourceFile.length();
        if (sourceSize < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Source file too small: " + task.sourceFile.getName() + " (" + sourceSize + " bytes)");
            // Preserve even tiny files: short finalized MP4 clips can be valid.
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, "Source file too small");
            }
            totalFailed++;
            return;
        }

        // 确保目标目录存在
        File targetDir = task.targetFile.getParentFile();
        if (targetDir != null && !targetDir.exists()) {
            AppLog.d(TAG, "Creating target directory: " + targetDir.getAbsolutePath());
            if (!targetDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create target directory: " + targetDir.getAbsolutePath());
                handleTransferFailure(task, "Cannot create target directory");
                return;
            }
        }

        // 检查目标目录是否可写
        if (targetDir != null && !targetDir.canWrite()) {
            AppLog.e(TAG, "Target directory is not writable: " + targetDir.getAbsolutePath());
            handleTransferFailure(task, "Target directory not writable");
            return;
        }

        try {
            com.kooo.evcam.storage.VerifiedFileCopy.copy(task.sourceFile, task.targetFile);
            long targetSize = task.targetFile.length();
            if (!task.sourceFile.delete()) AppLog.w(TAG, "Verified copy retained at both locations: " + task.sourceFile);
            totalTransferred++;
            totalBytesTransferred += targetSize;
            if (task.callback != null) task.callback.onTransferComplete(task.sourceFile, task.targetFile);
        } catch (IOException error) {
            // Existing destination and original source are both retained on failure/collision.
            handleTransferFailure(task, error.getMessage());
        }
    }

    /**
     * 处理传输失败
     */
    private void handleTransferFailure(TransferTask task, String error) {
        task.retryCount++;
        
        if (task.retryCount < MAX_RETRY_COUNT) {
            // 重试
            AppLog.w(TAG, "Transfer failed, will retry (" + task.retryCount + "/" + MAX_RETRY_COUNT + "): " + 
                    task.sourceFile.getName() + " - " + error);
            
            // 延迟后重新加入队列
            if (transferHandler != null) {
                transferHandler.postDelayed(() -> {
                    transferQueue.offer(task);
                }, RETRY_DELAY_MS);
            }
        } else {
            // 超过重试次数，放弃
            AppLog.e(TAG, "Transfer failed after " + MAX_RETRY_COUNT + " retries: " + 
                    task.sourceFile.getName() + " - " + error);
            
            totalFailed++;
            
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, error);
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
