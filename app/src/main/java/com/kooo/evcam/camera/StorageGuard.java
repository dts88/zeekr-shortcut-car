package com.kooo.evcam.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把 {@link StoragePlan} 的决定落到盘上：列文件、量剩余空间、删文件。
 *
 * <h3>什么时候跑</h3>
 *
 * <ul>
 *   <li><b>每个分段写完</b>（相机层直接调，不经过界面）—— 以前每小时才查一次，
 *       两次之间目录可以比上限多出整整一小时的录像；</li>
 *   <li><b>录制中每 30 秒</b>看一眼剩余空间，低于余量才做完整检查（只是一次 statfs，很便宜）；</li>
 *   <li><b>开录之前</b>，确认至少写得下一个分段。</li>
 * </ul>
 *
 * <p>列目录、删文件都可能在一块慢 U 盘上花掉不少时间，所以一律放在自己的单线程上；
 * 单线程也保证了几路相机同时触发时不会并发删同一批文件。</p>
 */
public final class StorageGuard {

    private static final String TAG = "StorageGuard";
    private static final long GB = 1024L * 1024L * 1024L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "StorageGuard");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 最近一次算出的余量。开录前的快速检查只看剩余空间，拿它当门槛 ——
     * 那一刻还没写过分段，量不出真实大小，就先用上一次的（进程里第一次用默认的两个 GB）。
     */
    private static volatile long lastMarginBytes =
            StoragePlan.margin(StoragePlan.DEFAULT_SEGMENT_BYTES);

    public interface Callback {
        /** 在主线程上回调。 */
        void onResult(StoragePlan.Decision decision);
    }

    private StorageGuard() {
    }

    public static long lastMarginBytes() {
        return lastMarginBytes;
    }

    /** 剩余空间；读不到时返回 -1（调用方当作「不知道」，不据此拒录）。 */
    public static long freeBytes(File dir) {
        if (dir == null) {
            return -1;
        }
        try {
            return StorageHelper.getAvailableSpace(dir);
        } catch (Exception e) {
            AppLog.w(TAG, "读不到剩余空间: " + e);
            return -1;
        }
    }

    /**
     * 在调用线程上完整跑一次：列出本应用的录像 → 决定 → 需要删就删。
     *
     * <p>不要在主线程调：U 盘上列目录和删文件都可能慢。</p>
     */
    public static StoragePlan.Decision enforce(Context context, File videoDir) {
        List<StoragePlan.Clip> clips = new ArrayList<>();
        File[] files = videoDir != null ? videoDir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && StoragePlan.isOwnClip(file.getName())) {
                    clips.add(new StoragePlan.Clip(file.getName(), file.length()));
                }
            }
        }

        long capBytes = new AppConfig(context).getVideoStorageLimitGb() * GB;
        long free = freeBytes(videoDir);
        long segment = StoragePlan.estimateSegmentBytes(clips);
        // 读不到剩余空间时不据此下结论：当作足够，只按上限管
        StoragePlan.Decision decision = StoragePlan.decide(
                clips, capBytes, free < 0 ? Long.MAX_VALUE : free, segment);
        lastMarginBytes = decision.marginBytes;

        AppLog.i(TAG, "存储检查: " + clips.size() + " 个录像 上限="
                + (capBytes > 0 ? StorageHelper.formatSize(capBytes) : "不限制")
                + " 剩余=" + (free < 0 ? "未知" : StorageHelper.formatSize(free))
                + " 一个分段≈" + StorageHelper.formatSize(segment)
                + " 余量=" + StorageHelper.formatSize(decision.marginBytes)
                + " → " + decision.verdict
                + (decision.verdict == StoragePlan.Verdict.FULL
                        ? (decision.capless ? "（没设上限，不删）" : "（删光本应用的旧录像也不够）")
                        : ""));

        if (decision.verdict == StoragePlan.Verdict.DELETE) {
            int deleted = 0;
            for (String name : decision.toDelete) {
                File file = new File(videoDir, name);
                if (file.delete()) {
                    deleted++;
                } else {
                    AppLog.w(TAG, "删不掉: " + name);
                }
            }
            AppLog.i(TAG, "删掉最旧的录像 " + deleted + "/" + decision.toDelete.size()
                    + " 个，约 " + StorageHelper.formatSize(decision.deleteBytes));
        }
        return decision;
    }

    /** 在自己的线程上跑 {@link #enforce}，结果回主线程。 */
    public static void enforceAsync(Context context, File videoDir, Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            StoragePlan.Decision decision;
            try {
                decision = enforce(app, videoDir);
            } catch (Exception e) {
                AppLog.e(TAG, "存储检查失败", e);
                return;
            }
            if (callback != null) {
                MAIN.post(() -> callback.onResult(decision));
            }
        });
    }
}
