package com.kooo.evcam.zeekr;

/**
 * 把拉直、转过角度的盲区画面装进这一路的原图里，不逐点夹住。
 *
 * <p>朝向和窗口形状照要的不动；要的视野越过了原图的边，就把视野收窄到刚好装下（二分找）。存着的设置不改；
 * 原图没拍到的地方不编、也不从边上拉伸去补。</p>
 *
 * <p>为什么：逐个角点夹在 0..1 里，转的角度大时整片角点挤到原图边上，那几格四个角挤成一条线，画出来是黑块。</p>
 *
 * <p>来源：wysie 的 PR #19；GPL-3.0。纯 Java，见 {@code SideViewFramingTest}。</p>
 */
public final class SideViewFraming {
    private static final float EDGE_MARGIN = 0.001f;
    private static final int BOUNDARY_STEPS = 64;
    private final float[] probe = new float[2];
    private SideViewProjection.Rays rays;
    private float fov, back, up, aspect;
    private int lane;
    private double fittedTangent;

    /** 参数变了才重算、返回 true；没变的时候每一帧都不用再算。 */
    public boolean configure(float fov, float back, float up, int lane, float aspect) {
        fov = clamp(fov, SideViewProjection.MIN_FOV_DEGREES, SideViewProjection.MAX_FOV_DEGREES);
        back = clamp(back, -SideViewProjection.MAX_YAW_DEGREES, SideViewProjection.MAX_YAW_DEGREES);
        up = clamp(up, -SideViewProjection.MAX_PITCH_DEGREES, SideViewProjection.MAX_PITCH_DEGREES);
        aspect = Float.isFinite(aspect) && aspect > 0f ? aspect : 1f;
        if (rays != null && this.fov == fov && this.back == back && this.up == up
                && this.lane == lane && this.aspect == aspect) {
            return false;
        }
        this.fov = fov;
        this.back = back;
        this.up = up;
        this.lane = lane;
        this.aspect = aspect;
        rays = new SideViewProjection.Rays(back, up, lane);
        double requested = Math.tan(Math.toRadians(fov) / 2.0);
        if (fits(requested)) {
            fittedTangent = requested;
        } else {
            double low = 0.0;
            double high = requested;
            for (int i = 0; i < 20; i++) {
                double middle = (low + high) / 2.0;
                if (fits(middle)) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            fittedTangent = low;
        }
        return true;
    }

    /** 实际用的横向视野（度）：装得下就是要的那个，装不下是收窄之后的。 */
    public float effectiveFovDegrees() {
        return (float) Math.toDegrees(2.0 * Math.atan(fittedTangent));
    }

    /** 输出画面里的一点 → 这一路原图里的采样点（0..1）。先 {@link #configure}。 */
    public void sourcePoint(float u, float v, float[] out) {
        if (rays == null) {
            throw new IllegalStateException("Configure side-view framing before sampling");
        }
        rays.map(u, v, fittedTangent, aspect, out, 0);
    }

    private boolean fits(double tangent) {
        // 弯过去的边可能在两个角点之间碰到原图的边：四条边密密地采样，再加网格的每一个角点，都在里面才算装得下
        for (int i = 0; i <= BOUNDARY_STEPS; i++) {
            float s = (float) i / BOUNDARY_STEPS;
            if (!inside(0f, s, tangent) || !inside(1f, s, tangent)
                    || !inside(s, 0f, tangent) || !inside(s, 1f, tangent)) {
                return false;
            }
        }
        int divisions = FisheyeProjection.MESH_DIVISIONS;
        for (int row = 0; row <= divisions; row++) {
            for (int column = 0; column <= divisions; column++) {
                if (!inside((float) column / divisions, (float) row / divisions, tangent)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean inside(float u, float v, double tangent) {
        rays.map(u, v, tangent, aspect, probe, 0);
        return Float.isFinite(probe[0]) && Float.isFinite(probe[1])
                && probe[0] >= EDGE_MARGIN && probe[0] <= 1f - EDGE_MARGIN
                && probe[1] >= EDGE_MARGIN && probe[1] <= 1f - EDGE_MARGIN;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, Float.isFinite(value) ? value : 0f));
    }
}
