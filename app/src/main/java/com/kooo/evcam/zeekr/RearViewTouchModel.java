package com.kooo.evcam.zeekr;

/**
 * 超级后视镜的手势规则。
 *
 * <p>把「摸在哪里、拖多远、变成什么」这套换算单独拎出来，是因为它决定手感，
 * 而手感错了在车上只会觉得「怪」，说不清哪里怪。放在这里可以直接跑单元测试。</p>
 *
 * <p>横向分三段：</p>
 *
 * <pre>
 *   ┌────────┬────────┬────────┐
 *   │  左1/3  │  中1/3  │  右1/3  │
 *   │  拖动   │ 上下滑  │  拖动   │
 *   │  窗口   │ 调取景  │  窗口   │
 *   └────────┴────────┴────────┘
 * </pre>
 */
public final class RearViewTouchModel {

    /** 触摸落在哪一段。 */
    public enum Zone {
        /** 左右三分之一：拖动窗口本身。 */
        MOVE_WINDOW,
        /** 中间三分之一：上下滑调整取景范围，窗口不动。 */
        ADJUST_CROP
    }

    /** 停靠到哪一边。 */
    public enum Dock {
        NONE, LEFT, RIGHT
    }

    private RearViewTouchModel() {
    }

    /**
     * 触摸点落在哪一段。
     *
     * @param x         触摸点在视图内的横坐标
     * @param viewWidth 视图宽度
     */
    public static Zone zoneFor(float x, int viewWidth) {
        if (viewWidth <= 0) {
            return Zone.MOVE_WINDOW;
        }
        float third = viewWidth / 3f;
        return (x >= third && x < third * 2f) ? Zone.ADJUST_CROP : Zone.MOVE_WINDOW;
    }

    /**
     * 竖直拖动换算成取景框的位移。
     *
     * <p>做成 1:1：手指在窗口里移动多少，画面就跟着移动多少。取景框只占该路画面的
     * {@code cropHeight}，而它被拉伸到整个窗口高度，所以窗口里的一个像素对应
     * 画面里的 {@code cropHeight / viewHeight}。</p>
     *
     * <p>方向是反的：手指往下拖，是想看画面<b>上方</b>的内容，取景框要往上走。</p>
     *
     * @param dy         手指竖直位移（像素，向下为正）
     * @param viewHeight 窗口高度
     * @param cropHeight 当前取景框高度（归一化）
     * @return 取景框的归一化位移，可直接喂给 {@link RearViewGeometry.Crop#shiftedVertically}
     */
    public static float cropShiftForDrag(float dy, int viewHeight, float cropHeight) {
        if (viewHeight <= 0) {
            return 0f;
        }
        return -(dy / viewHeight) * cropHeight;
    }

    /**
     * 拖到边缘后停靠到哪一侧。
     *
     * @param windowX   窗口左边缘
     * @param width     窗口宽度
     * @param screenW   屏幕宽度
     * @param threshold 距离边缘多少像素以内算贴边
     */
    public static Dock dockFor(int windowX, int width, int screenW, int threshold) {
        if (screenW <= 0 || width <= 0) {
            return Dock.NONE;
        }
        if (windowX <= threshold) {
            return Dock.LEFT;
        }
        if (windowX + width >= screenW - threshold) {
            return Dock.RIGHT;
        }
        return Dock.NONE;
    }

    /**
     * 停靠后窗口的横坐标：只露出一条边，其余推到屏幕外。
     *
     * @param dock       停靠方向
     * @param width      窗口宽度
     * @param screenW    屏幕宽度
     * @param peekWidth  贴边后仍然露出的宽度，用来再把它拖回来
     * @return 停靠后的 x；{@link Dock#NONE} 时返回原值
     */
    public static int dockedX(Dock dock, int windowX, int width, int screenW, int peekWidth) {
        switch (dock) {
            case LEFT:
                return peekWidth - width;
            case RIGHT:
                return screenW - peekWidth;
            default:
                return windowX;
        }
    }

    /**
     * 双指缩放后的窗口边长。
     *
     * <p>按比例缩放并夹在允许范围内。宽高一起缩，比例不变 ——
     * 后视镜的取景比例由蒙版决定，不该被随手捏变形。</p>
     */
    public static int scaledSize(int current, float factor, int min, int max) {
        if (factor <= 0f) {
            return current;
        }
        int scaled = Math.round(current * factor);
        return Math.max(min, Math.min(max, scaled));
    }

    /** 把窗口夹回屏幕内，允许贴边时露出一部分在外面。 */
    public static int clampX(int x, int width, int screenW, int peekWidth) {
        int min = peekWidth - width;
        int max = screenW - peekWidth;
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, x));
    }

    /** 竖直方向不允许移出屏幕 —— 上下没有停靠行为，划出去就找不回来了。 */
    public static int clampY(int y, int height, int screenH) {
        int max = screenH - height;
        if (max < 0) {
            return 0;
        }
        return Math.max(0, Math.min(max, y));
    }
}
