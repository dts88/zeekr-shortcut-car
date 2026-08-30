package com.kooo.evcam.zeekr;

import java.util.Locale;

/**
 * 超级后视镜的取景几何：显示框里究竟该看到那一路画面的哪一块。
 *
 * <p>整条链路是三层坐标：</p>
 *
 * <pre>
 *   合成流纹理 (1280x5140)
 *        │  lane 矩形 —— 取出后方那一路（2x2 里的右上格，lane 1）
 *        ▼
 *   单路鱼眼画面 (0..1)
 *        │  鱼眼校正（分片逼近，见 FisheyeProjection）
 *        ▼
 *   已校正画面 (0..1)
 *        │  取景矩形 —— 按显示框的形状中心裁一刀
 *        ▼
 *   后视镜窗口
 * </pre>
 *
 * <h3>比例是锁死的</h3>
 *
 * <p>显示框可以拉成任意宽高，但<b>画面本身的长宽比永远不变</b> ——
 * 后视镜里的车被拉扁或压长，会直接影响对距离的判断，
 * 这不是能拿来换布局自由度的东西。</p>
 *
 * <p>所以显示框的形状不去拉伸画面，而是决定<b>能看到多大一块</b>：
 * 框拉宽，看到的是更扁的一条；框拉高，看到的是更方的一块。
 * 画面按比例填满框，多出来的裁掉 —— 不留黑边，
 * 拉一条很扁的框却在上下留大片黑边等于白拉。</p>
 *
 * <h3>于是「蒙版」退化成了一个数</h3>
 *
 * <p>左右方向：框拉多宽就看多宽，看多远由<b>视野角度</b>决定，这里没有可调的余地。</p>
 *
 * <p>上下方向：能看到多高已经被「比例不许变」钉死了，也没有可调的余地 ——
 * <b>只剩「这一条落在画面的哪个高度上」</b>，也就是一个上下平移量。
 * 所以以前那个能随手拖框改大小的蒙版编辑模式没有存在的必要，已经删掉。</p>
 *
 * <p>为什么取景定义在<b>校正之后</b>的空间：鱼眼校正是非线性的，
 * 先裁再校正和先校正再裁得到的不是同一块内容。用户看的是校正后的画面，
 * 取景就必须跟着那个空间走。</p>
 *
 * <p>本类只算坐标，不碰 OpenGL 也不碰 Android，可以直接跑 JVM 单元测试 ——
 * 取景算错的表现是「画面偏了一点」，在车上很难用肉眼判断对不对，必须能测。</p>
 */
public final class RearViewGeometry {

    /** 2x2 四宫格里后方画面所在的格子：右上。 */
    public static final int REAR_CELL = 1;

    /** 上下平移的默认值：正中间。 */
    public static final float DEFAULT_PAN = 0.5f;

    private RearViewGeometry() {
    }

    /**
     * 视野角度换算成「看这一路的多大一块」。
     *
     * <p>和校正那条路是同一套说法：输出边缘对应偏离光轴 fov/2 的射线，
     * 而半径 1.0 记作 90°。所以 180° 就是整幅，110° 约六成，90° 一半。</p>
     *
     * <p>有了这个，<b>关掉鱼眼校正时那根滑块照样有用</b> —— 只是它不再是
     * 「校正到多大视野」，而是「取这一路的多大一块」。两种模式下拖动的手感一致。</p>
     */
    public static float visibleFractionForFov(float fovDegrees) {
        float fov = FisheyeProjection.clampFov(fovDegrees);
        return Math.max(0.05f, Math.min(1f, fov / 180f));
    }

    /** 把上下平移量夹进合法范围。0 = 贴着画面顶部，1 = 贴着底部。 */
    public static float clampPan(float pan) {
        return Math.max(0f, Math.min(1f, pan));
    }

    /**
     * 显示框里看得到的那一块，用该路画面里的归一化坐标表示。
     *
     * <p>该路画面是正方形的（合成流里每一格都是 1280×1280），
     * 所以这里的换算就是「把一个正方形按显示框的形状中心裁一刀」。</p>
     */
    public static final class Viewport {
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        Viewport(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** 整幅，不裁。 */
        public static Viewport full() {
            return new Viewport(0f, 0f, 1f, 1f);
        }

        /**
         * 按显示框的形状算出该看哪一块。
         *
         * @param windowWidth  显示框宽（px）
         * @param windowHeight 显示框高（px）
         * @param pan          上下平移量，0..1；0 贴顶，1 贴底，0.5 居中
         */
        public static Viewport forWindow(int windowWidth, int windowHeight, float pan) {
            return forWindow(windowWidth, windowHeight, pan, 1f);
        }

        /**
         * @param visibleFraction 看这一路的多大一块，0..1。1 表示整幅；
         *                        小于 1 就是往里收，等效于放大
         */
        public static Viewport forWindow(int windowWidth, int windowHeight,
                                         float pan, float visibleFraction) {
            if (windowWidth <= 0 || windowHeight <= 0) {
                return full();
            }
            float aspect = (float) windowWidth / windowHeight;

            // 源是正方形：框比它扁就横向占满、纵向裁；框比它高就反过来
            float width = aspect >= 1f ? 1f : aspect;
            float height = aspect >= 1f ? 1f / aspect : 1f;

            // 视野角度：不做校正时也要管用，否则那根滑块拖了没反应。
            // 宽高同比例收，形状不变 —— 比例是锁死的，这里不能破例。
            float fraction = Math.max(0.05f, Math.min(1f, visibleFraction));
            width *= fraction;
            height *= fraction;

            // 左右居中；上下由 pan 决定落在哪
            float x = (1f - width) / 2f;
            float y = clampPan(pan) * (1f - height);
            return new Viewport(x, y, width, height);
        }

        /**
         * 上下还能挪多少。
         *
         * <p>框越扁，看到的那一条越窄，可挪的范围越大；框接近正方形时几乎挪不动 ——
         * 这不是限制，是「已经全看到了，没有别的可挪」。</p>
         */
        public float verticalHeadroom() {
            return 1f - height;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Viewport[%.3f,%.3f %.3fx%.3f]", x, y, width, height);
        }
    }

    /**
     * 交给分片绘制（或着色器）的两组值。
     *
     * <pre>
     *   vec2 inLane = viewOffset + coord * viewScale;   // 显示框 -&gt; 校正后画面
     *   vec2 fixed  = correct(inLane);                  // 鱼眼校正
     *   vec2 src    = laneOffset + fixed * laneScale;   // -&gt; 合成流纹理
     * </pre>
     */
    public static final class ShaderRects {
        public final float laneOffsetX;
        public final float laneOffsetY;
        public final float laneScaleX;
        public final float laneScaleY;
        public final float viewOffsetX;
        public final float viewOffsetY;
        public final float viewScaleX;
        public final float viewScaleY;

        ShaderRects(float laneOffsetX, float laneOffsetY, float laneScaleX, float laneScaleY,
                    float viewOffsetX, float viewOffsetY, float viewScaleX, float viewScaleY) {
            this.laneOffsetX = laneOffsetX;
            this.laneOffsetY = laneOffsetY;
            this.laneScaleX = laneScaleX;
            this.laneScaleY = laneScaleY;
            this.viewOffsetX = viewOffsetX;
            this.viewOffsetY = viewOffsetY;
            this.viewScaleX = viewScaleX;
            this.viewScaleY = viewScaleY;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "ShaderRects[lane %.3f,%.3f +%.3fx%.3f | view %.3f,%.3f +%.3fx%.3f]",
                    laneOffsetX, laneOffsetY, laneScaleX, laneScaleY,
                    viewOffsetX, viewOffsetY, viewScaleX, viewScaleY);
        }
    }

    /**
     * 把「哪一路 + 看哪一块」换算成绘制要的两组矩形参数。
     *
     * @param plan      合成流拆分方案；为 null 或不是合成流时按整幅处理
     * @param laneIndex 要取的画面序号
     * @param viewport  校正后画面里看得到的那一块
     */
    public static ShaderRects toShaderRects(CompositeStreamGeometry.Plan plan,
                                            int laneIndex, Viewport viewport) {
        Viewport effective = viewport != null ? viewport : Viewport.full();
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
     * 把「lane + 取景」压成一个矩形：最终要在合成流纹理里取哪一块。
     *
     * <p>这是不做鱼眼校正时的快捷路径。校正是非线性的，必须分两步走；
     * 但纯裁切是线性的，两层可以直接相乘，用一个 2D 矩阵就能实现 ——
     * 也就不需要给悬浮窗接 GL 管线。</p>
     *
     * <p>{@link CompositeStreamGeometry} 那条平台经验说过：
     * 用普通 TextureView + 矩阵重画是这台车机上验证可行的做法，
     * 而用 GL 自建 SurfaceTexture 顶替生产者会崩。所以能用矩阵解决的就不要上 GL。</p>
     *
     * @return {@code {x, y, width, height}}，归一化到合成流纹理
     */
    public static float[] combinedSourceRect(CompositeStreamGeometry.Plan plan,
                                             int laneIndex, Viewport viewport) {
        ShaderRects r = toShaderRects(plan, laneIndex, viewport);
        return new float[]{
                r.laneOffsetX + r.viewOffsetX * r.laneScaleX,
                r.laneOffsetY + r.viewOffsetY * r.laneScaleY,
                r.viewScaleX * r.laneScaleX,
                r.viewScaleY * r.laneScaleY,
        };
    }

}
