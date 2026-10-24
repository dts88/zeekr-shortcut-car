package com.kooo.evcam.zeekr;

/**
 * 超级镜子窗口编辑框的换算（设置 → 超级镜子 → 窗口，项目所有者 2026-10-23）：按在哪、拖边拖角怎么改宽高、拖框内怎么挪。
 *
 * <p>双指缩放不在这里：用 {@link RearViewTouchModel#pinch}，和窗口本身一个手感。单独拎出来是为了能跑单元测试 ——
 * 和 {@link RearViewTouchModel} 一个道理，手感错了在车上只会觉得「怪」。</p>
 *
 * <p>坐标都是编辑框里的像素，框不出 {@code boundsWidth × boundsHeight}；宽高另有上限 {@code maxWidth × maxHeight}
 * （窗口能有多大，和 {@code AppConfig.clampRearViewSize} 同一个数），下限是 {@code AppConfig.REARVIEW_MIN_SIZE}。</p>
 */
public final class RearViewFrameModel {

    /** 按下的地方：四条边各一位，角是两位合起来；{@link #MOVE} 是框内，{@link #NONE} 是框外。 */
    public static final int NONE = 0;
    public static final int LEFT = 1;
    public static final int TOP = 2;
    public static final int RIGHT = 4;
    public static final int BOTTOM = 8;
    public static final int MOVE = 16;

    private RearViewFrameModel() {
    }

    /** 编辑框里的那个框：左上角和宽高。 */
    public static final class Frame {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Frame(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    /**
     * 按下的点抓住了什么：离边 {@code reach} 以内算抓住那条边（离两条对边都近时抓近的那条），两条相邻的边都抓住就是角；
     * 都不是而在框内，算拖着挪；框外不管。
     *
     * <p>框里面那一侧的抓取范围不超过宽、高的四分之一：框很小时，中间还留得出挪的地方。</p>
     */
    public static int hit(Frame frame, float px, float py, float reach) {
        if (px < frame.x - reach || px > frame.right() + reach
                || py < frame.y - reach || py > frame.bottom() + reach) {
            return NONE;
        }
        float insideX = Math.min(reach, frame.width / 4f);
        float insideY = Math.min(reach, frame.height / 4f);
        // 到四条边的距离：框内为正，框外为负
        float toLeft = px - frame.x;
        float toRight = frame.right() - px;
        float toTop = py - frame.y;
        float toBottom = frame.bottom() - py;
        int grab = NONE;
        if (toLeft <= insideX && toLeft <= toRight) {
            grab |= LEFT;
        } else if (toRight <= insideX) {
            grab |= RIGHT;
        }
        if (toTop <= insideY && toTop <= toBottom) {
            grab |= TOP;
        } else if (toBottom <= insideY) {
            grab |= BOTTOM;
        }
        if (grab != NONE) {
            return grab;
        }
        return toLeft >= 0 && toRight >= 0 && toTop >= 0 && toBottom >= 0 ? MOVE : NONE;
    }

    /**
     * 拖边、拖角：抓住的边跟着手指走，对边不动。宽高夹在 {@code minSize} 和上限之间，边不出编辑框。
     *
     * @param start 按下那一刻的框
     * @param grab  {@link #hit} 的结果（边、角）
     * @param dx,dy 手指从按下起走了多远
     */
    public static Frame resize(Frame start, int grab, float dx, float dy, int minSize,
                               int maxWidth, int maxHeight, int boundsWidth, int boundsHeight) {
        int left = start.x;
        int top = start.y;
        int right = start.right();
        int bottom = start.bottom();
        if ((grab & LEFT) != 0) {
            left = clamp(Math.round(start.x + dx),
                    Math.max(0, right - maxWidth), right - minSize);
        } else if ((grab & RIGHT) != 0) {
            right = clamp(Math.round(start.right() + dx),
                    left + minSize, Math.min(boundsWidth, left + maxWidth));
        }
        if ((grab & TOP) != 0) {
            top = clamp(Math.round(start.y + dy),
                    Math.max(0, bottom - maxHeight), bottom - minSize);
        } else if ((grab & BOTTOM) != 0) {
            bottom = clamp(Math.round(start.bottom() + dy),
                    top + minSize, Math.min(boundsHeight, top + maxHeight));
        }
        return new Frame(left, top, right - left, bottom - top);
    }

    /** 拖框内：整个框跟着手指走，宽高不变，不出编辑框。 */
    public static Frame move(Frame start, float dx, float dy, int boundsWidth, int boundsHeight) {
        return keepInside(Math.round(start.x + dx), Math.round(start.y + dy),
                start.width, start.height, boundsWidth, boundsHeight);
    }

    /**
     * 把一个框收进编辑框里：宽高不超过编辑框，位置推回来。双指缩放之后（缩放本身不管位置出不出界）、
     * 打开编辑框时（窗口贴边藏着一半）都过一遍。
     */
    public static Frame keepInside(int x, int y, int width, int height, int boundsWidth, int boundsHeight) {
        int w = Math.max(0, Math.min(width, boundsWidth));
        int h = Math.max(0, Math.min(height, boundsHeight));
        return new Frame(clamp(x, 0, boundsWidth - w), clamp(y, 0, boundsHeight - h), w, h);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
