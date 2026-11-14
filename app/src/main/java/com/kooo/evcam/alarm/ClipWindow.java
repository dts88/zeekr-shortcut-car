/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import java.util.List;

/**
 * 从已编码的样本里切出最后几秒 —— 纯选择，不碰编码器、不碰 muxer（{@code ClipWindowTest}）。
 *
 * <h3>为什么开头必须是关键帧</h3>
 *
 * <p>H.264 的一段视频要从关键帧开始才能解；从中间一帧切开，前几十帧是花的。
 * 所以窗口起点往前找最近的关键帧，宁可多几百毫秒。</p>
 *
 * <h3>声音怎么跑</h3>
 *
 * <p>声音样本没有关键帧，只能跟着画面走：取窗口里的画面时间范围，把落在里面的声音全拿。</p>
 */
public final class ClipWindow {

    /** 一次切出来的范围：{@code [from, to)} 下标，以及归零用的基准时间戳。 */
    public static final class Cut {
        public final int from;
        public final int to;
        /** 第一个被选中的样本的时间戳（微秒）；muxer 要从 0 开始。 */
        public final long baseUs;

        Cut(int from, int to, long baseUs) {
            this.from = from;
            this.to = to;
            this.baseUs = baseUs;
        }

        public boolean isEmpty() {
            return to <= from;
        }

        public int count() {
            return Math.max(0, to - from);
        }
    }

    private ClipWindow() {
    }

    /**
     * 选出最后 {@code windowUs} 微秒里的样本。
     *
     * @param ptsUs   每个样本的时间戳，单调递增
     * @param keyframe 哪些是关键帧（声音样本一律不是）
     * @param audio   哪些是声音样本
     * @param endUs   窗口结尾（通常是最后一个样本的时间戳）
     * @param windowUs 要多长
     * @return 一个空的 {@link Cut}（{@code isEmpty()} 为真）表示这段里没有可用的画面
     */
    public static Cut choose(long[] ptsUs, boolean[] keyframe, boolean[] audio, long endUs, long windowUs) {
        int empty = 0;
        if (ptsUs == null || keyframe == null || audio == null
                || ptsUs.length != keyframe.length || ptsUs.length != audio.length
                || endUs <= 0 || windowUs <= 0) {
            return new Cut(0, empty, 0);
        }
        long start = endUs - windowUs;
        // 窗口起点往前找最近的关键帧（不超过最后一个）
        int from = -1;
        for (int i = 0; i < ptsUs.length; i++) {
            if (audio[i] || !keyframe[i]) {
                continue;
            }
            if (ptsUs[i] <= start) {
                from = i;
            } else {
                break;
            }
        }
        if (from < 0) {
            // 窗口内还没有关键帧：退而求其次，从第一个关键帧开始（宁可短一点，也不能从中间切）
            for (int i = 0; i < ptsUs.length; i++) {
                if (!audio[i] && keyframe[i]) {
                    from = i;
                    break;
                }
            }
        }
        if (from < 0) {
            return new Cut(0, empty, 0);
        }
        int lastVideo = -1;
        for (int i = ptsUs.length - 1; i >= from; i--) {
            if (!audio[i]) {
                lastVideo = i;
                break;
            }
        }
        if (lastVideo < 0) {
            return new Cut(0, empty, 0);
        }
        return new Cut(from, lastVideo + 1, ptsUs[from]);
    }

    /** 把一个时间戳换成 muxer 要的相对时间（从 {@code baseUs} 算起，毫秒）。 */
    public static long relativeMs(long ptsUs, long baseUs) {
        return Math.max(0L, (ptsUs - baseUs) / 1000L);
    }

    /** 这份样本里有声音吗。 */
    public static boolean hasAudio(List<Boolean> audio) {
        if (audio == null) {
            return false;
        }
        for (Boolean value : audio) {
            if (Boolean.TRUE.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
