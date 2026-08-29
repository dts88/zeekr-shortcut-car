package com.kooo.evcam.zeekr;

import java.util.Locale;

/**
 * 超级后视镜的取景几何：从合成流里取出后方那一路，再在校正后的画面里框出要显示的部分。
 *
 * <p>整条链路是三层坐标：</p>
 *
 * <pre>
 *   合成流纹理 (1280x5140)
 *        │  lane 矩形 —— 取出后方那一路（2x2 里的右上格，lane 1）
 *        ▼
 *   单路鱼眼画面 (0..1)
 *        │  鱼眼校正（GL 着色器里做，本类不参与）
 *        ▼
 *   已校正画面 (0..1)
 *        │  蒙版矩形 —— 只保留其中一块
 *        ▼
 *   后视镜窗口
 * </pre>
 *
 * <p>本类只算坐标，不碰 OpenGL 也不碰 Android，可以直接跑 JVM 单元测试 ——
 * 取景框算错的表现是「画面偏了一点」，在车上很难用肉眼判断对不对，必须能测。</p>
 *
 * <p>为什么蒙版定义在<b>校正之后</b>的空间：鱼眼校正是非线性的，先裁再校正和先校正再裁
 * 得到的不是同一块内容。用户是看着校正后的画面框选的，所以蒙版必须跟着那个空间走。</p>
 */
public final class RearViewGeometry {

    /** 2x2 四宫格里后方画面所在的格子：右上。 */
    public static final int REAR_CELL = 1;

    /** 蒙版允许的最小边长，避免缩到 0 导致除零或全黑。 */
    public static final float MIN_CROP_SIZE = 0.05f;

    /**
     * 蒙版：在「已校正的单路画面」里保留哪一块。
     *
     * <p>全部为归一化坐标，左上为原点。{@code (0,0,1,1)} 表示整幅都要。</p>
     */
    public static final class Crop {
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public Crop(float x, float y, float width, float height) {
            // 先夹尺寸，再夹位置 —— 反过来的话，一个过大的尺寸会把位置挤到负数
            float w = clamp(width, MIN_CROP_SIZE, 1f);
            float h = clamp(height, MIN_CROP_SIZE, 1f);
            this.width = w;
            this.height = h;
            this.x = clamp(x, 0f, 1f - w);
            this.y = clamp(y, 0f, 1f - h);
        }

        /** 默认取中间偏下的一条横向区域 —— 后视镜要的是路面，不是天空。 */
        public static Crop defaultCrop() {
            return new Crop(0f, 0.35f, 1f, 0.45f);
        }

        public static Crop full() {
            return new Crop(0f, 0f, 1f, 1f);
        }

        /**
         * 只上下移动，左右不变。
         *
         * <p>对应中间三分之一区域的上下滑：临时调整取景范围，不移动窗口本身。</p>
         *
         * @param dy 归一化位移，正数向下
         */
        public Crop shiftedVertically(float dy) {
            return new Crop(x, y + dy, width, height);
        }

        /**
         * 以中心为基准缩放取景框。
         *
         * <p>双指捏合用这个：{@code factor > 1} 表示框变大（看到更多、画面显得更远），
         * {@code factor < 1} 表示框变小（放大细节）。</p>
         */
        public Crop scaledAboutCenter(float factor) {
            if (factor <= 0f) {
                return this;
            }
            float cx = x + width / 2f;
            float cy = y + height / 2f;
            float w = clamp(width * factor, MIN_CROP_SIZE, 1f);
            float h = clamp(height * factor, MIN_CROP_SIZE, 1f);
            return new Crop(cx - w / 2f, cy - h / 2f, w, h);
        }

        /** 上下还能移动多少（向上为负、向下为正）。 */
        public float headroomBelow() {
            return 1f - height - y;
        }

        public float headroomAbove() {
            return y;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Crop[%.3f,%.3f %.3fx%.3f]", x, y, width, height);
        }
    }

    /**
     * 交给着色器的四个值。
     *
     * <p>片段着色器里这样用：</p>
     *
     * <pre>
     *   vec2 inLane = cropOffset + vTextureCoord * cropScale;  // 输出窗口 -> 校正后画面
     *   vec2 fixed  = distort(inLane);                          // 鱼眼校正
     *   vec2 src    = laneOffset + fixed * laneScale;           // -> 合成流纹理
     * </pre>
     */
    public static final class ShaderRects {
        public final float laneOffsetX;
        public final float laneOffsetY;
        public final float laneScaleX;
        public final float laneScaleY;
        public final float cropOffsetX;
        public final float cropOffsetY;
        public final float cropScaleX;
        public final float cropScaleY;

        ShaderRects(float laneOffsetX, float laneOffsetY, float laneScaleX, float laneScaleY,
                    float cropOffsetX, float cropOffsetY, float cropScaleX, float cropScaleY) {
            this.laneOffsetX = laneOffsetX;
            this.laneOffsetY = laneOffsetY;
            this.laneScaleX = laneScaleX;
            this.laneScaleY = laneScaleY;
            this.cropOffsetX = cropOffsetX;
            this.cropOffsetY = cropOffsetY;
            this.cropScaleX = cropScaleX;
            this.cropScaleY = cropScaleY;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "ShaderRects[lane %.3f,%.3f +%.3fx%.3f | crop %.3f,%.3f +%.3fx%.3f]",
                    laneOffsetX, laneOffsetY, laneScaleX, laneScaleY,
                    cropOffsetX, cropOffsetY, cropScaleX, cropScaleY);
        }
    }

    private RearViewGeometry() {
    }

    /**
     * 把「哪一路 + 蒙版」换算成着色器要的四个矩形参数。
     *
     * @param plan      合成流拆分方案；为 null 或不是合成流时按整幅处理
     * @param laneIndex 要取的画面序号
     * @param crop      校正后画面里的蒙版
     */
    public static ShaderRects toShaderRects(CompositeStreamGeometry.Plan plan,
                                            int laneIndex, Crop crop) {
        Crop effective = crop != null ? crop : Crop.full();
        float laneX = 0f;
        float laneY = 0f;
        float laneW = 1f;
        float laneH = 1f;

        if (plan != null && plan.isComposite()
                && laneIndex >= 0 && laneIndex < plan.laneCount()) {
            CompositeStreamGeometry.Lane lane = plan.lane(laneIndex);
            laneX = lane.u0;
            laneY = lane.v0;
            laneW = lane.u1 - lane.u0;
            laneH = lane.v1 - lane.v0;
        }

        return new ShaderRects(
                laneX, laneY, laneW, laneH,
                effective.x, effective.y, effective.width, effective.height);
    }

    /**
     * 后方那一路在合成流里的序号。
     *
     * <p>2x2 的格子编号是「左上、右上、左下、右下」，后方在右上（见 {@link #REAR_CELL}）。
     * 格子到画面序号还要过一遍 laneOrder —— 用户可以调整四宫格的排列。</p>
     *
     * @param laneOrder laneOrder[格子] = 画面序号；传 null 用默认顺序
     */
    public static int rearLaneIndex(int[] laneOrder) {
        if (laneOrder == null || REAR_CELL >= laneOrder.length) {
            return REAR_CELL;
        }
        int lane = laneOrder[REAR_CELL];
        return (lane >= 0 && lane < CompositeStreamGeometry.LANE_COUNT) ? lane : REAR_CELL;
    }

    static float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }
}
