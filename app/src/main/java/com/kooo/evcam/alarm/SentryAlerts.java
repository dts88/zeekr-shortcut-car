/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
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
 */
public final class SentryAlerts implements Telemetry.Listener {

    private static final String TAG = "SentryAlerts";

    /** 在 {@link Telemetry} 里登记的名字（谁在用车辆信号）。 */
    private static final String USER = "sentry-alerts";

    private static final String CHANNEL_ID = "alerts_channel";
    private static final int NOTIFICATION_ID = 1002;

    /** 发不出去时，同一句话最少隔这么久才再说一次（本地通知）。 */
    private static final long FAILURE_NOTIFY_MS = 10 * 60_000L;

    /** 一次警报的结果。{@code message} 已经擦过令牌，可以直接显示。 */
    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /** 「找会话号」的结果。 */
    public interface ChatListCallback {
        void onChats(List<TelegramSender.Chat> chats);

        void onFailed(String message);
    }

    private static SentryAlerts instance;

    private final Context app;
    private final AppConfig config;
    private final AlertPolicy policy;
    /** 只给 {@link #time()} 用；SimpleDateFormat 不是线程安全的，命令那一路在主线程之外调。 */
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
    /** 画面里的移动：编码器把小图送到这里，这里只管算不算一条警报。 */
    private final MotionTap motion = MotionTap.get();
    /** 车主选中的触发项（{@link TriggerSet}）。 */
    private TriggerSet triggers = TriggerSet.parse(null);

    /** 人从 Telegram 要照片时的频率上限（和警报那一套分开）。 */
    private final RequestPolicy requests = new RequestPolicy();
    /**
     * 发照片、发文字都排这一条线程：一条一条来，不并发（Telegram 有频率限制），
     * 也不为每条消息各起一个没人管的线程。
     */
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "telegram-send");
                thread.setDaemon(true);
                return thread;
            });

    /** 去问「有人找我吗」的那条线程；关着时是 null。 */
    private TelegramPoller poller;
    /** 上一次看到的「哨兵模式开着」：变了就重看一次要不要轮询。 */
    private boolean pollerOn;
    /** 现在有没有在取画面移动的样（只用来记一次“开始 / 停下”）。 */
    private boolean sampling;
    /** 诊断：上一次把“看到的信号”写进黑匣子是什么时候（限流）。 */
    private long lastWatchNoteAt;
    /** 诊断：上一次写“还在看着”心跳的时候。 */
    private long lastHeartbeatAt;
    /** 上一次看到的“停着且布防”。 */
    private boolean lastArmedForWatch;
    /** 上一次读到的哨兵原值（锁上车后总线寝下去时还能用它判）。 */
    private Integer lastSentryReading;

    /**
     * 车睡了一阵才醒过来时，配文里写上这张照片是几点要的。
     *
     * <p>不写的话，一张几小时后才到的照片会让人以为是现在的车周围。</p>
     */
    private String lateNote(long requestedAt) {
        if (requestedAt <= 0 || System.currentTimeMillis() - requestedAt < PollerGate.NOTE_AGE_MS) {
            return "";
        }
        return app.getString(R.string.alert_caption_requested,
                new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new Date(requestedAt)));
    }

    /** 现在几点（{@code HH:mm}）。加锁是因为警报在主线程、命令在轮询线程，而 SimpleDateFormat 不是线程安全的。 */
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
        Telemetry telemetry = Telemetry.get();
        if (want) {
            Readings now = telemetry.readings();
            seedEdges(now);
            telemetry.addListener(this);
            telemetry.acquire(app, USER);
            BlackBox.note("停车警报：开始看双闪、远光、车门的信号");
        } else {
            telemetry.removeListener(this);
            telemetry.release(USER);
            lastHazard = null;
            lastFlash = null;
            lastLamps = null;
            lastScreenOn = null;
            seedEdges(Readings.empty());
            BlackBox.note("停车警报：不再看车辆信号");
        }
        syncPoller();
    }

    /** 设置里刚改过令牌 / 会话号：判断要重算，但没必要重挂监听。 */
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
     */
    private void syncPoller() {
        boolean want = config.isAlertsEnabled() && config.hasAlertsTelegramConfig()
                && config.isAlertsCommands();
        if (want == (poller != null)) {
            return;
        }
        if (want) {
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
                    String token = config.getAlertsTelegramToken();
                    String chatId = config.getAlertsTelegramChatId();
                    if (TelegramRequest.looksLikeToken(token) && TelegramRequest.looksLikeChatId(chatId)) {
                        deliverText(token, chatId, app.getString(R.string.alert_cmd_help));
                    }
                }
            });
            poller.start();
        } else {
            poller.stop();
            poller = null;
        }
    }

    /**
     * 车主在 Telegram 里要一张照片。
     *
     * <p>停着才拍：车在开的时候拍照，拍的是一张他现在没法看的图，还可能和录像抢相机。
     * 频率由 {@link RequestPolicy} 管（30 秒一张、一小时 6 张）。</p>
     */
    private void requestPicture(int lane, long requestedAt) {
        try {
            requestPictureInner(lane, requestedAt);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 已经发过「处理命令时出错」了，这里只保证不把进程带走
            BlackBox.noteImportant("停车警报：要照片时出错（"
                    + TelegramRequest.redact(String.valueOf(e)) + "）");
        }
    }

    private void requestPictureInner(int lane, long requestedAt) {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        VehicleState state = Telemetry.get().latest();
        if (!parkedNow(state)) {
            deliverText(token, chatId, app.getString(R.string.alert_cmd_not_parked));
            return;
        }
        if (!requests.allows(now)) {
            long wait = Math.max(1, requests.cooldownLeftMs(now) / 1000);
            deliverText(token, chatId, app.getString(R.string.alert_cmd_too_soon, wait));
            return;
        }
        requests.noteSent(now);
        BlackBox.note("停车警报：车主在 Telegram 里要了一张照片"
                + (lane == TelegramCommand.ANY_LANE ? "" : "（第 " + lane + " 路）"));
        String caption = TelegramRequest.caption(app.getString(R.string.alert_headline_request),
                lateNote(requestedAt), sentryLine(sentryValue()), time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(token, chatId, grid, caption, lane, null, null);
            }

            @Override
            public void onFailed(String reason) {
                deliverText(token, chatId, app.getString(R.string.set_alerts_failed, reason));
            }
        });
    }

    /** 车主问一句状态：回一句话，不拍。 */
    private void requestStatus() {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
            return;
        }
        try {
            requestStatusInner(token, chatId);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 不要报一句“状态”就没了下文：把原因回过去（已擦令牌）
            BlackBox.note("停车警报：回状态时出错（" + TelegramRequest.redact(String.valueOf(e)) + "）");
            deliverText(token, chatId, app.getString(R.string.set_alerts_failed,
                    TelegramRequest.redact(String.valueOf(e))));
        }
    }

    private void requestStatusInner(String token, String chatId) {
        VehicleState state = Telemetry.get().latest();
        String gear = state == null || state.gear == null ? "?" : state.gear;
        String text = sentryLine(sentryValue()) + "\n"
                + app.getString(R.string.alert_status_gear, gear);
        deliverText(token, chatId, text);
    }

    // ------------------------------------------------------------------ 设置里的两个动作

    /**
     * 发一张测试照片：拍照、发送，结果回在主线程。
     *
     * <p>这条路和真警报走的是同一条（{@link SnapshotTaker} + {@link TelegramSender}），
     * 所以它能通，警报就能通；它不通，设置界面会说出是哪一步。</p>
     */
    public void sendTestPicture(Callback callback) {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onResult(false, app.getString(R.string.set_alerts_need_token));
            return;
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            callback.onResult(false, app.getString(R.string.set_alerts_need_chat));
            return;
        }
        String caption = TelegramRequest.caption(
                app.getString(R.string.alert_headline_test),
                sentryLine(sentryValue()),
                time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(token, chatId, grid, caption, TelegramCommand.ANY_LANE, callback, null);
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
        BlackBox.note("停车警报：还在看着（哨兵 " + (state == null ? "?" : state.sentry)
                + "，挡位 " + (state == null || state.gear == null ? "?" : state.gear) + "）");
    }

    /**
     * 这个会话 Telegram 认不认（{@code getChat}）。回在主线程。
     *
     * <p>手打错一位数字时，形状照样是数字、照样能填进去，照片就发给别人了。</p>
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

    /** 用已填的令牌去问 Telegram：最近谁给机器人发过话。回在主线程。 */
    public void findChats(ChatListCallback callback) {
        String token = config.getAlertsTelegramToken();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onFailed(app.getString(R.string.set_alerts_need_token));
            return;
        }
        sender.execute(() -> {
            try {
                List<TelegramSender.Chat> chats = TelegramSender.recentChats(token);
                app.getMainExecutor().execute(() -> callback.onChats(chats));
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                app.getMainExecutor().execute(() -> callback.onFailed(message));
            }
        });
    }

    // ------------------------------------------------------------------ 信号

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
        boolean armed = SentryFacts.armed(sentry != null ? sentry : lastSentryReading);
        policy.setParked(parked);
        policy.setArmed(armed);
        lastArmedForWatch = armed && parked;
        // 轮询线程的开关照四件事实重算（开着警报、填了令牌与会话号、允许回应命令、哨兵开着）。
        // 不只在哨兵位翻转时看：进程刚起来时哨兵还没读到，读到了就得把线程带起来。
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
        boolean wantSample = watching && parked && triggers.has(TriggerSet.MOTION);
        if (wantSample != sampling) {
            sampling = wantSample;
            if (wantSample) {
                BlackBox.note("停车警报：开始看画面移动");
            } else {
                BlackBox.note("停车警报：不看画面了（"
                        + (!watching ? "警报未开" : !parked ? "车在走"
                        : "移动不在勾选里") + "）");
            }
            motion.setWanted(wantSample);
        }

        // 诊断：车上真正响了一次、而我们没发消息时，要能从诊断报告里看出
        // “那一刻哪些信号动了”。只在假车、布防时记，每 3 秒最多一行。
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

        AlertPolicy.Decision decision = policy.decide(now);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("停车警报：" + headline + "，但这一条不发（" + decision + "）");
            return;
        }
        BlackBox.note("停车警报：" + headline);
        // 冷却和每小时上限只在真的发出去之后才算数：发失败不该把这一小时的话都堵死
        fire(headline, () -> policy.noteSent(SystemClock.elapsedRealtime()));
    }

    /**
     * 假车、布防时，把“看到的信号”写一行进黑匣子。
     *
     * <p>这不是警报，是证据：车自己响了、我们却没发消息时，诊断报告里就能看出
     * 当时哪一项真的变了、哪一项根本没动。没变化就不写。</p>
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
        BlackBox.note("停车警报：看到信号变化 " + changed);
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
     * 画面里有东西在动（编码器线程送来的分数）。
     *
     * <p>分数已经过了 {@link FrameDiff} 的三道过滤；这里只看车是不是该报。</p>
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
        AlertPolicy.Decision decision = policy.decide(now);
        String headline = app.getString(R.string.alert_headline_motion);
        if (decision != AlertPolicy.Decision.ALLOW) {
            BlackBox.note("停车警报：画面在动，但这一条不发（" + decision + "）");
            return;
        }
        BlackBox.note("停车警报：" + headline + "（" + Math.round(score * 100) + "%）");
        fire(headline, () -> policy.noteSent(SystemClock.elapsedRealtime()));
    }

    /** 双闪亮没亮：转向指示状态是 3（双闪）；没数据为 null。 */
    private static Boolean hazardOn(Readings readings) {
        Integer indicator = readings.code(Signal.INDICATOR);
        return indicator == null ? null : indicator == 3;
    }

    /** 车外有没有灯自己亮起来（{@link SentryFacts#lampsOn}）。 */
    private static Boolean lampsOn(Readings readings) {
        return SentryFacts.lampsOn(readings.bool(Signal.DRL),
                readings.bool(Signal.FRONT_POSITION_LAMP),
                readings.bool(Signal.REAR_POSITION_LAMP),
                readings.bool(Signal.LOW_BEAM));
    }

    /** 中控屏亮着没有（{@link SentryFacts#screenLit}）。 */
    private static Boolean screenLit(Integer screenPowerEvent) {
        return SentryFacts.screenLit(screenPowerEvent);
    }

    /** 六个门里有没有一个刚从关到开。 */
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

    /** 从当前读数起算：一直开着的门、一直亮着的双闪都不算「刚发生」。 */
    private void seedEdges(Readings readings) {
        lastHazard = hazardOn(readings);
        lastFlash = readings.bool(Signal.HIGH_BEAM_FLASH);
        lastLamps = lampsOn(readings);
        lastScreenOn = screenLit(readings.code(Signal.SCREEN_POWER_EVENT));
        rememberDoors(readings);
    }

    // ------------------------------------------------------------------ 拍照与发送

    private void fire(String headline, Runnable onDelivered) {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
            return;
        }
        String caption = TelegramRequest.caption(headline, sentryLine(sentryValue()),
                time());
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(Bitmap grid) {
                deliver(token, chatId, grid, caption, TelegramCommand.ANY_LANE, null, onDelivered);
            }

            @Override
            public void onFailed(String reason) {
                // 照片没拍成，文字照样发：车主至少要知道「刚才有人碰了车」。
                BlackBox.note("停车警报：照片没拍成（" + reason + "），只发文字");
                notifyFailure(app.getString(R.string.alert_no_storage));
                deliverText(token, chatId, caption + "\n" + app.getString(R.string.alert_no_storage));
            }
        });
    }

    /**
     * 把这一趟的图画出来、发出去。
     *
     * <p>一张 2×2，或者按勾选的那几路各一张；要不要先掰直。三件事都由 {@link AlertPicture} 决定，
     * 这里只负责把字节一张张按顺序发出去（一次一条，别撞上 Telegram 的频率限制），
     * 然后是回收位图 —— 这条路一晚上可能走好几次，不能漏内存。</p>
     */
    private void deliver(String token, String chatId, Bitmap grid, String caption, int onlyLane,
                         Callback callback, Runnable onDelivered) {
        sender.execute(() -> {
            // 掰直、切图、压缩都在这一条线程上：一张 2×2 的校正要翻一百六十万像素，
            // 放在主线程上就是和相机、和设置界面抢那几十毫秒。
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
            if (!config.isAlertsEnabled() || !sentryOn()) {
                BlackBox.note("停车警报：这一张不发了（功能已关或哨兵已退）");
                if (callback != null) {
                    app.getMainExecutor().execute(() -> callback.onResult(false,
                            app.getString(R.string.set_alerts_failed, "alerts off")));
                }
                return;
            }

            TelegramSender.Result last = TelegramSender.Result.fail("nothing sent", 0);
            String markup = TelegramRequest.keyboardMarkup();
            for (int i = 0; i < photos.size(); i++) {
                last = TelegramSender.sendPhoto(token, chatId, photos.get(i), captions.get(i), markup);
                if (!last.ok) {
                    break;
                }
            }
            final TelegramSender.Result outcome = last;
            if (outcome.ok && onDelivered != null) {
                onDelivered.run();
            }
            if (outcome.ok) {
                sendClip(token, chatId, caption, markup);
            }
            if (!outcome.ok) {
                BlackBox.note("停车警报：发不出去（" + outcome.display() + "）");
                notifyFailure(outcome.display());
            }
            if (callback != null) {
                String message;
                if (outcome.ok) {
                    message = app.getString(R.string.set_alerts_sent);
                } else if (outcome.rejected) {
                    message = app.getString(R.string.set_alerts_token_rejected);
                } else {
                    message = app.getString(R.string.set_alerts_failed, outcome.display());
                }
                app.getMainExecutor().execute(() -> callback.onResult(outcome.ok, message));
            }
        });
    }

    /** 掰直用的视野角度：和回看界面那一项同一个值。 */
    private float fisheyeFov() {
        return config.getFisheyeFov();
    }

    /** 掰直用的投影方式：同上。 */
    private String fisheyeProjection() {
        return config.getFisheyeProjection();
    }

    /** 没有照片时只发一句话（Telegram 的 sendMessage）。 */
    private void deliverText(String token, String chatId, String text) {
        sender.execute(() -> {
            TelegramSender.Result result = TelegramSender.sendMessage(token, chatId, text,
                    TelegramRequest.keyboardMarkup());
            if (!result.ok) {
                BlackBox.note("停车警报：文字也发不出去（" + result.display() + "）");
                notifyFailure(result.display());
            }
        });
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
     */
    private boolean sentryOn() {
        Integer sentry = sentryValue();
        return sentry != null && sentry != 0;
    }

    /** 配图里那一行哨兵模式状态：布防 / 开 / 关。 */
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
     */
    /**
     * 警报的短视频：从环里切出最后 5 或 10 秒，发出去再删掉临时文件。
     *
     * <p>只在照片已经发出去之后才做：照片是那条一定会到的消息，视频是附加的。
     * 写不出来、发不出去，都只记一行，不去打扰车主。</p>
     */
    private void sendClip(String token, String chatId, String caption, String markup) {
        int seconds = config.getAlertsClipSeconds();
        if (seconds <= 0) {
            return;
        }
        java.io.File file = new java.io.File(app.getCacheDir(), "alert_clip.mp4");
        CameraKeeper.get(app).writeClip(file, seconds, config.isAlertsClipAudio(), ok -> {
            if (!ok || !file.isFile() || file.length() == 0) {
                BlackBox.note("停车警报：短视频没写成（环里没有足够的画面）");
                return;
            }
            sender.execute(() -> {
                TelegramSender.Result result = TelegramSender.sendVideo(token, chatId, file, caption, markup);
                if (!result.ok) {
                    BlackBox.note("停车警报：短视频发不出去（" + result.display() + "）");
                }
                if (!file.delete()) {
                    BlackBox.note("停车警报：临时视频删不掉");
                }
            });
        });
    }

    /**
     * 把一个异常式的理由压成一行能读的话。
     *
     * <p>通知栏在车机上就那么一点宽，“java.io.IOException: …”连第一个词都显不完。
     * 原文不丢：它在黑匣子和诊断报告里。</p>
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
