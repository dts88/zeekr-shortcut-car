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
        float halfFovTangent = (float) Math.tan(Math.toRadians(clampFov(fovDegrees)) / 2.0);
        float planeX = (x * 2f - 1f) * halfFovTangent;
        float planeY = (y * 2f - 1f) * halfFovTangent;

        float planeRadius = (float) Math.hypot(planeX, planeY);
        // 等距鱼眼：原图半径正比于射线角度，半径 1.0 记作 90°
        float angle = (float) Math.atan(planeRadius);
        float sourceRadius = angle / ((float) Math.PI / 2f);

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
     * 静态图片上的分片密度。
     *
     * <p>照片只算一次，不像预览那样每帧都要算，所以可以比 {@link #MESH_DIVISIONS} 密得多 ——
     * 密度换来的是曲线逼近得更准，而这里的成本只有一次。</p>
     */
    public static final int PHOTO_MESH_DIVISIONS = 24;

    /** 一路画面的四个角：到中心的距离是半宽的 √2 倍，也就是这张图里最远的点。 */
    public static final float CORNER_RADIUS = (float) Math.sqrt(2.0);

    /**
     * 半径上的正向映射：原图半径 → 校正后的半径。两边都以<b>半宽为 1</b> 计。
     *
     * <h3>为什么这里不是 {@link #sourcePoint} 那套直线投影</h3>
     *
     * <p>直线投影（虚拟针孔相机）把偏离光轴 θ 的射线放到 tan θ 上，θ 越接近 90°
     * 拉得越远，到 90° 就是无穷 —— 而等距鱼眼里半宽 1.0 正是 90°，四个角更是到了 127°。
     * 也就是说<b>直线投影根本装不下整幅鱼眼画面</b>，只能选一个视野角度，其余的裁掉。
     * 后视镜就是这么做的：那是个小窗口，本来就只看中间一块。</p>
     *
     * <p>回看要的是另一件事 —— 先尽量把画面都留下，再由人决定裁多少。所以这里用
     * <b>立体投影</b>（r = tan(θ/2)）：θ 到 180° 才发散，整幅鱼眼都落在有限的范围内，
     * 弯的东西也大致被掰直了，只是不像直线投影那样把直线掰得一根不剩。</p>
     */
    public static float correctedRadius(float sourceRadius) {
        double angle = Math.max(0f, sourceRadius) * (Math.PI / 2.0);
        // 夹在最远的那个角上：再远的输入只会是调用方算错了，不该让它跑到无穷去
        angle = Math.min(angle, CORNER_RADIUS * (Math.PI / 2.0));
        return (float) Math.tan(angle / 2.0);
    }

    /**
     * 缩放：让<b>整个鱼眼圆</b>（半宽 1.0，也就是 180° 全视场）正好落在画面边上。
     *
     * <p>四个角在圆之外，会被画到框外去，由调用方裁掉 —— 那一圈要么是暗角，
     * 要么是拉到看不出东西的极边缘。要连角也留住就把这个值改小，
     * 代价是画面整体缩到一半，中间反而看不清。</p>
     */
    public static float keepCircleScale() {
        return 1f / correctedRadius(1f);
    }

    /**
     * 正向映射：原始鱼眼画面里的一点 → 校正后画面里的位置。
     * 两边都是本路画面内的归一化坐标，光心按画面正中算。
     *
     * <p>和 {@link #sourcePoint} 方向相反：那边是「输出点该去源图哪里取色」，
     * 这边是「源图这一点该画到哪里去」。{@code drawBitmapMesh} 要的正是后者。</p>
     *
     * <p>结果<b>可能落在 [0,1] 之外</b>（四个角），调用方必须按本路的范围裁剪，
     * 否则会画到隔壁那一路上。</p>
     */
    public static void correctedPoint(float x, float y, float[] out, int offset) {
        float dx = (x - DEFAULT_CENTER_X) * 2f;
        float dy = (y - DEFAULT_CENTER_Y) * 2f;
        float radius = (float) Math.hypot(dx, dy);
        if (radius < EPSILON) {
            out[offset] = DEFAULT_CENTER_X;
            out[offset + 1] = DEFAULT_CENTER_Y;
            return;
        }
        float mapped = correctedRadius(radius) * keepCircleScale();
        out[offset] = DEFAULT_CENTER_X + (dx / radius) * mapped * 0.5f;
        out[offset + 1] = DEFAULT_CENTER_Y + (dy / radius) * mapped * 0.5f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
