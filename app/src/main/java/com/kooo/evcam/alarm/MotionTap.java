/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
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
 */
public final class MotionTap {

    /** 多久取一次样。四分之一秒足够了：走过的人不会只在两帧之间出现。 */
    private static final long SAMPLE_INTERVAL_NS = 250_000_000L;

    /** 一张小图多少像素（128×72）。 */
    public static final int WIDTH = 128;
    public static final int HEIGHT = 72;

    public interface Listener {
        /** @param score 0–1；{@link FrameDiff} 已经把曝光跳变和噪点过滤掉了 */
        void onMotionScore(double score);
    }

    private static final MotionTap INSTANCE = new MotionTap();

    private volatile boolean wanted;
    private volatile Listener listener;
    private long lastSampleAt;
    /**
     * 取过几次样、最近一次的分数：不出警报时，这两个数字是唯一能看到
     * “它到底看没看见”的东西。
     */
    private volatile int samples;
    private volatile double lastScore;
    private volatile long lastSampleWallClock;
    private int[] previous;
    private int[] current;

    private MotionTap() {
    }

    public static MotionTap get() {
        return INSTANCE;
    }

    /** 警报要不要看画面。关掉时编码器连一次都不读。 */
    public void setWanted(boolean value) {
        wanted = value;
        if (!value) {
            previous = null;
        }
    }

    public void setListener(Listener value) {
        listener = value;
    }

    /** 现在该不该读一帧（编码器每帧问一次，很廉价）。 */
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

    /** 编码器读回来的一弧亮度图（长度 {@code WIDTH*HEIGHT}）。 */
    public void submit(int[] luma) {
        if (luma == null || luma.length != WIDTH * HEIGHT) {
            return;
        }
        if (current == null) {
            current = new int[luma.length];
        }
        System.arraycopy(luma, 0, current, 0, luma.length);
        double score = previous == null ? 0 : FrameDiff.score(previous, current);
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

    /** 取过多少次样。 */
    public int samples() {
        return samples;
    }

    /** 最近一次的分数（过滤之后）；0 表示没动或者还没取过样。 */
    public double lastScore() {
        return lastScore;
    }

    /** 最近一次取样的时刻（壁钟）；0 表示没取过。 */
    public long lastSampleWallClock() {
        return lastSampleWallClock;
    }

    /** 下一次从头算（比如刚从开车变成停车）。 */
    public void forget() {
        previous = null;
        lastSampleAt = 0;
    }
}
