package com.kooo.evcam;

import android.content.Context;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 保活管理器
 * 管理 WorkManager 定时保活任务
 */
public class KeepAliveManager {
    private static final String TAG = "KeepAliveManager";
    private static final String KEEP_ALIVE_WORK_NAME = "keep_alive_work";

    /**
     * 启动定时保活任务
     * 每15分钟执行一次（Android WorkManager 最小间隔）
     */
    public static void startKeepAliveWork(Context context) {
        if (!new AppConfig(context).isAutoStartOnBoot()) {
            // 保活关着：不登记，已经登记的也取消（规格 §3：关 = 被杀了不回来）
            stopKeepAliveWork(context);
            return;
        }
        AppLog.d(TAG, "启动定时保活任务（每15分钟）");

        // 创建周期性任务请求
        PeriodicWorkRequest keepAliveWork = new PeriodicWorkRequest.Builder(
                KeepAliveWorker.class,
                15, // 最小间隔15分钟
                TimeUnit.MINUTES
        ).build();

        // 使用 KEEP 策略：如果已存在，保持现有任务
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                KEEP_ALIVE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                keepAliveWork
        );

        AppLog.d(TAG, "定时保活任务已启动");
    }

    /**
     * 停止定时保活任务
     */
    public static void stopKeepAliveWork(Context context) {
        AppLog.d(TAG, "停止定时保活任务");
        WorkManager.getInstance(context).cancelUniqueWork(KEEP_ALIVE_WORK_NAME);
    }
}
