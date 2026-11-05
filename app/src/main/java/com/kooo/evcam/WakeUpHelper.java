package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 唤醒工具类
 *
 * 阻止休眠的关键：
 * 1. PARTIAL_WAKE_LOCK - 保持 CPU 运行
 * 2. 电池优化白名单 - 防止 Doze 模式忽略 WakeLock
 * 3. 在前台服务中持有 WakeLock - 比 Activity 更可靠
 */
public class WakeUpHelper {
    private static final String TAG = "WakeUpHelper";

    // 持续唤醒锁 - 用于防止休眠（无超时）
    private static PowerManager.WakeLock persistentWakeLock;

    /**
     * 检查是否有悬浮窗权限（用于后台启动Activity）
     * Android 10+ 需要此权限才能从后台启动 Activity
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    /**
     * 请求悬浮窗权限
     * 需要用户手动授权
     */
    public static void requestOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * 拿一个不超时的唤醒锁，车机就不会深睡。
     *
     * <h3>这是有代价的，而且代价是实测过的</h3>
     *
     * <p>2026-09-23 那份黑匣子：车机开机 26 小时，其中 <b>20.8 小时在深睡</b>
     * —— 全是停着的时候。这个锁拿着，那 20.8 小时就变成醒着，停在那里耗 12V 电瓶。</p>
     *
     * <p>同一份日志里还有一条：两段深睡前后进程 pid 一模一样，
     * <b>进程不需要这个锁也能活下来</b>。所以名叫「防止休眠」的那个开关 1.25.0 删掉了。</p>
     *
     * <p>现在只有一个调用者：「熄屏录制（阻止休眠）」（规格 §3.1，{@code ScreenOffRecording}）——
     * 熄屏时在录像才拿，录像停了、亮屏了、到了用户设的时长就放。项目所有者 2026-09-27
     * 明确允许熄屏录制不让车机睡。完整数据见 {@code docs/zeekr-platform-notes.md} §3.6。</p>
     *
     * <p>2026-11-02 熄屏录制停用（项目所有者：从来没有启用过），这里没有人再拿；代码留着等它恢复。</p>
     */
    public static void acquirePersistentWakeLock(Context context) {
        AppLog.d(TAG, "Acquiring persistent wake lock (prevent sleep)...");
        com.kooo.evcam.blackbox.BlackBox.note("请求持续唤醒锁");

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            AppLog.e(TAG, "PowerManager is null");
            return;
        }

        // 如果已经持有，不重复获取
        if (persistentWakeLock != null && persistentWakeLock.isHeld()) {
            AppLog.d(TAG, "Persistent WakeLock already held");
            return;
        }

        // 创建持续唤醒锁
        // PARTIAL_WAKE_LOCK: 只保持CPU运行，不亮屏
        persistentWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EVCam:AutoStartAwake"
        );

        // 持有唤醒锁，不设置超时（直到手动释放）
        persistentWakeLock.acquire();
        AppLog.d(TAG, "Persistent WakeLock acquired (no timeout) - system will not sleep");
    }
    
    /**
     * 释放持续唤醒锁
     */
    public static void releasePersistentWakeLock() {
        if (persistentWakeLock != null && persistentWakeLock.isHeld()) {
            try {
                persistentWakeLock.release();
                com.kooo.evcam.blackbox.BlackBox.note("释放持续唤醒锁");
                AppLog.d(TAG, "Persistent WakeLock released - system can sleep now");
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to release persistent WakeLock", e);
            }
        }
        persistentWakeLock = null;
    }
    
    /**
     * 检查持续唤醒锁是否被持有
     */
    public static boolean isPersistentWakeLockHeld() {
        return persistentWakeLock != null && persistentWakeLock.isHeld();
    }
}
