package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务，用于在后台使用摄像头
 * Android 11+ 要求后台使用摄像头时必须有前台服务
 * 
 * 增强保活功能：
 * - 当无障碍服务未开启时，此服务会动态注册 TIME_TICK 广播
 * - TIME_TICK 每分钟触发一次，可以保持应用活跃
 * - onTaskRemoved: 用户滑动清除应用时自动重启
 * - onDestroy: 服务被杀时发送延迟重启广播
 * - WakeLock: 防止系统休眠（需用户开启）
 */
public class CameraForegroundService extends Service {
    private static final String TAG = "CameraForegroundService";
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // 服务重启延迟时间
    private static final long RESTART_DELAY_MS = 1000;

    private static volatile boolean isForegroundReady = false;
    /** 见 {@link #startedWhileVisible()}。 */
    private static volatile boolean startedWhileVisible = false;

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:CameraForegroundService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("前台服务 CameraForegroundService onCreate");
        if (UserExit.blocks(this, "CameraForegroundService.onCreate")) {
            // 系统按 START_STICKY 重启了我们，但用户已经退出：什么都别起（尤其别去拉主界面），
            // 在 onStartCommand 里把自己停掉
            exitedIdle = true;
            return;
        }
        AppLog.d(TAG, "Service created");
        createNotificationChannel();
        
        // 保活开着就注册 TIME_TICK 广播（每分钟确认一次前台服务在）
        registerTimeTickIfNeeded();
                
        // 黑匣子心跳：每分钟一行。停车那一夜有没有真的睡过去、睡了多久，
        // 全在这些行的 slept= 里；中间断掉的那一段就是「我们不在」
        startBlackBoxHeartbeat();

        // 相机服务眼里每一路空不空。环视卡死、只能重启车机的那种状态，
        // 要靠它说出「是谁占着」—— 注册时系统会把当前状态报一遍
        com.kooo.evcam.camera.CameraAvailabilityWatch.start(this);
        // 恢复（Recovery.restore）在 onStartCommand 里做：started 服务的 onCreate 后面必然跟一次 onStartCommand，
        // 以前两处各调一次，冷启动的恢复跑两遍
    }

    /** 前台服务此刻在不在（startForeground 做完了）。保活的各条路只在它不在时才去启动它。 */
    public static boolean isRunning() {
        return isForegroundReady;
    }

    /**
     * 这个前台服务能用相机：它是在主界面在前时起的，或者主界面在前时刷新过（{@code onStartCommand} 那一刻主界面在前）。
     *
     * <p>安卓 12 起，后台才起的前台服务用不了相机（lifecycle-spec §3）：通道调度据此判「能用相机」 ——
     * 主界面在前，或者这一项为真（项目所有者 2026-10-10 确认，大纲 §0）。不能用时它不开、不重开、不计次数。</p>
     *
     * <p>只升不降，服务停了才清：AOSP 12 的 {@code ActiveServices.setFgsRestrictionLocked} 对已经放行的服务不再收回
     * （同一个服务在后台再被 start 一次，例如停录时换通知文字，还是放行的）。照「每次 start 都重新判」的话，
     * 录着时在后台换一下通知，这一路丢了就再也不重开。</p>
     */
    public static boolean startedWhileVisible() {
        return isForegroundReady && startedWhileVisible;
    }

    /** 被系统杀了要不要重启：保活开着才要（规格 §3：关 = 被杀了不回来）。几个服务共用这一条。 */
    public static int stickiness(Context context) {
        return new AppConfig(context).isAutoStartOnBoot() ? START_STICKY : START_NOT_STICKY;
    }
    
    /**
     * 唤醒锁不在这里管了：它只属于「熄屏录制（阻止休眠）」（规格 §3.1，{@code ScreenOffRecording}），
     * 熄屏时在录像才拿、录像停了就放、到了用户设的时长就放。
     */

    /** 保活开着就注册 TIME_TICK 广播（每分钟触发，前台服务不在就拉起来）；无障碍服务在跑的话它自己有心跳。 */
    private void registerTimeTickIfNeeded() {
        if (new AppConfig(this).isAutoStartOnBoot() && !KeepAliveAccessibilityService.isRunning()
                && !KeepAliveReceiver.isTimeTickRegistered()) {
            KeepAliveReceiver.registerTimeTick(this);
        }
    }

    /** 用户已退出、这一次是被系统重启的：onCreate 什么都没起，onStartCommand 里停掉自己。 */
    private boolean exitedIdle;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (exitedIdle || UserExit.isExited(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // flags 里的 START_FLAG_RETRY / START_FLAG_REDELIVERY 直接说明
        // 这一次是不是系统在做 sticky 重启 —— 「START_STICKY 到底生不生效」看它。
        //
        // 只有那种才值一行。每分钟那一次例行叫醒不记：原来一律记，48KB 的导出只装得下
        // 三个小时；后来改成计数，睡眠唤醒那一轮研究完（平台笔记 §3.6）计数也没用了
        if (flags != 0 || intent == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant(
                    "CameraForegroundService onStartCommand flags=" + flags
                            + (intent == null ? " intent=null(sticky重启)" : "")
                            + " startId=" + startId);
        }
        AppLog.d(TAG, "Service started");
        
        // 每次启动时检查并注册 TIME_TICK
        registerTimeTickIfNeeded();
                
        // 每次被启动都按开机自启动的规矩看一眼该恢复什么（例行的那种五分钟做一次）
        com.kooo.evcam.recovery.Recovery.restore(this, com.kooo.evcam.recovery.Recovery.WHY_ROUTINE);

        // 通知上的字照录像此刻的样子写（正在录制 / 等待摄像头 / 在后台运行，大纲 5c），谁叫起来的都一样 ——
        // 以前由叫的那一处带着字来：保活那几处只会写「在后台运行」，录着时被它们叫起来，通知就说错了
        com.kooo.evcam.recording.RecordingCoordinator coordinator =
                com.kooo.evcam.recording.RecordingCoordinator.get(this);
        Notification notification = buildNotification(this,
                coordinator.notificationTitle(), coordinator.notificationContent());

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);

        // 这一次 start 时主界面在前：从此能用相机（只升不降，见 startedWhileVisible）
        if (com.kooo.evcam.camera.StallWatch.isForeground()) {
            startedWhileVisible = true;
        }
        isForegroundReady = true;
        // 服务就绪：叫醒通道调度 —— 没人要的相机可以保持 30 秒（isRunning），是主界面在前时起的、刷新的，后台也能用相机了
        // （startedWhileVisible）。以前这里还执行排着的「就绪后」回调（whenReady，开相机用的）：相机只由调度开，2.11 阶段 6 删了
        com.kooo.evcam.camera.ChannelScheduler.wakeAll();

        return stickiness(this);
    }

    /** 黑匣子心跳间隔：一分钟看一次。 */
    private static final long BLACK_BOX_TICK_MS = 60_000L;
    /** 状态没变的话，多久才值得再写一行。 */
    private static final long BLACK_BOX_QUIET_MS = 10 * 60_000L;

    /** 上一行心跳写的是什么状态，以及什么时候写的（开机起算，含深睡）。 */
    private boolean lastBeatRecording;
    private boolean lastBeatScreenOn;
    private long lastBeatAtMs;

    private final android.os.Handler blackBoxHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable blackBoxTick = new Runnable() {
        @Override
        public void run() {
            // 每分钟看一次，但只有「变了」或者「安静太久了」才写下来。
            //
            // 一分钟一行看着不多，一夜也就六十行 —— 可它一分钟并不止一行，
            // 连带前台服务那两行，三分之二的黑匣子都是这种什么也没发生的行。
            // 真正要看的是变化：什么时候开始录、什么时候熄的屏，以及
            // <b>两行之间 slept= 涨了多少</b>，那一段就是车机睡过去的时间。
            boolean recording = isRecordingNow();
            boolean screenOn = isScreenOnNow();
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastBeatAtMs == 0 || recording != lastBeatRecording || screenOn != lastBeatScreenOn
                    || now - lastBeatAtMs >= BLACK_BOX_QUIET_MS) {
                // view：录像此刻的样子（开录中 / 在录 / 等待摄像头），一夜里一路都录不了的那几段在这里看得出来
                com.kooo.evcam.blackbox.BlackBox.note("心跳 recording=" + recording + " view="
                        + com.kooo.evcam.recording.RecordingCoordinator.get(CameraForegroundService.this).view() + " screenOn=" + screenOn);
                lastBeatRecording = recording;
                lastBeatScreenOn = screenOn;
                lastBeatAtMs = now;
            }
            com.kooo.evcam.blackbox.BlackBox.flushCountsIfDue();
            blackBoxHandler.postDelayed(this, BLACK_BOX_TICK_MS);
        }
    };

    private void startBlackBoxHeartbeat() {
        blackBoxHandler.removeCallbacks(blackBoxTick);
        blackBoxHandler.postDelayed(blackBoxTick, BLACK_BOX_TICK_MS);
    }

    private boolean isRecordingNow() {
        return com.kooo.evcam.recording.RecordingCoordinator.get(this).isRecording();
    }

    private boolean isScreenOnNow() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("CameraForegroundService onDestroy");
        blackBoxHandler.removeCallbacks(blackBoxTick);
        AppLog.d(TAG, "Service destroyed - 尝试重启...");
        isForegroundReady = false;
        startedWhileVisible = false;
        // 前台服务没了：不再保持，后台用不了相机 —— 叫醒通道调度
        com.kooo.evcam.camera.ChannelScheduler.wakeAll();

        // 服务被杀时自己拉自己：这是保活的手段之一，保活开着才做（规格 §3）；
        // 用户主动退出时不做 —— 这正是以前「退不掉」的一条路
        if (!UserExit.isExited(this) && new AppConfig(this).isAutoStartOnBoot()) {
            scheduleServiceRestart();
        }

        super.onDestroy();
    }


    /**
     * 当用户从最近任务中滑动清除应用时调用：保活开着就重新启动服务。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLog.d(TAG, "onTaskRemoved - 应用被从最近任务清除");
        if (!UserExit.isExited(this) && new AppConfig(this).isAutoStartOnBoot()) {
            scheduleServiceRestart();
        }
        super.onTaskRemoved(rootIntent);
    }
    
    /**
     * 调度服务重启
     * 使用 Handler 延迟重启，避免立即重启被系统拦截
     */
    private void scheduleServiceRestart() {
        try {
            // 方案1：使用 Handler 延迟重启
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    AppLog.d(TAG, "执行延迟重启...");
                    start(getApplicationContext());
                } catch (Exception e) {
                    AppLog.e(TAG, "延迟重启失败: " + e.getMessage(), e);
                }
            }, RESTART_DELAY_MS);
            
            // 方案2：发送保活广播（备份）
            KeepAliveReceiver.sendKeepAliveCheck(getApplicationContext());
            
        } catch (Exception e) {
            AppLog.e(TAG, "调度重启失败: " + e.getMessage(), e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /** 前台服务那一条通知：标题写状态，正文写能做的动作，点了回主界面。 */
    private static Notification buildNotification(Context context, String title, String content) {
        Intent notificationIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * 换通知上的字（录像的样子变了、停录换回「在后台运行」）：服务在就用 {@link NotificationManager} 当场换同一条通知，
     * 不再 start 一次 —— 安卓 12 起在后台 startForegroundService 会被拦（大纲 5c：在后台时绝不为换字起服务）。
     * 服务不在就什么都不做：它起来时自己照录像此刻的样子写（{@link #onStartCommand}）。
     */
    public static void showText(Context context, String title, String content) {
        if (!isForegroundReady) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(context, title, content));
        }
    }

    /**
     * 启动前台服务（已经在的再走一次 onStartCommand）。通知上的字不由叫的这一处给：服务起来时照录像此刻的样子写
     * （{@code RecordingCoordinator#notificationTitle}）。
     */
    public static void start(Context context) {
        // 保活广播、ContentProvider、无障碍服务都从这里启动前台服务 —— 用户退出后一律不启动
        if (UserExit.blocks(context, "CameraForegroundService.start")) {
            return;
        }
        Intent intent = new Intent(context, CameraForegroundService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 13+ 对前台服务启动有限制，但录制时需要强制启动
            // 使用 startForegroundService 启动，服务必须在 5 秒内调用 startForeground
            try {
                context.startForegroundService(intent);
                AppLog.d(TAG, "Starting foreground service");
            } catch (Exception e) {
                // Android 12 起，后台启前台服务会被拦 —— 拦不拦、什么条件下拦，看这一行
                com.kooo.evcam.blackbox.BlackBox.noteImportant("startForegroundService 被拒: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                AppLog.e(TAG, "启动前台服务失败: " + e.getMessage(), e);
                // 如果失败，尝试普通启动
                try {
                    context.startService(intent);
                    AppLog.d(TAG, "尝试普通服务启动");
                } catch (Exception e2) {
                    AppLog.e(TAG, "普通启动也失败: " + e2.getMessage(), e2);
                }
            }
        } else {
            context.startService(intent);
            AppLog.d(TAG, "Starting service");
        }
    }

    /**
     * 录像停了（RecordingCoordinator 调）、或主界面收尾 / 退出要停服务。
     *
     * <p>前台服务的寿命 = 「开机自启动」开着、在录、或者等着接回（规格 §3）。保活开着时它本来就该一直在：
     * 这里只把通知照录像此刻的样子换回「在后台运行」（{@link #showText}，不再 start 一次），不停。等着接回的也一样
     * （「这一趟要录」立着：存储一类被打断等自动接回、因熄屏停下等亮屏，2026-10-10）—— 接回时相机是从后台开的，
     * 后台拿着相机安卓要求有前台服务。都不是、或用户退出了，才真的停。
     * 以前一律 stopService，保活开着时它 onDestroy 里一秒后又把自己拉起来 ——
     * 每次停录都停一次、起一次，通知闪一下，冷启动的恢复再跑一遍。</p>
     */
    public static void stop(Context context) {
        if (!UserExit.isExited(context) && (new AppConfig(context).isAutoStartOnBoot()
                || com.kooo.evcam.recording.RecordingIntent.current().recordingWanted())) {
            com.kooo.evcam.recording.RecordingCoordinator coordinator =
                    com.kooo.evcam.recording.RecordingCoordinator.get(context);
            showText(context, coordinator.notificationTitle(), coordinator.notificationContent());
            return;
        }
        isForegroundReady = false;
        startedWhileVisible = false;
        Intent intent = new Intent(context, CameraForegroundService.class);
        context.stopService(intent);
        AppLog.d(TAG, "Stopping foreground service");
    }
}
