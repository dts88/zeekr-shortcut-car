package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.settings.Languages;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 每小时一次的自动清理（冷启动 30 秒后先跑一次）。
 *
 * <p>只执行两条已有的规则，不另立规矩（docs/storage-spec.md §2）：</p>
 * <ul>
 *   <li>录像：设了上限就按 {@code StoragePlan} 管（{@code StorageGuard.enforce}，和录制中分段切换时同一套），
 *       没设上限一个不删；</li>
 *   <li>照片：超了上限，从最旧的删到上限的八成。</li>
 * </ul>
 *
 * <p>两条都只认本应用写出来的文件名，锁定的不删（但算占用）。没设上限就不删，不管存在哪个盘上。</p>
 */
public class StorageCleanupManager {
    private static final String TAG = "StorageCleanupManager";

    // 定时任务延迟
    private static final long INITIAL_DELAY_MS = 30 * 1000;  // 冷启动后30秒
    private static final long PERIODIC_INTERVAL_MS = 60 * 60 * 1000;  // 每1小时

    // 照片超了上限时删到上限的八成，免得刚删完又超
    private static final double EXTRA_DELETE_RATIO = 0.20;

    // GB 转 字节
    private static final long GB_TO_BYTES = 1024L * 1024L * 1024L;

    private final Context context;
    private final AppConfig appConfig;
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;
    private boolean isRunning = false;
    
    public StorageCleanupManager(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 启动存储清理任务
     * 冷启动30秒后执行首次检测，之后每隔1小时执行一次。
     * 两个上限都没设时也照样排上：上限随时会在设置里改，每一轮跑的时候才读
     */
    public void start() {
        if (isRunning) {
            AppLog.d(TAG, "存储清理任务已在运行");
            return;
        }
        
        isRunning = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 30秒后执行首次检测
        scheduler.schedule(this::performCleanup, INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        
        // 每1小时执行一次定期检测
        scheduler.scheduleAtFixedRate(
            this::performCleanup,
            INITIAL_DELAY_MS + PERIODIC_INTERVAL_MS,  // 首次定期检测在首次检测后1小时
            PERIODIC_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        AppLog.d(TAG, "存储清理任务已启动：30秒后首次检测，之后每1小时检测一次");
        AppLog.d(TAG, "视频限制: " + appConfig.getVideoStorageLimitGb() + " GB, 图片限制: " + appConfig.getPhotoStorageLimitGb() + " GB");
    }
    
    /**
     * 停止存储清理任务
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            scheduler = null;
        }
        isRunning = false;
        AppLog.d(TAG, "存储清理任务已停止");
    }
    
    /**
     * 执行清理任务
     */
    private void performCleanup() {
        AppLog.d(TAG, "开始执行存储清理检测...");
        
        int videoLimitGb = appConfig.getVideoStorageLimitGb();
        int photoLimitGb = appConfig.getPhotoStorageLimitGb();
        
        // 视频：和录制中分段切换时同一套规则（StoragePlan），只是这里在没在录都会跑一次。
        // 两套规则各删各的，是最容易「删多了」的写法
        if (videoLimitGb > 0) {
            com.kooo.evcam.camera.StorageGuard.enforce(context, StorageHelper.getVideoDir(context));
        }
        
        // 照片：超了上限从最旧的删到上限的八成
        if (photoLimitGb > 0) {
            CleanupResult photoResult = cleanupPhotos(
                StorageHelper.getPhotoDir(context),
                photoLimitGb * GB_TO_BYTES
            );
            if (photoResult.deletedCount > 0) {
                showCleanupNotification(photoResult);
            }
        }
        
        AppLog.d(TAG, "存储清理检测完成");
    }
    
    /**
     * 清理照片目录：超了上限，从最旧的删到上限的八成
     * @param directory 照片目录
     * @param limitBytes 限制大小（字节）
     * @return 清理结果
     */
    private CleanupResult cleanupPhotos(File directory, long limitBytes) {
        synchronized (com.kooo.evcam.storage.FootageLocks.guard()) {
            return cleanupPhotosLocked(directory, limitBytes);
        }
    }

    private CleanupResult cleanupPhotosLocked(File directory, long limitBytes) {
        CleanupResult result = new CleanupResult();
        
        if (directory == null) {
            // 没插 U 盘、开发者选项也关着：照片没有地方可存，自然也没有要清的（不是异常）
            return result;
        }
        if (!directory.exists() || !directory.isDirectory()) {
            AppLog.w(TAG, "照片目录不存在: " + directory.getAbsolutePath());
            return result;
        }
        
        // 获取目录中所有文件（不筛选格式）
        // 只认本应用写出来的文件：U 盘上可能有用户自己的东西，以前这里有什么删什么
        File[] files = directory.listFiles(file -> file.isFile()
                && (com.kooo.evcam.camera.StoragePlan.isOwnClip(file.getName())
                || com.kooo.evcam.camera.StoragePlan.isOwnPhoto(file.getName())));
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, "照片目录为空");
            return result;
        }
        
        // 计算当前总大小
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        
        result.originalSize = totalSize;
        
        AppLog.d(TAG, "照片当前占用: " + StorageHelper.formatSize(totalSize) + 
                " / 限制: " + StorageHelper.formatSize(limitBytes));
        
        // 如果未超过限制，无需清理
        if (totalSize <= limitBytes) {
            AppLog.d(TAG, "照片未超过限制，无需清理");
            return result;
        }
        
        // 计算目标大小（限制的80%，即额外删除20%）
        long targetSize = (long) (limitBytes * (1 - EXTRA_DELETE_RATIO));
        long needToDelete = totalSize - targetSize;
        
        AppLog.d(TAG, "照片超过限制，需要删除: " + StorageHelper.formatSize(needToDelete) + 
                "，目标大小: " + StorageHelper.formatSize(targetSize));
        
        // 按修改时间排序（最旧的在前）；锁定的不删（但算在占用里）
        List<File> sortedFiles = deletableOldestFirst(directory, files);
        if (sortedFiles == null) {
            return result;
        }
        
        // 删除最旧的文件直到达到目标大小
        long deletedSize = 0;
        int deletedCount = 0;
        List<String> gone = new ArrayList<>();
        
        for (File file : sortedFiles) {
            if (totalSize - deletedSize <= targetSize) {
                break;
            }
            
            long fileSize = file.length();
            String fileName = file.getName();
            
            if (file.delete()) {
                gone.add(fileName);
                deletedSize += fileSize;
                deletedCount++;
                AppLog.d(TAG, "已删除照片: " + fileName + " (" + StorageHelper.formatSize(fileSize) + ")");
            } else {
                AppLog.w(TAG, "删除照片失败: " + fileName);
            }
        }
        
        com.kooo.evcam.storage.FootageLocks.forget(directory, gone);
        result.deletedCount = deletedCount;
        result.deletedSize = deletedSize;
        result.finalSize = totalSize - deletedSize;
        
        AppLog.d(TAG, "照片清理完成：删除 " + deletedCount + " 个文件，释放 " + 
                StorageHelper.formatSize(deletedSize) + "，剩余 " + StorageHelper.formatSize(result.finalSize));
        
        return result;
    }
    
    /**
     * 能删的文件，最旧的在前：锁定的拿掉（{@link com.kooo.evcam.storage.FootageLocks}）。
     * 锁定清单读不出来返回 null —— 这一轮不删。调用方拿着清单的锁。
     */
    private List<File> deletableOldestFirst(File directory, File[] files) {
        java.util.Set<String> locked =
                com.kooo.evcam.storage.FootageLocks.protectedNames(context, directory);
        if (locked == null) {
            AppLog.w(TAG, "锁定清单读不出来，这一轮不清理: " + directory);
            return null;
        }
        List<File> sorted = new ArrayList<>();
        for (File file : files) {
            if (!locked.contains(file.getName())) {
                sorted.add(file);
            }
        }
        sorted.sort(Comparator.comparingLong(File::lastModified));
        return sorted;
    }

    /**
     * 清掉了照片：提示删了几张、多大。context 是 Application 的，按「应用语言」取（见 Languages.localized）
     */
    private void showCleanupNotification(CleanupResult result) {
        mainHandler.post(() -> {
            String message = Languages.localized(context).getResources().getQuantityString(
                    R.plurals.msg_cleanup_photos, result.deletedCount,
                    result.deletedCount, StorageHelper.formatSize(result.deletedSize));
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            AppLog.d(TAG, "清理通知: " + message);
        });
    }
    
    /**
     * 手动触发清理（用于测试或用户手动清理）
     */
    public void manualCleanup() {
        new Thread(this::performCleanup).start();
    }
    
    /**
     * 获取当前视频占用大小
     * @return 占用大小（字节）
     */
    public long getVideoUsedSize() {
        return getDirectorySize(StorageHelper.getVideoDir(context));
    }
    
    /**
     * 获取当前图片占用大小
     * @return 占用大小（字节）
     */
    public long getPhotoUsedSize() {
        return getDirectorySize(StorageHelper.getPhotoDir(context));
    }
    
    /**
     * 获取目录中所有文件的总大小
     */
    private long getDirectorySize(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0;
        }
        
        // 只认本应用写出来的文件：U 盘上可能有用户自己的东西，以前这里有什么删什么
        File[] files = directory.listFiles(file -> file.isFile()
                && (com.kooo.evcam.camera.StoragePlan.isOwnClip(file.getName())
                || com.kooo.evcam.camera.StoragePlan.isOwnPhoto(file.getName())));
        
        if (files == null) {
            return 0;
        }
        
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        return totalSize;
    }
    
    /**
     * 清理结果
     */
    private static class CleanupResult {
        long originalSize = 0;  // 清理前大小
        long deletedSize = 0;   // 删除的大小
        long finalSize = 0;     // 清理后大小
        int deletedCount = 0;   // 删除的文件数
    }
}
