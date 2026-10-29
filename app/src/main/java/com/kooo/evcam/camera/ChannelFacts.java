package com.kooo.evcam.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 通道调度在进程这一层的事实（{@link ChannelScheduler.Facts} 的 Android 那一层）：登记表、亮熄屏、哨兵模式、在不在开车、前台服务、
 * 主界面在不在前、相机服务那几句、车上的几样；问相机服务、记争用。装上那一个调度时建一次（{@link #install}，应用一起来就装），
 * 叫醒它的几个监听也在这时挂一次、不再摘 —— 登记表（{@link CameraNeeds}）、相机服务（{@link CameraAvailabilityWatch}）、亮熄屏、
 * 车辆信号（哨兵模式、在不在开车变了）。
 *
 * <p>以前这些跟着每一份管线（MultiCameraManager 的 ChannelSide）：管线重建时两份并存，登记表的监听只认后挂上的那一份、
 * 摘的时候要看是不是自己（第 2 步「只有一个调度」，项目所有者 2026-10-29 批：管线只剩它自己用的那几样）。</p>
 *
 * <p>都是缓存的值或者进程里的状态：主线程上调，不进相机服务、不进车辆接口（问相机服务读的也是本进程的缓存）。</p>
 */
public final class ChannelFacts implements ChannelScheduler.Facts {

    private static final String TAG = "ChannelFacts";

    private static boolean installed;

    private final Context app;
    private ChannelScheduler scheduler;
    /** 读切换范围、熄屏持续录制用的设置（第一次要时建）。 */
    private volatile AppConfig config;
    /** 上一轮读到的「超级镜子要后座舱」：变了记一行黑匣子。 */
    private volatile boolean mirrorCabinNoted;
    /** 屏幕知道的那一刻要叫醒调度：已经排着的不再排（主线程）。 */
    private boolean knownWakeQueued;

    private ChannelFacts(Context app) {
        this.app = app;
    }

    /**
     * 装上进程里那一个调度（应用一起来，ZeekrShortcutApp.onCreate，主线程）：钟是开机以来、不含深睡（{@code SystemClock.uptimeMillis}），
     * 队列是主线程（管线放掉时清自己的队列，碰不到调度的这一个）。只装一次。
     */
    public static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final Handler main = new Handler(Looper.getMainLooper());
        ChannelFacts facts = new ChannelFacts(app);
        ChannelScheduler scheduler = ChannelScheduler.install(SystemClock::uptimeMillis, new ChannelScheduler.Poster() {
            @Override
            public void post(Runnable task) {
                main.post(task);
            }

            @Override
            public void postDelayed(Runnable task, long delayMs) {
                main.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                main.removeCallbacks(task);
            }
        }, facts);
        facts.attach(scheduler);
    }

    /** 叫醒调度的几个监听，挂一次（主线程）。 */
    private void attach(ChannelScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
        // 登记表一变就叫醒（监听可能在别的线程上叫）；车机要睡发布那一下当场置「正在按次序关」
        CameraNeeds.current().setListener(scheduler::onNeedsChanged);
        // 相机服务每说一句、问的结果回来、注册回放收全：叫醒；分类时问它「这一路有没有我们的开或关在途」
        CameraAvailabilityWatch.setListener(new CameraAvailabilityWatch.Listener() {
            @Override
            public boolean ourStepInFlight(String cameraId) {
                return scheduler.ourStepInFlight(cameraId);
            }

            @Override
            public void onServiceChanged() {
                scheduler.wake();
            }

            @Override
            public void onAccessPrioritiesChanged() {
                scheduler.askNow();
            }
        });
        // 亮屏、熄屏（ScreenState 在主线程上叫）：熄屏时 PREVIEW 不算、不保持、座舱谁都不要、没留着的环视放开，由规则判；
        // 屏幕黑满 30 秒才算，那一刻调度自己醒（ChannelRules.darkForChannel）
        com.kooo.evcam.screen.ScreenState.addListener(new com.kooo.evcam.screen.ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                scheduler.wake();
            }

            @Override
            public void onScreenOn() {
                scheduler.wake();
            }
        });
        // 哨兵模式（车辆接口没连着算读不到）、在不在开车变了才叫醒 —— 环视熄屏时留不留看它们（项目所有者 2026-10-27）；Telemetry 在主线程上叫
        com.kooo.evcam.telemetry.Telemetry.get().addListener(new com.kooo.evcam.telemetry.Telemetry.Listener() {
            private boolean seen;
            private Integer sentry;
            private boolean driving;

            @Override
            public void onReadingsChanged(com.kooo.evcam.telemetry.Readings readings) {
                Integer nowSentry = sentry();
                boolean nowDriving = driving();
                if (seen && Objects.equals(nowSentry, sentry) && nowDriving == driving) {
                    return;
                }
                seen = true;
                sentry = nowSentry;
                driving = nowDriving;
                scheduler.wake();
            }
        });
    }

    /** 设置（第一次要时建）。 */
    private AppConfig config() {
        AppConfig settings = config;
        if (settings == null) {
            settings = new AppConfig(app);
            config = settings;
        }
        return settings;
    }

    // ---- 登记表、车机要睡

    @Override
    public Set<CameraNeeds.Holder> holders() {
        Set<CameraNeeds.Holder> held = EnumSet.noneOf(CameraNeeds.Holder.class);
        CameraNeeds needs = CameraNeeds.current();
        for (CameraNeeds.Holder holder : CameraNeeds.Holder.values()) {
            if (needs.isHeld(holder)) {
                held.add(holder);
            }
        }
        return held;
    }

    @Override
    public boolean headUnitSleeps() {
        return CameraNeeds.current().headUnitSleeps();
    }

    @Override
    public boolean mirrorWantsCabinRear() {
        boolean wants = config().rearViewWantsCabinRear();
        if (wants != mirrorCabinNoted) {
            mirrorCabinNoted = wants;
            com.kooo.evcam.blackbox.BlackBox.note(wants
                    ? "超级镜子切换范围是「后视 + 后座舱」：登记着超级镜子时后座舱也有人要"
                    : "超级镜子切换范围不含后座舱：超级镜子只要环视");
        }
        return wants;
    }

    // ---- 屏幕

    @Override
    public boolean screenOn() {
        return !com.kooo.evcam.screen.ScreenState.dark();
    }

    /** 屏幕黑了多久；起点不明交 -1（规则按黑满 30 秒算）。算法只在 {@link MultiCameraManager#channelDarkForMs} 一处。 */
    @Override
    public long darkForMs() {
        return MultiCameraManager.channelDarkForMs();
    }

    @Override
    public boolean androidOff() {
        return com.kooo.evcam.screen.ScreenState.androidOff();
    }

    /**
     * 屏幕知道黑没黑（ScreenState.known）。不知道时排一下（ScreenState.whenKnown，主线程上叫）：知道了叫醒调度，
     * 不等下一个两秒（项目所有者 2026-10-27：屏幕不知道时不开相机，上限是屏幕判断自己的）。
     */
    @Override
    public boolean screenKnown() {
        if (com.kooo.evcam.screen.ScreenState.known()) {
            return true;
        }
        if (!knownWakeQueued) {
            knownWakeQueued = true;
            com.kooo.evcam.screen.ScreenState.whenKnown(() -> {
                knownWakeQueued = false;
                ChannelScheduler s = scheduler;
                if (s != null) {
                    s.wake();
                }
            });
        }
        return false;
    }

    // ---- 车

    /** 哨兵模式（车辆信号核心那一份，熄屏判断登记的、一直在读）；车辆接口没连着算读不到。 */
    @Override
    public Integer sentry() {
        com.kooo.evcam.telemetry.Telemetry t = com.kooo.evcam.telemetry.Telemetry.get();
        return t.carLinkConnected() ? t.latest().sentry : null;
    }

    /** 「熄屏持续录制」（开发者「熄屏录制（阻止休眠）」不算，lifecycle-spec §3.1）。每一轮现读：黑着时没人改得了它。 */
    @Override
    public boolean keepRecordingWhenDark() {
        return config().isScreenOffKeepRecording();
    }

    /** 在开车（ScreenOffPlan.driving，和录像那张表同一句）；车辆接口没连着算没在开车。 */
    @Override
    public boolean driving() {
        com.kooo.evcam.telemetry.Telemetry t = com.kooo.evcam.telemetry.Telemetry.get();
        if (!t.carLinkConnected()) {
            return false;
        }
        com.kooo.evcam.telemetry.VehicleState v = t.latest();
        return com.kooo.evcam.recording.ScreenOffPlan.driving(v.gear, v.speedKmh);
    }

    /**
     * 车上的几样事实（ChannelScheduler.Vehicle）：哨兵原值读车辆信号那一份快照，点火 / 主驾门 / 屏幕电源事件多久以前变过读
     * {@link com.kooo.evcam.telemetry.SignalAges}，进程的重要性读监测线程缓存的 —— 都不进车辆接口、不进系统服务（主线程）。
     */
    @Override
    public ChannelScheduler.Vehicle vehicle() {
        try {
            com.kooo.evcam.telemetry.Readings r = com.kooo.evcam.telemetry.Telemetry.get().readings();
            long now = SystemClock.elapsedRealtime();
            return new ChannelScheduler.Vehicle(r.code(com.kooo.evcam.telemetry.Signal.SENTRY_MODE),
                    r.code(com.kooo.evcam.telemetry.Signal.IGNITION),
                    com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.IGNITION, now),
                    com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.DOOR_DRIVER, now),
                    com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.SCREEN_POWER_EVENT, now),
                    StallWatch.importance());
        } catch (RuntimeException e) {
            AppLog.w(TAG, "vehicle facts unavailable: " + e);
            return ChannelScheduler.Vehicle.UNKNOWN;
        }
    }

    // ---- 能不能用相机

    @Override
    public boolean foregroundService() {
        return com.kooo.evcam.CameraForegroundService.isRunning();
    }

    @Override
    public boolean mainForeground() {
        return StallWatch.isForeground();
    }

    @Override
    public boolean serviceStartedWhileVisible() {
        return com.kooo.evcam.CameraForegroundService.startedWhileVisible();
    }

    // ---- 相机服务

    @Override
    public boolean heardAnything() {
        return CameraAvailabilityWatch.heardAnything();
    }

    @Override
    public long elapsedNow() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public long lastOthersChangeAt() {
        return CameraAvailabilityWatch.lastOthersChangeAt();
    }

    @Override
    public CameraAvailabilityWatch.Word word(String cameraId) {
        return CameraAvailabilityWatch.word(cameraId);
    }

    /** 这一台相机的来历（{@link CameraHistory}，内存里的那一份，主线程不碰盘）。 */
    @Override
    public CameraHistory.Camera history(String cameraId) {
        return StepLog.history().camera(cameraId);
    }

    @Override
    public void ask(Consumer<CameraAvailabilityWatch.Asked> answered) {
        CameraAvailabilityWatch.ask(answered);
    }

    @Override
    public void othersTook(String cameraId, long atElapsed) {
        // 相机服务那一声「被占用、不是我们」当时就记过争用的，不再记一遍
        if (!CameraContention.othersHoldNow(cameraId)) {
            CameraContention.othersTookAt(cameraId, atElapsed);
        }
    }

    @Override
    public boolean frontCabinOneShot() {
        return FrontCabinOneShot.ACTIVE;
    }
}
