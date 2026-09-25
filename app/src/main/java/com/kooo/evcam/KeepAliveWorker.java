package com.kooo.evcam;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * 定时保活任务
 * 每15分钟执行一次，确保应用进程保持活跃
 */
public class KeepAliveWorker extends Worker {
    private static final String TAG = "KeepAliveWorker";

    public KeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppLog.d(TAG, "定时保活任务执行 - 确保应用进程活跃");
        // WorkManager 在停车之后还跑不跑，只能靠这一行的时间戳看
        com.kooo.evcam.blackbox.BlackBox.attach(getApplicationContext(), "WorkManager");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("保活任务执行");
        if (UserExit.blocks(getApplicationContext(), "KeepAliveWorker")) {
            // 退出时已经取消过；还能跑到这里说明取消没赶上，再取消一次
            KeepAliveManager.stopKeepAliveWork(getApplicationContext());
            return Result.success();
        }

        try {
            // 检查远程查看服务状态
            Context context = getApplicationContext();
            
            // 记录当前运行状态
            AppLog.d(TAG, "应用进程保持活跃");
            AppLog.d(TAG, "无障碍服务状态: " + (KeepAliveAccessibilityService.isRunning() ? "运行中" : "未运行"));
            
            // 可以在这里做一些轻量级的检查，确保核心服务正常
            // 例如检查钉钉连接状态等
            
            return Result.success();
        } catch (Exception e) {
            AppLog.e(TAG, "保活任务执行失败", e);
            return Result.retry();
        }
    }
}
