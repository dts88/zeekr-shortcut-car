/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
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
 */
public final class AlertPolicy {

    /** 一条之后静多久：同一件事连着触发的几个信号合成一条。 */
    public static final long COOLDOWN_MS = 60_000L;

    /** 一小时内最多几条：超过就只记黑匣子，不再往手机上发。 */
    public static final int MAX_PER_HOUR = 6;

    /** 判断结果。只有 {@link #ALLOW} 才会发。 */
    public enum Decision {
        ALLOW,
        /** 总开关关着，或者令牌 / 会话号没填。 */
        OFF,
        /** 车没停着（在开、或者档位不在 P）。 */
        NOT_PARKED,
        /** 哨兵模式没开或没布防：车不会报警，我们也不该报。 */
        NOT_ARMED,
        /** 上一条还在冷却里。 */
        COOLDOWN,
        /** 这一小时已经发满了。 */
        HOURLY_CAP
    }

    private boolean enabled;
    private boolean configured;
    private boolean parked;
    private boolean armed;
    private long lastSentAt;
    private long hourStart;
    private int sentThisHour;

    /** @param enabled 用户在设置里打开了警报 */
    public AlertPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 令牌和会话号都填了没有：缺一个就发不出去，判断在这里就停。 */
    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    /** 车停着没有。 */
    public void setParked(boolean parked) {
        this.parked = parked;
    }

    /** 哨兵模式开着、并且已经布防。 */
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /**
     * 现在能不能发。
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

    /** 一条发出去了。只有真的发成功才调，失败重试不该再等一分钟。 */
    public void noteSent(long nowMs) {
        rotateHour(nowMs);
        lastSentAt = nowMs;
        sentThisHour++;
    }

    /**
     * 上次真正发出去的时间，取整到分钟给外人看。
     *
     * <p>{@code 0} 表示这一趟还没发过。</p>
     */
    public long lastSentAt() {
        return lastSentAt;
    }

    /** 这一小时发了几条。 */
    public int sentThisHour(long nowMs) {
        rotateHour(nowMs);
        return sentThisHour;
    }

    /** 冷却还剩多久；不在冷却里是 0。 */
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
