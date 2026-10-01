package com.kooo.evcam.zeekr;

/**
 * 侧视弹窗的「转过去的虚拟相机」：拉直的同时把视线往后、往上转，看后视镜够不着的那块盲区。
 *
 * <h3>和 {@link FisheyeProjection} 的差别</h3>
 *
 * <p>那边的直线投影光轴固定朝正前方（鱼眼中心），视野最宽 140°，要看偏的地方只能在拉直后的画面里裁一块 ——
 * 裁不到 70° 以外。这里先把虚拟相机<b>转一个角度</b>，再按同一个模型反投影：
 * 等距鱼眼，原图半径 0.5（这一路的边）对应偏离光轴 90°。转 80° 之后画面中心就落在鱼眼边上。</p>
 *
 * <h3>方向是猜的（2026-10-01，PR 作者实车反馈「往后不够」后改）</h3>
 *
 * <p>同 {@link SideViewAim}：每一路<b>上边朝车外</b>，左侧那一路<b>左边是车尾</b>、右侧那一路<b>右边是车尾</b>。
 * 往后 = 绕竖轴朝车尾那一边转，往上 = 绕横轴朝画面上边转。猜反了滑块往另一头拉。</p>
 *
 * <p>纯 Java，见 {@code SideViewProjectionTest}。</p>
 */
public final class SideViewProjection {

    public static final float MIN_FOV_DEGREES = 60f;
    public static final float MAX_FOV_DEGREES = 130f;
    public static final float MAX_YAW_DEGREES = 80f;
    public static final float MAX_PITCH_DEGREES = 60f;

    private static final double EPSILON = 1e-9;

    private SideViewProjection() {
    }

    /**
     * 输出画面里的一点 → 这一路原始鱼眼画面里的采样点（这一路内的归一化坐标，夹在 0..1，不会串到隔壁那一路）。
     *
     * @param u          输出画面横向 0..1
     * @param v          输出画面纵向 0..1
     * @param fovDegrees 虚拟相机的视野（左右边缘之间的角度）
     * @param backDegrees 往车尾转多少度，负数往车头
     * @param upDegrees  往上（车外）转多少度，负数往下
     * @param lane       {@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}：决定车尾在画面哪一边
     */
    public static void sourcePoint(float u, float v, float fovDegrees, float backDegrees, float upDegrees,
                                   int lane, float[] out, int offset) {
        double fov = Math.toRadians(clamp(fovDegrees, MIN_FOV_DEGREES, MAX_FOV_DEGREES));
        double t = Math.tan(fov / 2.0);
        // 虚拟相机里的射线：x 右、y 下、z 朝前
        double x = (u * 2.0 - 1.0) * t;
        double y = (v * 2.0 - 1.0) * t;
        double z = 1.0;

        // 先往上（绕 x 轴，朝 −y 转），再往后（绕 y 轴，朝车尾那一边转）
        double pitch = Math.toRadians(clamp(upDegrees, -MAX_PITCH_DEGREES, MAX_PITCH_DEGREES));
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double y1 = y * cp - z * sp;
        double z1 = y * sp + z * cp;

        double towardRear = lane == LaneCycle.RIGHT ? 1.0 : -1.0;
        double yaw = Math.toRadians(clamp(backDegrees, -MAX_YAW_DEGREES, MAX_YAW_DEGREES)) * towardRear;
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double x2 = x * cy + z1 * sy;
        double z2 = -x * sy + z1 * cy;
        double y2 = y1;

        // 射线偏离鱼眼光轴多少度 → 原图上离中心多远（等距：半径 0.5 = 90°）
        double length = Math.sqrt(x2 * x2 + y2 * y2 + z2 * z2);
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, z2 / length)));
        double planar = Math.hypot(x2, y2);
        double dirX = planar > EPSILON ? x2 / planar : 0.0;
        double dirY = planar > EPSILON ? y2 / planar : 0.0;
        double radius = angle / (Math.PI / 2.0) * 0.5;
        out[offset] = clamp01((float) (0.5 + dirX * radius));
        out[offset + 1] = clamp01((float) (0.5 + dirY * radius));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
