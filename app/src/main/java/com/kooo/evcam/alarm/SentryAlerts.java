/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.app.NotificationChannel;
import android.graphics.Bitmap;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.media.AlertPicture;
import com.kooo.evcam.alarm.media.FrameDiff;
import com.kooo.evcam.alarm.media.MotionTap;
import com.kooo.evcam.alarm.media.PictureBudget;
import com.kooo.evcam.alarm.media.SnapshotTaker;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.AlertPolicy;
import com.kooo.evcam.alarm.policy.ArmDelay;
import com.kooo.evcam.alarm.policy.QuietHours;
import com.kooo.evcam.alarm.policy.SentryFacts;
import com.kooo.evcam.alarm.policy.TriggerSet;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.zeekr.FisheyeProjection;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 停车警报：车停着、哨兵模式开着时，车上的动静把四个摄像头的一张照片单向发到一个 ntfy 话题。
 *
 * <h3>看的是什么</h3>
 *
 * <p>全部来自车辆总线上的信号，{@link Telemetry} 一直在读的那一份（熄屏判断也是靠它，
 * 所以停车、熄屏、录像三件事同时成立的时候它也是热的）：</p>
 *
 * <ul>
 *   <li><b>双闪亮起</b>（{@code INDICATOR == 3}）：车在报警时会闪灯。</li>
 *   <li><b>远光闪一下</b>（{@code HIGH_BEAM_FLASH} 上升沿）。</li>
 *   <li><b>任何一个车门 / 前备箱 / 后备箱从关到开</b>。</li>
 *   <li><b>车灯自亮</b>（日行灯 / 前后位置灯 / 近光任一从灭到亮）：车被叫醒、解锁或报警时会亮灯。</li>
 *   <li><b>中控屏亮起</b>（{@code SCREEN_POWER_EVENT} 从黑到亮）：哨兵模式被触发时屏幕会亮，车主回来时也会。</li>
 * </ul>
 *
 * <p>这台车上<b>没有</b>撞击、气囊、喇叭、防盗触发的信号（{@code Signal} 里就没有，
 * 见 {@code docs/sentry-alerts-spec.md}）。所以「有人蹭了一下车但没碰车门」看不见 ——
 * 这一版靠的是车自己会做的动作；像素级的移动检测是下一步，不在这一个改动里。</p>
 *
 * <h3>什么时候不发</h3>
 *
 * <p>规则全在 {@link AlertPolicy}：要停着、要哨兵模式开着、一条之后静 60 秒、每小时最多 6 条。
 * 判断是纯函数，有单测；这里只负责把车上的事实喂给它。</p>
 *
 * <h3>话题名</h3>
 *
 * <p>话题名存在 {@link AppConfig} 的<b>另一个 SharedPreferences 文件</b>里（{@code app_config_secrets}），
 * 不在设置导出、诊断报告、黑匣子里出现 —— 名字就是地址，知道名字的人就能看。
 * 这是这一版唯一一个要当凭据看的东西。</p>
 *
 * Parking alerts: when the car is parked and Sentry mode is on, activity on the car sends one
 * photo from the four cameras, one way, to a private ntfy topic.
 *
 * <h3>What it watches</h3>
 *
 * <p>Everything comes from vehicle-bus signals, the same copy that {@link Telemetry} keeps
 * reading (the screen-off check relies on it too, so it is also warm when parked, screen-off,
 * and recording all hold at once):</p>
 *
 * <ul>
 *   <li><b>Hazard lights on</b> ({@code INDICATOR == 3}): the car flashes its lights when the
 *   alarm goes off.</li>
 *   <li><b>High beam flashes once</b> ({@code HIGH_BEAM_FLASH} rising edge).</li>
 *   <li><b>Any door / frunk / trunk goes from closed to open</b>.</li>
 *   <li><b>Lights come on by themselves</b> (DRL / front or rear position lamps / low beam any
 *   going from off to on): the car lights up when woken, unlocked, or alarmed.</li>
 *   <li><b>Center screen lights up</b> ({@code SCREEN_POWER_EVENT} from dark to on): the screen
 *   turns on when Sentry mode is triggered, and also when the owner returns.</li>
 * </ul>
 *
 * <p>This car has <b>no</b> impact, airbag, horn, or anti-theft trigger signal ({@code Signal}
 * simply has none; see {@code docs/sentry-alerts-spec.md}). So "someone grazed the car but did
 * not touch a door" is invisible; this version relies on actions the car performs itself;
 * pixel-level motion detection is the next step and is not in this change.</p>
 *
 * <h3>When it does not send</h3>
 *
 * <p>The rules are all in {@link AlertPolicy}: must be parked, Sentry mode on, 60 seconds of
 * quiet after one alert, at most 6 per hour. The decision is a pure function with unit tests;
 * this code only feeds it the facts from the car.</p>
 *
 * <h3>Topic</h3>
 *
 * <p>The topic lives in a <b>separate SharedPreferences file</b> of {@link AppConfig}
 * ({@code app_config_secrets}), and does not appear in settings export, the diagnostic report,
 * or the black box: the name is the address, and whoever knows it can read. It is the only
 * credential in this version.</p>
 */
public final class SentryAlerts implements Telemetry.Listener {

    private static final String TAG = "SentryAlerts";

    /**
     * 在 {@link Telemetry} 里登记的名字（谁在用车辆信号）。
     *
     * Name registered with {@link Telemetry} (who is using the vehicle signals).
     */
    private static final String USER = "sentry-alerts";

    private static final String CHANNEL_ID = "alerts_channel";
    private static final int NOTIFICATION_ID = 1002;

    /**
     * 发不出去时，同一句话最少隔这么久才再说一次（本地通知）。
     *
     * When a send fails, wait at least this long before saying the same thing again (local notification).
     */
    private static final long FAILURE_NOTIFY_MS = 10 * 60_000L;


    private static SentryAlerts instance;

    private final Context app;
    private final AppConfig config;
    private final AlertPolicy policy;
    /**
     * 只给 {@link #time()} 用；SimpleDateFormat 不是线程安全的，发送那条线程在主线程之外调。
     *
     * Only used by {@link #time()}; SimpleDateFormat is not thread-safe, and the send thread calls from off the main thread.
     */
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

    private boolean watching;
    private Boolean lastHazard;
    private Boolean lastFlash;
    private Boolean lastDoorDriver;
    private Boolean lastDoorPassenger;
    private Boolean lastDoorRearLeft;
    private Boolean lastDoorRearRight;
    private Boolean lastDoorFrunk;
    private Boolean lastDoorTrunk;
    private Boolean lastLamps;
    private Boolean lastScreenOn;
    private volatile long failureNotifiedAt;
    /**
     * 上一次「发不出去」的原因（{@code 出口:原因}）。同一个原因只闹一次，换了原因才再闹。
     *
     * The last reason nothing could be sent ({@code channel:kind}). One noise per reason, and again
     * only when the reason changes.
     */
    private volatile String lastBlockedState;

    /**
     * 画面里的移动：编码器把小图送到这里，这里只管算不算一条警报。
     *
     * Motion in the frame: the encoder sends the small image here, and this code only decides whether it counts as an alert.
     */
    private final MotionTap motion = MotionTap.get();
    /**
     * 车主选中的触发项（{@link TriggerSet}）。
     *
     * The triggers the owner has selected ({@link TriggerSet}).
     */
    private TriggerSet triggers = TriggerSet.parse(null);

    /**
     * 发照片、发文字都排这一条线程：一条一条来，不并发，也不为每条消息各起一个没人管的线程。
     *
     * Sending photos and text both queue on this one thread: one at a time, no concurrency,
     * and no unmanaged thread per message.
     */
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "telegram-send");
                thread.setDaemon(true);
                return thread;
            });


    /**
     * 现在有没有在取画面移动的样（只用来记一次“开始 / 停下”）。
     *
     * Whether motion samples are being taken now (only used to log a "started / stopped").
     */
    private boolean sampling;
    /**
     * 诊断：上一次把“看到的信号”写进黑匣子是什么时候（限流）。
     *
     * Diagnostics: when the "signals seen" were last written to the black box (rate limited).
     */
    private long lastWatchNoteAt;
    /**
     * 诊断：上一次写“还在看着”心跳的时候。
     *
     * Diagnostics: when the "still watching" heartbeat was last written.
     */
    private long lastHeartbeatAt;
    /**
     * 上一次看到的“停着且布防”。
     *
     * The "parked and armed" last seen.
     */
    private boolean lastArmedForWatch;
    /**
     * 上一次读到的哨兵原值（锁上车后总线寝下去时还能用它判）。
     *
     * The last raw sentry value read (still usable for the check when the bus sleeps after locking).
     */
    private Integer lastSentryReading;
    /**
     * 这一次布防是从什么时候开始的（elapsedRealtime）；-1 = 不知道。
     *
     * When this arming started (elapsedRealtime); -1 = unknown.
     */
    private long armedAtMs = -1;
    /**
     * 上一次看到的“已布防”；null = 还没看到过。
     *
     * The "armed" last seen; null = not seen yet.
     */
    private Boolean lastArmed;

    /**
     * 现在几点（{@code HH:mm}）。加锁是因为警报在主线程、发送在另一条线程，而 SimpleDateFormat 不是线程安全的。
     *
     * Current time ({@code HH:mm}). Locked because alerts run on the main thread and sends run on another thread, while SimpleDateFormat is not thread-safe.
     */
    private String time() {
        synchronized (clock) {
            return clock.format(new Date());
        }
    }

    private SentryAlerts(Context context) {
        this.app = context.getApplicationContext();
        // 编码器把分数从它自己的 GL 线程送上来（MotionTap 是直接调的）。判断挪到主线程做：
        // 这样 AlertPolicy 和上面那些 last* 字段只有一条线程写。拍照、压缩、发送本来
        // 就不在这儿，它们在 {@link #sender} 那条线程上。
        motion.setListener(score -> app.getMainExecutor().execute(() -> onMotion(score)));
        this.config = new AppConfig(this.app);
        this.policy = new AlertPolicy(config.isAlertsEnabled());
    }

    public static SentryAlerts get(Context context) {
        synchronized (SentryAlerts.class) {
            if (instance == null) {
                instance = new SentryAlerts(context);
            }
            return instance;
        }
    }

    /**
     * 照设置把这一套接上或者摘掉。进程起来时调一次，设置里改过之后再调一次。
     *
     * <p>关着的时候什么都不登记 —— 不读信号、不占车辆接口。</p>
     *
     * Hook this system up or tear it down according to settings. Called once at process start,
     * and again after settings change.
     *
     * <p>When off, nothing is registered; no signals read, no vehicle interface held.</p>
     */
    public void refresh() {
        triggers = TriggerSet.parse(config.getAlertsTriggers());
        motion.setSensitivity(config.getAlertsMotionSensitivity());
        boolean enabled = config.isAlertsEnabled();
        policy.setEnabled(enabled);
        policy.setConfigured(config.hasAlertsNtfyConfig());
        boolean want = enabled && config.hasAlertsNtfyConfig();
        if (want == watching) {
            return;
        }
        watching = want;
        // 每个进程一行：开关、话题到底有没有。
        // “什么都没发生”要能从这一行看出是哪一项缺着。
        // One line per process: whether the switch and the topic are actually present.
        // "Nothing happened" must be readable from this line as to which item is missing.
        BlackBox.noteImportant("alerts: enabled=" + enabled
                + " 话题=" + (config.getAlertsNtfyTopic().isEmpty() ? "无" : "有")
                + " 监听=" + want);
        Telemetry telemetry = Telemetry.get();
        if (want) {
            Readings now = telemetry.readings();
            seedEdges(now);
            telemetry.addListener(this);
            telemetry.acquire(app, USER);
            BlackBox.note("alerts: watching hazard, high beam, doors, lamps, screen");
        } else {
            telemetry.removeListener(this);
            telemetry.release(USER);
            lastHazard = null;
            lastFlash = null;
            lastLamps = null;
            lastScreenOn = null;
            seedEdges(Readings.empty());
            // 关掉时把取样也停了：下面那段取样逻辑在“不在监听”时直接 return，
            // 不会再把 wanted 置假 —— 编码器就会一直往离屏缓存里画。
            // Also stop sampling when turning off: the sampling logic below returns directly when
            // "not listening" and would never set wanted to false again; so the encoder would
            // keep drawing into the offscreen buffer.
            sampling = false;
            motion.setWanted(false);
            motion.forget();
            BlackBox.note("alerts: no longer watching car signals");
        }
    }

    /**
     * 设置里刚改过话题 / 服务器那一类：判断要重算，但没必要重挂监听。
     *
     * The topic or the server just changed in settings: the check must be recomputed, but no need to re-register the listener.
     */
    public void refreshConfig() {
        triggers = TriggerSet.parse(config.getAlertsTriggers());
        motion.setSensitivity(config.getAlertsMotionSensitivity());
        policy.setEnabled(config.isAlertsEnabled());
        boolean configured = config.hasAlertsNtfyConfig();
        policy.setConfigured(configured);
        if (!configured && watching) {
            refresh();
        } else if (configured && !watching) {
            refresh();
        }
    }

    



    // ------------------------------------------------------------------ 设置里的两个动作
    // ------------------------------------------------------------------ the two settings actions



    /**
     * 一份给人看的 JSON：开关、监听、取样、车身状态，加上黑匣子的最后一段。
     *
     * <p>话题名不进这份报告：名字就是地址，知道名字的人就能看。</p>
     *
     * A human-readable JSON: switch, listening, sampling, vehicle state, plus the last
     * section of the black box.
     *
     * <p>The topic does not go into this report: the name is the address, and whoever knows it can read.
     */
    public String debugReport() {
        org.json.JSONObject root = new org.json.JSONObject();
        try {
            org.json.JSONObject appJson = new org.json.JSONObject();
            appJson.put("version", com.kooo.evcam.update.UpdateFlow.currentVersion(this.app));
            appJson.put("processUptimeS", android.os.SystemClock.elapsedRealtime() / 1000);
            appJson.put("watching", watching);
            appJson.put("sampling", sampling);
            root.put("app", appJson);

            org.json.JSONObject settingsJson = new org.json.JSONObject();
            settingsJson.put("enabled", config.isAlertsEnabled());
            settingsJson.put("triggers", config.getAlertsTriggers());
            settingsJson.put("layout", config.getAlertsLayout());
            settingsJson.put("cameras", config.getAlertsCameras());
            settingsJson.put("straighten", config.isAlertsStraighten());
            settingsJson.put("motionSensitivity", config.getAlertsMotionSensitivity());
            settingsJson.put("topicSet", !config.getAlertsNtfyTopic().isEmpty());
            root.put("settings", settingsJson);

            org.json.JSONObject motionJson = new org.json.JSONObject();
            motionJson.put("samples", this.motion.samples());
            motionJson.put("lastScore", round(this.motion.lastScore()));
            motionJson.put("threshold", round(this.motion.threshold()));
            motionJson.put("lastSampleAgoS", this.motion.lastSampleWallClock() == 0 ? -1
                    : (System.currentTimeMillis() - this.motion.lastSampleWallClock()) / 1000);
            root.put("motion", motionJson);

            org.json.JSONObject carJson = new org.json.JSONObject();
            VehicleState state = Telemetry.get().latest();
            Readings now = Telemetry.get().readings();
            carJson.put("sentryRaw", state == null || state.sentry == null ? "null" : state.sentry);
            carJson.put("sentryLastSeen", lastSentryReading == null ? "null" : lastSentryReading);
            carJson.put("armed", SentryFacts.armed(state == null ? null
                    : (state.sentry != null ? state.sentry : lastSentryReading)));
            carJson.put("gear", state == null ? "null" : String.valueOf(state.gear));
            carJson.put("speedKmh", state == null || state.speedKmh == null ? "null" : state.speedKmh);
            carJson.put("parked", parkedNow(state));
            carJson.put("hazard", String.valueOf(hazardOn(now)));
            carJson.put("lamps", String.valueOf(lampsOn(now)));
            carJson.put("screenLit", String.valueOf(screenLit(now)));
            carJson.put("carLink", Telemetry.get().carLinkConnected());
            root.put("car", carJson);
        } catch (org.json.JSONException e) {
            return "{\"error\":\"could not build report\"}";
        }
        String json;
        try {
            json = root.toString(2);
        } catch (org.json.JSONException e) {
            json = root.toString();
        }
        return json + "\n\n--- recent log ---\n"
                + com.kooo.evcam.blackbox.BlackBox.export(this.app, 8000);
    }

    private static String maskTail(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= 4 ? "…" : "…" + value.substring(value.length() - 4);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }


    // ------------------------------------------------------------------ 信号
    // ------------------------------------------------------------------ signals

    /**
     * 每十分钟说一句“还在看着”。
     *
     * <p>诊断报告里有没有这一行，直接回答一个问题：警报那一刻这个进程到底在不在。</p>
     *
     * Say "still watching" every ten minutes.
     *
     * <p>Whether this line is in the diagnostic report directly answers one question: was this
     * process actually alive at the moment of the alert.</p>
     */
    private void heartbeatIfDue() {
        if (!watching) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastHeartbeatAt != 0 && now - lastHeartbeatAt < 10 * 60_000L) {
            return;
        }
        lastHeartbeatAt = now;
        VehicleState state = Telemetry.get().latest();
        BlackBox.note("alerts: alive (sentry " + (state == null ? "?" : state.sentry)
                + "，挡位 " + (state == null || state.gear == null ? "?" : state.gear) + "）");
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        if (!watching) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Telemetry telemetry = Telemetry.get();
        VehicleState state = telemetry.latest();
        boolean parked = parkedNow(state);
        Integer sentry = state == null ? null : state.sentry;
        if (sentry != null) {
            lastSentryReading = sentry;
        }
        // 「布防 = 锁着」：解锁那一下哨兵就 2 → 1 了，车主回来之后的亮屏、亮灯都发不出去（SentryFacts）。
        // 锁上车之后总线可能寝下去、哨兵读成 null：那时用上一次真实读到的值，
        // 否则“锁着、哨兵开着”这个最该发警报的场景反而永远不发。
        // "Armed = locked": the moment of unlocking the sentry goes 2 → 1, so the screen-on and
        // lights-on after the owner returns would never send (SentryFacts).
        // After locking the bus may sleep and the sentry reads as null: then use the last real
        // value read, otherwise "locked and Sentry on"; the scenario that most deserves an
        // alert; would never fire.
        boolean armed = SentryFacts.armed(sentry != null ? sentry : lastSentryReading);
        policy.setParked(parked);
        policy.setArmed(armed);
        lastArmedForWatch = armed && parked;
        if (Boolean.TRUE.equals(lastArmed) && !armed) {
            armedAtMs = -1;
        } else if (!armed) {
            armedAtMs = -1;
        } else if (lastArmed == null) {
            // 进程团刚起来，车本来就锁着：不知道锁了多久。看信号变化的记录（进程内没变过就是 -1）
            // The process just came up and the car was already locked: unknown how long. Look at the
            // signal-change record (-1 if unchanged within this process)
            armedAtMs = com.kooo.evcam.telemetry.SignalAges.ageMs(
                    com.kooo.evcam.telemetry.Signal.SENTRY_MODE, SystemClock.elapsedRealtime()) >= 0
                    ? SystemClock.elapsedRealtime() - com.kooo.evcam.telemetry.SignalAges.ageMs(
                            com.kooo.evcam.telemetry.Signal.SENTRY_MODE, SystemClock.elapsedRealtime())
                    : -1;
        } else if (!lastArmed && armed) {
            armedAtMs = SystemClock.elapsedRealtime();
        }
        lastArmed = armed;

        Boolean hazard = hazardOn(readings);
        boolean hazardRising = SentryFacts.rising(hazard, lastHazard);
        Boolean flash = readings.bool(Signal.HIGH_BEAM_FLASH);
        boolean flashRising = SentryFacts.rising(flash, lastFlash);
        boolean doorOpening = doorOpened(readings);
        Boolean lamps = lampsOn(readings);
        boolean lampsRising = SentryFacts.rising(lamps, lastLamps);
        Boolean screenOn = screenLit(readings);
        boolean screenRising = SentryFacts.rising(screenOn, lastScreenOn);

        heartbeatIfDue();

        // 取样的条件比“发警报”宽一点：停着、哨兵开着就取。
        // 为什么不要求已布防：车主拿着钥匙走过去时车会解锁，哨兵从 2 退到 1，
        // 要求布防就会把“正好有人走过”这一刻也关掉，根本验不了。
        // 发不发警报仍然由 {@link AlertPolicy}（要布防）决定。
        // The sampling condition is slightly looser than "send an alert": sample whenever parked
        // and sentry is on.
        // Why not require armed: as the owner walks over with the key, the car unlocks and the
        // sentry drops from 2 to 1, so requiring armed would also turn off the exact moment
        // "someone is walking by", and it could not be validated at all.
        // Whether an alert fires is still decided by {@link AlertPolicy} (which requires armed).
        boolean wantSample = watching && parked && triggers.has(TriggerSet.MOTION);
        if (wantSample != sampling) {
            sampling = wantSample;
            if (wantSample) {
                BlackBox.note("alerts: motion sampling on");
            } else {
                BlackBox.note("alerts: motion sampling off ("
                        + (!watching ? "警报未开" : !parked ? "车在走"
                        : "移动不在勾选里") + "）");
            }
            motion.setWanted(wantSample);
        }

        // 诊断：车上真正响了一次、而我们没发消息时，要能从诊断报告里看出
        // “那一刻哪些信号动了”。只在假车、布防时记，每 3 秒最多一行。
        // Diagnostics: when the car really triggered once and we sent no message, the diagnostic
        // report must show "which signals moved at that moment". Only logged while parked and
        // armed, at most one line every 3 seconds.
        watchSignals(hazard, flash, lamps, screenOn);

        lastHazard = hazard;
        lastFlash = flash;
        lastLamps = lamps;
        lastScreenOn = screenOn;
        rememberDoors(readings);

        hazardRising = hazardRising && triggers.has(TriggerSet.HAZARD);
        flashRising = flashRising && triggers.has(TriggerSet.FLASH);
        doorOpening = doorOpening && triggers.has(TriggerSet.DOORS);
        lampsRising = lampsRising && triggers.has(TriggerSet.LAMPS);
        screenRising = screenRising && triggers.has(TriggerSet.SCREEN);

        if (!hazardRising && !flashRising && !doorOpening && !lampsRising && !screenRising) {
            return;
        }
        String headline;
        if (doorOpening) {
            headline = app.getString(R.string.alert_headline_door);
        } else if (hazardRising) {
            headline = app.getString(R.string.alert_headline_hazard);
        } else if (flashRising) {
            headline = app.getString(R.string.alert_headline_flash);
        } else if (lampsRising) {
            headline = app.getString(R.string.alert_headline_lights);
        } else {
            headline = app.getString(R.string.alert_headline_screen);
        }

        if (!armDelayPassed()) {
            return;
        }
        if (quietNow()) {
            BlackBox.note("alerts: " + headline + " seen, quiet hours");
            return;
        }
        AlertPolicy.Decision decision = policy.reserve(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("alerts: " + headline + " seen, not sent (" + decision + ")");
            return;
        }
        BlackBox.note("alerts: " + headline);
        // 判断这一步就把这一格占掉（D-03）：冷却与每小时上限当场算。发不出去再补一次失败退避。
        // The decision takes the slot right here (D-03): the cooldown and the hourly cap count at
        // once. A send that fails then adds the failure backoff.
        fire(headline, this::noteSendFailure);
    }

    /**
     * 假车、布防时，把“看到的信号”写一行进黑匣子。
     *
     * <p>这不是警报，是证据：车自己响了、我们却没发消息时，诊断报告里就能看出
     * 当时哪一项真的变了、哪一项根本没动。没变化就不写。</p>
     *
     * When parked and armed, write one line of "signals seen" into the black box.
     *
     * <p>This is not an alert, it is evidence: when the car triggered on its own and we sent no
     * message, the diagnostic report can show which item really changed and which never moved.
     * Nothing is written when nothing changed.</p>
     */
    private void watchSignals(Boolean hazard, Boolean flash, Boolean lamps, Boolean screenOn) {
        if (!watching || !lastArmedForWatch) {
            return;
        }
        StringBuilder changed = new StringBuilder();
        appendChange(changed, "hazard", lastHazard, hazard);
        appendChange(changed, "flash", lastFlash, flash);
        appendChange(changed, "lamps", lastLamps, lamps);
        appendChange(changed, "screen", lastScreenOn, screenOn);
        if (changed.length() == 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastWatchNoteAt != 0 && now - lastWatchNoteAt < 3_000L) {
            return;
        }
        lastWatchNoteAt = now;
        BlackBox.note("alerts: signal change " + changed);
    }

    private static void appendChange(StringBuilder out, String name, Boolean before, Boolean after) {
        if (after == null || after.equals(before)) {
            return;
        }
        if (out.length() > 0) {
            out.append('、');
        }
        out.append(name).append(after ? "=on" : "=off");
    }

    /**
     * 现在是不是安静时段。
     *
     * <p>车停在家里、小区里的夜晚，有人走过、猫踏过都会响；那不是车主要知道的事。
     * 发不发由 {@link QuietHours} 算（跨午夜也算得对）。</p>
     *
     * Whether it is currently a quiet period.
     *
     * <p>At night, with the car parked at home or in the compound, a person walking by or a cat
     * stepping on it would all trigger; that is not something the owner needs to know. Whether
     * to send is computed by {@link QuietHours} (it handles midnight crossing correctly).</p>
     */
    /**
     * 锁车之后的“走开”时间。
     *
     * <p>锁车、关车门、往外走那一分钟，车会亮灯、屏幕会亮、画面里一直有人在动 ——
     * 都是车主自己。等一下再开始报，就不会收到一张自己的照片。</p>
     *
     * The "walk-away" time after locking.
     *
     * <p>In the minute after locking, closing the door, and walking off, the car lights up, the
     * screen turns on, and someone is moving in the frame the whole time; all the owner
     * himself. Wait a bit before reporting, so he does not receive a photo of himself.</p>
     */
    private boolean armDelayPassed() {
        int wait = config.getAlertsArmDelayMinutes();
        if (wait == 0) {
            return true;
        }
        long since = armedAtMs < 0 ? -1 : SystemClock.elapsedRealtime() - armedAtMs;
        if (ArmDelay.allows(wait, since)) {
            return true;
        }
        BlackBox.note("alerts: armed " + (since / 1000) + " s ago, waiting for " + wait + " min");
        return false;
    }

    private boolean quietNow() {
        if (!config.isAlertsQuietEnabled()) {
            return false;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        return QuietHours.active(true, config.getAlertsQuietStart(), config.getAlertsQuietEnd(), minute);
    }

    /**
     * 画面里有东西在动（编码器线程送来的分数）。
     *
     * <p>分数已经过了 {@link FrameDiff} 的三道过滤；这里只看车是不是该报。</p>
     *
     * Something is moving in the frame (score sent from the encoder thread).
     *
     * <p>The score has already passed {@link FrameDiff}'s three filters; here we only check
     * whether the car should report.</p>
     */
    private void onMotion(double score) {
        if (!watching || !triggers.has(TriggerSet.MOTION)
                || !FrameDiff.isMovement(score, motion.threshold())) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        VehicleState state = Telemetry.get().latest();
        Integer sentry = state == null ? null : state.sentry;
        policy.setParked(parkedNow(state));
        policy.setArmed(SentryFacts.armed(sentry != null ? sentry : lastSentryReading));
        String headline = app.getString(R.string.alert_headline_motion);
        if (!armDelayPassed() || quietNow()) {
            BlackBox.note("alerts: motion seen, quiet hours");
            return;
        }
        AlertPolicy.Decision decision = policy.reserve(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("alerts: motion seen, not sent (" + decision + ")");
            return;
        }
        BlackBox.note("alerts: " + headline + " (" + Math.round(score * 100) + "%)");
        fire(headline, this::noteSendFailure);
    }

    /**
     * 双闪亮没亮：转向指示状态是 3（双闪）；没数据为 null。
     *
     * Whether the hazard lights are on: turn-indicator state is 3 (hazard); null when no data.
     */
    private static Boolean hazardOn(Readings readings) {
        Integer indicator = readings.code(Signal.INDICATOR);
        return indicator == null ? null : indicator == 3;
    }

    /**
     * 车外有没有灯自己亮起来（{@link SentryFacts#lampsOn}）。
     *
     * Whether any exterior light has come on by itself ({@link SentryFacts#lampsOn}).
     */
    private static Boolean lampsOn(Readings readings) {
        return SentryFacts.lampsOn(readings.bool(Signal.DRL),
                readings.bool(Signal.FRONT_POSITION_LAMP),
                readings.bool(Signal.REAR_POSITION_LAMP),
                readings.bool(Signal.LOW_BEAM));
    }

    /**
     * 中控屏亮着没有（{@link SentryFacts#screenLit}）。
     *
     * Whether the center screen is lit ({@link SentryFacts#screenLit}).
     */
    private static Boolean screenLit(Readings readings) {
        return SentryFacts.screenLit(readings);
    }

    /**
     * 六个门里有没有一个刚从关到开。
     *
     * Whether any of the six doors has just gone from closed to open.
     */
    private boolean doorOpened(Readings readings) {
        return opened(readings.bool(Signal.DOOR_DRIVER), lastDoorDriver)
                || opened(readings.bool(Signal.DOOR_PASSENGER), lastDoorPassenger)
                || opened(readings.bool(Signal.DOOR_REAR_LEFT), lastDoorRearLeft)
                || opened(readings.bool(Signal.DOOR_REAR_RIGHT), lastDoorRearRight)
                || opened(readings.bool(Signal.DOOR_FRUNK), lastDoorFrunk)
                || opened(readings.bool(Signal.DOOR_TRUNK), lastDoorTrunk);
    }

    private static boolean opened(Boolean now, Boolean before) {
        return Boolean.TRUE.equals(now) && !Boolean.TRUE.equals(before);
    }

    private void rememberDoors(Readings readings) {
        lastDoorDriver = readings.bool(Signal.DOOR_DRIVER);
        lastDoorPassenger = readings.bool(Signal.DOOR_PASSENGER);
        lastDoorRearLeft = readings.bool(Signal.DOOR_REAR_LEFT);
        lastDoorRearRight = readings.bool(Signal.DOOR_REAR_RIGHT);
        lastDoorFrunk = readings.bool(Signal.DOOR_FRUNK);
        lastDoorTrunk = readings.bool(Signal.DOOR_TRUNK);
    }

    /**
     * 从当前读数起算：一直开着的门、一直亮着的双闪都不算「刚发生」。
     *
     * Seeded from the current readings: a door that was already open, or hazard lights already on, do not count as "just happened".
     */
    private void seedEdges(Readings readings) {
        lastHazard = hazardOn(readings);
        lastFlash = readings.bool(Signal.HIGH_BEAM_FLASH);
        lastLamps = lampsOn(readings);
        lastScreenOn = screenLit(readings);
        rememberDoors(readings);
    }

    // ------------------------------------------------------------------ 拍照与发送
    // ------------------------------------------------------------------ photo and send

    private void fire(String headline, Runnable onSendFailure) {
        AlertChannel channel = channel();
        if (!channel.ready()) {
            blockedChannel(channel);
            return;
        }
        String caption = caption(headline, sentryLine(sentryValue()), time());
        if (!config.isAlertsPictureEnabled()) {
            // 只发文字：连相机都不去碰（不拍、不拼、不压），车主自己设的这一档。
            // Text only: the camera is never touched — no capture, no stitch, no squeeze. That is the
            // setting the owner chose.
            BlackBox.note("alerts: text only, pictures off in settings");
            deliverText(channel, caption, onSendFailure);
            return;
        }
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(channel, grid, caption, onSendFailure);
            }

            @Override
            public void onFailed(String reason) {
                // 照片没拍成，文字照样发：车主至少要知道「刚才有人碰了车」。
                // Photo not taken, still send text: the owner should at least know "someone just touched the car".
                BlackBox.note("alerts: no picture (" + reason + "), text only");
                notifyFailure(app.getString(R.string.alert_no_storage));
                deliverText(channel, caption + "\n" + app.getString(R.string.alert_no_storage),
                        onSendFailure);
            }
        });
    }

    /**
     * 警报这一步发不出去：记下这次失败，接下来的五分钟不再为同一个会话重试（D-12）。
     *
     * An alert step failed to send: record the failure, and stop retrying the same chat for the
     * next five minutes (D-12).
     */
    private void noteSendFailure() {
        policy.noteFailure(SystemClock.elapsedRealtime());
    }

    /**
     * 现在该走哪条出口。判断本身在 {@link AlertChannels}：那是全应用唯一认得出具体出口的名字的地方，
     * 也是 {@code ProviderWiringTest} 钉住的那一处。
     *
     * Which exit to use now. The decision itself is {@link AlertChannels}, the one place in the app that
     * knows concrete exit names and the one {@code ProviderWiringTest} pins.
     */
    private AlertChannel channel() {
        return AlertChannels.of(config);
    }

    /**
     * 发不出去：黑匣子记一行，车主那边闹一次。同一个原因只闹一次。
     *
     * <p>宁可吵一下，也不能让车主以为车在盯着而其实一条都没发。</p>
     *
     * Nothing could be sent: one blackbox line, one local notification, once per reason — noise is
     * cheaper than an owner believing the car is watched when nothing is leaving it.
     */
    private void blockedChannel(AlertChannel channel) {
        AlertChannel.Kind why = channel.blockedReason();
        String state = channel.name() + ":" + why;
        BlackBox.note("alerts: dropped (" + state + ")");
        if (state.equals(lastBlockedState)) {
            return;
        }
        lastBlockedState = state;
        BlackBox.noteImportant("alerts: not sending (" + state + ")");
        notifyFailure(blockedText(why));
    }

    /** 发不出去的原因，翻成一句话。The reason, as one sentence. */
    private String blockedText(AlertChannel.Kind why) {
        if (why == AlertChannel.Kind.NOT_ACKNOWLEDGED) {
            return app.getString(R.string.alert_ntfy_not_acknowledged);
        }
        return app.getString(R.string.alert_ntfy_no_topic);
    }

    /**
     * 把这一趟的图画出来、发出去。
     *
     * <p>一张 2×2，或者按勾选的那几路各一张；要不要先掰直。三件事都由 {@link AlertPicture} 决定，
     * 这里只负责把字节一张张按顺序发出去，然后是回收位图 —— 这条路一晚上可能走好几次，不能漏内存。</p>
     *
     * Draw this run's picture(s) and send them.
     *
     * <p>One 2×2, or one per selected lane; whether to straighten first. All three are decided by
     * {@link AlertPicture}, and this code only sends the bytes one at a time in order, then
     * recycles bitmaps; this path can run several times a night and must not leak memory.</p>
     */
    private void deliver(AlertChannel channel, Bitmap grid, String caption,
                          Runnable onSendFailure) {
        sender.execute(() -> {
            // 掰直、切图、压缩都在这一条线程上：一张 2×2 的校正要翻一百六十万像素，
            // 放在主线程上就是和相机、和设置界面抢那几十毫秒。
            // Straighten, cut, and compress all run on this one thread: correcting one 2×2 touches
            // 1.6 million pixels, and doing it on the main thread would contend with the camera and
            // the settings screen for those few tens of milliseconds.
            Bitmap picture = AlertPicture.straighten(grid, config.isAlertsStraighten(),
                    fisheyeFov(), fisheyeProjection());
            // 时刻印在画面上（车主 2026-11-15）：那张图以后被翻出来时，自己带着是哪一刻。
            // The moment is drawn onto the picture: whenever it is looked at again, it carries when it was.
            if (picture != null) {
                Bitmap stamped = AlertPicture.stamped(picture, isoNow());
                if (stamped != null && stamped != picture) {
                    if (picture != grid && !picture.isRecycled()) {
                        picture.recycle();
                    }
                    picture = stamped;
                }
            }
            List<byte[]> photos = new ArrayList<>();
            List<String> captions = new ArrayList<>();
            try {
                int[] lanes = AlertPicture.layout(config.getAlertsLayout()) == AlertPicture.Layout.SEPARATE
                        ? AlertPicture.lanes(config.getAlertsCameras()) : null;
                if (lanes != null) {
                    for (int lane : lanes) {
                        Bitmap cell = AlertPicture.quadrant(picture, lane);
                        if (cell == null) {
                            continue;
                        }
                        byte[] jpeg = AlertPicture.jpegUnder(cell, PictureBudget.ALERT_BYTES);
                        cell.recycle();
                        if (jpeg != null && jpeg.length > 0) {
                            photos.add(jpeg);
                            captions.add(CameraNames.ofLane(app, lane) + "\n" + caption);
                        }
                    }
                } else {
                    byte[] jpeg = AlertPicture.jpegUnder(picture, PictureBudget.ALERT_BYTES);
                    if (jpeg != null && jpeg.length > 0) {
                        photos.add(jpeg);
                        captions.add(caption);
                    }
                }
            } finally {
                if (picture != null && picture != grid) {
                    picture.recycle();
                }
                if (grid != null && !grid.isRecycled()) {
                    grid.recycle();
                }
            }

            if (photos.isEmpty()) {
                BlackBox.note("alerts: no picture came out of the capture");
                notifyFailure(app.getString(R.string.set_alerts_failed, "no picture"));
                if (onSendFailure != null) {
                    onSendFailure.run();
                }
                return;
            }
            // 开工之后设置可能已经关掉、哨兵也可能刚退出：那就不发了，别送出一张没人要的照片。
            // 开关刚关、布防刚退，说明这一张已经没人要了。
            // After starting, settings may have been turned off or sentry may have just exited: a
            // switch just turned off means nobody wants this photo any more.
            if (!config.isAlertsEnabled() || !sentryOn()) {
                BlackBox.note("alerts: dropped (feature off or sentry gone)");
                return;
            }

            int biggestPhoto = 0;
            AlertChannel.Result last = AlertChannel.Result.fail(
                    AlertChannel.Kind.NETWORK, 0, "nothing sent");
            for (int i = 0; i < photos.size(); i++) {
                last = channel.sendPhoto(photos.get(i), captions.get(i));
                if (!last.ok) {
                    break;
                }
                biggestPhoto = Math.max(biggestPhoto, photos.get(i).length);
            }
            final int photoBytes = biggestPhoto;
            final AlertChannel.Result outcome = last;
            if (!outcome.ok) {
                BlackBox.note("alerts: send failed via " + channel.name() + " ("
                        + outcome.kind + " " + outcome.display() + ")");
                notifyFailure(failureText(outcome));
                // 发不出去才知道那一格没兑现：记一次退避（D-12）。
                // Only now, on a failure, is it known the slot was not delivered: record the
                // backoff (D-12).
                if (onSendFailure != null) {
                    onSendFailure.run();
                }
            }
        });
    }

    /**
     * 配文：标题一行、时刻一行、哨兵状态一行。空的那一行不占地方。
     *
     * The caption: headline, moment, sentry state, one line each; empty lines take no room.
     */
    private static String caption(String headline, String sentry, String time) {
        StringBuilder out = new StringBuilder();
        out.append(headline == null ? "" : headline.trim());
        if (time != null && !time.trim().isEmpty()) {
            out.append('\n').append(time.trim());
        }
        if (sentry != null && !sentry.trim().isEmpty()) {
            out.append('\n').append(sentry.trim());
        }
        return out.toString();
    }

    /**
     * 盖在图上的那一刻（ISO）。那一刻的时间戳跟着图走，图转手几道也还在。
     *
     * The moment drawn onto the picture (ISO). The timestamp travels with the picture, whatever
     * hands it passes through.
     */
    private static String isoNow() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    .format(new Date());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 掰直用的视野角度：和回看界面那一项同一个值。
     *
     * Field-of-view angle used for straightening: the same value as the review screen item.
     */
    private float fisheyeFov() {
        return config.getFisheyeFov();
    }

    /**
     * 掰直用的投影方式：同上。
     *
     * Projection used for straightening: same as above.
     */
    private String fisheyeProjection() {
        return config.getFisheyeProjection();
    }

    /**
     * 没有照片时只发一句话。
     *
     * When there is no photo, send only one line.
     */
    private void deliverText(AlertChannel channel, String text) {
        deliverText(channel, text, null);
    }

    /**
     * 同上，但带上一句「发失败了怎么补」：警报那条路在这里记失败退避（D-12）。
     *
     * Same, plus what to do when it fails: the alert path records the failure backoff here (D-12).
     */
    private void deliverText(AlertChannel channel, String text, Runnable onSendFailure) {
        sender.execute(() -> {
            AlertChannel.Result result = channel.sendText(text);
            if (!result.ok) {
                BlackBox.note("alerts: text send failed via " + channel.name() + " ("
                        + result.kind + " " + result.display() + ")");
                notifyFailure(failureText(result));
                if (onSendFailure != null) {
                    onSendFailure.run();
                }
            }
        });
    }

    /**
     * 一次失败的说明：先说「哪一步不对」，再说细节。
     *
     * <p>「什么没配好」「图太大」「免费方案的提醒没接受」是三件不同的事，车主能做的也是三件不同的事。</p>
     *
     * The explanation for one failure: what is wrong, then the detail. "Not set up", "picture too
     * large" and "free-plan warning not accepted" are three things the owner fixes three ways.
     */
    private String failureText(AlertChannel.Result result) {
        switch (result.kind) {
            case NOT_CONFIGURED:
                return app.getString(R.string.set_alerts_ntfy_no_topic);
            case NOT_ACKNOWLEDGED:
                return app.getString(R.string.alert_ntfy_not_acknowledged);
            case REJECTED:
                return app.getString(R.string.set_alerts_ntfy_rejected);
            case TOO_LARGE:
                return app.getString(R.string.set_alerts_ntfy_too_big);
            case RATE_LIMITED:
                return app.getString(R.string.set_alerts_ntfy_rate);
            default:
                return app.getString(R.string.set_alerts_failed, result.display());
        }
    }

    private Integer sentryValue() {
        VehicleState state = Telemetry.get().latest();
        return state == null ? null : state.sentry;
    }

    /**
     * 车现在停着吗。
     *
     * <p>不能只认挡位是 "P"：车锁上、车机就寝时，挡位读不到（null），
     * 而那正是哨兵要发挥作用的时候。认 null 为“没在开车” = 停着，
     * 跟录像那边判“在开车”用的是同一条规则。</p>
     *
     * Is the car parked now.
     *
     * <p>Cannot just accept gear "P": when the car is locked and the head unit asleep, the gear
     * is unreadable (null), and that is exactly when sentry must do its job. Treat null as
     * "not driving" = parked, the same rule the recording side uses to judge "driving".</p>
     */
    private static boolean parkedNow(VehicleState state) {
        if (state == null) {
            return true;
        }
        return !com.kooo.evcam.camera.ChannelRules.driving(state.gear, state.speedKmh);
    }

    /**
     * 哨兵模式开着没有（0 关、1 开、2 布防）。
     *
     * <p>取样和真警报的最后一道判断都看它：哨兵模式是车机保持醒着的唯一原因（平台笔记 §3.6），
     * 关掉之后车机会在几分钟内下电、进程被结束，那一刻之后拍到的也不会有人收。</p>
     *
     * Is Sentry mode on (0 off, 1 on, 2 armed).
     *
     * <p>Sampling and the last gate of a real alert both check it: Sentry mode is the only reason
     * the head unit stays awake (platform notes §3.6). After it is off the head unit powers down
     * within a few minutes and the process is killed, so nothing sent then would be received.</p>
     */
    private boolean sentryOn() {
        Integer sentry = sentryValue();
        return sentry != null && sentry != 0;
    }

    /**
     * 配图里那一行哨兵模式状态：布防 / 开 / 关。
     *
     * The sentry-mode status line in the caption: armed / on / off.
     */
    private String sentryLine(Integer sentry) {
        if (SentryFacts.armed(sentry)) {
            return app.getString(R.string.alert_sentry_armed);
        }
        boolean on = sentry != null && sentry != 0;
        return app.getString(on ? R.string.alert_sentry_on : R.string.alert_sentry_off);
    }

    /**
     * 发不出去时说一声（本地通知）。
     *
     * <p>一条警报发不出去是很要紧的事：车主以为有事会响，结果什么都没有。
     * 但也不能每 60 秒弹一次，所以这里自己再限一次频。</p>
     *
     * Say something when a send fails (local notification).
     *
     * <p>An alert failing to send matters a lot: the owner thinks it will ring when something
     * happens, and then nothing comes. But it also must not pop up every 60 seconds, so here we
     * rate-limit once more ourselves.</p>
     */





    /**
     * 把一个异常式的理由压成一行能读的话。
     *
     * <p>通知栏在车机上就那么一点宽，“java.io.IOException: …”连第一个词都显不完。
     * 原文不丢：它在黑匣子和诊断报告里。</p>
     *
     * Squash an exception-style reason into one readable line.
     *
     * <p>The notification bar on the head unit is only that wide, and
     * "java.io.IOException: …" cannot even show its first word. The original is not lost: it is
     * in the black box and the diagnostic report.</p>
     */
    private static String shortReason(String why) {
        if (why == null || why.isEmpty()) {
            return "";
        }
        String text = why;
        int cut = text.indexOf(':');
        if (cut > 0 && cut < text.length() - 1) {
            text = text.substring(cut + 1);
        }
        text = text.trim();
        return text.length() <= 90 ? text : text.substring(0, 90);
    }

    private void notifyFailure(String why) {
        long now = SystemClock.elapsedRealtime();
        if (failureNotifiedAt != 0 && now - failureNotifiedAt < FAILURE_NOTIFY_MS) {
            return;
        }
        failureNotifiedAt = now;
        try {
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    app.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(app.getString(R.string.alert_channel_desc));
            channel.setShowBadge(true);
            manager.createNotificationChannel(channel);

            PendingIntent open = PendingIntent.getActivity(app, 0,
                    new Intent(app, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_app)
                    .setContentTitle(app.getString(R.string.alert_fail_title))
                    .setContentText(app.getString(R.string.alert_fail_body, shortReason(why)))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(app.getString(R.string.alert_fail_body, shortReason(why))))
                    .setContentIntent(open)
                    .setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException e) {
            AppLog.w(TAG, "could not show failure notification: " + e);
        }
    }
}
