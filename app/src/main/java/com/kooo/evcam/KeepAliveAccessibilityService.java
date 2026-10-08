package com.kooo.evcam;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 保活手段之一：无障碍服务（规格 §3）。
 *
 * <p>系统管理的服务，进程优先级高；它跑着时每分钟看一眼前台服务在不在。
 * 要用户去系统设置里打开它。<b>这个容器肯不肯让它跑，还没在车上试过</b>（项目所有者 2026-09-27：留着，试）——
 * 所以它连上、断开都记黑匣子，答案会自己出现在日志里。</p>
 *
 * <p>不读取、不操作任何界面内容，只借它的优先级。保活开关关着时它什么都不做。</p>
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveAccessibility";
    private static final long HEARTBEAT_INTERVAL_MS = 60000; // 60秒心跳
    
    private static KeepAliveAccessibilityService instance;
    private static boolean isServiceRunning = false;
    
    private ScheduledExecutorService heartbeatExecutor;
    private long startTime;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isServiceRunning = true;
        startTime = System.currentTimeMillis();
        // 是它把进程拉起来的话，「拉起者」那一行会写它
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Accessibility");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("无障碍服务已创建：系统让它跑了");
        if (!new AppConfig(this).isAutoStartOnBoot()) {
            AppLog.d(TAG, "保活关着，无障碍服务不做事");
            return;
        }

        AppLog.d(TAG, "无障碍服务已启动（增强保活模式）");
        
        // 启动心跳定时器
        startHeartbeat();
        
        // 动态注册 TIME_TICK 广播（每分钟触发）
        registerTimeTickBroadcast();
        
        // 确保前台服务也在运行
        ensureForegroundServiceRunning();
    }
    
    /**
     * 动态注册 TIME_TICK 广播
     * TIME_TICK 在 Android 8.0+ 只能动态注册，每分钟触发一次
     * 这是保活的关键手段之一
     */
    private void registerTimeTickBroadcast() {
        try {
            KeepAliveReceiver.registerTimeTick(this);
            AppLog.d(TAG, "TIME_TICK 广播已注册");
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 广播失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注销 TIME_TICK 广播
     */
    private void unregisterTimeTickBroadcast() {
        try {
            KeepAliveReceiver.unregisterTimeTick(this);
            AppLog.d(TAG, "TIME_TICK 广播已注销");
        } catch (Exception e) {
            AppLog.e(TAG, "注销 TIME_TICK 广播失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动心跳定时器
     * 定期执行任务，防止进程被系统判定为空闲而清理
     */
    private void startHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;
                AppLog.d(TAG, "心跳: 服务已运行 " + runningMinutes + " 分钟");
                
                // 检查并确保前台服务运行
                ensureForegroundServiceRunning();
            } catch (Exception e) {
                AppLog.e(TAG, "心跳任务异常: " + e.getMessage(), e);
            }
        }, 5000, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        AppLog.d(TAG, "心跳定时器已启动，间隔: " + (HEARTBEAT_INTERVAL_MS / 1000) + "秒");
    }

    /**
     * 停止心跳定时器
     */
    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
            }
            heartbeatExecutor = null;
            AppLog.d(TAG, "心跳定时器已停止");
        }
    }

    /**
     * 确保前台服务正在运行
     * 辅助服务拉起前台服务，形成双重保活
     */
    private void ensureForegroundServiceRunning() {
        if (!new AppConfig(this).isAutoStartOnBoot() || CameraForegroundService.isRunning()) {
            return;
        }
        try {
            // 启动摄像头前台服务
            CameraForegroundService.start(this, getString(R.string.notif_background_title),
                    getString(R.string.notif_tap_to_return));
        } catch (Exception e) {
            AppLog.e(TAG, "拉起前台服务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理任何无障碍事件，仅用于保活
        // 虽然配置允许获取窗口内容，但代码中不会实际读取
        // 这样既能获得高优先级，又能保护用户隐私
    }

    @Override
    public void onInterrupt() {
        AppLog.d(TAG, "无障碍服务被中断");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "无障碍服务 onStartCommand");
        return START_STICKY; // 确保被杀后重启
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppLog.d(TAG, "无障碍服务已连接到系统");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("无障碍服务已连接到系统");
        if (!new AppConfig(this).isAutoStartOnBoot()) {
            return;
        }

        // 服务连接后再次确保所有保活组件运行
        ensureForegroundServiceRunning();
    }

    @Override
    public void onDestroy() {
        AppLog.d(TAG, "无障碍服务正在销毁...");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("无障碍服务被销毁（跑了 "
                + (System.currentTimeMillis() - startTime) / 60000 + " 分钟）");
        
        // 停止心跳
        stopHeartbeat();
        
        // 注销 TIME_TICK 广播
        unregisterTimeTickBroadcast();
        
        // 前台通知由 CameraForegroundService 管理，这里不需要停止
        
        instance = null;
        isServiceRunning = false;
        
        super.onDestroy();
        AppLog.d(TAG, "无障碍服务已销毁");
    }

    /**
     * 检查服务是否正在运行
     */
    public static boolean isRunning() {
        return isServiceRunning && instance != null;
    }
}
