/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

/**
 * 盲区画面编辑框的换算（设置 → 盲区画面 → 中控屏上的位置和大小，项目所有者 2026-11-03）：两个框互为镜像，
 * 动哪一个都只改左边那一个，右边那个照 {@link SideViewPlacement#mirror} 画。
 *
 * <ul>
 *   <li>只调位置和大小，宽 ÷ 高不变（{@link SideViewPlacement#ASPECT}）。</li>
 *   <li>按在哪：照超级镜子编辑框（{@link RearViewFrameModel#hit}）—— 边、角、框内；两个框叠着时先看哪个框的边角抓住了，
 *       都抓住就算离中心近的那个，再看框内。</li>
 *   <li>拖右边那个：左右方向反过来（镜像），上下一样；抓的左边、右边也对调。</li>
 *   <li>拖角：对角不动，沿对角线放大缩小；拖一条边：对边不动、另一个方向居中不动。双指：以两指中点为锚缩放、跟着中点走。</li>
 *   <li>最后都收一遍（{@link #fit}）：高不小于 {@link SideViewPlacement#MIN_HEIGHT_PERMILLE}、不出编辑框、左边那个不过中线。</li>
 * </ul>
 *
 * <p>坐标是编辑框里的像素，编辑框和盲区画面窗口在同一个坐标里（同一类悬浮窗、同样的标志）。纯 Java，见 {@code SideViewFramesTest}。</p>
 */
public final class SideViewFrames {

    /** 按下的地方抓住了哪个框。 */
    public static final int NO_FRAME = 0;
    public static final int LEFT_FRAME = 1;
    public static final int RIGHT_FRAME = 2;

    private SideViewFrames() {
    }

    /** 一次按下：抓住哪个框（{@link #NO_FRAME} 是都没抓住）、抓住了什么（{@link RearViewFrameModel} 的边、角、框内）。 */
    public static final class Grab {
        public final int frame;
        public final int part;

        Grab(int frame, int part) {
            this.frame = frame;
            this.part = part;
        }

        public boolean any() {
            return frame != NO_FRAME && part != RearViewFrameModel.NONE;
        }
    }

    /** 右边那个框：左边那个以编辑框竖中线为轴的镜像。 */
    public static SideViewPlacement.Rect right(SideViewPlacement.Rect left, int boundsWidth) {
        return SideViewPlacement.mirror(left, boundsWidth);
    }

    /** 按下的点抓住了什么。 */
    public static Grab hit(SideViewPlacement.Rect left, int boundsWidth, float px, float py, float reach) {
        SideViewPlacement.Rect right = right(left, boundsWidth);
        int onLeft = RearViewFrameModel.hit(frame(left), px, py, reach);
        int onRight = RearViewFrameModel.hit(frame(right), px, py, reach);
        boolean leftEdge = onLeft != RearViewFrameModel.NONE && onLeft != RearViewFrameModel.MOVE;
        boolean rightEdge = onRight != RearViewFrameModel.NONE && onRight != RearViewFrameModel.MOVE;
        if (leftEdge && rightEdge) {
            return closer(left, right, px, py) ? new Grab(LEFT_FRAME, onLeft) : new Grab(RIGHT_FRAME, onRight);
        }
        if (leftEdge) {
            return new Grab(LEFT_FRAME, onLeft);
        }
        if (rightEdge) {
            return new Grab(RIGHT_FRAME, onRight);
        }
        if (onLeft == RearViewFrameModel.MOVE && onRight == RearViewFrameModel.MOVE) {
            return closer(left, right, px, py) ? new Grab(LEFT_FRAME, onLeft) : new Grab(RIGHT_FRAME, onRight);
        }
        if (onLeft == RearViewFrameModel.MOVE) {
            return new Grab(LEFT_FRAME, onLeft);
        }
        if (onRight == RearViewFrameModel.MOVE) {
            return new Grab(RIGHT_FRAME, onRight);
        }
        return new Grab(NO_FRAME, RearViewFrameModel.NONE);
    }

    /**
     * 拖：按下那一刻的左边那个框 {@code start}，手指从按下起走了 {@code dx, dy}。
     *
     * @return 新的左边那个框（已经收过，{@link #fit}）
     */
    public static SideViewPlacement.Rect drag(SideViewPlacement.Rect start, Grab grab, float dx, float dy,
                                              int boundsWidth, int boundsHeight) {
        if (!grab.any()) {
            return start;
        }
        int part = grab.part;
        if (grab.frame == RIGHT_FRAME) {
            // 拖的是镜像：左右方向反过来，抓的左右两边对调
            dx = -dx;
            part = swapSides(part);
        }
        if (part == RearViewFrameModel.MOVE) {
            return fit(Math.round(start.left + dx), Math.round(start.top + dy), start.height, boundsWidth, boundsHeight);
        }
        return resize(start, part, dx, dy, boundsWidth, boundsHeight);
    }

    /**
     * 双指缩放（照超级镜子 {@link RearViewTouchModel#pinch} 的手感：以两指中点为锚、跟着中点走）。
     *
     * @param start   两指按下那一刻的左边那个框
     * @param anchorX 两指中点在那个框里的位置（0–1，按下那一刻）
     * @param focusX  此刻的两指中点（编辑框坐标）；捏的是右边那个框时调用方先把它换成镜像那一侧的
     */
    public static SideViewPlacement.Rect pinch(SideViewPlacement.Rect start, float anchorX, float anchorY,
                                               float focusX, float focusY, float scale,
                                               int boundsWidth, int boundsHeight) {
        int height = Math.round(start.height * scale);
        int width = Math.round(height * SideViewPlacement.ASPECT);
        return fit(Math.round(focusX - anchorX * width), Math.round(focusY - anchorY * height), height,
                boundsWidth, boundsHeight);
    }

    /**
     * 收：高夹在最小和编辑框放得下的之间（宽 = 高 × {@link SideViewPlacement#ASPECT}，宽放不下按宽收），
     * 位置收进编辑框、左边那个不过中线（{@link SideViewPlacement#maxEdge}）。
     */
    public static SideViewPlacement.Rect fit(int x, int y, int height, int boundsWidth, int boundsHeight) {
        int w = Math.max(1, boundsWidth);
        int h = Math.max(1, boundsHeight);
        int maxHeight = Math.max(1, Math.min(h, (int) Math.floor(w / SideViewPlacement.ASPECT)));
        int minHeight = Math.min(maxHeight, Math.max(1, Math.round(h * SideViewPlacement.MIN_HEIGHT_PERMILLE / 1000f)));
        int fittedHeight = Math.max(minHeight, Math.min(maxHeight, height));
        int fittedWidth = Math.min(w, Math.round(fittedHeight * SideViewPlacement.ASPECT));
        int left = Math.max(0, Math.min(x, SideViewPlacement.maxEdge(w, fittedWidth)));
        int top = Math.max(0, Math.min(y, h - fittedHeight));
        return new SideViewPlacement.Rect(left, top, fittedWidth, fittedHeight);
    }

    /**
     * 拖角、拖边：对角（拖边时是对边，另一个方向居中）不动，宽 ÷ 高不变。大小先按不动的那一边收：再大就出编辑框、
     * 左边那个过中线；缩到最小、收不下时整个框挪进去（{@link #fit}）。
     */
    private static SideViewPlacement.Rect resize(SideViewPlacement.Rect start, int part, float dx, float dy,
                                                 int boundsWidth, int boundsHeight) {
        int w = Math.max(1, boundsWidth);
        int h = Math.max(1, boundsHeight);
        boolean dragLeft = (part & RearViewFrameModel.LEFT) != 0;
        boolean dragRight = (part & RearViewFrameModel.RIGHT) != 0;
        boolean dragTop = (part & RearViewFrameModel.TOP) != 0;
        boolean dragBottom = (part & RearViewFrameModel.BOTTOM) != 0;
        // 抓的那条边往外拖是变大
        float growX = dragLeft ? -dx : dx;
        float growY = dragTop ? -dy : dy;
        float wantHeight;
        if ((dragLeft || dragRight) && (dragTop || dragBottom)) {
            // 角：把手指走的投到对角线上
            float diagonal = (float) Math.sqrt((float) start.width * start.width + (float) start.height * start.height);
            float along = (growX * start.width + growY * start.height) / diagonal;
            wantHeight = start.height * (diagonal + along) / diagonal;
        } else if (dragLeft || dragRight) {
            wantHeight = (start.width + growX) / SideViewPlacement.ASPECT;
        } else {
            wantHeight = start.height + growY;
        }
        float cx = start.left + start.width / 2f;
        float cy = start.top + start.height / 2f;
        float capWidth = dragLeft ? start.right() : dragRight ? w - 2f * start.left : 2f * cx;
        float capHeight = dragTop ? start.bottom() : dragBottom ? h - start.top : 2f * Math.min(cy, h - cy);
        wantHeight = Math.min(wantHeight, Math.min(capHeight, capWidth / SideViewPlacement.ASPECT));
        SideViewPlacement.Rect sized = fit(start.left, start.top, Math.round(wantHeight), w, h);
        float x = dragLeft ? start.right() - sized.width : dragRight ? start.left : cx - sized.width / 2f;
        float y = dragTop ? start.bottom() - sized.height : dragBottom ? start.top : cy - sized.height / 2f;
        return fit(Math.round(x), Math.round(y), sized.height, w, h);
    }

    private static int swapSides(int part) {
        int out = part & ~(RearViewFrameModel.LEFT | RearViewFrameModel.RIGHT);
        if ((part & RearViewFrameModel.LEFT) != 0) {
            out |= RearViewFrameModel.RIGHT;
        }
        if ((part & RearViewFrameModel.RIGHT) != 0) {
            out |= RearViewFrameModel.LEFT;
        }
        return out;
    }

    /** 离哪个框的中心近：左边那个近（一样近也算左边）。 */
    private static boolean closer(SideViewPlacement.Rect left, SideViewPlacement.Rect right, float px, float py) {
        return distance(left, px, py) <= distance(right, px, py);
    }

    private static float distance(SideViewPlacement.Rect r, float px, float py) {
        float cx = r.left + r.width / 2f;
        float cy = r.top + r.height / 2f;
        return (px - cx) * (px - cx) + (py - cy) * (py - cy);
    }

    private static RearViewFrameModel.Frame frame(SideViewPlacement.Rect r) {
        return new RearViewFrameModel.Frame(r.left, r.top, r.width, r.height);
    }
}
