/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

/**
 * 两帧缩略图的差 —— 纯数学，不碰 GL、不碰相机。
 *
 * <h3>为什么不是「差了多少就算动了」</h3>
 *
 * <p>夜里最容易被三件事骗到：自动曝光一变整幅亮度同时跳；雨、虫子、树叶在镜头前一闪；
 * 传感器噪点。所以过三道：整幅平均亮度跳过 {@link #EXPOSURE_JUMP} 就当曝光在动；
 * 平均差要够大；显著变化的像素占比要够多。</p>
 *
 * Difference between two thumbnail frames; pure math, no GL, no camera.
 *
 * <h3>Why not "the difference is how much moved"</h3>
 *
 * <p>At night three things fool you most easily: an auto-exposure change makes the whole frame's
 * brightness jump at once; rain, bugs, and leaves flash in front of the lens; sensor noise. So it
 * passes three gates: if the whole-frame average brightness jumps by {@link #EXPOSURE_JUMP}, treat
 * it as exposure changing; the average difference must be big enough; the fraction of significantly
 * changed pixels must be high enough.</p>
 */
public final class FrameDiff {

    /**
     * 整幅平均亮度跳这么多（0–1）就当曝光变了，直接不算。
     *
     * Whole-frame average brightness jumping this much (0–1) counts as exposure
     * changing, so it is skipped.
     */
    public static final double EXPOSURE_JUMP = 0.22;

    /**
     * 超过这个平均差（0–1）算「有东西」。
     *
     * <p>128×72 的小图里，十几米外走过的人只占百分之一两的像素，
     * 平均差只有 0.01–0.03。阈值定在 0.03 时它根本不会响——
     * 测过了「人在车边走”却没警报”就是这个原因。</p>
     *
     * An average difference above this (0–1) counts as "something there".
     *
     * <p>In a 128×72 thumbnail, a person walking a dozen meters away covers only one or two percent
     * of the pixels, so the average difference is only 0.01–0.03. With a threshold of 0.03 it never
     * fires; the tested case of "someone walks beside the car" with no alarm is exactly this.</p>
     */
    public static final double DEFAULT_THRESHOLD = 0.012;

    /**
     * 单像素差多少算「显著变化」（0–255）。低于它是噪声。
     *
     * How much a single pixel must differ to count as a "significant change"
     * (0–255). Below this it is noise.
     */
    public static final int SIGNIFICANT_DELTA = 24;

    /**
     * 显著变化的像素至少占这么多（0–1）。
     *
     * Significantly changed pixels must make up at least this fraction (0–1).
     */
    public static final double MIN_CHANGED_FRACTION = 0.008;

    private FrameDiff() {
    }

    /**
     * 两帧的差。
     *
     * @return 0–1；{@code 0} 表示没动、或者被当成曝光 / 噪点丢掉了
     *
     * Difference between two frames.
     *
     * @return 0–1; {@code 0} means no movement, or it was discarded as exposure / noise
     */
    public static double score(int[] previous, int[] current) {
        return score(previous, current, DEFAULT_THRESHOLD, MIN_CHANGED_FRACTION);
    }

    /**
     * 同上，但阈值由调节器决定（{@link MotionSensitivity}）。
     *
     * @param threshold 平均差门槛
     * @param fraction  显著变化像素的最少占比
     *
     * Same as above, but the thresholds are decided by the adjuster ({@link MotionSensitivity}).
     *
     * @param threshold average-difference gate
     * @param fraction  minimum fraction of significantly changed pixels
     */
    public static double score(int[] previous, int[] current, double threshold, double fraction) {
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
        if (score < threshold) {
            return 0;
        }
        if ((double) changed / n < fraction) {
            return 0;
        }
        return Math.min(1.0, score);
    }

    /**
     * 这个分算不算「有东西」。
     *
     * Whether this score counts as "something there".
     */
    public static boolean isMovement(double score, double threshold) {
        return score >= threshold;
    }
}
