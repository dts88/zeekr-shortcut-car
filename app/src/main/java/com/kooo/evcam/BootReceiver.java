package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 开机启动广播接收器。
 *
 * <p>这个容器不给我们送开机广播（三次重启都没送到，平台笔记 §3.6）。留着它是为了万一哪天送到了：
 * 记黑匣子、清掉用户退出的标记（规格 1.4）、保活开着就起前台服务和定时任务。
 * 「开机后恢复什么」不在这里 —— 前台服务起来时按开机自启动的规矩去做。</p>
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    
    // 开机后延迟启动时间（毫秒），等待系统稳定
    private static final long BOOT_DELAY_MS = 5000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        AppLog.d(TAG, "收到广播: " + action);
        // 开机广播到底投不投递给 App Lab 里的应用 —— 这一行就是答案
        com.kooo.evcam.blackbox.BlackBox.attach(context, "BootReceiver:" + action);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开机广播: " + action);

        // 监听开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            AppLog.d(TAG, "系统开机完成！");
            // 真正开机：用户上一次的「退出」到此为止
            UserExit.clear(context, "boot");
            
            // 保活开着才起前台服务（规格 §3）
            if (new AppConfig(context).isAutoStartOnBoot()) {
                startForegroundServiceImmediately(context);
            }
                        
            // 延迟执行其他初始化（等待系统稳定）
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                performDelayedInit(context);
            }, BOOT_DELAY_MS);
        }
    }
    
    /**
     * 立即启动前台服务（关键！）
     * 参考应用0：收到广播后直接启动服务，不做任何检查
     */
    private void startForegroundServiceImmediately(Context context) {
        try {
            AppLog.d(TAG, "立即启动前台服务...");
            
            // 直接启动前台服务，不检查任何配置
            // 这是保活应用的关键做法：无条件启动
            Intent serviceIntent = new Intent(context, CameraForegroundService.class);
            serviceIntent.putExtra("title", context.getString(R.string.notif_background_title));
            serviceIntent.putExtra("content", context.getString(R.string.notif_tap_to_return));
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            AppLog.d(TAG, "前台服务启动成功");
        } catch (Exception e) {
            AppLog.e(TAG, "启动前台服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 延迟执行的初始化任务
     * 等待系统稳定后再执行复杂的初始化
     */
    private void performDelayedInit(Context context) {
        AppLog.d(TAG, "执行延迟初始化...");
        
        try {
            if (new AppConfig(context).isAutoStartOnBoot()) {
                KeepAliveReceiver.registerTimeTick(context);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 失败: " + e.getMessage(), e);
        }
                
        try {
            // WorkManager 保活任务（它自己看保活开关）
            KeepAliveManager.startKeepAliveWork(context);
        } catch (Exception e) {
            AppLog.e(TAG, "延迟初始化失败: " + e.getMessage(), e);
        }

        AppLog.d(TAG, "开机自启动初始化完成");
    }
}
