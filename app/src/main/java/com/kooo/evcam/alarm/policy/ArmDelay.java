/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

/**
 * 布防之后等多久才开始发提醒 —— 纯判断（{@code ArmDelayTest}）。
 *
 * <p>为什么要有这一段：锁车、关车门、往车位外面走的那一阵，车会亮灯、画面里一直有人在动。那些都是车主自己。
 * 等着走开再开始报，就不必收到一条自己的提醒。</p>
 *
 * <p>多久：项目所有者 2026-11-18 定布防满 30 秒，不给选项（以后找到哨兵模式里「发现异常」的那个信号，就不用等了）。
 * 读不到锁车的那一刻：读得到的是哨兵模式从「开启」变成「已布防」，那是车自己在锁车后约 90 秒做的
 * （{@code telemetry.Signal#SENTRY_MODE}）。从布防的那一刻起等 {@link #AFTER_ARMED_MS}。</p>
 *
 * <p>不知道布防多久时（进程刚起来、车本来就布防着）<b>照常发</b>：宁可偶尔吵一下，也不能因为不知道就把真事吞掉。</p>
 *
 * How long to wait after arming before sending alerts -- pure decision logic ({@code ArmDelayTest}).
 *
 * <p>In the time after locking, closing the door and walking away, the car lights up and someone keeps
 * moving in the picture. That someone is the owner. Waiting for them to leave means they do not get an
 * alert about themselves. The owner fixed it at 30 seconds after arming on 2026-11-18, with no option.
 * The lock itself is not readable; what is readable is Sentry Mode going from "on" to "armed", which the
 * car does by itself about 90 seconds after locking. The wait counts from that moment.</p>
 *
 * <p>When the arming time is unknown (the process just started, the car was already armed) <b>send as
 * usual</b>: better to ping occasionally than to swallow a real event because of the unknown.</p>
 */
public final class ArmDelay {

    /** 布防满多久才开始提醒。How long after arming alerts start. */
    public static final long AFTER_ARMED_MS = 30_000L;

    private ArmDelay() {
    }

    /**
     * 现在能报了吗。
     *
     * @param sinceArmedMs 布防到现在多久；{@code -1} = 不知道
     *
     * Can an alert be sent now.
     *
     * @param sinceArmedMs how long since arming; {@code -1} = unknown
     */
    public static boolean allows(long sinceArmedMs) {
        return sinceArmedMs < 0 || sinceArmedMs >= AFTER_ARMED_MS;
    }

    /**
     * 还要等多久（毫秒）；能报或不知道时是 0。
     *
     * How much longer to wait (milliseconds); 0 when it can send or the time is unknown.
     */
    public static long remainingMs(long sinceArmedMs) {
        if (sinceArmedMs < 0) {
            return 0;
        }
        return Math.max(0, AFTER_ARMED_MS - sinceArmedMs);
    }
}
