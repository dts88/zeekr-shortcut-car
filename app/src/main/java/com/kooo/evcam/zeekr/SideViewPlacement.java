package com.kooo.evcam.zeekr;

/**
 * 盲区画面窗口的大小和位置：固定的，不能拖、不能缩放，左右各一个位置（项目所有者 2026-10-16）。
 *
 * <p>左灯的画面靠屏幕左边、右灯的靠右边，离那一侧的屏幕边 {@link #EDGE_PERCENT}% 屏幕宽；上下居中 ——
 * 超级镜子默认在右上角，两个窗口同时出现时不叠在一起。高度是屏幕高的 {@link #HEIGHT_PERCENT}%，宽 ÷ 高 = 4 : 3
 * （看旁边车道，横着比竖着有用）。屏幕放不下时按屏幕收。这几个数是第一版，上车看了再定。</p>
 *
 * <p>纯 Java，见 {@code SideViewPlacementTest}。</p>
 */
public final class SideViewPlacement {

    static final int HEIGHT_PERCENT = 36;
    static final int EDGE_PERCENT = 3;
    /** 宽 ÷ 高。 */
    static final float ASPECT = 4f / 3f;

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
    }

    /**
     * @param lane {@link LaneCycle#LEFT} 靠左；其他（{@link LaneCycle#RIGHT}）靠右
     */
    public static Rect compute(int screenWidth, int screenHeight, int lane) {
        int w = Math.max(1, screenWidth);
        int h = Math.max(1, screenHeight);
        int height = Math.max(1, Math.round(h * HEIGHT_PERCENT / 100f));
        int width = Math.round(height * ASPECT);
        if (width > w) {
            width = w;
            height = Math.max(1, Math.min(height, Math.round(w / ASPECT)));
        }
        int edge = Math.min(Math.round(w * EDGE_PERCENT / 100f), Math.max(0, (w - width) / 2));
        int left = lane == LaneCycle.LEFT ? edge : w - width - edge;
        int top = Math.max(0, (h - height) / 2);
        return new Rect(left, top, width, height);
    }
}
