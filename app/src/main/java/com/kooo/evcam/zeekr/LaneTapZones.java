package com.kooo.evcam.zeekr;

/**
 * 按键模式：窗口切成几块，点哪一块就切到哪一路。
 *
 * <pre>
 *   四向（默认）                    前后                   后视 + 后座舱
 *   ┌───────────────┐              ┌───────────────┐      ┌───────────────┐
 *   │ \    前     / │              │               │      │               │
 *   │   \       /   │              │      前       │      │    后座舱     │
 *   │ 左  ╳ ── ╳  右 │              ├───────────────┤      ├───────────────┤
 *   │   /       \   │              │      后       │      │      后       │
 *   │ /    后     \ │              │               │      │               │
 *   └───────────────┘              └───────────────┘      └───────────────┘
 * </pre>
 *
 * <h3>为什么不是四个按钮</h3>
 *
 * <p>以前是一组菱形摆放的「前 后 左 右」按钮：碰一下窗口才出现，5 秒后消失，
 * 按钮固定 dp，窗口还得留出装下它们的最小尺寸。开车时要先碰一下、等它出现、
 * 再找准那一小块 —— 三步。切成几块之后整个窗口都是按钮，位置就是方向，
 * 一下就够；也不用给窗口设下限。</p>
 *
 * <h3>按窗口的比例切，不按正方形切</h3>
 *
 * <p>对角线连的是窗口的四个角。窗口拉扁时上下两块变矮变宽、左右两块变窄 ——
 * 这正好：四块的分界永远是那两条对角线，不用去想一个看不见的正方形。</p>
 *
 * <h3>两路的那两档切成上下两半</h3>
 *
 * <p>那时侧视不在可选范围里，左右两块没有东西可切。不是让它们「点了不响应」——
 * 那样窗口上就有两块死区 —— 而是整个窗口只分上下：下半永远是后视，上半是另一路
 * （前视，或者后座舱：项目所有者 2026-10-19 定）。「切换范围」于是对划动和点击同样生效，不用哪边单独打补丁。</p>
 */
public final class LaneTapZones {

    private LaneTapZones() {
    }

    /**
     * 点在了哪一块。
     *
     * @param views 切换范围：两路的那两档只分上下两半
     * @return {@link LaneCycle#FRONT} / {@link LaneCycle#REAR} / {@link LaneCycle#LEFT} /
     *         {@link LaneCycle#RIGHT} / {@link LaneCycle#CABIN_REAR}；窗口还没有尺寸时返回 -1
     */
    public static int laneAt(float x, float y, int width, int height, MirrorViews views) {
        if (width <= 0 || height <= 0) {
            return -1;
        }
        if (views.twoViews()) {
            return y < height / 2f ? upper(views) : LaneCycle.REAR;
        }
        // 归一化之后两条对角线就是 v = u 和 v = 1 - u
        float u = x / width;
        float v = y / height;
        boolean belowMain = v > u;          // 在左上—右下那条线的下方
        boolean belowAnti = v > 1f - u;     // 在右上—左下那条线的下方
        if (!belowMain && !belowAnti) {
            return LaneCycle.FRONT;
        }
        if (belowMain && belowAnti) {
            return LaneCycle.REAR;
        }
        return belowMain ? LaneCycle.LEFT : LaneCycle.RIGHT;
    }

    /**
     * 这一路那一块的轮廓，用来画点中时的闪光。
     *
     * @return 顶点 {@code {x0, y0, x1, y1, ...}}，窗口自己的坐标系：
     *         四向时是三角（三个点），两路的那两档是半个窗口（四个点）
     */
    public static float[] outline(int lane, int width, int height, MirrorViews views) {
        float cx = width / 2f;
        float cy = height / 2f;
        if (views.twoViews()) {
            return lane == LaneCycle.REAR
                    ? new float[]{0f, cy, width, cy, width, height, 0f, height}
                    : new float[]{0f, 0f, width, 0f, width, cy, 0f, cy};
        }
        switch (lane) {
            case LaneCycle.REAR:
                return new float[]{0f, height, width, height, cx, cy};
            case LaneCycle.LEFT:
                return new float[]{0f, 0f, 0f, height, cx, cy};
            case LaneCycle.RIGHT:
                return new float[]{width, 0f, width, height, cx, cy};
            default:
                return new float[]{0f, 0f, width, 0f, cx, cy};
        }
    }

    /** 两路的那两档里上半是哪一路：前后档是前视，后视 + 后座舱档是后座舱。 */
    private static int upper(MirrorViews views) {
        return views == MirrorViews.REAR_CABIN ? LaneCycle.CABIN_REAR : LaneCycle.FRONT;
    }
}
