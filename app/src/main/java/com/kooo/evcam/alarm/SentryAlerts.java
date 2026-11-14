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

    /** 现在几点（{@code HH:mm}）。加锁是因为警报在主线程、命令在轮询线程，而 SimpleDateFormat 不是线程安全的。 */
    private String time() {
        synchronized (clock) {
            return clock.format(new Date());
        }
    }

    private SentryAlerts(Context context) {
        this.app = context.getApplicationContext();
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
     * <p>四件事都成立才问：开着警报、令牌与会话号都填了、允许回应命令、<b>哨兵模式开着</b>。
     * 最后一条不是可有可无的：哨兵模式开着时车机一直醒着（平台笔记 §3.6），这条线程才有意义；
     * 哨兵关掉之后车机会在几分钟内下电、进程被结束，那时既问不到也没有照片可拍 ——
     * 与其留一条必然要死的线程，不如当场停掉。</p>
     */
    private void syncPoller() {
        boolean want = config.isAlertsEnabled() && config.hasAlertsTelegramConfig()
                && config.isAlertsCommands() && sentryOn();
        if (want == (poller != null)) {
            return;
        }
        if (want) {
            poller = new TelegramPoller(config, new TelegramPoller.Handler() {
                @Override
                public void onPicture(int lane) {
                    requestPicture(lane);
                }

                @Override
                public void onStatus() {
                    requestStatus();
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
    private void requestPicture(int lane) {
        try {
            requestPictureInner(lane);
        } catch (RuntimeException | OutOfMemoryError e) {
            // 已经发过「处理命令时出错」了，这里只保证不把进程带走
            BlackBox.noteImportant("停车警报：要照片时出错（"
                    + TelegramRequest.redact(String.valueOf(e)) + "）");
        }
    }

    private void requestPictureInner(int lane) {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        VehicleState state = Telemetry.get().latest();
        if (!sentryOn()) {
            // 哨兵关掉之后车机就要睡了：拍不了，也别假装拍
            deliverText(token, chatId, app.getString(R.string.alert_cmd_no_sentry));
            return;
        }
        if (state == null || !"P".equals(state.gear)) {
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
                sentryLine(sentryValue()), time());
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
                String.valueOf(config.getAlertsCommandOffset())));
        callback.onResult(watchingNow, out.toString());
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
        boolean parked = state != null && "P".equals(state.gear);
        Integer sentry = state == null ? null : state.sentry;
        // 「布防 = 锁着」：解锁那一下哨兵就 2 → 1 了，车主回来之后的亮屏、亮灯都发不出去（SentryFacts）。
        boolean armed = SentryFacts.armed(sentry);
        policy.setParked(parked);
        policy.setArmed(armed);
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

        lastHazard = hazard;
        lastFlash = flash;
        lastLamps = lamps;
        lastScreenOn = screenOn;
        rememberDoors(readings);

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
                    .setContentTitle(app.getString(R.string.set_section_alerts))
                    .setContentText(why)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(why))
                    .setContentIntent(open)
                    .setAutoCancel(true);
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException e) {
            AppLog.w(TAG, "could not show failure notification: " + e);
        }
    }
}
