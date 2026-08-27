package com.kooo.evcam.zeekr;

import java.util.Locale;

/**
 * 把「一路合成环视视频流」拆成四个画面的纯几何计算。
 *
 * <p>背景：在极氪 App Lab 环境中，第三方应用拿到的不是四个独立摄像头，而是
 * <b>一路已经拼接好的合成流</b>。四个约 1280x1280 的方形画面被排在同一帧里，
 * 画面之间还夹着几像素宽的分隔带。已知的两种排布：</p>
 *
 * <ul>
 *   <li><b>竖排</b> 1280x5140：四个 1280x1280 竖向堆叠，
 *       5140 = 4x1280 + 20，多出来的 20 行是 5 条 4px 分隔带
 *       （上边缘、三条内部分隔、下边缘）；</li>
 *   <li><b>横排</b> 5120x1280：四个 1280x1280 横向排列，
 *       5120 = 4x1280 正好整除，没有分隔带。</li>
 * </ul>
 *
 * <p>以上尺寸与排布属于对车机输出接口的<b>事实性描述</b>，来源是
 * openavm-recorder 项目公开 README 中记录的实测结论
 * （https://github.com/Dantenothing/openavm-recorder）。本类为独立编写实现，
 * 未复制该项目任何源代码。特此致谢原作者公开这些观测结果。</p>
 *
 * <p>本类不依赖任何 Android API，因此可以直接跑 JVM 单元测试。</p>
 */
public final class CompositeStreamGeometry {

    /** 合成流固定包含 4 个画面。 */
    public static final int LANE_COUNT = 4;

    /** 长宽比达到该阈值才认定为「四联长条」，而不是普通画面。 */
    private static final float STRIP_RATIO_THRESHOLD = 3.2f;

    /**
     * 分隔带总厚度相对单个画面边长的上限。
     * 超过这个比例说明多出来的像素不是分隔带，而是别的排布，
     * 此时退回等分策略，宁可略微偏移也不要凭空裁掉真实画面。
     */
    private static final float MAX_BAND_FRACTION = 0.125f;

    /** 分隔带数量：上边缘 + 三条内部分隔 + 下边缘。 */
    private static final int BAND_COUNT = LANE_COUNT + 1;

    private CompositeStreamGeometry() {
    }

    /** 四联画面的排布方向。 */
    public enum Stacking {
        /** 四个画面竖向堆叠，例如 1280x5140。 */
        VERTICAL,
        /** 四个画面横向排列，例如 5120x1280。 */
        HORIZONTAL,
        /** 长宽比不像四联长条，按普通单画面处理。 */
        NOT_COMPOSITE
    }

    /** 合成帧里的一个画面：像素矩形 + 归一化纹理坐标。 */
    public static final class Lane {
        /** 画面序号，0..3，沿排布方向从上到下 / 从左到右。 */
        public final int index;
        /** 在源帧中的像素矩形。 */
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        /** 归一化纹理坐标（0..1），左上为原点，可直接喂给 OpenGL / Matrix。 */
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;

        Lane(int index, int x, int y, int width, int height, int frameWidth, int frameHeight) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.u0 = (float) x / frameWidth;
            this.v0 = (float) y / frameHeight;
            this.u1 = (float) (x + width) / frameWidth;
            this.v1 = (float) (y + height) / frameHeight;
        }

        /** 画面自身的宽高比。合成流里的画面通常接近 1.0（正方形）。 */
        public float aspect() {
            return height == 0 ? 0f : (float) width / height;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Lane%d[%d,%d %dx%d]", index, x, y, width, height);
        }
    }

    /** 一次拆分的完整结果。 */
    public static final class Plan {
        public final Stacking stacking;
        public final int frameWidth;
        public final int frameHeight;
        /** 单个画面的边长（像素）。等分回退时为沿排布方向的等分长度。 */
        public final int laneSizePx;
        /** 单条分隔带的厚度（像素）；没有分隔带或走等分回退时为 0。 */
        public final int bandPx;
        /** 是否成功识别出分隔带排布。false 表示走了等分回退。 */
        public final boolean bandsDetected;
        public final Lane[] lanes;
        /** 人类可读的判定说明，用于日志和设置页诊断信息。 */
        public final String note;

        Plan(Stacking stacking, int frameWidth, int frameHeight, int laneSizePx,
             int bandPx, boolean bandsDetected, Lane[] lanes, String note) {
            this.stacking = stacking;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.laneSizePx = laneSizePx;
            this.bandPx = bandPx;
            this.bandsDetected = bandsDetected;
            this.lanes = lanes;
            this.note = note;
        }

        /** 是否为四联合成流（竖排或横排）。 */
        public boolean isComposite() {
            return stacking != Stacking.NOT_COMPOSITE;
        }

        /** 实际可用的画面数量：合成流为 4，普通单画面为 1。 */
        public int laneCount() {
            return lanes.length;
        }

        public Lane lane(int index) {
            return lanes[index];
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%dx%d %s lane=%dpx band=%dpx (%s)",
                    frameWidth, frameHeight, stacking, laneSizePx, bandPx, note);
        }
    }

    /**
     * 判断给定尺寸是否像四联合成流。用于在候选分辨率里挑出合成流。
     */
    public static boolean looksLikeComposite(int frameWidth, int frameHeight) {
        return detectStacking(frameWidth, frameHeight) != Stacking.NOT_COMPOSITE;
    }

    static Stacking detectStacking(int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return Stacking.NOT_COMPOSITE;
        }
        if ((float) frameWidth / frameHeight >= STRIP_RATIO_THRESHOLD) {
            return Stacking.HORIZONTAL;
        }
        if ((float) frameHeight / frameWidth >= STRIP_RATIO_THRESHOLD) {
            return Stacking.VERTICAL;
        }
        return Stacking.NOT_COMPOSITE;
    }

    /**
     * 拆分一帧合成流。
     *
     * @param frameWidth  源帧宽度（像素），必须为正
     * @param frameHeight 源帧高度（像素），必须为正
     * @return 拆分结果；若不是合成流，返回只含一个整帧 Lane 的 Plan
     */
    public static Plan analyse(int frameWidth, int frameHeight) {
        return analyse(frameWidth, frameHeight, 0);
    }

    /**
     * 拆分一帧合成流，并对每个画面额外内缩若干像素。
     *
     * <p>内缩用于消除分隔带残留：不同固件版本的分隔带厚度可能与实测值差 1~2 像素，
     * 内缩 1~4 像素可以把残留的边缘裁掉，代价是极小的视野损失。</p>
     *
     * @param cropInsetPx 每个画面四边各内缩的像素数，负值按 0 处理
     */
    public static Plan analyse(int frameWidth, int frameHeight, int cropInsetPx) {
        if (frameWidth <= 0) {
            throw new IllegalArgumentException("frameWidth must be positive, got " + frameWidth);
        }
        if (frameHeight <= 0) {
            throw new IllegalArgumentException("frameHeight must be positive, got " + frameHeight);
        }
        int inset = Math.max(0, cropInsetPx);

        Stacking stacking = detectStacking(frameWidth, frameHeight);
        if (stacking == Stacking.NOT_COMPOSITE) {
            Lane[] single = {new Lane(0, 0, 0, frameWidth, frameHeight, frameWidth, frameHeight)};
            return new Plan(stacking, frameWidth, frameHeight, Math.min(frameWidth, frameHeight),
                    0, false, single, "长宽比不足 " + STRIP_RATIO_THRESHOLD + "，按单画面处理");
        }

        boolean vertical = stacking == Stacking.VERTICAL;
        // along = 排布方向上的总长度；across = 垂直于排布方向的边长，也就是画面边长
        int along = vertical ? frameHeight : frameWidth;
        int across = vertical ? frameWidth : frameHeight;

        Split split = splitAlongAxis(along, across);
        Lane[] lanes = new Lane[LANE_COUNT];
        for (int i = 0; i < LANE_COUNT; i++) {
            int start = split.startOf(i);
            int size = split.laneSize;

            int x;
            int y;
            int w;
            int h;
            if (vertical) {
                x = 0;
                y = start;
                w = frameWidth;
                h = size;
            } else {
                x = start;
                y = 0;
                w = size;
                h = frameHeight;
            }

            // 应用内缩，并夹紧到源帧范围内，保证每边至少留 1 像素
            int ix = Math.min(inset, Math.max(0, (w - 1) / 2));
            int iy = Math.min(inset, Math.max(0, (h - 1) / 2));
            x += ix;
            y += iy;
            w -= 2 * ix;
            h -= 2 * iy;

            lanes[i] = new Lane(i, x, y, w, h, frameWidth, frameHeight);
        }

        String note = split.bandsDetected
                ? String.format(Locale.US, "识别到 %d 条 %dpx 分隔带，画面边长 %dpx",
                        BAND_COUNT, split.bandPx, split.laneSize)
                : String.format(Locale.US, "未匹配分隔带排布（余量 %dpx），按 %d 等分回退",
                        along - across * LANE_COUNT, LANE_COUNT);
        if (inset > 0) {
            note = note + "，每边内缩 " + inset + "px";
        }

        return new Plan(stacking, frameWidth, frameHeight, split.laneSize,
                split.bandPx, split.bandsDetected, lanes, note);
    }

    /**
     * 沿排布方向切四刀。
     *
     * <p>理想情况下四个画面是正方形，边长等于横向边长 across，
     * 剩下的 along - 4*across 像素平均分给 5 条分隔带。
     * 如果余量为负（画面被压扁）或者大得不像分隔带，就退回等分。</p>
     */
    private static Split splitAlongAxis(int along, int across) {
        int slack = along - across * LANE_COUNT;
        boolean plausible = slack >= 0 && slack <= across * MAX_BAND_FRACTION;
        if (plausible) {
            // 整数除法向下取整：宁可分隔带算窄一点，也不要把画面裁掉
            return new Split(across, slack / BAND_COUNT, true);
        }
        return new Split(along / LANE_COUNT, 0, false);
    }

    private static final class Split {
        final int laneSize;
        final int bandPx;
        final boolean bandsDetected;

        Split(int laneSize, int bandPx, boolean bandsDetected) {
            this.laneSize = laneSize;
            this.bandPx = bandPx;
            this.bandsDetected = bandsDetected;
        }

        int startOf(int index) {
            if (bandsDetected) {
                return bandPx + index * (laneSize + bandPx);
            }
            return index * laneSize;
        }
    }
}
