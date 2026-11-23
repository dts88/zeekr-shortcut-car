/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

/**
 * 什么时候该发一条警报 —— 纯判断，不碰相机、不碰网络，便于单测。
 *
 * <h3>规矩</h3>
 *
 * <p>只有<b>停着、哨兵模式开着</b>的时候才发：车在开、哨兵没开，警报没有意义，
 * 而且会把一次出门变成十几条消息。</p>
 *
 * <p>一条之后要静一会：一次事件往往连着触发好几个信号（关门、再闪灯），
 * {@link #COOLDOWN_MS} 之内一律不发，免得车主被同一件事吵六次。另外每小时有上限
 * {@link #MAX_PER_HOUR}，用来兜住「画面一直在动」的误判（树叶、雨、虫子）。</p>
 *
 * When to send an alert -- pure decision logic: it touches neither the camera nor the
 * network, so it is easy to unit test.
 *
 * <h3>Rules</h3>
 *
 * <p>Send only when the car is <b>parked and Sentry mode is on</b>: when the car is
 * driving or Sentry is off, an alert means nothing, and one outing would turn into a
 * dozen messages.</p>
 *
 * <p>After one alert, stay quiet for a while: one event often fires several signals in a
 * row (door close, then the lights flash again), so within {@link #COOLDOWN_MS} send
 * nothing, to keep the owner from being pinged six times for the same thing. There is
 * also an hourly cap {@link #MAX_PER_HOUR} to catch the "the picture keeps moving" false
 * positives (leaves, rain, insects).</p>
 *
 * <p>警报那条路用 {@link #reserve}：判成 ALLOW 就当场把这一格占掉。以前是判完先去拍照、
 * 发送，成功才记账，所以两个线程能在对方记账之前都判成 ALLOW（同一件事发重），
 * 发不出去时更糟 —— 谁都不记，于是每个触发边都要重新拍一张、传一次（见 {@link #noteFailure}）。
 * {@link #decide} 留给诊断，保持纯判断。</p>
 *
 * <p>The alert path uses {@link #reserve}: a decision of ALLOW takes the slot right there.
 * It used to take the picture and send first and only record on success, so two threads
 * could both decide ALLOW before either recorded it (the same event sent twice), and a
 * failing send was worse -- nothing was recorded, so every trigger edge took a picture and
 * uploaded again (see {@link #noteFailure}). {@link #decide} stays a pure check for
 * diagnostics.</p>
 *
 * <p>写这些字段的线程现在只有一条（{@code SentryAlerts} 把判断挪到了主线程），
 * 方法还是统一加锁 —— 便宜，而且不许再有人从别的线程调进来。</p>
 *
 * <p>Only one thread writes these fields now ({@code SentryAlerts} moved the decision to
 * the main thread), and the methods are still all locked -- cheap, and nobody may call in
 * from another thread again.</p>
 */
public final class AlertPolicy {

    /**
     * 一条之后静多久：同一件事连着触发的几个信号合成一条。
     *
     * How long to stay quiet after one alert: the several signals fired in a row for the
     * same event are merged into one alert.
     */
    public static final long COOLDOWN_MS = 60_000L;

    /**
     * 一小时内最多几条：超过就只记黑匣子，不再往手机上发。
     *
     * The maximum number of alerts in one hour: beyond that, only the black box records
     * them and nothing more is sent to the phone.
     */
    public static final int MAX_PER_HOUR = 6;

    /**
     * 一条发不出去之后静多久（D-12）。比成功的 {@link #COOLDOWN_MS} 长，单独算：
     * 机器人被人从会话里踢了、或者车主拉黑了它，每一条触发边都会重新拍照、压缩、上传两回。
     *
     * How long to stay quiet after one failed send (D-12). Longer than the successful
     * {@link #COOLDOWN_MS} and counted apart: once the bot has been kicked out of the chat
     * or blocked by the owner, every trigger edge retakes, recompresses and uploads twice.
     */
    public static final long FAILURE_BACKOFF_MS = 5 * 60_000L;

    /**
     * 判断结果。只有 {@link #ALLOW} 才会发。
     *
     * The decision result. Only {@link #ALLOW} triggers a send.
     */
    public enum Decision {
        ALLOW,
        /**
         * 总开关关着，或者令牌 / 会话号没填。
         *
         * The master switch is off, or the token / session id is not filled in.
         */
        OFF,
        /**
         * 车没停着（在开、或者档位不在 P）。
         *
         * The car is not parked (it is driving, or the gear is not in P).
         */
        NOT_PARKED,
        /**
         * 哨兵模式没开或没布防：车不会报警，我们也不该报。
         *
         * Sentry mode is off or not armed: the car will not alarm, so neither should we.
         */
        NOT_ARMED,
        /**
         * 上一条还在冷却里。
         *
         * The previous alert is still in its cooldown.
         */
        COOLDOWN,
        /**
         * 这一小时已经发满了。
         *
         * The hourly limit has already been reached.
         */
        HOURLY_CAP,
        /**
         * 上一条发不出去，还在 {@link #FAILURE_BACKOFF_MS} 里。
         *
         * The previous send failed and is still inside {@link #FAILURE_BACKOFF_MS}.
         */
        FAILURE_BACKOFF
    }

    private boolean enabled;
    private boolean configured;
    private boolean parked;
    private boolean armed;
    private long lastSentAt;
    private long hourStart;
    private int sentThisHour;
    /** 发不出去要静到什么时候（elapsedRealtime）；0 = 不欠。 */
    private long failureUntilMs;

    /**
     * @param enabled 用户在设置里打开了警报
     *
     * @param enabled the user turned alerts on in settings
     */
    public AlertPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 令牌和会话号都填了没有：缺一个就发不出去，判断在这里就停。
     *
     * Whether the token and session id are both filled in: if either is missing the alert
     * cannot be sent, so the decision stops here.
     */
    public synchronized void setConfigured(boolean configured) {
        this.configured = configured;
    }

    /**
     * 车停着没有。
     *
     * Whether the car is parked.
     */
    public synchronized void setParked(boolean parked) {
        this.parked = parked;
    }

    /**
     * 哨兵模式开着、并且已经布防。
     *
     * Sentry mode is on and the car is armed.
     */
    public synchronized void setArmed(boolean armed) {
        this.armed = armed;
    }

    /**
     * 现在能不能发。纯判断，什么都不记 —— 给诊断用。警报那条路调 {@link #reserve}。
     *
     * Whether an alert can be sent right now. A pure check that records nothing -- that is
     * for diagnostics; the alert path calls {@link #reserve}.
     *
     * @param nowMs {@code SystemClock.elapsedRealtime()}
     */
    public synchronized Decision decide(long nowMs) {
        return decideLocked(nowMs);
    }

    /**
     * 判断，并且当场占位：判成 ALLOW 就等于这一格归这次触发，冷却与每小时上限一起算。
     *
     * Decide and take the slot right there: an ALLOW means this slot belongs to this trigger,
     * and the cooldown and the hourly cap both count it at once.
     *
     * @param nowMs {@code SystemClock.elapsedRealtime()}
     */
    public synchronized Decision reserve(long nowMs) {
        Decision decision = decideLocked(nowMs);
        if (decision == Decision.ALLOW) {
            noteSentLocked(nowMs);
        }
        return decision;
    }

    private Decision decideLocked(long nowMs) {
        if (!enabled || !configured) {
            return Decision.OFF;
        }
        if (!parked) {
            return Decision.NOT_PARKED;
        }
        if (!armed) {
            return Decision.NOT_ARMED;
        }
        rotateHour(nowMs);
        if (sentThisHour >= MAX_PER_HOUR) {
            return Decision.HOURLY_CAP;
        }
        if (failureUntilMs != 0 && nowMs < failureUntilMs) {
            return Decision.FAILURE_BACKOFF;
        }
        if (lastSentAt != 0 && nowMs - lastSentAt < COOLDOWN_MS) {
            return Decision.COOLDOWN;
        }
        return Decision.ALLOW;
    }

    /**
     * 记下真正发出去的一条。已经先 {@link #reserve} 过的路（警报）不用再调这里；
     * 留给没有占位就自己发出去的人。
     *
     * Record one alert that really went out. A path that already called {@link #reserve}
     * (the alert path) does not call this again; it is for whoever sends without reserving.
     */
    public synchronized void noteSent(long nowMs) {
        noteSentLocked(nowMs);
    }

    private void noteSentLocked(long nowMs) {
        rotateHour(nowMs);
        lastSentAt = nowMs;
        sentThisHour++;
    }

    /**
     * 一条发不出去（被踢、被拉黑、Telegram 不回）：从现在起静 {@link #FAILURE_BACKOFF_MS}。
     *
     * <p>这是占位之后的退款，不另开一套账：占位已经把那一格算给这次触发了。</p>
     *
     * One send failed (kicked out, blocked, Telegram does not answer): stay quiet for
     * {@link #FAILURE_BACKOFF_MS} from now.
     *
     * <p>This is the refund after reserving, not a second ledger: the reservation already
     * counted that slot for this trigger.</p>
     *
     * @param nowMs {@code SystemClock.elapsedRealtime()}
     */
    public synchronized void noteFailure(long nowMs) {
        failureUntilMs = nowMs + FAILURE_BACKOFF_MS;
    }

    /**
     * 上次真正发出去的时间，取整到分钟给外人看。
     *
     * <p>{@code 0} 表示这一趟还没发过。</p>
     *
     * The time the last alert was actually sent, rounded to the minute for display.
     *
     * <p>{@code 0} means nothing has been sent this session yet.</p>
     */
    public synchronized long lastSentAt() {
        return lastSentAt;
    }

    /**
     * 这一小时发了几条。
     *
     * How many alerts have been sent this hour.
     */
    public synchronized int sentThisHour(long nowMs) {
        rotateHour(nowMs);
        return sentThisHour;
    }

    /**
     * 冷却与失败退避哪个还剩得多就报哪个；不在任何一个里才是 0。{@link #decide} 不变。
     *
     * Report whichever of the cooldown and the failure backoff has more left; 0 only when in
     * neither. {@link #decide} is unchanged.
     */
    public synchronized long cooldownLeftMs(long nowMs) {
        long left = 0;
        if (failureUntilMs != 0) {
            left = Math.max(left, failureUntilMs - nowMs);
        }
        if (lastSentAt != 0) {
            left = Math.max(left, COOLDOWN_MS - (nowMs - lastSentAt));
        }
        return Math.max(0, left);
    }

    private void rotateHour(long nowMs) {
        if (hourStart == 0 || nowMs - hourStart >= 3_600_000L) {
            hourStart = nowMs;
            sentThisHour = 0;
        }
    }
}
