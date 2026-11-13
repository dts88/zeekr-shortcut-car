/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.app.NotificationChannel;
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
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.text.SimpleDateFormat;
import java.util.Date;
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
    private long failureNotifiedAt;

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
                clock.format(new Date()));
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(byte[] jpeg) {
                deliver(token, chatId, jpeg, caption, callback);
            }

            @Override
            public void onFailed(String reason) {
                callback.onResult(false, app.getString(R.string.set_alerts_failed, reason));
            }
        });
    }

    /** 用已填的令牌去问 Telegram：最近谁给机器人发过话。回在主线程。 */
    public void findChats(ChatListCallback callback) {
        String token = config.getAlertsTelegramToken();
        if (!TelegramRequest.looksLikeToken(token)) {
            callback.onFailed(app.getString(R.string.set_alerts_need_token));
            return;
        }
        new Thread(() -> {
            try {
                List<TelegramSender.Chat> chats = TelegramSender.recentChats(token);
                app.getMainExecutor().execute(() -> callback.onChats(chats));
            } catch (TelegramSender.ChatListException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                app.getMainExecutor().execute(() -> callback.onFailed(message));
            }
        }, "telegram-chats").start();
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
        policy.noteSent(now);
        BlackBox.note("停车警报：" + headline);
        fire(headline);
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

    private void fire(String headline) {
        String token = config.getAlertsTelegramToken();
        String chatId = config.getAlertsTelegramChatId();
        if (!TelegramRequest.looksLikeToken(token) || !TelegramRequest.looksLikeChatId(chatId)) {
            return;
        }
        String caption = TelegramRequest.caption(headline, sentryLine(sentryValue()),
                clock.format(new Date()));
        SnapshotTaker.take(app, new SnapshotTaker.Callback() {
            @Override
            public void onPicture(byte[] jpeg) {
                deliver(token, chatId, jpeg, caption, null);
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

    private void deliver(String token, String chatId, byte[] jpeg, String caption, Callback callback) {
        new Thread(() -> {
            TelegramSender.Result result = TelegramSender.sendPhoto(token, chatId, jpeg, caption);
            if (!result.ok) {
                BlackBox.note("停车警报：发不出去（" + result.display() + "）");
                notifyFailure(result.display());
            }
            if (callback != null) {
                String message = result.ok ? app.getString(R.string.set_alerts_sent)
                        : app.getString(R.string.set_alerts_failed, result.display());
                app.getMainExecutor().execute(() -> callback.onResult(result.ok, message));
            }
        }, "telegram-photo").start();
    }

    /** 没有照片时只发一句话（Telegram 的 sendMessage）。 */
    private void deliverText(String token, String chatId, String text) {
        new Thread(() -> {
            TelegramSender.Result result = TelegramSender.sendMessage(token, chatId, text);
            if (!result.ok) {
                BlackBox.note("停车警报：文字也发不出去（" + result.display() + "）");
                notifyFailure(result.display());
            }
        }, "telegram-text").start();
    }

    private Integer sentryValue() {
        VehicleState state = Telemetry.get().latest();
        return state == null ? null : state.sentry;
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
