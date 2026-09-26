package com.kooo.evcam.zeekr;

/**
 * 鱼眼画面的反投影：把「校正后想看到的那个点」换算回原始鱼眼画面里的采样点。
 *
 * <p><b>为什么是反着算的。</b>校正一幅画面，直觉上是「把原图的每个点挪到新位置」，
 * 但绘制时我们拿到的是输出上的位置，需要知道该去源图哪里取色 —— 所以走的是反方向。</p>
 *
 * <p><b>模型：等距鱼眼 + 直线虚拟相机。</b>输出画面被看作一台普通（直线成像）相机
 * 拍到的像；每个输出点对应一条射线，射线偏离光轴的角度 θ 决定它在鱼眼原图里的半径 ——
 * 等距鱼眼的特征就是半径正比于角度，半径 1.0 对应 90°（即 180° 全视场）。</p>
 *
 * <p>这个模型只需要一个参数：{@link #DEFAULT_FOV_DEGREES 目标视野角度}。
 * 相比之下 Brown-Conrady 那类畸变模型要 k1/k2 这样的标定系数，
 * 必须拿棋盘格标定才能得到，没法让人凭手感调 —— 而「视野 110°」是看得懂的。</p>
 *
 * <p><b>这里没有 OpenGL。</b>校正是非线性的，一个 2D 矩阵表达不了，
 * 通常的做法是上着色器；但 {@link CompositeStreamGeometry} 记着的那条平台经验说过，
 * 在这台车机上用 GL 自建 SurfaceTexture 顶替相机生产者会崩。
 * 绕开的办法是分片逼近：把输出切成 {@link #MESH_DIVISIONS} 见方的小格，
 * 每一格四个角各算一次本类，然后用 {@code Matrix.setPolyToPoly} 把源四边形映射到目标格 ——
 * 每一格内部是线性的，格子够密，拼起来就足够接近那条曲线。
 * 画的仍然是原来那个 TextureView，没有新的 Surface，也没有回读位图。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class FisheyeProjection {

    /** 目标视野角度（度）。输出画面左右边缘正好对应偏离光轴 fov/2 的那条射线。 */
    public static final float DEFAULT_FOV_DEGREES = 110f;
    public static final float MIN_FOV_DEGREES = 90f;
    public static final float MAX_FOV_DEGREES = 140f;

    /** 直线投影：直线掰得笔直，代价是视野之外一律裁掉，而且越靠边放得越大。 */
    public static final String PROJECTION_RECTILINEAR = "rectilinear";

    /** 柱面投影：竖着的东西保持直，横线略弯，换来的是左右能多留住一大片。 */
    public static final String PROJECTION_CYLINDRICAL = "cylindrical";

    /**
     * 立体投影：整幅鱼眼都装得下，直线掰得没那么彻底。
     *
     * <p>把射线放在 tan(θ/2) 上而不是 tan(θ)。后者到 90° 就发散，所以直线投影必须裁；
     * 前者到 180° 才发散，于是整个圆都能留在画面里，代价是直线只掰直了一部分。</p>
     */
    public static final String PROJECTION_STEREOGRAPHIC = "stereographic";

    /**
     * 柱面投影的视野上限。
     *
     * <p>直线投影到 90° 就发散，所以卡在 140°；柱面没有这个问题 —— 横向位置正比于方位角，
     * 180°（每边 90°）仍然老老实实落在画面里。</p>
     */
    public static final float MAX_CYLINDRICAL_FOV_DEGREES = 180f;

    /**
     * 屏幕上鱼眼校正（主界面预览、图片回看、视频回看）的默认视野角度。
     *
     * <p>比后视镜的默认值宽：后视镜是边开车边扫一眼，窄一点、大一点反而好认；
     * 预览和回看要看全，留下的范围越大越有用，四角裁掉的也少一些。
     * 校正的<b>算法</b>两边完全一样，差的只是这一个数。名字里的 PHOTO 是历史：它最早只管图片回看。</p>
     */
    public static final float PHOTO_FOV_DEGREES = 140f;

    /** 光心在原始画面里的位置。多数情况下就是正中间。 */
    public static final float DEFAULT_CENTER_X = 0.5f;
    public static final float DEFAULT_CENTER_Y = 0.5f;

    /**
     * 分片密度：输出切成 N×N 个小格。
     *
     * <p>代价是每帧 N² 次绘制，收益是曲线逼近得更准。10 是这台车机上单路画面
     * 已知够用的密度 —— 再密看不出区别，再稀就能看出格子边界。</p>
     */
    public static final int MESH_DIVISIONS = 10;

    private static final float EPSILON = 1e-5f;

    private FisheyeProjection() {
    }

    public static float clampFov(float degrees) {
        return Math.max(MIN_FOV_DEGREES, Math.min(MAX_FOV_DEGREES, degrees));
    }

    /**
     * 这种投影能给到多大的视野。
     *
     * <p>直线投影卡在 140°：它把射线放在 tan(θ) 上，θ 到 90° 就是无穷，再宽边缘就没法看了。
     * 另外两种没有这个问题。</p>
     */
    public static float maxFovFor(String projection) {
        return PROJECTION_RECTILINEAR.equals(projection) || projection == null
                ? MAX_FOV_DEGREES : MAX_CYLINDRICAL_FOV_DEGREES;
    }

    /** 按投影方式夹住视野角度 —— 上限两种投影不一样。 */
    public static float clampFov(float degrees, String projection) {
        return Math.max(MIN_FOV_DEGREES, Math.min(maxFovFor(projection), degrees));
    }

    /**
     * 按指定投影做反向映射。
     *
     * @param projection {@link #PROJECTION_RECTILINEAR} 或 {@link #PROJECTION_CYLINDRICAL}；
     *                   认不出来的值走直线投影
     */
    public static void sourcePoint(float x, float y, float fovDegrees, String projection,
                                   float[] out, int offset) {
        if (PROJECTION_CYLINDRICAL.equals(projection)) {
            cylindricalSourcePoint(x, y, fovDegrees, out, offset);
            return;
        }
        if (PROJECTION_STEREOGRAPHIC.equals(projection)) {
            stereographicSourcePoint(x, y, fovDegrees, out, offset);
            return;
        }
        sourcePoint(x, y, fovDegrees, out, offset);
    }

    /**
     * 带强度的反向映射：{@code strength} 从 0 到 1，在「原图不动」和「完全校正」之间插值。
     *
     * <p>0 就是原样：输出点直接取源图同一个位置。1 就是那一种投影本来的样子。
     * 中间是线性插值 —— 它不对应任何一种真实的成像模型，但这里要的是一个能用手感调的旋钮：
     * 校正过头和校正不足都能看出来，中间那一档往往才是顺眼的。</p>
     */
    public static void sourcePoint(float x, float y, float fovDegrees, String projection,
                                   float strength, float[] out, int offset) {
        sourcePoint(x, y, fovDegrees, projection, out, offset);
        if (strength >= 1f) {
            return;
        }
        float amount = Math.max(0f, strength);
        out[offset] = x + (out[offset] - x) * amount;
        out[offset + 1] = y + (out[offset + 1] - y) * amount;
    }

    /**
     * 立体投影的反向映射：校正后画面里的一点 → 原始鱼眼画面里的采样点。
     *
     * <p>和直线投影同一个约定：画面边缘对应偏离光轴 fov/2 的那条射线。差别只在中间怎么分配 ——
     * 半径放在 tan(θ/2) 上，中心压得比直线投影轻，边缘也不会被拉到没法看。</p>
     */
    public static void stereographicSourcePoint(float x, float y, float fovDegrees,
                                                float[] out, int offset) {
        double halfFov = Math.toRadians(clampFov(fovDegrees, PROJECTION_STEREOGRAPHIC) / 2.0);
        double edge = Math.tan(halfFov / 2.0);
        double dx = (x * 2.0 - 1.0) * edge;
        double dy = (y * 2.0 - 1.0) * edge;
        double planeRadius = Math.hypot(dx, dy);
        if (planeRadius < EPSILON) {
            out[offset] = DEFAULT_CENTER_X;
            out[offset + 1] = DEFAULT_CENTER_Y;
            return;
        }
        double angle = 2.0 * Math.atan(planeRadius);
        float sourceRadius = (float) (angle / (Math.PI / 2.0));
        out[offset] = clamp01((float) (DEFAULT_CENTER_X + dx / planeRadius * sourceRadius * 0.5));
        out[offset + 1] = clamp01((float) (DEFAULT_CENTER_Y + dy / planeRadius * sourceRadius * 0.5));
    }

    /**
     * 柱面投影的反向映射：校正后画面里的一点 → 原始鱼眼画面里的采样点。
     *
     * <h3>和直线投影差在哪</h3>
     *
     * <p>直线投影把画面摊在一块<b>平板</b>上，横向位置正比于 tan(方位角) —— 所以世界里的直线
     * 全都是直的，但角度一大，平板就得无限宽，只能裁。柱面投影把画面卷在一个<b>圆柱</b>上，
     * 横向位置<b>正比于方位角本身</b>：圆柱可以一直卷下去，180° 也装得下。</p>
     *
     * <p>代价是横向的直线会弯 —— 只有竖直方向仍按平板算，所以车柱、路灯、门框这类竖线保持直，
     * 而地平线、路沿会有一点弧度。对行车记录仪来说这笔买卖大多是划算的：
     * 左右能看到的范围才是这类画面的价值所在。</p>
     *
     * <p>纵向那个系数取半视野（弧度），是为了让<b>画面正中不被拉扁</b>：
     * 中心处横向每单位对应 halfFov 弧度，纵向也得是 halfFov，两边才一样。</p>
     */
    public static void cylindricalSourcePoint(float x, float y, float fovDegrees,
                                              float[] out, int offset) {
        double halfFov = Math.toRadians(clampFov(fovDegrees, PROJECTION_CYLINDRICAL) / 2.0);
        double azimuth = (x * 2.0 - 1.0) * halfFov;
        double height = (y * 2.0 - 1.0) * halfFov;

        double sinAzimuth = Math.sin(azimuth);
        double cosAzimuth = Math.cos(azimuth);
        // 射线是 (sinφ, h, cosφ)；它偏离光轴多少，决定在原图上离中心多远
        double norm = Math.sqrt(1.0 + height * height);
        double cosAngle = Math.max(-1.0, Math.min(1.0, cosAzimuth / norm));
        float sourceRadius = (float) (Math.acos(cosAngle) / (Math.PI / 2.0));

        double planar = Math.sqrt(sinAzimuth * sinAzimuth + height * height);
        double directionX = planar > EPSILON ? sinAzimuth / planar : 0.0;
        double directionY = planar > EPSILON ? height / planar : 0.0;

        out[offset] = clamp01((float) (DEFAULT_CENTER_X + directionX * sourceRadius * 0.5));
        out[offset + 1] = clamp01((float) (DEFAULT_CENTER_Y + directionY * sourceRadius * 0.5));
    }

    /**
     * 校正后画面里的一点 → 原始鱼眼画面里的采样点。两边都是该路画面内的归一化坐标。
     *
     * <p>结果<b>夹在 [0,1] 内</b>，这一步不是保险而是必须的：合成流里四路画面上下
     * 紧挨着排列，采样一旦越过本路边界，取到的就是隔壁那个摄像头的画面。
     * 夹住之后最坏情况只是边缘被拉伸，不会串画面。</p>
     *
     * @param x      校正后画面里的横向归一化坐标
     * @param y      校正后画面里的纵向归一化坐标
     * @param fovDegrees 目标视野角度
     * @param centerX 光心横向位置
     * @param centerY 光心纵向位置
     * @param out    结果写到 {@code out[offset]}（x）和 {@code out[offset+1]}（y）
     */
    public static void sourcePoint(float x, float y, float fovDegrees,
                                   float centerX, float centerY,
                                   float[] out, int offset) {
        // 输出点摊到虚拟相机的像平面上。乘 tan(fov/2) 之后，边缘（±1）正好落在
        // 偏离光轴 fov/2 的那条射线上 —— 所以这个参数就是字面意义上的「视野」。
        float halfFovTangent = halfFovTangent(fovDegrees);
        float planeX = (x * 2f - 1f) * halfFovTangent;
        float planeY = (y * 2f - 1f) * halfFovTangent;

        float planeRadius = (float) Math.hypot(planeX, planeY);
        float sourceRadius = sourceRadius(planeRadius);

        float directionX = planeRadius > EPSILON ? planeX / planeRadius : 0f;
        float directionY = planeRadius > EPSILON ? planeY / planeRadius : 0f;

        // 半径是以光心为原点、到画面半宽为 1 计的，所以折回归一化坐标要乘 0.5
        out[offset] = clamp01(centerX + directionX * sourceRadius * 0.5f);
        out[offset + 1] = clamp01(centerY + directionY * sourceRadius * 0.5f);
    }

    /** 用默认光心的简写。 */
    public static void sourcePoint(float x, float y, float fovDegrees, float[] out, int offset) {
        sourcePoint(x, y, fovDegrees, DEFAULT_CENTER_X, DEFAULT_CENTER_Y, out, offset);
    }

    /**
     * {@code tan(fov/2)}：像平面的半宽。
     *
     * <p>单独拿出来，是因为逐像素重映射（{@link FisheyeCorrector}）每个像素都要用它，
     * 而它只跟视野角度有关 —— 一格算一次就够，不必每个像素重算一遍三角函数。</p>
     */
    public static float halfFovTangent(float fovDegrees) {
        return (float) Math.tan(Math.toRadians(clampFov(fovDegrees)) / 2.0);
    }

    /**
     * 像平面半径 → 原图半径。两边都以画面半宽为 1 计。
     *
     * <p>等距鱼眼的定义就在这一行：原图半径正比于射线偏离光轴的角度，半径 1.0 记作 90°。</p>
     */
    public static float sourceRadius(float planeRadius) {
        return (float) (Math.atan(planeRadius) / (Math.PI / 2.0));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
