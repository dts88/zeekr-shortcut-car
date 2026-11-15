/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

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
        HOURLY_CAP
    }

    private boolean enabled;
    private boolean configured;
    private boolean parked;
    private boolean armed;
    private long lastSentAt;
    private long hourStart;
    private int sentThisHour;

    /**
     * @param enabled 用户在设置里打开了警报
     *
     * @param enabled the user turned alerts on in settings
     */
    public AlertPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 令牌和会话号都填了没有：缺一个就发不出去，判断在这里就停。
     *
     * Whether the token and session id are both filled in: if either is missing the alert
     * cannot be sent, so the decision stops here.
     */
    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    /**
     * 车停着没有。
     *
     * Whether the car is parked.
     */
    public void setParked(boolean parked) {
        this.parked = parked;
    }

    /**
     * 哨兵模式开着、并且已经布防。
     *
     * Sentry mode is on and the car is armed.
     */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /**
     * 现在能不能发。
     *
     * Whether an alert can be sent right now.
     *
     * @param nowMs {@code SystemClock.elapsedRealtime()}
     */
    public Decision decide(long nowMs) {
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
        if (lastSentAt != 0 && nowMs - lastSentAt < COOLDOWN_MS) {
            return Decision.COOLDOWN;
        }
        return Decision.ALLOW;
    }

    /**
     * 一条发出去了。只有真的发成功才调，失败重试不该再等一分钟。
     *
     * One alert has been sent. Call this only when the send actually succeeded; a failed
     * retry should not wait another minute.
     */
    public void noteSent(long nowMs) {
        rotateHour(nowMs);
        lastSentAt = nowMs;
        sentThisHour++;
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
    public long lastSentAt() {
        return lastSentAt;
    }

    /**
     * 这一小时发了几条。
     *
     * How many alerts have been sent this hour.
     */
    public int sentThisHour(long nowMs) {
        rotateHour(nowMs);
        return sentThisHour;
    }

    /**
     * 冷却还剩多久；不在冷却里是 0。
     *
     * How much cooldown is left; 0 when not in cooldown.
     */
    public long cooldownLeftMs(long nowMs) {
        if (lastSentAt == 0) {
            return 0;
        }
        long left = COOLDOWN_MS - (nowMs - lastSentAt);
        return Math.max(0, left);
    }

    private void rotateHour(long nowMs) {
        if (hourStart == 0 || nowMs - hourStart >= 3_600_000L) {
            hourStart = nowMs;
            sentThisHour = 0;
        }
    }
}
