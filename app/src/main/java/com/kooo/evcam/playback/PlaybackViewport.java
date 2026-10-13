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
 * <p>不碰 Android，方便直接跑单元测试。</p>
 */
public final class PlaybackViewport {

    /** 不放大，显示完整的四宫格。 */
    public static final int NO_CELL = -1;

    /** 2×2 的格子编号：0=左上，1=右上，2=左下，3=右下。 */
    public static final int CELL_COUNT = 4;

    /** 行驶信息条的高（和 {@code telemetry.InfoBar.HEIGHT} 同一个数；回放只认几何，不引那个包）。 */
    public static final int INFO_BAR_HEIGHT = 100;

    private PlaybackViewport() {
    }

    /**
     * 这段录像下面带没带行驶信息条 —— 带了返回它的高（像素），没带返回 0。
     *
     * <p>文件里没有写着「带不带」，只能从几何上看：按 2×2 存的环视录像每格是正方形，
     * 减掉 100 像素之后格子<b>更方</b>，那 100 像素就是信息条。座舱那种整幅录像没有格子可比，
     * 也不需要知道 —— 它不放大某一格、不做鱼眼校正，信息条就是画面的一部分。</p>
     */
    public static int infoBarInset(int videoWidth, int videoHeight, boolean grid) {
        if (!grid || videoWidth <= 0 || videoHeight <= INFO_BAR_HEIGHT) {
            return 0;
        }
        float asIs = Math.abs(cellRatio(videoWidth, videoHeight) - 1f);
        float withoutBar = Math.abs(cellRatio(videoWidth, videoHeight - INFO_BAR_HEIGHT) - 1f);
        return withoutBar < asIs && hasSquareCells(videoWidth, videoHeight - INFO_BAR_HEIGHT)
                ? INFO_BAR_HEIGHT : 0;
    }

    private static float cellRatio(int width, int height) {
        return ((float) width / 2f) / ((float) height / 2f);
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
     * 点在照片的哪一路上。
     *
     * <p>和 {@link #cellAt} 的差别是<b>黑边</b>：那一个按视图的正中划四等分，
     * 用在视频上没问题 —— 画面是居中摆的，中线和视图中线重合。而照片这边，点还要
     * 落在画面<b>之内</b>才算数：网格里那一格是横的、照片是方的，两侧留出来的黑边
     * 可以很宽，点在那里算成某一路，就成了「我明明点的是旁边」。</p>
     *
     * <p>这也给了「退出去」一个落脚点：黑边上没有画面，点它就是收回网格。</p>
     *
     * @return 0..3；点在画面外或者尺寸未知时返回 {@link #NO_CELL}
     */
    public static int cellAtInPicture(float x, float y, int imageWidth, int imageHeight,
                                      int viewWidth, int viewHeight) {
        return cellAtInPicture(x, y, imageWidth, imageHeight, 0, viewWidth, viewHeight);
    }

    /**
     * 同上，画面下沿带着一条不属于四格的信息条时用：格子只在信息条以上分，点在信息条上不算任何一格。
     *
     * @param bottomInset 信息条的高（像素），0 表示没有
     */
    public static int cellAtInPicture(float x, float y, int imageWidth, int imageHeight,
                                      int bottomInset, int viewWidth, int viewHeight) {
        float[] r = imageRects(NO_CELL, imageWidth, imageHeight, viewWidth, viewHeight);
        if (r == null) {
            return NO_CELL;
        }
        float left = r[4];
        float top = r[5];
        float right = r[6];
        float bottom = r[7];
        float contentBottom = top + (bottom - top) * contentFraction(imageHeight, bottomInset);
        if (x < left || x > right || y < top || y > contentBottom) {
            return NO_CELL;
        }
        int column = x < (left + right) / 2f ? 0 : 1;
        int row = y < (top + contentBottom) / 2f ? 0 : 1;
        return row * 2 + column;
    }

    /** 画面里四格占的那一部分（0..1）：没有信息条就是 1。 */
    private static float contentFraction(int height, int bottomInset) {
        if (height <= 0 || bottomInset <= 0 || bottomInset >= height) {
            return 1f;
        }
        return (float) (height - bottomInset) / height;
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
        return transformRects(cell, videoWidth, videoHeight, 0, viewWidth, viewHeight);
    }

    /**
     * 同上，画面下沿带着一条不属于四格的信息条时用（{@link #infoBarInset}）：
     * 整幅照常连着信息条一起显示；放大一格时四格只在信息条以上分。
     *
     * @param bottomInset 信息条的高（像素），0 表示没有
     */
    public static float[] transformRects(int cell, int videoWidth, int videoHeight,
                                         int bottomInset, int viewWidth, int viewHeight) {
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
            float fraction = contentFraction(videoHeight, bottomInset);
            float halfWidth = viewWidth / 2f;
            float halfHeight = viewHeight * fraction / 2f;
            srcLeft = column * halfWidth;
            srcTop = row * halfHeight;
            srcRight = srcLeft + halfWidth;
            srcBottom = srcTop + halfHeight;
            // 一格的宽高比与四格那部分相同（2×2 等分），写成除法是为了不依赖这个巧合
            sourceAspect = ((float) videoWidth / 2f) / ((videoHeight * fraction) / 2f);
        }

        float[] dest = destRect(sourceAspect, viewWidth, viewHeight);
        return new float[]{
                srcLeft, srcTop, srcRight, srcBottom,
                dest[0], dest[1], dest[2], dest[3],
        };
    }

    /**
     * ImageView 用的取景：源矩形在<b>图片像素</b>坐标里。
     *
     * <p>和 {@link #transformRects} 是同一件事，差的只是源用哪个坐标系 ——
     * 而这一点差错会让人完全看不出是坐标系的问题。TextureView 先把画面拉满自己的
     * 边框，矩阵作用在那个结果上，所以源写视图坐标；ImageView 的矩阵是直接把
     * <b>图片像素</b>映到视图上的。拿视图坐标去当图片坐标，画面就只是挪了挪位置，
     * 该放大的没放大 —— 之前这里正是这个样子。</p>
     *
     * @param cell        要放大的格子；{@link #NO_CELL} 表示整张
     * @param imageWidth  图片宽（像素，即 drawable 的固有宽）
     * @param imageHeight 图片高（像素）
     * @return {@code {srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB}}，
     *         源在<b>图片像素</b>坐标、目标在视图坐标；尺寸未知时返回 null
     */
    public static float[] imageRects(int cell, int imageWidth, int imageHeight,
                                     int viewWidth, int viewHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        float srcLeft = 0f;
        float srcTop = 0f;
        float srcRight = imageWidth;
        float srcBottom = imageHeight;
        if (cell >= 0 && cell < CELL_COUNT) {
            int column = cell % 2;
            int row = cell / 2;
            float halfWidth = imageWidth / 2f;
            float halfHeight = imageHeight / 2f;
            srcLeft = column * halfWidth;
            srcTop = row * halfHeight;
            srcRight = srcLeft + halfWidth;
            srcBottom = srcTop + halfHeight;
        }
        float[] dest = destRect((srcRight - srcLeft) / (srcBottom - srcTop),
                viewWidth, viewHeight);
        return new float[]{
                srcLeft, srcTop, srcRight, srcBottom,
                dest[0], dest[1], dest[2], dest[3],
        };
    }

    /**
     * 2×2 排列的画面，每一格是不是正方形。
     *
     * <p>环视合成流每一路都是 1280×1280，录成 2560×2560 —— 只有这样的画面才是四路鱼眼，
     * 才该做鱼眼校正。座舱录像是 16:9 的一整幅，不是。和图片回看那边的判断
     * （{@code FisheyeCorrector}：格子宽高比在 0.96–1.04 之内）一致。</p>
     */
    public static boolean hasSquareCells(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        float ratio = ((float) width / 2f) / ((float) height / 2f);
        return ratio >= 0.96f && ratio <= 1.04f;
    }

    /**
     * 按源的宽高比把目标矩形塞进视图，居中，留黑边。
     *
     * <p>宁可留黑边也不拉变形 —— 环视是方的，这块视图是宽的。</p>
     */
    private static float[] destRect(float sourceAspect, int viewWidth, int viewHeight) {
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
        return new float[]{destLeft, destTop, destLeft + destWidth, destTop + destHeight};
    }
}
