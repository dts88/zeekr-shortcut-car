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
    /**
     * 窗口被拖出屏幕多少比例才算「想把它收起来」。
     *
     * <p>一半 —— 拖到这个程度已经不像是手滑了。</p>
     */
    public static final float DOCK_OFFSCREEN_FRACTION = 0.5f;

    /**
     * 松手时该不该贴边。
     *
     * <p><b>窗口只要还整个在屏幕里，就一定不贴边。</b>这是这条规则的重点：
     * 之前判断的是「离屏幕边缘多近」，于是窗口明明还完整可见就被吸走了 ——
     * 「还没到边就隐藏了」说的就是这个。贴边应当是<b>做出来的动作</b>，
     * 不是靠近某个位置的副作用。</p>
     *
     * <p>两种算「做出来了」：</p>
     * <ul>
     *   <li>已经推出去一半以上 —— 拖到这个程度不会是手滑；</li>
     *   <li>朝着那条边甩了一下，而且窗口确实已经压到边上 ——
     *       快速甩出去，不用一路拖到底。</li>
     * </ul>
     *
     * @param velocityX        松手时的横向速度（px/s），右为正
     * @param minFlingVelocity 系统认定的最小甩动速度，取自
     *                         {@code ViewConfiguration.getScaledMinimumFlingVelocity()} ——
     *                         用平台自己的阈值，手感和其他应用一致，也省得自己编一个数字
     */
    public static Dock deliberateDock(int windowX, int width, int screenW,
                                      float velocityX, float minFlingVelocity) {
        if (screenW <= 0 || width <= 0) {
            return Dock.NONE;
        }
        int offLeft = Math.max(0, -windowX);
        int offRight = Math.max(0, windowX + width - screenW);

        // 还整个在屏幕里 —— 不管甩得多快都不贴边
        if (offLeft == 0 && offRight == 0) {
            return Dock.NONE;
        }

        float needed = width * DOCK_OFFSCREEN_FRACTION;
        if (offLeft >= needed) {
            return Dock.LEFT;
        }
        if (offRight >= needed) {
            return Dock.RIGHT;
        }

        if (minFlingVelocity > 0) {
            if (velocityX <= -minFlingVelocity && offLeft > 0) {
                return Dock.LEFT;
            }
            if (velocityX >= minFlingVelocity && offRight > 0) {
                return Dock.RIGHT;
            }
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

    /**
     * 把窗口夹回屏幕内，允许贴边时露出一部分在外面。
     *
     * <p>窗口比屏幕还宽时（当前尺寸上限装不下，但公式不该因此失效）另算：
     * 此时它必然覆盖整个屏幕，能做的只是左右平移去看两端，所以左边缘限制在
     * {@code [screenW - width, 0]}。不这样分情况的话，通用公式会允许把一个
     * 超宽窗口拖到几乎完全移出屏幕。</p>
     */
    public static int clampX(int x, int width, int screenW, int peekWidth) {
        if (width >= screenW) {
            return Math.max(screenW - width, Math.min(0, x));
        }
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
