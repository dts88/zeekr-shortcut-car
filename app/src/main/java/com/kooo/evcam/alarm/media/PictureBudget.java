/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

/**
 * 一张要发的照片能有多大：先降画质，再降尺寸，直到进得了预算（纯逻辑，{@code PictureBudgetTest}）。
 *
 * <h3>为什么要预算，而不是一个固定画质</h3>
 *
 * <p>同一张夜间四宫格，q80 可能 350 kB，也可能 700 kB —— 像素里有多少细节，编码器说了算。
 * 车主那边要的是「小到可以完全不管」，所以这里钉住的是<b>字节数</b>，让画质跟着内容走。</p>
 *
 * <h3>顺序：先降画质，再降尺寸</h3>
 *
 * <p>先降画质只丢噪声，先降尺寸丢的是车牌上的笔画。所以一档档降到最差的画质还不进预算，才缩一次图，
 * 再从较高的画质重来。缩得越狠，一张图越不像证据，所以缩到 {@link #MIN_SCALE} 就停，报「发不出这么小的」。</p>
 *
 * <h3>Why a budget and not a fixed quality</h3>
 *
 * <p>The same night-time four-up can be 350 kB at q80 or 700 kB; the content decides. The owner asked for
 * something small enough to ignore, so the thing that is pinned here is the byte count, and quality
 * follows the content. Quality drops before size does — noise goes first, plate strokes last — and the
 * scale stops at {@link #MIN_SCALE}, because a picture scaled further stops being evidence.</p>
 */
public final class PictureBudget {

    /** 警报第一张的上限：不超过 100 kB（车主的原话「小到可以完全不管」）。 */
    public static final int ALERT_BYTES = 100 * 1024;

    /** 「HD 一下」那张的上限：还是老样子，看得清车牌比省流量重要。 */
    public static final int HIGH_BYTES = 900 * 1024;

    /** 一段按需视频的上限：10 MB（车主的原话「within ~10MB」）。 */
    public static final int CLIP_BYTES = 10 * 1024 * 1024;

    /** 画质一档档往下试。The quality steps, best first. */
    static final int[] QUALITIES = {70, 60, 50, 45};

    /** 画质降到底还不够时，缩一次图再从头试。The scales, full size first. */
    static final float[] SCALES = {1.0f, 0.85f, 0.7f};

    /** 再小就不像证据了：到这里还不进预算就报最大的一张。Below this the picture stops being evidence. */
    static final float MIN_SCALE = 0.7f;

    private PictureBudget() {
    }

    /**
     * 第几步试什么。一步是「这个尺寸 + 这个画质」。
     *
     * @param step 从 0 开始；超出范围返回 {@code null}（没有更多可试的了）
     * @return {@code [scale, quality]}，或者 {@code null}
     */
    public static float[] step(int step) {
        if (step < 0 || step >= SCALES.length * QUALITIES.length) {
            return null;
        }
        // 先把这个尺寸下的画质降完，再缩图：外层是尺寸，内层是画质
        int scale = step / QUALITIES.length;
        int quality = step % QUALITIES.length;
        return new float[] {SCALES[scale], QUALITIES[quality]};
    }

    /** 一共有几步可试。How many steps exist. */
    public static int steps() {
        return SCALES.length * QUALITIES.length;
    }

    /** 这个字节数进预算了没有。Whether this many bytes fit. */
    public static boolean fits(long bytes, int maxBytes) {
        return bytes > 0 && bytes <= maxBytes;
    }

    /** 一步的尺寸要不要缩图（缩过就得另建一张，用完要回收）。Whether this step needs a scaled copy. */
    public static boolean shrinks(float scale) {
        return scale < 1.0f;
    }

    /** 人话里的「多大」：界面和回话用它，不念字节。A plain size for the UI: kB, or MB once it is big. */
    public static String size(long bytes) {
        if (bytes <= 0) {
            return "0 kB";
        }
        long kb = (bytes + 1023) / 1024;
        if (kb < 1024) {
            return kb + " kB";
        }
        long tenths = (bytes * 10 + 512 * 1024) / (1024 * 1024);
        return (tenths / 10) + "." + (tenths % 10) + " MB";
    }
}
