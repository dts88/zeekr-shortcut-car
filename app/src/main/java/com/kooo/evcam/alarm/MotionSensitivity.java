/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 移动检测的灵敏度：一个 1–10 的档位，换算成 {@link FrameDiff} 的两个阈值。
 *
 * <p>为什么是一个档位而不是两个滑条：车主要回答的只有一个问题
 * ——“它太敏了还是太钝了”。两个阈值同时走，不会出现“一个放宽了、
 * 另一个还卡着”这种怎么调都不对的情况。</p>
 *
 * <p>1 = 最不敏敏（只有大动静才响），10 = 最敏敏（远处有人走过也响，
 * 但雨、飞虫、树叶也更容易带进来）。默认 5。</p>
 */
public final class MotionSensitivity {

    public static final int MIN = 1;
    public static final int MAX = 10;
    public static final int DEFAULT = 5;

    /** 最不敏敏时的平均差阈值。 */
    private static final double COARSE_THRESHOLD = 0.030;
    /** 最敏敏时的平均差阈值。 */
    private static final double FINE_THRESHOLD = 0.004;
    /** 两端的“显著变化像素占比”。 */
    private static final double COARSE_FRACTION = 0.020;
    private static final double FINE_FRACTION = 0.004;

    private MotionSensitivity() {
    }

    /** 把任何读数夹到合法范围；读不到时用默认。 */
    public static int clamp(int level) {
        if (level < MIN) {
            return MIN;
        }
        return Math.min(level, MAX);
    }

    /** 平均差阈值：档位越高越小（越灵敏）。 */
    public static double threshold(int level) {
        return lerp(COARSE_THRESHOLD, FINE_THRESHOLD, clamp(level));
    }

    /** 显著变化像素占比阈值，同样随档位走。 */
    public static double fraction(int level) {
        return lerp(COARSE_FRACTION, FINE_FRACTION, clamp(level));
    }

    private static double lerp(double coarse, double fine, int level) {
        double t = (level - MIN) / (double) (MAX - MIN);
        return coarse + (fine - coarse) * t;
    }
}
