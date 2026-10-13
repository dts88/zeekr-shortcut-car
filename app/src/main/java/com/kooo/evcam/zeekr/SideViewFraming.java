package com.kooo.evcam.zeekr;

/**
 * Fits a corrected side view inside its camera image without clamping individual samples.
 * Keeps the requested aim and aspect ratio; reduces FOV when the requested view crosses
 * an image edge. The saved settings are unchanged. No pixels are invented or stretched
 * from an edge to cover missing camera coverage.
 */
public final class SideViewFraming {
    private static final float EDGE_MARGIN = 0.001f;
    private static final int BOUNDARY_STEPS = 64;
    private final float[] probe = new float[2];
    private SideViewProjection.Rays rays;
    private float fov, back, up, aspect;
    private int lane;
    private double fittedTangent;

    /** Returns true only when the configuration changes; fitting is cached between frames. */
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

    public float effectiveFovDegrees() {
        return (float) Math.toDegrees(2.0 * Math.atan(fittedTangent));
    }

    public void sourcePoint(float u, float v, float[] out) {
        if (rays == null) {
            throw new IllegalStateException("Configure side-view framing before sampling");
        }
        rays.map(u, v, fittedTangent, aspect, out, 0);
    }

    private boolean fits(double tangent) {
        // The curved boundary can reach an edge between corners. Sample all four edges
        // densely, plus every actual mesh vertex, before accepting a field of view.
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
