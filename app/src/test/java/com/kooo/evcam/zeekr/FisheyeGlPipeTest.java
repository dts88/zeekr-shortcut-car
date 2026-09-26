package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * GPU 逐像素校正（{@link FisheyeGlPipe}）的着色器必须和 {@link FisheyeProjection#sourcePoint}
 * 算同一件事 —— 否则同一组设置，预览、视频回看和图片回看看到的是三种样子。
 *
 * <p>JVM 上跑不了着色器，所以这里放一份<b>逐行照抄着色器</b>的 Java 版（{@link #shader}），
 * 只用着色器拿得到的那几个数（{@link FisheyeProjection#shaderProjectionCode}、
 * {@link FisheyeProjection#shaderParameter}），和 sourcePoint 逐点比。改着色器时这份要一起改。</p>
 */
public class FisheyeGlPipeTest {

    private static final float TOLERANCE = 0.0005f;
    private static final double HALF_PI = 1.5707963268;

    /** 照抄 FRAGMENT_SHADER 里的 corrected() 和强度插值。 */
    private static float[] shader(float x, float y, int projection, float param, float strength) {
        double px;
        double py;
        if (projection == 0) {
            double planeX = (x * 2.0 - 1.0) * param;
            double planeY = (y * 2.0 - 1.0) * param;
            double r = Math.hypot(planeX, planeY);
            if (r < 0.00001) {
                px = 0.5;
                py = 0.5;
            } else {
                double sr = Math.atan(r) / HALF_PI;
                px = clamp01(0.5 + planeX / r * sr * 0.5);
                py = clamp01(0.5 + planeY / r * sr * 0.5);
            }
        } else if (projection == 1) {
            double az = (x * 2.0 - 1.0) * param;
            double h = (y * 2.0 - 1.0) * param;
            double s = Math.sin(az);
            double cosAngle = Math.max(-1.0, Math.min(1.0, Math.cos(az) / Math.sqrt(1.0 + h * h)));
            double sr = Math.acos(cosAngle) / HALF_PI;
            double planar = Math.sqrt(s * s + h * h);
            double dx = planar > 0.00001 ? s / planar : 0.0;
            double dy = planar > 0.00001 ? h / planar : 0.0;
            px = clamp01(0.5 + dx * sr * 0.5);
            py = clamp01(0.5 + dy * sr * 0.5);
        } else {
            double dx = (x * 2.0 - 1.0) * param;
            double dy = (y * 2.0 - 1.0) * param;
            double r = Math.hypot(dx, dy);
            if (r < 0.00001) {
                px = 0.5;
                py = 0.5;
            } else {
                double sr = 2.0 * Math.atan(r) / HALF_PI;
                px = clamp01(0.5 + dx / r * sr * 0.5);
                py = clamp01(0.5 + dy / r * sr * 0.5);
            }
        }
        return new float[]{
                (float) (x + (px - x) * strength),
                (float) (y + (py - y) * strength),
        };
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Test
    public void theShaderComputesWhatSourcePointComputes() {
        String[] projections = {FisheyeProjection.PROJECTION_RECTILINEAR,
                FisheyeProjection.PROJECTION_CYLINDRICAL, FisheyeProjection.PROJECTION_STEREOGRAPHIC};
        float[] fovs = {90f, 110f, 140f, 170f, 180f};
        float[] strengths = {1f, 0.6f, 0.1f};
        float[] want = new float[2];
        for (String projection : projections) {
            int code = FisheyeProjection.shaderProjectionCode(projection);
            for (float fov : fovs) {
                float param = FisheyeProjection.shaderParameter(fov, projection);
                for (float strength : strengths) {
                    for (float y = 0f; y <= 1.0001f; y += 0.0625f) {
                        for (float x = 0f; x <= 1.0001f; x += 0.0625f) {
                            FisheyeProjection.sourcePoint(x, y, fov, projection, strength, want, 0);
                            float[] got = shader(x, y, code, param, strength);
                            String at = projection + " fov=" + fov + " strength=" + strength
                                    + " @" + x + "," + y;
                            assertEquals(at + " x", want[0], got[0], TOLERANCE);
                            assertEquals(at + " y", want[1], got[1], TOLERANCE);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void projectionCodesMatchTheShaderBranches() {
        assertEquals(0, FisheyeProjection.shaderProjectionCode(FisheyeProjection.PROJECTION_RECTILINEAR));
        assertEquals(1, FisheyeProjection.shaderProjectionCode(FisheyeProjection.PROJECTION_CYLINDRICAL));
        assertEquals(2, FisheyeProjection.shaderProjectionCode(FisheyeProjection.PROJECTION_STEREOGRAPHIC));
        // 认不出来的值走直线，和 sourcePoint 一样
        assertEquals(0, FisheyeProjection.shaderProjectionCode(null));
        assertEquals(0, FisheyeProjection.shaderProjectionCode("whatever"));
    }

    /** 视野先按投影夹：直线投影 170° 生效的是 140°。 */
    @Test
    public void theParameterUsesTheClampedFieldOfView() {
        assertEquals((float) Math.tan(Math.toRadians(70)),
                FisheyeProjection.shaderParameter(170f, FisheyeProjection.PROJECTION_RECTILINEAR),
                TOLERANCE);
        assertEquals((float) Math.toRadians(85),
                FisheyeProjection.shaderParameter(170f, FisheyeProjection.PROJECTION_CYLINDRICAL),
                TOLERANCE);
        assertEquals((float) Math.tan(Math.toRadians(45)),
                FisheyeProjection.shaderParameter(180f, FisheyeProjection.PROJECTION_STEREOGRAPHIC),
                TOLERANCE);
    }

    /** 录好的环视视频是 2×2：四格铺满、互不重叠。 */
    @Test
    public void theVideoGridCoversTheFrame() {
        float[] grid = FisheyeGlPipe.GRID_2X2;
        assertEquals(16, grid.length);
        float area = 0f;
        for (int i = 0; i < 4; i++) {
            area += grid[i * 4 + 2] * grid[i * 4 + 3];
        }
        assertEquals(1f, area, TOLERANCE);
    }
}
