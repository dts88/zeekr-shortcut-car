/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 画面里有东西在动吗 —— 编码器每隔几帧读一次小缩略图，这里只管判断和分发。
 *
 * <h3>为什么放在编码器里</h3>
 *
 * <p>环视的每一帧都经过 {@code EglSurfaceEncoder} 的 GL 上下文。在那里把同一张 OES 贴图
 * 画进一张 128×72 的离屏缓存再读回来，是几十微秒的事；换成“定时拍一张 JPEG”
 * 则要 120–200 毫秒，还会和录像抢同一批相机。</p>
 *
 * <h3>什么时候才读</h3>
 *
 * <p>只有警报开着、相机在录、且车停着的时候才要帧（{@link #wantsFrames} 由
 * {@code SentryAlerts} 置位）。开车时一次也不读 —— 那时候的画面本来就在动。</p>
 *
 * Is something moving in the picture; the encoder reads a small thumbnail every few frames, and
 * this class only judges and dispatches.
 *
 * <h3>Why put it in the encoder</h3>
 *
 * <p>Every surround-view frame goes through {@code EglSurfaceEncoder}'s GL context. Drawing the same
 * OES texture into a 128×72 off-screen buffer and reading it back there takes tens of microseconds;
 * replacing it with "take a JPEG on a timer" takes 120–200 milliseconds and also fights recording
 * for the same cameras.</p>
 *
 * <h3>When it reads at all</h3>
 *
 * <p>It only wants frames when the alarm is on, the camera is recording, and the car is parked
 * ({@link #wantsFrames} is set by {@code SentryAlerts}). While driving it never reads; the picture
 * is already moving then.</p>
 */
public final class MotionTap {

    /**
     * 多久取一次样。四分之一秒足够了：走过的人不会只在两帧之间出现。
     *
     * How often to sample. A quarter second is enough: a person walking by will not appear only
     * between two frames.
     */
    private static final long SAMPLE_INTERVAL_NS = 250_000_000L;

    /**
     * 一张小图多少像素（128×72）。
     *
     * How many pixels the small image has (128×72).
     */
    public static final int WIDTH = 128;
    public static final int HEIGHT = 72;

    public interface Listener {
        /**
         * @param score 0–1；{@link FrameDiff} 已经把曝光跳变和噪点过滤掉了
         *
         * @param score 0–1; {@link FrameDiff} has already filtered out exposure jumps and noise
         */
        void onMotionScore(double score);
    }

    private static final MotionTap INSTANCE = new MotionTap();

    private volatile boolean wanted;
    private volatile Listener listener;
    private long lastSampleAt;
    /**
     * 取过几次样、最近一次的分数：不出警报时，这两个数字是唯一能看到
     * “它到底看没看见”的东西。
     *
     * How many samples were taken and the most recent score: when no alarm fires, these two numbers
     * are the only thing that shows "whether it saw anything at all".
     */
    private volatile int samples;
    private volatile double lastScore;
    private volatile long lastSampleWallClock;
    /**
     * 现在用的两个阈值（由灵敏度档位算出来）。
     *
     * The two thresholds in use now (computed from the sensitivity level).
     */
    private volatile double threshold = MotionSensitivity.threshold(MotionSensitivity.DEFAULT);
    private volatile double fraction = MotionSensitivity.fraction(MotionSensitivity.DEFAULT);
    private int[] previous;
    private int[] current;

    private MotionTap() {
    }

    public static MotionTap get() {
        return INSTANCE;
    }

    /**
     * 警报要不要看画面。关掉时编码器连一次都不读。
     *
     * Whether the alarm wants to look at the picture. When off, the encoder never reads.
     */
    public void setWanted(boolean value) {
        wanted = value;
        if (!value) {
            previous = null;
        }
    }

    public void setListener(Listener value) {
        listener = value;
    }

    /**
     * 现在该不该读一帧（编码器每帧问一次，很廉价）。
     *
     * Whether to read a frame now (the encoder asks every frame, very cheap).
     */
    public boolean wantsFrames(long nowNs) {
        if (!wanted || listener == null) {
            return false;
        }
        if (lastSampleAt != 0 && nowNs - lastSampleAt < SAMPLE_INTERVAL_NS) {
            return false;
        }
        lastSampleAt = nowNs;
        return true;
    }

    /**
     * 编码器读回来的一弧亮度图（长度 {@code WIDTH*HEIGHT}）。
     *
     * The luma image the encoder reads back (length {@code WIDTH*HEIGHT}).
     */
    public void submit(int[] luma) {
        if (luma == null || luma.length != WIDTH * HEIGHT) {
            return;
        }
        if (current == null) {
            current = new int[luma.length];
        }
        System.arraycopy(luma, 0, current, 0, luma.length);
        double score = previous == null ? 0 : FrameDiff.score(previous, current, threshold, fraction);
        int[] swap = previous;
        previous = current;
        current = swap;
        samples++;
        lastScore = score;
        lastSampleWallClock = System.currentTimeMillis();
        if (score > 0) {
            Listener target = listener;
            if (target != null) {
                target.onMotionScore(score);
            }
        }
    }

    /**
     * 换一个灵敏度（1–10）。
     *
     * Change the sensitivity (1–10).
     */
    public void setSensitivity(int level) {
        threshold = MotionSensitivity.threshold(level);
        fraction = MotionSensitivity.fraction(level);
    }

    /**
     * 现在的平均差阈值（诊断里显示用）。
     *
     * The current average-difference threshold (for showing in diagnostics).
     */
    public double threshold() {
        return threshold;
    }

    /**
     * 取过多少次样。
     *
     * How many samples have been taken.
     */
    public int samples() {
        return samples;
    }

    /**
     * 最近一次的分数（过滤之后）；0 表示没动或者还没取过样。
     *
     * The most recent score (after filtering); 0 means no movement or no sample taken yet.
     */
    public double lastScore() {
        return lastScore;
    }

    /**
     * 最近一次取样的时刻（壁钟）；0 表示没取过。
     *
     * The time of the most recent sample (wall clock); 0 means none taken.
     */
    public long lastSampleWallClock() {
        return lastSampleWallClock;
    }

    /**
     * 下一次从头算（比如刚从开车变成停车）。
     *
     * Recalculate from scratch next time (for example right after changing from driving to parked).
     */
    public void forget() {
        previous = null;
        lastSampleAt = 0;
    }
}
