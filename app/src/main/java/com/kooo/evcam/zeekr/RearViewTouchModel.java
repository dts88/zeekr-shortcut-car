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
     * 中间三分之一的这一下，是「左右划着换一路」还是「上下挪取景」。
     *
     * <p>一开始就把方向锁死，而不是等松手再判断：不锁的话，横着划的过程中
     * 那一点点竖直位移会让画面上下抖 —— 明明在换路，取景却动了。</p>
     *
     * @return true 表示这一下按横向处理
     */
    public static boolean isHorizontalIntent(float dx, float dy) {
        return Math.abs(dx) > Math.abs(dy);
    }

    /**
     * 横向的这一下够不够「明显」，够了才换路。
     *
     * <p>两条任选其一：划得够远，或者划得够快。慢慢挪一点点不算 ——
     * 换路是个明确的动作，不该被手指的轻微横移触发。</p>
     *
     * @param dx               总横向位移（像素，向右为正）
     * @param velocityX        松手时的横向速度（px/s）
     * @param minDistancePx    够远的门槛
     * @param minFlingVelocity 够快的门槛，取自 ViewConfiguration
     */
    public static boolean isDeliberateSwipe(float dx, float velocityX,
                                            int minDistancePx, float minFlingVelocity) {
        if (Math.abs(dx) >= minDistancePx) {
            return true;
        }
        // 快速甩的方向必须和位移方向一致，否则是划出去又拉回来
        return minFlingVelocity > 0
                && Math.abs(velocityX) >= minFlingVelocity
                && Math.signum(velocityX) == Math.signum(dx)
                && dx != 0f;
    }

    /**
     * 中间三分之一上下拖动时，取景要平移多少。
     *
     * <p>方向是反的：手指往下拖，是想看画面<b>上方</b>的内容，取景要往上走。</p>
     *
     * <p>换算成 0..1 的平移量而不是像素：可挪的总距离取决于窗口的形状
     * （框越扁能挪的越多），用比例表示才能让手指划过整个窗口高度
     * 正好等于把取景从最上挪到最下 —— 不管窗口是什么形状。</p>
     *
     * @param dy         手指竖直位移（像素，向下为正）
     * @param viewHeight 窗口高度
     * @param headroom   还能挪的归一化距离，见 {@link RearViewGeometry.Viewport#verticalHeadroom}
     * @return 平移量的增量，加到当前 pan 上；headroom 为 0 时返回 0
     */
    public static float panShiftForDrag(float dy, int viewHeight, float headroom) {
        if (viewHeight <= 0 || headroom <= 0f) {
            // 窗口是方的，整幅都看得见，没有可挪的余地
            return 0f;
        }
        return -(dy / viewHeight);
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
