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
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.zeekr.FisheyeProjection;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 停车警报：车停着、哨兵模式开着时，车上的动静把四个摄像头的一张照片发到 Telegram。
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
 * <h3>令牌</h3>
 *
 * <p>令牌存在 {@link AppConfig} 的<b>另一个 SharedPreferences 文件</b>里（{@code app_config_secrets}），
 * 不在设置导出、诊断报告、黑匣子里出现；任何要写下来的字符串先过
 * {@link TelegramRequest#redact(String)}。这是这一版唯一一个用户自己填的凭据。</p>
 *
 * Parking alerts: when the car is parked and Sentry mode is on, activity on the car sends one
 * photo from the four cameras to Telegram.
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
 * <h3>Token</h3>
 *
 * <p>The token lives in a <b>separate SharedPreferences file</b> of {@link AppConfig}
 * ({@code app_config_secrets}), and does not appear in settings export, the diagnostic report,
 * or the black box; any string to be written down goes through
 * {@link TelegramRequest#redact(String)} first. This is the only credential the user enters by
 * hand in this version.</p>
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

    /**
     * 一次警报的结果。{@code message} 已经擦过令牌，可以直接显示。
     *
     * Result of one alert. {@code message} has already had the token redacted, so it can be shown directly.
     */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /**
     * 「找会话号」的结果。
     *
     * Result of "find chat id".
     */
    public interface ChatListCallback {
        void onChats(List<TelegramSender.Chat> chats);

        void onFailed(String message);
    }

    private static SentryAlerts instance;

    private final Context app;
    private final AppConfig config;
    private final AlertPolicy policy;
    /**
     * 只给 {@link #time()} 用；SimpleDateFormat 不是线程安全的，命令那一路在主线程之外调。
     *
     * Only used by {@link #time()}; SimpleDateFormat is not thread-safe, and the command path calls from off the main thread.
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
     * 人从 Telegram 要照片时的频率上限（和警报那一套分开）。
     *
     * Rate limit for photo requests made from Telegram (separate from the alert limits).
     */
    private final RequestPolicy requests = new RequestPolicy();
    /**
     * 发照片、发文字都排这一条线程：一条一条来，不并发（Telegram 有频率限制），
     * 也不为每条消息各起一个没人管的线程。
     *
     * Sending photos and text both queue on this one thread: one at a time, no concurrency
     * (Telegram has rate limits), and no unmanaged thread per message.
     */
    /**
     * 延后一半时长再切短视频用的。和发送那条线程分开，
     * 否则等这几秒会把后面的照片也堵在后面。
     *
     * Used to cut the short video after half its duration has passed. Kept separate from the
     * sending thread, otherwise waiting these seconds would also block the photos behind it.
     */
    private final java.util.concurrent.ScheduledExecutorService clipDelay =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "alert-clip-delay");
                thread.setDaemon(true);
                return thread;
            });

    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "telegram-send");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 去问「有人找我吗」的那条线程；关着时是 null。从主线程之外写，所以 volatile。
     *
     * The thread that asks "does anyone want me?"; null when off. Written from off the main thread, so volatile.
     */
    private volatile TelegramPoller poller;
    /**
     * 开 / 关轮询线程的活在这条线程上做。
     *
     * <p>{@link TelegramPoller#stop()} 要打断并等待那条长轮询起来（最多 3 秒），
     * 而 {@code syncPoller} 是从车辆信号的回调里调的 —— 那是主线程。
     * 诊断里那条“main has not run a queued task for 1006 ms”就是这么来的。</p>
     *
     * Turning the polling thread on and off happens on this thread.
     *
     * <p>{@link TelegramPoller#stop()} must interrupt and wait for the long poll to come up (up to
     * 3 seconds), and {@code syncPoller} is called from a vehicle-signal callback; which is the
     * main thread. That "main has not run a queued task for 1006 ms" line in diagnostics comes
     * from this.</p>
     */
    private final java.util.concurrent.ExecutorService pollerLifecycle =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "alert-poller-lifecycle");
                thread.setDaemon(true);
                return thread;
            });
    /**
     * 上一次要求的状态：不变就不往那条线程上排活。
     *
     * The state last requested: when unchanged, no work is queued onto that thread.
     */
    private volatile boolean pollerWanted;
    /**
     * 上一次看到的「哨兵模式开着」：变了就重看一次要不要轮询。
     *
     * The "Sentry mode on" last seen: when it changes, re-check whether to poll.
     */
    private boolean pollerOn;
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
     * 车睡了一阵才醒过来时，配文里写上这张照片是几点要的。
     *
     * <p>不写的话，一张几小时后才到的照片会让人以为是现在的车周围。</p>
     *
     * When the car wakes up after sleeping a while, the caption notes what time this photo was
     * requested.
     *
     * <p>Without it, a photo that arrives hours later would read as the car's surroundings
     * right now.</p>
     */
    private String lateNote(long requestedAt) {
        if (requestedAt <= 0 || System.currentTimeMillis() - requestedAt < PollerGate.NOTE_AGE_MS) {
            return "";
        }
        return app.getString(R.string.alert_caption_requested,
                new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new Date(requestedAt)));
    }

    /**
     * 现在几点（{@code HH:mm}）。加锁是因为警报在主线程、命令在轮询线程，而 SimpleDateFormat 不是线程安全的。
     *
     * Current time ({@code HH:mm}). Locked because alerts run on the main thread and commands on the polling thread, while SimpleDateFormat is not thread-safe.
     */
    private String time() {
        synchronized (clock) {
            return clock.format(new Date());
        }
    }

    private SentryAlerts(Context context) {
        this.app = context.getApplicationContext();
        motion.setListener(score -> onMotion(score));
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
        policy.setConfigured(config.hasAlertsTelegramConfig());
        boolean want = enabled && config.hasAlertsTelegramConfig();
        if (want == watching) {
            return;
        }
        watching = want;
        // 每个进程一行：开关、令牌、会话号到底有没有。
        // “什么都没发生”要能从这一行看出是哪一项缺着。
        // One line per process: whether the switch, token, and chat id are actually present.
        // "Nothing happened" must be readable from this line as to which item is missing.
        BlackBox.noteImportant("alerts: enabled=" + enabled
                + " 令牌=" + (config.getAlertsTelegramToken().isEmpty() ? "无" : "有")
                + " 会话号=" + (config.getAlertsTelegramChatId().isEmpty() ? "无"
                        : "…" + config.getAlertsTelegramChatId()
                                .substring(Math.max(0, config.getAlertsTelegramChatId().length() - 4)))
                + " 监听=" + want + " 命令=" + config.isAlertsCommands());
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
        syncPoller();
    }

    /**
     * 设置里刚改过令牌 / 会话号：判断要重算，但没必要重挂监听。
     *
     * Token / chat id just changed in settings: the check must be recomputed, but no need to re-register the listener.
     */
    public void refreshConfig() {
        triggers = TriggerSet.parse(config.getAlertsTriggers());
        motion.setSensitivity(config.getAlertsMotionSensitivity());
        policy.setEnabled(config.isAlertsEnabled());
        boolean configured = config.hasAlertsTelegramConfig();
        policy.setConfigured(configured);
        if (!configured && watching) {
            refresh();
        } else if (configured && !watching) {
            refresh();
        }
        syncPoller();
    }

    /**
     * 照设置开或关那条轮询线程。
     *
     * <p>三件事成立就问：开着警报、令牌与会话号都填了、允许回应命令。</p>
     *
     * <p>以前还要一条“哨兵模式开着”，那是错的：锁上车之后车辆总线容易寝下去，
     * 哨兵读数读不到（null），于是轮询根本不起来 —— 现场就是“锁了就不回应，一解锁就好”。
     * 进程还活着、设置也开着，就没有理由不问。</p>
     *
     * Turn the polling thread on or off according to settings.
     *
     * <p>Ask when three things hold: alerts on, token and chat id both filled, and command
     * replies allowed.</p>
     *
     * <p>It used to also require "Sentry mode on", which was wrong: after locking, the vehicle
     * bus tends to sleep and the sentry reading is unavailable (null), so polling never started
     * at all; in the field this showed up as "no reply once locked, works again once unlocked".
     * As long as the process is alive and settings are on, there is no reason not to ask.</p>
     */
    private void syncPoller() {
        // 命令是 Telegram 的功能：ntfy 这条路没有入口（开发者选项里先只做警报）。
        // Commands belong to Telegram: the ntfy route has no entry point (alerts only, for now).
        boolean want = config.isAlertsEnabled() && config.hasAlertsTelegramConfig()
                && config.isAlertsCommands() && !config.isAlertsNtfy();
        if (want == pollerWanted) {
            return;
        }
        pollerWanted = want;
        // 开关的动作（尤其是 stop 里的 join）不能占着主线程
        // The on/off action (especially the join inside stop) must not occupy the main thread
        pollerLifecycle.execute(() -> {
            if (!pollerWanted) {
                TelegramPoller stopping = poller;
                poller = null;
                if (stopping != null) {
                    stopping.stop();
                }
                return;
            }
            if (poller == null) {
                poller = new TelegramPoller(config, new TelegramPoller.Handler() {
                    @Override
                    public void onPicture(int lane, long requestedAt) {
                        requestPicture(lane, requestedAt);
                    }

                    @Override
                    public void onStatus(long requestedAt) {
                        requestStatus();
                    }

                    @Override
                    public void onHelp() {
                        AlertChannel channel = channel();
                        if (channel.ready()) {
                            deliverText(channel, app.getString(R.string.alert_cmd_help));
                        }
                    }
                });
                poller.start();
            }
        });
    }


    /**
     * 车主在 Telegram 里要一张照片。
     *
     * <p>停着才拍：车在开的时候拍照，拍的是一张他现在没法看的图，还可能和录像抢相机。
     * 频率由 {@link RequestPolicy} 管（30 秒一张、一小时 6 张）。</p>
     *
     * The owner asks for a photo in Telegram.
     *
     * <p>Only take it when parked: a photo taken while driving is one he cannot look at now, and
     * it could contend with recording for the camera. Frequency is governed by
     * {@link RequestPolicy} (one per 30 seconds, 6 per hour).</p>
     */
    private void requestPicture(int lane, long requestedAt) {
        try {
            requestPictureInner(lane, requestedAt);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 已经发过「处理命令时出错」了，这里只保证不把进程带走
            // The "error handling command" has already been sent; here we only make sure the process is not taken down
            BlackBox.noteImportant("alerts: picture request failed ("
                    + TelegramRequest.redact(String.valueOf(e)) + ")");
        }
    }

    private void requestPictureInner(int lane, long requestedAt) {
        AlertChannel channel = channel();
        if (!channel.ready()) {
            blockedChannel(channel);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        VehicleState state = Telemetry.get().latest();
        if (!parkedNow(state)) {
            deliverText(channel, app.getString(R.string.alert_cmd_not_parked));
            return;
        }
        if (!requests.allows(now)) {
            long wait = Math.max(1, requests.cooldownLeftMs(now) / 1000);
            deliverText(channel, app.getString(R.string.alert_cmd_too_soon, wait));
            return;
        }
        requests.noteSent(now);
        BlackBox.note("alerts: picture requested from Telegram"
                + (lane == TelegramCommand.ANY_LANE ? "" : "（第 " + lane + " 路）"));
        String caption = TelegramRequest.caption(app.getString(R.string.alert_headline_request),
                lateNote(requestedAt), sentryLine(sentryValue()), time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(channel, grid, caption, lane, null, null);
            }

            @Override
            public void onFailed(String reason) {
                deliverText(channel, app.getString(R.string.set_alerts_failed, reason));
            }
        });
    }

    /**
     * 车主问一句状态：回一句话，不拍。
     *
     * The owner asks for status: reply with one line, take no photo.
     */
    private void requestStatus() {
        AlertChannel channel = channel();
        if (!channel.ready()) {
            blockedChannel(channel);
            return;
        }
        try {
            requestStatusInner(channel);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 不要报一句“状态”就没了下文：把原因回过去（已擦令牌）
            // Do not leave the user with a bare "status" and no follow-up: send the reason back (token already redacted)
            BlackBox.note("alerts: status reply failed (" + TelegramRequest.redact(String.valueOf(e)) + ")");
            deliverText(channel, app.getString(R.string.set_alerts_failed,
                    TelegramRequest.redact(String.valueOf(e))));
        }
    }

    private void requestStatusInner(AlertChannel channel) {
        VehicleState state = Telemetry.get().latest();
        String gear = state == null || state.gear == null ? "?" : state.gear;
        String text = sentryLine(sentryValue()) + "\n"
                + app.getString(R.string.alert_status_gear, gear);
        deliverText(channel, text);
    }

    // ------------------------------------------------------------------ 设置里的两个动作
    // ------------------------------------------------------------------ the two settings actions

    /**
     * 发一张测试照片：拍照、发送，结果回在主线程。
     *
     * <p>这条路和真警报走的是同一条（{@link SnapshotTaker} + {@link TelegramSender}），
     * 所以它能通，警报就能通；它不通，设置界面会说出是哪一步。</p>
     *
     * Send a test photo: take it, send it, return the result on the main thread.
     *
     * <p>This path is the same one real alerts use ({@link SnapshotTaker} +
     * {@link TelegramSender}), so if it works, alerts work; if it does not, the settings screen
     * will say which step failed.</p>
     */
    public void sendTestPicture(Callback callback) {
        AlertChannel channel = channel();
        if (!channel.ready()) {
            callback.onResult(false, blockedText(channel.blockedReason()));
            return;
        }
        String caption = TelegramRequest.caption(
                app.getString(R.string.alert_headline_test),
                sentryLine(sentryValue()),
                time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(channel, grid, caption, TelegramCommand.ANY_LANE, callback, null);
            }

            @Override
            public void onFailed(String reason) {
                callback.onResult(false, app.getString(R.string.set_alerts_failed, reason));
            }
        });
    }

    /**
     * 问一句「这个令牌 Telegram 认不认」，回在主线程。
     *
     * <p>填令牌那一步最容易出错（少粘几位、粘进一个空格、拿的是别的机器人的）。
     * 存完当场问一次，比等真出事那一刻才发现要好。</p>
     *
     * Ask "does Telegram accept this token", replied on the main thread.
     *
     * <p>The token entry step is the easiest to get wrong (missing a few characters, a stray
     * space pasted in, or a token from a different bot). Ask once right after saving, better
     * than discovering it only at the moment something really goes wrong.</p>
     */
    public void checkToken(Callback callback) {
        String token = config.getAlertsTelegramToken();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onResult(false, app.getString(R.string.set_alerts_need_token));
            return;
        }
        sender.execute(() -> {
            try {
                String name = TelegramSender.botName(token);
                String message = name.isEmpty() ? app.getString(R.string.set_alerts_token_ok_unnamed)
                        : app.getString(R.string.set_alerts_token_ok, name);
                app.getMainExecutor().execute(() -> callback.onResult(true, message));
            } catch (TelegramSender.ChatListException e) {
                app.getMainExecutor().execute(
                        () -> callback.onResult(false, app.getString(R.string.set_alerts_token_rejected)));
            }
        });
    }

    /**
     * 一份给人看的 JSON：开关、监听、轮询、取样、车身状态，加上黑匣子的最后一段。
     *
     * <p>令牌不进这份报告；会话号和车主号只写末四位。</p>
     *
     * A human-readable JSON: switch, listening, polling, sampling, vehicle state, plus the last
     * section of the black box.
     *
     * <p>The token does not go into this report; chat id and owner id show only the last four
     * characters.</p>
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
            settingsJson.put("commands", config.isAlertsCommands());
            settingsJson.put("triggers", config.getAlertsTriggers());
            settingsJson.put("layout", config.getAlertsLayout());
            settingsJson.put("cameras", config.getAlertsCameras());
            settingsJson.put("straighten", config.isAlertsStraighten());
            settingsJson.put("motionSensitivity", config.getAlertsMotionSensitivity());
            settingsJson.put("tokenSet", !config.getAlertsTelegramToken().isEmpty());
            settingsJson.put("chatId", maskTail(config.getAlertsTelegramChatId()));
            settingsJson.put("ownerId", maskTail(config.getAlertsOwnerId()));
            root.put("settings", settingsJson);

            org.json.JSONObject pollerJson = new org.json.JSONObject();
            pollerJson.put("running", poller != null && poller.isRunning());
            pollerJson.put("offset", config.getAlertsCommandOffset());
            pollerJson.put("lastOkAgoS", poller == null || poller.lastOkAt() == 0 ? -1
                    : (System.currentTimeMillis() - poller.lastOkAt()) / 1000);
            pollerJson.put("lastError", poller == null ? "" : poller.lastError());
            root.put("poller", pollerJson);

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
            carJson.put("screenLit", String.valueOf(screenLit(now.code(Signal.SCREEN_POWER_EVENT))));
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

    /**
     * 把这条链路现在的样子报一遍 —— 设置里那一行用它。
     *
     * <p>只读状态，不发消息。回答在主线程，一行一项，不含令牌。</p>
     *
     * Report the current state of this whole chain; the settings line uses it.
     *
     * <p>Read-only, sends no message. Answer on the main thread, one item per line, no token.</p>
     */
    public void probe(Callback callback) {
        StringBuilder out = new StringBuilder();
        boolean watchingNow = watching;
        out.append(app.getString(R.string.set_alerts_probe_watch, watchingNow ? "✓" : "✗")).append('\n');
        out.append(app.getString(R.string.set_alerts_probe_poller,
                poller != null && poller.isRunning() ? "✓" : "✗")).append('\n');
        out.append(app.getString(R.string.set_alerts_probe_sentry,
                sentryLine(sentryValue()))).append('\n');
        VehicleState state = Telemetry.get().latest();
        out.append(app.getString(R.string.set_alerts_probe_gear,
                state == null || state.gear == null ? "?" : state.gear)).append('\n');
        out.append(app.getString(R.string.set_alerts_probe_offset,
                String.valueOf(config.getAlertsCommandOffset()))).append('\n');
        long lastOk = poller == null ? 0 : poller.lastOkAt();
        Readings now = Telemetry.get().readings();
        out.append(app.getString(R.string.set_alerts_probe_signals,
                hazardOn(now) == null ? "?" : (hazardOn(now) ? "\u53cc\u95ea" : "-"),
                lampsOn(now) == null ? "?" : (lampsOn(now) ? "\u4eae" : "\u706d"),
                screenLit(now.code(Signal.SCREEN_POWER_EVENT)) == null ? "?" : (screenLit(now.code(Signal.SCREEN_POWER_EVENT)) ? "\u4eae" : "\u9ed1"))).append('\n');
        int samples = motion.samples();
        out.append(app.getString(R.string.set_alerts_probe_motion,
                (sampling ? "on" : "off"), String.valueOf(samples),
                String.format(Locale.US, "%.3f", motion.lastScore()),
                String.format(Locale.US, "%.3f", motion.threshold()))).append('\n');
        out.append(app.getString(R.string.set_alerts_probe_last_ok,
                lastOk == 0 ? "never" : (System.currentTimeMillis() - lastOk) / 1000 + "s ago"));
        String lastError = poller == null ? "" : poller.lastError();
        if (!lastError.isEmpty()) {
            out.append('\n').append(app.getString(R.string.set_alerts_probe_last_error, lastError));
        }
        callback.onResult(watchingNow, out.toString());

        // 顺手再问一次“现在能不能连上”：远处不回应、回家就好的问题，差别就在这一条
        // Also ask "can it connect now": for the "no response away, fine at home" problem, the difference is this one check
        if (TelegramRequest.looksLikeToken(config.getAlertsTelegramToken())) {
            sender.execute(() -> {
                long started = System.currentTimeMillis();
                String verdict;
                try {
                    String name = TelegramSender.botName(config.getAlertsTelegramToken());
                    verdict = app.getString(R.string.set_alerts_probe_reach_ok,
                            String.valueOf(System.currentTimeMillis() - started), name);
                } catch (TelegramSender.ChatListException e) {
                    verdict = app.getString(R.string.set_alerts_probe_reach_fail,
                            e.getMessage() == null ? "" : e.getMessage());
                }
                final String line = verdict;
                app.getMainExecutor().execute(() -> callback.onResult(watchingNow, line));
            });
        }
    }

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

    /**
     * 这个会话 Telegram 认不认（{@code getChat}）。回在主线程。
     *
     * <p>手打错一位数字时，形状照样是数字、照样能填进去，照片就发给别人了。</p>
     *
     * Whether Telegram accepts this chat ({@code getChat}). Replied on the main thread.
     *
     * <p>When one digit is typed wrong, the shape is still digits and still passes entry, and the
     * photo would be sent to someone else.</p>
     */
    public void checkChat(String chatId, Callback callback) {
        String token = config.getAlertsTelegramToken();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onResult(false, app.getString(R.string.set_alerts_need_token));
            return;
        }
        sender.execute(() -> {
            String title = "";
            boolean ok;
            try {
                title = TelegramSender.chatTitle(token, chatId);
                ok = !title.isEmpty();
            } catch (TelegramSender.ChatListException e) {
                ok = false;
            }
            final boolean works = ok;
            final String name = title;
            app.getMainExecutor().execute(() -> callback.onResult(works,
                    works ? app.getString(R.string.set_alerts_chat_ok, name)
                            : app.getString(R.string.set_alerts_chat_unknown)));
        });
    }

    /**
     * 用已填的令牌去问 Telegram：最近谁给机器人发过话。回在主线程。
     *
     * Ask Telegram with the token already filled in: who has recently messaged the bot. Replied on the main thread.
     */
    public void findChats(ChatListCallback callback) {
        String token = config.getAlertsTelegramToken();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onFailed(app.getString(R.string.set_alerts_need_token));
            return;
        }
        sender.execute(() -> {
            try {
                List<TelegramSender.Chat> chats = TelegramSender.recentChats(token);
                if (chats.isEmpty()) {
                    // 历史空了不等于没配好：轮询线程会把消息一条条吃掉（getUpdates 带 offset）。
                    // 那时去问一句已配的会话还认不认，比“机器人没收到消息”有用得多。
                    // An empty history does not mean misconfigured: the polling thread consumes
                    // messages one by one (getUpdates carries an offset).
                    // Then asking whether the already-configured chat is still accepted is far more
                    // useful than "the bot received no message".
                    String configured = config.getAlertsTelegramChatId();
                    if (!configured.isEmpty()) {
                        String title = TelegramSender.chatTitle(token, configured);
                        if (!title.isEmpty()) {
                            chats = java.util.Collections.singletonList(
                                    new TelegramSender.Chat(configured, title, config.getAlertsOwnerId()));
                        }
                    }
                }
                List<TelegramSender.Chat> found = chats;
                app.getMainExecutor().execute(() -> callback.onChats(found));
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                app.getMainExecutor().execute(() -> callback.onFailed(message));
            }
        });
    }

    // ------------------------------------------------------------------ 信号
    // ------------------------------------------------------------------ signals

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
        // 轮询线程的开关照四件事实重算（开着警报、填了令牌与会话号、允许回应命令、哨兵开着）。
        // 不只在哨兵位翻转时看：进程刚起来时哨兵还没读到，读到了就得把线程带起来。
        // The polling thread on/off is recomputed from four facts (alerts on, token and chat id
        // filled, command replies allowed, sentry on).
        // Not only when the sentry bit flips: at process start the sentry has not been read yet,
        // and once read the thread must be brought up.
        pollerOn = sentryOn();
        syncPoller();

        Boolean hazard = hazardOn(readings);
        boolean hazardRising = SentryFacts.rising(hazard, lastHazard);
        Boolean flash = readings.bool(Signal.HIGH_BEAM_FLASH);
        boolean flashRising = SentryFacts.rising(flash, lastFlash);
        boolean doorOpening = doorOpened(readings);
        Boolean lamps = lampsOn(readings);
        boolean lampsRising = SentryFacts.rising(lamps, lastLamps);
        Boolean screenOn = screenLit(readings.code(Signal.SCREEN_POWER_EVENT));
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
        AlertPolicy.Decision decision = policy.decide(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("alerts: " + headline + " seen, not sent (" + decision + ")");
            return;
        }
        BlackBox.note("alerts: " + headline);
        // 冷却和每小时上限只在真的发出去之后才算数：发失败不该把这一小时的话都堵死
        // Cooldown and the per-hour cap count only after actually sending: a failed send must not
        // block this hour's alerts
        fire(headline, () -> policy.noteSent(SystemClock.elapsedRealtime()));
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
        AlertPolicy.Decision decision = policy.decide(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("alerts: motion seen, not sent (" + decision + ")");
            return;
        }
        BlackBox.note("alerts: " + headline + " (" + Math.round(score * 100) + "%)");
        fire(headline, () -> policy.noteSent(SystemClock.elapsedRealtime()));
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
    private static Boolean screenLit(Integer screenPowerEvent) {
        return SentryFacts.screenLit(screenPowerEvent);
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
        lastScreenOn = screenLit(readings.code(Signal.SCREEN_POWER_EVENT));
        rememberDoors(readings);
    }

    // ------------------------------------------------------------------ 拍照与发送
    // ------------------------------------------------------------------ photo and send

    private void fire(String headline, Runnable onDelivered) {
        AlertChannel channel = channel();
        if (!channel.ready()) {
            blockedChannel(channel);
            return;
        }
        String caption = TelegramRequest.caption(headline, sentryLine(sentryValue()),
                time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(channel, grid, caption, TelegramCommand.ANY_LANE, null, onDelivered);
            }

            @Override
            public void onFailed(String reason) {
                // 照片没拍成，文字照样发：车主至少要知道「刚才有人碰了车」。
                // Photo not taken, still send text: the owner should at least know "someone just touched the car".
                BlackBox.note("alerts: no picture (" + reason + "), text only");
                notifyFailure(app.getString(R.string.alert_no_storage));
                deliverText(channel, caption + "\n" + app.getString(R.string.alert_no_storage));
            }
        });
    }

    /**
     * 现在该走哪条出口。
     *
     * <p>两条规矩都在这里，且只在这里：开发者选项关着就是 Telegram，存着 ntfy 也一样
     * （{@link NtfyGate}）—— 藏起来的功能不能变成「悄悄不发了」；ntfy 免费话题的风险提示没被接受，
     * 就返回一个永远发不出去的出口（{@link NtfyRisk}），由 {@link #blockedChannel} 说清楚。</p>
     *
     * Which exit to use now. Both rules live here and only here: developer options closed means
     * Telegram even when ntfy is stored (a hidden feature must not turn into silence), and an
     * unaccepted free-topic warning yields an exit that never sends, explained by
     * {@link #blockedChannel}.
     */
    private AlertChannel channel() {
        if (config.isAlertsNtfy()) {
            return NtfyChannel.of(config);
        }
        return new TelegramChannel(config.getAlertsTelegramToken(), config.getAlertsTelegramChatId());
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
        if (why == AlertChannel.Kind.NOT_CONFIGURED && config.isAlertsNtfy()) {
            return app.getString(R.string.alert_ntfy_no_topic);
        }
        return app.getString(R.string.set_alerts_need_token);
    }

    /**
     * 把这一趟的图画出来、发出去。
     *
     * <p>一张 2×2，或者按勾选的那几路各一张；要不要先掰直。三件事都由 {@link AlertPicture} 决定，
     * 这里只负责把字节一张张按顺序发出去（一次一条，别撞上 Telegram 的频率限制），
     * 然后是回收位图 —— 这条路一晚上可能走好几次，不能漏内存。</p>
     *
     * Draw this run's picture(s) and send them.
     *
     * <p>One 2×2, or one per selected lane; whether to straighten first. All three are decided by
     * {@link AlertPicture}, and this code only sends the bytes one at a time in order (one at a
     * time, so as not to hit Telegram's rate limits), then recycles bitmaps; this path can run
     * several times a night and must not leak memory.</p>
     */
    private void deliver(AlertChannel channel, Bitmap grid, String caption, int onlyLane,
                         Callback callback, Runnable onDelivered) {
        sender.execute(() -> {
            // 掰直、切图、压缩都在这一条线程上：一张 2×2 的校正要翻一百六十万像素，
            // 放在主线程上就是和相机、和设置界面抢那几十毫秒。
            // Straighten, cut, and compress all run on this one thread: correcting one 2×2 touches
            // 1.6 million pixels, and doing it on the main thread would contend with the camera and
            // the settings screen for those few tens of milliseconds.
            Bitmap picture = AlertPicture.straighten(grid, config.isAlertsStraighten(),
                    fisheyeFov(), fisheyeProjection());
            List<byte[]> photos = new ArrayList<>();
            List<String> captions = new ArrayList<>();
            try {
                int[] lanes = onlyLane >= 0 ? new int[] {onlyLane}
                        : AlertPicture.layout(config.getAlertsLayout()) == AlertPicture.Layout.SEPARATE
                                ? AlertPicture.lanes(config.getAlertsCameras()) : null;
                if (lanes != null) {
                    for (int lane : lanes) {
                        Bitmap cell = AlertPicture.quadrant(picture, lane);
                        if (cell == null) {
                            continue;
                        }
                        byte[] jpeg = AlertPicture.jpeg(cell);
                        cell.recycle();
                        if (jpeg != null && jpeg.length > 0) {
                            photos.add(jpeg);
                            captions.add(CameraNames.ofLane(app, lane) + "'+bs+'n" + caption);
                        }
                    }
                } else {
                    byte[] jpeg = AlertPicture.jpeg(picture);
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
                if (callback != null) {
                    app.getMainExecutor().execute(() -> callback.onResult(false,
                            app.getString(R.string.set_alerts_failed, "no picture")));
                }
                return;
            }
            // 开工之后设置可能已经关掉、哨兵也可能刚退出：那就不发了，别送出一张没人要的照片
            // After starting, settings may have been turned off or sentry may have just exited: then
            // don't send, so as not to deliver a photo nobody wants
            if (!config.isAlertsEnabled() || !sentryOn()) {
                BlackBox.note("alerts: dropped (feature off or sentry gone)");
                if (callback != null) {
                    app.getMainExecutor().execute(() -> callback.onResult(false,
                            app.getString(R.string.set_alerts_failed, "alerts off")));
                }
                return;
            }

            AlertChannel.Result last = AlertChannel.Result.fail(
                    AlertChannel.Kind.NETWORK, 0, "nothing sent");
            for (int i = 0; i < photos.size(); i++) {
                last = channel.sendPhoto(photos.get(i), captions.get(i));
                if (!last.ok) {
                    break;
                }
            }
            final AlertChannel.Result outcome = last;
            if (outcome.ok && onDelivered != null) {
                onDelivered.run();
            }
            if (outcome.ok && channel.supportsClips()) {
                sendClip(config.getAlertsTelegramToken(), config.getAlertsTelegramChatId(), caption,
                        TelegramRequest.keyboardMarkup());
            }
            if (!outcome.ok) {
                BlackBox.note("alerts: send failed via " + channel.name() + " ("
                        + outcome.kind + " " + outcome.display() + ")");
                notifyFailure(failureText(outcome));
            }
            if (callback != null) {
                String message;
                if (outcome.ok) {
                    message = app.getString(R.string.set_alerts_sent);
                    if (config.isAlertsNtfy() && !NtfyRisk.accepted(config.getAlertsNtfyTopic(),
                            config.getAlertsNtfyRiskAck())) {
                        // 发得出去不等于风险提示已经被接受
                        // Being able to send does not mean the warning was accepted
                        message = message + "\n" + app.getString(R.string.alert_ntfy_not_acknowledged);
                    } else if (!config.isAlertsEnabled()) {
                        // 发得出去不等于警报开着
                        // Being able to send does not mean alerts are on
                        message = message + "\n" + app.getString(R.string.set_alerts_test_switch_off);
                    }
                } else {
                    message = failureText(outcome);
                }
                final String reply = message;
                app.getMainExecutor().execute(() -> callback.onResult(outcome.ok, reply));
            }
        });
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
     * 没有照片时只发一句话（Telegram 的 sendMessage）。
     *
     * When there is no photo, send only one line (Telegram's sendMessage).
     */
    private void deliverText(AlertChannel channel, String text) {
        sender.execute(() -> {
            AlertChannel.Result result = channel.sendText(text);
            if (!result.ok) {
                BlackBox.note("alerts: text send failed via " + channel.name() + " ("
                        + result.kind + " " + result.display() + ")");
                notifyFailure(failureText(result));
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
        boolean ntfy = config.isAlertsNtfy();
        switch (result.kind) {
            case NOT_CONFIGURED:
                return app.getString(ntfy
                        ? R.string.set_alerts_ntfy_no_topic : R.string.set_alerts_need_token);
            case NOT_ACKNOWLEDGED:
                return app.getString(R.string.alert_ntfy_not_acknowledged);
            case REJECTED:
                return app.getString(ntfy
                        ? R.string.set_alerts_ntfy_rejected : R.string.set_alerts_token_rejected);
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
     * <p>命令这一路只在开的时候跑：哨兵模式是车机保持醒着的唯一原因（平台笔记 §3.6）。
     * 关掉之后车机会在几分钟内下电、进程被结束，留着一条必然要死的轮询线程没有意义。</p>
     *
     * Is Sentry mode on (0 off, 1 on, 2 armed).
     *
     * <p>The command path only runs when it is on: Sentry mode is the only reason the head unit
     * stays awake (platform notes §3.6). After it is off, the head unit powers down within a few
     * minutes and the process is killed, so keeping a polling thread that is bound to die is
     * pointless.</p>
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
     * 警报的短视频：从环里切出最后 5 或 10 秒，发出去再删掉临时文件。
     *
     * <p>只在照片已经发出去之后才做：照片是那条一定会到的消息，视频是附加的。
     * 写不出来、发不出去，都只记一行，不去打扰车主。</p>
     *
     * The alert's short video: cut the last 5 or 10 seconds out of the ring, send it, then delete
     * the temp file.
     *
     * <p>Only done after the photo has already been sent: the photo is the message that will
     * definitely arrive, the video is a bonus. If it cannot be written or sent, just log one line
     * and do not bother the owner.</p>
     */
    private void sendClip(String token, String chatId, String caption, String markup) {
        int seconds = config.getAlertsClipSeconds();
        if (seconds <= 0) {
            return;
        }
        // 名字带时间戳：两条警报挨得近时不会互相覆盖
        // The name carries a timestamp: two alerts close together won't overwrite each other
        java.io.File file = new java.io.File(app.getCacheDir(),
                "alert_clip_" + System.currentTimeMillis() + ".mp4");
        // 要的是“那一刻”：环里只有已经录过的画面，所以等一半时长再切，
        // 切出来的那段就是一半在事情之前、一半在之后。
        // What we want is "that moment": the ring only holds already-recorded frames, so wait
        // half the duration before cutting, and the cut segment is half before the event and
        // half after.
        long waitMs = seconds * 1000L / 2L;
        clipDelay.schedule(() -> CameraKeeper.get(app).writeClip(file, seconds,
                config.isAlertsClipAudio(), ok -> {
            if (!ok || !file.isFile() || file.length() == 0) {
                BlackBox.note("alerts: no clip (ring holds too little video)");
                return;
            }
            String clipCaption = caption + "'+bs+'n"
                    + app.getString(seconds == 5 ? R.string.opt_alerts_clip_5 : R.string.opt_alerts_clip_10);
            sender.execute(() -> {
                TelegramSender.Result result = TelegramSender.sendVideo(token, chatId, file, clipCaption, markup);
                if (!result.ok) {
                    BlackBox.note("alerts: clip send failed (" + result.display() + ")");
                }
                if (!file.delete()) {
                    BlackBox.note("alerts: temp clip could not be deleted");
                }
            });
        }), waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

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
