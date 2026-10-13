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
        if (UserExit.blocks(getApplicationContext(), "KeepAliveWorker")
                || !new AppConfig(getApplicationContext()).isAutoStartOnBoot()) {
            // 退出时 / 关保活时已经取消过；还能跑到这里说明取消没赶上，再取消一次
            KeepAliveManager.stopKeepAliveWork(getApplicationContext());
            return Result.success();
        }
        // 它本身不用做别的：登记着这个任务，进程死了系统到点就把它拉起来，这就是它的全部作用。
        // 醒来后一秒内必跑（平台笔记 §3.6），「睡醒了要恢复什么」以后挂在这里（规格 1.5）
        AppLog.d(TAG, "应用进程保持活跃");
        // 睡醒后一秒内必跑到这里（平台笔记 §3.6）：按开机自启动的规矩恢复核心程序（规格 1.5）
        com.kooo.evcam.recovery.Recovery.restore(getApplicationContext(), "keep-alive-task");
        return Result.success();
    }
}
