/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

/**
 * 中控屏上盲区画面窗口的大小和位置：左右各一个位置，<b>互为镜像</b>（项目所有者 2026-11-03）—— 一样高、一样大，
 * 以屏幕的竖中线为轴左右对称；左侧的画面在左边那个位置，右侧的在右边。两个位置可以叠在一起，但左边那个不过中线
 * （最多和右边那个完全叠在屏幕正中）—— 过了就是左右对调，没有人要那样。
 *
 * <p>宽 ÷ 高固定 {@link #ASPECT}（2.2 : 1，左右看得多比上下看得多有用 —— 照 FerixzTanz 实车用的默认，CAMCORDER；
 * 项目所有者 2026-10-17 定换成偏宽的），只能调位置和大小，不能调比例。只能在设置里调（{@link SideViewPlacementEditor}）：
 * 窗口本身不能拖、不能缩放，开车时动不了它（项目所有者 2026-11-03）。</p>
 *
 * <p>存的是相对屏幕的千分比（{@link Spec}），换了屏幕分辨率照样落在同一处：高度占屏高、左边那个离屏幕左边占屏宽、上边占屏高。
 * 默认（{@link Spec#DEFAULT}）是以前固定的那个：高 {@link #HEIGHT_PERCENT}% 屏高、离边 {@link #EDGE_PERCENT}% 屏宽、上下居中 ——
 * 超级镜子默认在右上角，两个窗口同时出现时不叠在一起。屏幕放不下时按屏幕收。</p>
 *
 * <p>纯 Java，见 {@code SideViewPlacementTest}。</p>
 */
public final class SideViewPlacement {

    static final int HEIGHT_PERCENT = 33;
    static final int EDGE_PERCENT = 3;
    /** 宽 ÷ 高。 */
    static final float ASPECT = 2.2f;
    /** 最小多高：屏高的千分之几（太小看不清，手指也抓不住框）。 */
    static final int MIN_HEIGHT_PERMILLE = 120;

    private SideViewPlacement() {
    }

    /** 窗口在屏幕上的位置（像素）。 */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        Rect(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        public int right() {
            return left + width;
        }

        public int bottom() {
            return top + height;
        }
    }

    /** 存着的位置和大小：千分比（{@code top} 是 -1 时上下居中）。不可变。 */
    public static final class Spec {
        /** 以前固定的那个：高 33% 屏高、离边 3% 屏宽、上下居中。 */
        public static final Spec DEFAULT = new Spec(HEIGHT_PERCENT * 10, EDGE_PERCENT * 10, -1);

        /** 高度：屏高的千分之几。 */
        public final int height;
        /** 左边那个离屏幕左边多远：屏宽的千分之几（右边那个离屏幕右边一样远）。 */
        public final int edge;
        /** 上边离屏幕上边多远：屏高的千分之几；-1 = 上下居中。 */
        public final int top;

        public Spec(int height, int edge, int top) {
            this.height = height;
            this.edge = edge;
            this.top = top;
        }

        public boolean isDefault() {
            return height == DEFAULT.height && edge == DEFAULT.edge && top == DEFAULT.top;
        }
    }

    /** 默认的位置和大小（{@link Spec#DEFAULT}）。 */
    public static Rect compute(int screenWidth, int screenHeight, int lane) {
        return compute(screenWidth, screenHeight, lane, Spec.DEFAULT);
    }

    /**
     * @param lane {@link LaneCycle#LEFT} 左边那个位置；其他（{@link LaneCycle#RIGHT}）右边那个（左边那个的镜像）
     */
    public static Rect compute(int screenWidth, int screenHeight, int lane, Spec spec) {
        Rect left = left(screenWidth, screenHeight, spec);
        return lane == LaneCycle.LEFT ? left : mirror(left, Math.max(1, screenWidth));
    }

    /** 左边那个：大小先按屏幕收（宽放不下按宽收），再把位置收进屏幕、不过中线。 */
    static Rect left(int screenWidth, int screenHeight, Spec spec) {
        int w = Math.max(1, screenWidth);
        int h = Math.max(1, screenHeight);
        int permille = Math.max(MIN_HEIGHT_PERMILLE, Math.min(1000, spec.height));
        int height = Math.max(1, Math.round(h * permille / 1000f));
        int width = Math.round(height * ASPECT);
        if (width > w) {
            width = w;
            height = Math.max(1, Math.min(height, Math.round(w / ASPECT)));
        }
        int edge = Math.max(0, Math.min(Math.round(w * Math.max(0, spec.edge) / 1000f), maxEdge(w, width)));
        int top = spec.top < 0 ? Math.max(0, (h - height) / 2)
                : Math.max(0, Math.min(Math.round(h * spec.top / 1000f), h - height));
        return new Rect(edge, top, width, height);
    }

    /** 左边那个离屏幕左边最远多少：再远就过了中线（左右两个完全叠在正中）。 */
    static int maxEdge(int screenWidth, int width) {
        return Math.max(0, (screenWidth - width) / 2);
    }

    /** 以屏幕竖中线为轴的镜像：一样高、一样大，左右位置相反。 */
    static Rect mirror(Rect r, int screenWidth) {
        return new Rect(screenWidth - r.left - r.width, r.top, r.width, r.height);
    }

    /** 左边那个框（像素）存成千分比：编辑框保存时用（{@link SideViewPlacementEditor}）。 */
    static Spec toSpec(int screenWidth, int screenHeight, Rect left) {
        int w = Math.max(1, screenWidth);
        int h = Math.max(1, screenHeight);
        return new Spec(Math.round(left.height * 1000f / h), Math.round(left.left * 1000f / w),
                Math.round(left.top * 1000f / h));
    }
}
