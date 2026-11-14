/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 锁车之后等多久才开始发警报 —— 纯判断（{@code ArmDelayTest}）。
 *
 * <p>为什么要有这一段：锁车、关车门、往车位外面走的那一分钟，车会亮灯、屏幕会亮、画面里一直有人在动。
 * 那些都是车主自己。等着走开再开始报，就不必收到一张自己的照片。</p>
 *
 * <p>不知道锁车多久时（进程刚起来、车本来就锁着）<b>照常发</b>：宁可偶尔吵一下，也不能因为不知道就把真事吞掉。</p>
 */
public final class ArmDelay {

    /** 可选的等待：立刻、1 分钟、3 分钟。 */
    public static final int[] OPTIONS_MIN = {0, 1, 3};

    /** 默认等 1 分钟。 */
    public static final int DEFAULT_MIN = 1;

    private ArmDelay() {
    }

    /** 把存着的值夹到可选的那几档。 */
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

    /** 还要等多久（毫秒）；能报或不知道时是 0。 */
    public static long remainingMs(int minutes, long sinceArmedMs) {
        int wait = clamp(minutes);
        if (wait == 0 || sinceArmedMs < 0) {
            return 0;
        }
        long target = wait * 60_000L;
        return Math.max(0, target - sinceArmedMs);
    }
}
