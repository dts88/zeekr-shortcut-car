/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

/**
 * 锁车之后等多久才开始发警报 —— 纯判断（{@code ArmDelayTest}）。
 *
 * <p>为什么要有这一段：锁车、关车门、往车位外面走的那一分钟，车会亮灯、屏幕会亮、画面里一直有人在动。
 * 那些都是车主自己。等着走开再开始报，就不必收到一张自己的照片。</p>
 *
 * <p>不知道锁车多久时（进程刚起来、车本来就锁着）<b>照常发</b>：宁可偶尔吵一下，也不能因为不知道就把真事吞掉。</p>
 *
 * How long to wait after locking before sending alerts -- pure decision logic
 * ({@code ArmDelayTest}).
 *
 * <p>Why this delay exists: in the minute after locking, closing the door, and walking
 * away from the parking spot, the car lights up, the screen lights up, and someone keeps
 * moving in the picture. That someone is the owner. Wait for them to leave before
 * reporting, so they do not get a photo of themselves.</p>
 *
 * <p>When the lock time is unknown (the process just started, the car was already
 * locked) <b>send as usual</b>: better to ping occasionally than to swallow a real event
 * because of the unknown.</p>
 */
public final class ArmDelay {

    /**
     * 可选的等待：立刻、1 分钟、3 分钟。
     *
     * The waiting options: immediately, 1 minute, 3 minutes.
     */
    public static final int[] OPTIONS_MIN = {0, 1, 3};

    /**
     * 默认等 1 分钟。
     *
     * Default is to wait 1 minute.
     */
    public static final int DEFAULT_MIN = 1;

    private ArmDelay() {
    }

    /**
     * 把存着的值夹到可选的那几档。
     *
     * Clamp the stored value to one of the available options.
     */
    public static int clamp(int minutes) {
        for (int option : OPTIONS_MIN) {
            if (option == minutes) {
                return minutes;
            }
        }
        return DEFAULT_MIN;
    }

    /**
     * 现在能报了吗。
     *
     * @param minutes        设置里选的那一档
     * @param sinceArmedMs   锁车（布防）到现在多久；{@code -1} = 不知道
     *
     * Can an alert be sent now.
     *
     * @param minutes       the option selected in settings
     * @param sinceArmedMs  how long since locking (arming); {@code -1} = unknown
     */
    public static boolean allows(int minutes, long sinceArmedMs) {
        int wait = clamp(minutes);
        if (wait == 0) {
            return true;
        }
        if (sinceArmedMs < 0) {
            return true;
        }
        return sinceArmedMs >= wait * 60_000L;
    }

    /**
     * 还要等多久（毫秒）；能报或不知道时是 0。
     *
     * How much longer to wait (milliseconds); 0 when it can send or the time is unknown.
     */
    public static long remainingMs(int minutes, long sinceArmedMs) {
        int wait = clamp(minutes);
        if (wait == 0 || sinceArmedMs < 0) {
            return 0;
        }
        long target = wait * 60_000L;
        return Math.max(0, target - sinceArmedMs);
    }
}
