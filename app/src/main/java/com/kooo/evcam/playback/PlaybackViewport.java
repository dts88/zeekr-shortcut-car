package com.kooo.evcam.playback;

/**
 * 回放画面的取景：决定 TextureView 上那个变换矩阵的源矩形与目标矩形。
 *
 * <p>负责两件事：<b>按比例摆正</b>，以及<b>放大其中一路</b>。</p>
 *
 * <h3>为什么放大一路不需要第二个播放器</h3>
 *
 * <p>环视录像落盘时已经拼成了一个 2×2 网格（2560×2560），四路都在同一个文件里。
 * 所以「放大右上那一路」只是<b>在同一个解码器的输出上换个取景</b> ——
 * 不用再开播放器、不用切文件、不用重新 seek。</p>
 *
 * <p>旧的回看界面为此开到 5 个播放器，是因为它是照着 E5 那种「每路一个文件」的
 * 结构写的。绿屏、马赛克、卡顿都出在那些播放器的来回创建与切换上。
 * 对环视来说那套结构从一开始就是多余的。</p>
 *
 * <h3>关于 TextureView 的坐标系</h3>
 *
 * <p>TextureView 默认把视频拉伸铺满自己的边框，{@code setTransform} 的矩阵是在
 * <b>这个已经拉伸过的结果</b>上再作用一次。所以源矩形用的是视图坐标，
 * 而不是视频像素坐标 —— 这一点弄反了画面就会跑偏。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class PlaybackViewport {

    /** 不放大，显示完整的四宫格。 */
    public static final int NO_CELL = -1;

    /** 2×2 的格子编号：0=左上，1=右上，2=左下，3=右下。 */
    public static final int CELL_COUNT = 4;

    private PlaybackViewport() {
    }

    /**
     * 点在哪个格子上。
     *
     * @return 0..3；视图尺寸非法时返回 {@link #NO_CELL}
     */
    public static int cellAt(float x, float y, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return NO_CELL;
        }
        int column = x < viewWidth / 2f ? 0 : 1;
        int row = y < viewHeight / 2f ? 0 : 1;
        return row * 2 + column;
    }

    /**
     * 算出变换矩阵要的两个矩形。
     *
     * <p>目标矩形按源画面的宽高比居中摆放 —— 环视录像是正方形的，
     * 铺满一个宽视图会横向拉伸。宁可留黑边，也不要把画面拉变形。</p>
     *
     * @param cell      要放大的格子；{@link #NO_CELL} 表示显示完整画面
     * @param videoWidth  视频宽（像素）
     * @param videoHeight 视频高（像素）
     * @param viewWidth   视图宽
     * @param viewHeight  视图高
     * @return {@code {srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB}}，
     *         源矩形在<b>视图坐标</b>里；视频尺寸未知时返回 null
     */
    public static float[] transformRects(int cell, int videoWidth, int videoHeight,
                                         int viewWidth, int viewHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }

        // 源：整幅，或其中一格。视频已被默认拉伸铺满视图，所以这里用视图坐标。
        float srcLeft = 0f;
        float srcTop = 0f;
        float srcRight = viewWidth;
        float srcBottom = viewHeight;
        float sourceAspect = (float) videoWidth / videoHeight;

        if (cell >= 0 && cell < CELL_COUNT) {
            int column = cell % 2;
            int row = cell / 2;
            float halfWidth = viewWidth / 2f;
            float halfHeight = viewHeight / 2f;
            srcLeft = column * halfWidth;
            srcTop = row * halfHeight;
            srcRight = srcLeft + halfWidth;
            srcBottom = srcTop + halfHeight;
            // 一格的宽高比与整幅相同（2×2 等分），写成除法是为了不依赖这个巧合
            sourceAspect = ((float) videoWidth / 2f) / ((float) videoHeight / 2f);
        }

        // 目标：按源比例塞进视图，居中，留黑边
        float viewAspect = (float) viewWidth / viewHeight;
        float destWidth;
        float destHeight;
        if (sourceAspect > viewAspect) {
            destWidth = viewWidth;
            destHeight = viewWidth / sourceAspect;
        } else {
            destHeight = viewHeight;
            destWidth = viewHeight * sourceAspect;
        }
        float destLeft = (viewWidth - destWidth) / 2f;
        float destTop = (viewHeight - destHeight) / 2f;

        return new float[]{
                srcLeft, srcTop, srcRight, srcBottom,
                destLeft, destTop, destLeft + destWidth, destTop + destHeight,
        };
    }

    /**
     * 格子的名字。
     *
     * <p>只有右上是后方这一条在实车确认过（超级后视镜取的就是这一格）。
     * 其余三格对应车辆哪个方向尚未确认，所以按位置命名，不瞎标方向。</p>
     */
    public static String labelOf(int cell) {
        switch (cell) {
            case 0: return "左上";
            case 1: return "右上（后方）";
            case 2: return "左下";
            case 3: return "右下";
            default: return "四宫格";
        }
    }
}
