/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 「哨兵模式被触发」怎么从读数里认出来 —— 纯逻辑，不碰 Android（{@code SentryWatchTest}）。项目所有者 2026-11-23：
 * 用哨兵被触发做触发，不比较录像画面。
 *
 * <p>哨兵模式的读数（{@code telemetry.Signal#SENTRY_MODE}）是哨兵应用自己写的状态：0 关、1 开启（待机）、2 已布防、
 * 3 警戒、4 报警（Lab 读车机代码得到；3、4 还没在车上见过，黑匣子记下每一次变化，上车核对）。车在锁车后约 90 秒自己从
 * 「开启」变成「已布防」，解锁回「开启」，挂 D 回「关闭」。</p>
 *
 * <ul>
 *   <li><b>被触发</b>：读数从别的值变成警戒或报警的那一下（警戒升到报警也算一下）。一直停在警戒、报警不算新的一下。</li>
 *   <li><b>布防着</b>：已布防、警戒、报警都算（被触发以后车还在看着）。</li>
 *   <li>读不到（null）照上一次真读到的算 —— 锁车以后车辆接口可能读不到；读不到不算变化。进程起来后头一次读到就是警戒、报警的，
 *       不知道是不是刚变的，宁可报。</li>
 * </ul>
 *
 * How "Sentry Mode was triggered" is recognised: the Sentry Mode reading (0 off, 1 on, 2 armed, 3 alert,
 * 4 alarm) changing into 3 or 4. Armed means 2, 3 or 4. A missing reading keeps the last real one.
 */
public final class SentryWatch {

    /** 哨兵模式「已布防」。Sentry Mode "armed". */
    public static final int SENTRY_ARMED = 2;
    /** 哨兵模式「警戒」。Sentry Mode "alert". */
    public static final int SENTRY_ALERT = 3;
    /** 哨兵模式「报警」。Sentry Mode "alarm". */
    public static final int SENTRY_ALARM = 4;

    /** 被触发成了哪一种。Which kind of trigger. */
    public enum Trigger {
        /** 警戒。Alert. */
        ALERT,
        /** 报警。Alarm. */
        ALARM
    }

    /** 最近一次真读到的哨兵模式；还没读到 null。The last real Sentry Mode reading. */
    private Integer sentry;

    /** 重新开始看：之前读到的都不算（提醒刚打开）。Start over: nothing seen before counts. */
    public void reset() {
        sentry = null;
    }

    /**
     * 喂一份读数。
     *
     * @return 这一份里新被触发的那一种；没有就 null
     */
    public Trigger update(Integer sentryReading) {
        if (sentryReading == null || sentryReading.equals(sentry)) {
            return null;
        }
        sentry = sentryReading;
        if (sentryReading == SENTRY_ALERT) {
            return Trigger.ALERT;
        }
        return sentryReading == SENTRY_ALARM ? Trigger.ALARM : null;
    }

    /** 最近一次真读到的哨兵模式（黑匣子记变化用）；还没读到 null。 */
    public Integer reading() {
        return sentry;
    }

    /** 此刻布防着：已布防、警戒、报警。Armed right now: armed, alert or alarm. */
    public boolean armed() {
        return sentry != null && (sentry == SENTRY_ARMED || sentry == SENTRY_ALERT || sentry == SENTRY_ALARM);
    }
}
