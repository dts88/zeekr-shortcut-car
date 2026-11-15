/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
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
 *
 * Motion-detection sensitivity: a 1–10 level converted into the two {@link FrameDiff} thresholds.
 *
 * <p>Why one level instead of two sliders: the car owner answers only one question; "is it too
 * sensitive or too dull". The two thresholds move together, so you never get the "one is loosened
 * but the other is still stuck" situation where nothing you adjust is right.</p>
 *
 * <p>1 = least sensitive (only big motion fires), 10 = most sensitive (a person walking far away
 * fires, but rain, flying bugs, and leaves come in more easily too). Default 5.</p>
 */
public final class MotionSensitivity {

    public static final int MIN = 1;
    public static final int MAX = 10;
    public static final int DEFAULT = 5;

    /**
     * 最不敏敏时的平均差阈值。
     *
     * Average-difference threshold at the least sensitive end.
     */
    private static final double COARSE_THRESHOLD = 0.030;
    /**
     * 最敏敏时的平均差阈值。
     *
     * Average-difference threshold at the most sensitive end.
     */
    private static final double FINE_THRESHOLD = 0.004;
    /**
     * 两端的“显著变化像素占比”。
     *
     * The "fraction of significantly changed pixels" at the two ends.
     */
    private static final double COARSE_FRACTION = 0.020;
    private static final double FINE_FRACTION = 0.004;

    private MotionSensitivity() {
    }

    /**
     * 把任何读数夹到合法范围；读不到时用默认。
     *
     * Clamp any reading to the legal range; use the default when nothing is read.
     */
    public static int clamp(int level) {
        if (level < MIN) {
            return MIN;
        }
        return Math.min(level, MAX);
    }

    /**
     * 平均差阈值：档位越高越小（越灵敏）。
     *
     * Average-difference threshold: the higher the level, the smaller it is (more sensitive).
     */
    public static double threshold(int level) {
        return lerp(COARSE_THRESHOLD, FINE_THRESHOLD, clamp(level));
    }

    /**
     * 显著变化像素占比阈值，同样随档位走。
     *
     * Fraction-of-significantly-changed-pixels threshold, also follows the level.
     */
    public static double fraction(int level) {
        return lerp(COARSE_FRACTION, FINE_FRACTION, clamp(level));
    }

    private static double lerp(double coarse, double fine, int level) {
        double t = (level - MIN) / (double) (MAX - MIN);
        return coarse + (fine - coarse) * t;
    }
}
