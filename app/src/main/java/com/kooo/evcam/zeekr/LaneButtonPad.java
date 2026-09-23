package com.kooo.evcam.zeekr;

/**
 * 按键模式里那四个「前 后 左 右」按钮的几何：摆成菱形，各自占哪一块、点到了哪一个。
 *
 * <h3>为什么是菱形不是一排</h3>
 *
 * <pre>
 *          ┌────┐
 *          │ 前 │
 *   ┌────┐ ├────┤ ┌────┐
 *   │ 左 │ │ 后 │ │ 右 │
 *   └────┘ └────┘ └────┘
 * </pre>
 *
 * <p>横排的话，四个按钮的<b>位置和它们代表的方向没有关系</b>，每次都得读字。
 * 摆成菱形之后位置本身就是答案：上面那个是前，下面那个是后，左边是左，右边是右 ——
 * 按的是方位，不是标签。</p>
 *
 * <p>中间那一列上下两格（前 / 后），左右两格竖直方向落在它们中间。</p>
 *
 * <h3>三件事用同一套数</h3>
 *
 * <p>这一组决定了按钮画在哪、手指点在哪算数、以及<b>窗口最小能多小</b>。三件事必须同源，
 * 否则会出现「按钮画在框外」或者「看得见却点不着」—— 都是只有在车上缩到极限时才暴露的毛病。</p>
 *
 * <h3>按钮不跟着窗口缩放</h3>
 *
 * <p>尺寸是<b>固定的 dp</b>。手指的大小不会因为窗口变小而变小，按钮跟着缩到点不中等于白放。
 * 代价是这个模式下窗口有个下限，见 {@link #minWindowPx}。</p>
 */
public final class LaneButtonPad {

    /** 四个按钮。编号和 {@link LaneCycle} 一致：0 前、1 后、2 左、3 右。 */
    public static final int COUNT = 4;

    /**
     * 单个按钮的宽。
     *
     * <p>实车上按着偏小，整体放大到原来的一倍半（52 → 78）。开车时按键按的是
     * 余光加肌肉记忆，48dp 那个下限是照着「手机、看着按」定的，在车里不够用。</p>
     */
    private static final float BUTTON_WIDTH_DP = 78f;
    /** 单个按钮的高。同样一倍半（48 → 72），远在安卓建议的 48dp 下限之上。 */
    private static final float BUTTON_HEIGHT_DP = 72f;
    /** 按钮之间的间隙 —— 挨着放会误触到隔壁。 */
    private static final float GAP_DP = 8f;
    /** 这一组到窗口左右边的最小留白。 */
    private static final float SIDE_MARGIN_DP = 10f;
    /** 这一组的上沿落在窗口高度的哪个位置：居中偏上。 */
    private static final float TOP_FRACTION = 0.18f;
    /**
     * 在那个锚点之上再提多少个按钮的高度。
     *
     * <p>按钮放大之后这一组整体长高了，还按原来的锚点摆，下面那一排就压到画面正中 ——
     * 而正中间恰恰是最该看清的地方。提一个按钮的高度，位置回到画面上部。</p>
     */
    private static final float RAISE_BY_BUTTONS = 1f;
    /** 提到顶也要留的一道边，免得按钮贴着窗口上沿。 */
    private static final float TOP_MARGIN_DP = 12f;
    /** 圆角。按钮大了，圆角跟着走，不然看着像方板。 */
    private static final float CORNER_DP = 12f;

    private LaneButtonPad() {
    }

    public static float buttonWidth(float density) {
        return BUTTON_WIDTH_DP * density;
    }

    public static float buttonHeight(float density) {
        return BUTTON_HEIGHT_DP * density;
    }

    public static float corner(float density) {
        return CORNER_DP * density;
    }

    /** 整组的宽：三列。 */
    public static float padWidth(float density) {
        return 3 * buttonWidth(density) + 2 * GAP_DP * density;
    }

    /** 整组的高：两行。 */
    public static float padHeight(float density) {
        return 2 * buttonHeight(density) + GAP_DP * density;
    }

    /**
     * 按键模式下窗口最小能多小。
     *
     * <p>宽高都取「整组的宽加两边留白」——宽的那一头是约束，用同一个数保证不管窗口
     * 是什么形状，四个按钮都整个在框里。这也正是这个模式要付的代价：
     * 后视镜不能再拉成一条细条。</p>
     */
    public static int minWindowPx(float density) {
        return Math.round(padWidth(density) + 2 * SIDE_MARGIN_DP * density);
    }

    /**
     * 这一组的上沿在哪。
     *
     * <p>{@link #TOP_FRACTION} 是锚点，再往上提 {@link #RAISE_BY_BUTTONS} 个按钮的高度。
     * 窗口矮的时候会提到框外去，所以留一道 {@link #TOP_MARGIN_DP} 把它按住 ——
     * 「看得见但点不着」和「画在框外」是同一类毛病，只有缩到极限时才露面。</p>
     */
    public static float padTop(int viewHeight, float density) {
        float raised = viewHeight * TOP_FRACTION - RAISE_BY_BUTTONS * buttonHeight(density);
        return Math.max(TOP_MARGIN_DP * density, raised);
    }

    /**
     * 第 {@code index} 个按钮在窗口里的位置。
     *
     * @return {@code {left, top, right, bottom}}，窗口自己的坐标系
     */
    public static float[] rectFor(int index, int viewWidth, int viewHeight, float density) {
        float buttonW = buttonWidth(density);
        float buttonH = buttonHeight(density);
        float gap = GAP_DP * density;
        float padLeft = (viewWidth - padWidth(density)) / 2f;
        float padTopPx = padTop(viewHeight, density);
        float column = buttonW + gap;

        float left;
        float top;
        switch (index) {
            case LaneCycle.REAR:                    // 中间一列，下面那格
                left = padLeft + column;
                top = padTopPx + buttonH + gap;
                break;
            case LaneCycle.LEFT:                    // 左边一列，竖直居中
                left = padLeft;
                top = padTopPx + (buttonH + gap) / 2f;
                break;
            case LaneCycle.RIGHT:                   // 右边一列，竖直居中
                left = padLeft + 2 * column;
                top = padTopPx + (buttonH + gap) / 2f;
                break;
            default:                                // 前：中间一列，上面那格
                left = padLeft + column;
                top = padTopPx;
                break;
        }
        return new float[]{left, top, left + buttonW, top + buttonH};
    }

    /**
     * 点在了哪个按钮上。
     *
     * @return 0..3；没点中返回 -1
     */
    public static int hitTest(float x, float y, int viewWidth, int viewHeight, float density) {
        for (int i = 0; i < COUNT; i++) {
            float[] r = rectFor(i, viewWidth, viewHeight, density);
            if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) {
                return i;
            }
        }
        return -1;
    }

    /** 第 {@code index} 个按钮对应哪一路。 */
    public static int laneFor(int index) {
        return index;
    }

    /** 哪一路对应第几个按钮；不在这四路里返回 -1。 */
    public static int indexForLane(int lane) {
        return lane >= 0 && lane < COUNT ? lane : -1;
    }
}
