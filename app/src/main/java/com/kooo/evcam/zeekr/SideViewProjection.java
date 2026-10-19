package com.kooo.evcam.zeekr;

/**
 * 盲区画面的「转过去的虚拟相机」：拉直的同时把视线往后、往上转，看后视镜够不着的那块盲区。
 *
 * <h3>和 {@link FisheyeProjection} 的差别</h3>
 *
 * <p>那边的直线投影光轴固定朝正前方（鱼眼中心），视野最宽 140°，要看偏的地方只能在拉直后的画面里裁一块 ——
 * 裁不到 70° 以外。这里先把虚拟相机<b>转一个角度</b>，再按同一个镜头模型反投影：
 * 等距鱼眼，原图半径 0.5（这一路的边）对应偏离光轴 90°。转 80° 之后画面中心就落在鱼眼边上。</p>
 *
 * <h3>方向是猜的（2026-10-01，PR 作者实车反馈「往后不够」后改）</h3>
 *
 * <p>每一路<b>上边朝车外</b>，左侧那一路<b>左边是车尾</b>、右侧那一路<b>右边是车尾</b>。
 * 往后 = 绕竖轴朝车尾那一边转，往上 = 绕横轴朝画面上边转。猜反了就把角度往另一头调；默认角度要上车标定。</p>
 *
 * <p>画的时候走 {@link SideViewFraming}：转得多时整幅视野收窄，不在角点上一个个夹住 —— 角点夹到边上会把整格挤扁，
 * 画出黑块。</p>
 *
 * <p>来源：FerixzTanz 的 PR #4；{@link Rays} 和随窗口形状变的纵向视野是 wysie 在 PR #19 里加的；GPL-3.0。
 * 纯 Java，见 {@code SideViewProjectionTest}。</p>
 */
public final class SideViewProjection {

    public static final float MIN_FOV_DEGREES = 60f;
    public static final float MAX_FOV_DEGREES = 130f;
    public static final float MAX_YAW_DEGREES = 80f;
    public static final float MAX_PITCH_DEGREES = 60f;

    private static final double EPSILON = 1e-9;

    private SideViewProjection() {
    }

    /** 转好角度的虚拟相机：输出画面里的一点 → 这一路原始鱼眼画面里的采样点（不夹；越界由 {@link SideViewFraming} 先收窄视野）。 */
    static final class Rays {
        private final double cp, sp, cy, sy;

        /**
         * @param backDegrees 往车尾转多少度，负数往车头
         * @param upDegrees   往上（车外）转多少度，负数往下
         * @param lane        {@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}：决定车尾在画面哪一边
         */
        Rays(float backDegrees, float upDegrees, int lane) {
            double pitch = Math.toRadians(clamp(upDegrees, -MAX_PITCH_DEGREES, MAX_PITCH_DEGREES));
            cp = Math.cos(pitch);
            sp = Math.sin(pitch);
            double towardRear = lane == LaneCycle.RIGHT ? 1.0 : -1.0;
            double yaw = Math.toRadians(clamp(backDegrees, -MAX_YAW_DEGREES, MAX_YAW_DEGREES)) * towardRear;
            cy = Math.cos(yaw);
            sy = Math.sin(yaw);
        }

        /**
         * @param u           输出画面横向 0..1
         * @param v           输出画面纵向 0..1
         * @param t           tan(横向视野 / 2)
         * @param aspectRatio 窗口宽 ÷ 高：横向视野不变，纵向跟着窗口形状收（不合法的值按 1）
         */
        void map(float u, float v, double t, float aspectRatio, float[] out, int offset) {
            // 虚拟相机里的射线：x 右、y 下、z 朝前
            double x = (u * 2.0 - 1.0) * t;
            double aspect = Float.isFinite(aspectRatio) && aspectRatio > 0f ? aspectRatio : 1.0;
            double y = (v * 2.0 - 1.0) * t / aspect;
            double z = 1.0;

            // 先往上（绕 x 轴，朝 −y 转），再往后（绕 y 轴，朝车尾那一边转）
            double y1 = y * cp - z * sp;
            double z1 = y * sp + z * cp;

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
            out[offset] = (float) (0.5 + dirX * radius);
            out[offset + 1] = (float) (0.5 + dirY * radius);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
