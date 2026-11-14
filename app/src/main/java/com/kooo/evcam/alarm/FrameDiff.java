/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 两帧缩略图的差 —— 纯数学，不碰 GL、不碰相机。
 *
 * <h3>为什么不是「差了多少就算动了」</h3>
 *
 * <p>夜里最容易被三件事骗到：自动曝光一变整幅亮度同时跳；雨、虫子、树叶在镜头前一闪；
 * 传感器噪点。所以过三道：整幅平均亮度跳过 {@link #EXPOSURE_JUMP} 就当曝光在动；
 * 平均差要够大；显著变化的像素占比要够多。</p>
 */
public final class FrameDiff {

    /** 整幅平均亮度跳这么多（0–1）就当曝光变了，直接不算。 */
    public static final double EXPOSURE_JUMP = 0.22;

    /**
     * 超过这个平均差（0–1）算「有东西」。
     *
     * <p>128×72 的小图里，十几米外走过的人只占百分之一两的像素，
     * 平均差只有 0.01–0.03。阈值定在 0.03 时它根本不会响——
     * 测过了「人在车边走”却没警报”就是这个原因。</p>
     */
    public static final double DEFAULT_THRESHOLD = 0.012;

    /** 单像素差多少算「显著变化」（0–255）。低于它是噪声。 */
    public static final int SIGNIFICANT_DELTA = 24;

    /** 显著变化的像素至少占这么多（0–1）。 */
    public static final double MIN_CHANGED_FRACTION = 0.008;

    private FrameDiff() {
    }

    /**
     * 两帧的差。
     *
     * @return 0–1；{@code 0} 表示没动、或者被当成曝光 / 噪点丢掉了
     */
    public static double score(int[] previous, int[] current) {
        if (previous == null || current == null || previous.length == 0
                || previous.length != current.length) {
            return 0;
        }
        int n = previous.length;
        long sumBefore = 0;
        long sumAfter = 0;
        for (int i = 0; i < n; i++) {
            sumBefore += previous[i];
            sumAfter += current[i];
        }
        double meanBefore = (double) sumBefore / n / 255.0;
        double meanAfter = (double) sumAfter / n / 255.0;
        if (Math.abs(meanAfter - meanBefore) >= EXPOSURE_JUMP) {
            return 0;
        }
        long total = 0;
        int changed = 0;
        for (int i = 0; i < n; i++) {
            int delta = Math.abs(current[i] - previous[i]);
            total += delta;
            if (delta >= SIGNIFICANT_DELTA) {
                changed++;
            }
        }
        double score = (double) total / n / 255.0;
        if (score < DEFAULT_THRESHOLD) {
            return 0;
        }
        if ((double) changed / n < MIN_CHANGED_FRACTION) {
            return 0;
        }
        return Math.min(1.0, score);
    }

    /** 这个分算不算「有东西」。 */
    public static boolean isMovement(double score, double threshold) {
        return score >= threshold;
    }
}
