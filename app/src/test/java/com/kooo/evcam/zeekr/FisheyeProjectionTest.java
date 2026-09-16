package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FisheyeProjection} 的单元测试。
 *
 * <p>校正算错的表现是「画面看着有点怪」—— 在车上很难判断是校正过头了、
 * 还是本来就该这样。所以数学部分必须在这里钉死。</p>
 */
public class FisheyeProjectionTest {

    private static final float TOLERANCE = 0.0005f;

    private float[] at(float x, float y, float fov) {
        float[] out = new float[2];
        FisheyeProjection.sourcePoint(x, y, fov, out, 0);
        return out;
    }

    @Test
    public void centreStaysAtTheCentre() {
        float[] p = at(0.5f, 0.5f, 110f);
        assertEquals(0.5f, p[0], TOLERANCE);
        assertEquals(0.5f, p[1], TOLERANCE);
    }

    /**
     * 参数就是字面意义上的视野：输出的左右边缘正好对应偏离光轴 fov/2 的射线，
     * 而等距鱼眼里半径正比于角度、90° 记作 0.5。这条成立，滑块上的度数才有意义。
     */
    @Test
    public void theEdgeLandsAtExactlyHalfTheStatedFieldOfView() {
        assertEquals(0.5f + (55f / 90f) * 0.5f, at(1f, 0.5f, 110f)[0], TOLERANCE);
        assertEquals(0.5f + (70f / 90f) * 0.5f, at(1f, 0.5f, 140f)[0], TOLERANCE);
        assertEquals(0.5f + (45f / 90f) * 0.5f, at(1f, 0.5f, 90f)[0], TOLERANCE);
    }

    @Test
    public void widerFieldOfViewReachesFurtherIntoTheSource() {
        assertTrue(at(1f, 0.5f, 140f)[0] > at(1f, 0.5f, 110f)[0]);
        assertTrue(at(1f, 0.5f, 110f)[0] > at(1f, 0.5f, 90f)[0]);
    }

    @Test
    public void movingRightInTheOutputMovesRightInTheSource() {
        float previous = -1f;
        for (float x = 0.5f; x <= 1.0001f; x += 0.1f) {
            float current = at(x, 0.5f, 110f)[0];
            assertTrue("应当单调递增，x=" + x, current > previous);
            previous = current;
        }
    }

    /**
     * 这正是矩阵做不到、必须分片逼近的原因：若映射是线性的，
     * 四分之三处的采样点就该落在中点与边缘的正中间 —— 它没有。
     */
    @Test
    public void theMappingIsNotLinear() {
        float edge = at(1f, 0.5f, 110f)[0];
        float threeQuarters = at(0.75f, 0.5f, 110f)[0];
        float ifItWereLinear = (0.5f + edge) / 2f;
        assertTrue("非线性差异应当明显", Math.abs(threeQuarters - ifItWereLinear) > 0.02f);
    }

    /**
     * 合成流里四路画面上下紧挨着，采样越界取到的是隔壁摄像头的画面。
     * 夹住之后最坏只是边缘被拉伸，不会串画面。
     */
    @Test
    public void samplingNeverLeavesThisLane() {
        for (float fov = FisheyeProjection.MIN_FOV_DEGREES;
                fov <= FisheyeProjection.MAX_FOV_DEGREES; fov += 5f) {
            for (float x = -0.5f; x <= 1.5f; x += 0.25f) {
                for (float y = -0.5f; y <= 1.5f; y += 0.25f) {
                    float[] p = at(x, y, fov);
                    assertTrue("x 越界 fov=" + fov, p[0] >= 0f && p[0] <= 1f);
                    assertTrue("y 越界 fov=" + fov, p[1] >= 0f && p[1] <= 1f);
                }
            }
        }
    }

    @Test
    public void fieldOfViewIsClampedToTheUsableRange() {
        assertEquals(FisheyeProjection.MIN_FOV_DEGREES, FisheyeProjection.clampFov(10f), TOLERANCE);
        assertEquals(FisheyeProjection.MAX_FOV_DEGREES, FisheyeProjection.clampFov(999f), TOLERANCE);
        assertEquals(110f, FisheyeProjection.clampFov(110f), TOLERANCE);
        // 超出范围的输入不该让投影本身失效
        assertEquals(at(1f, 0.5f, FisheyeProjection.MAX_FOV_DEGREES)[0], at(1f, 0.5f, 999f)[0], TOLERANCE);
    }

    /** 校正是对称的：偏左多少，就该对称地偏右多少。 */
    @Test
    public void theCorrectionIsSymmetricAboutTheCentre() {
        for (float offset = 0.1f; offset <= 0.5f; offset += 0.1f) {
            float left = at(0.5f - offset, 0.5f, 110f)[0];
            float right = at(0.5f + offset, 0.5f, 110f)[0];
            assertEquals(0.5f - left, right - 0.5f, TOLERANCE);
        }
    }

    /** 光心可以挪，挪多少画面就整体偏多少。 */
    @Test
    public void theOpticalCentreShiftsTheWholeMapping() {
        float[] shifted = new float[2];
        FisheyeProjection.sourcePoint(0.5f, 0.5f, 110f, 0.5f, 0.47f, shifted, 0);
        assertEquals(0.5f, shifted[0], TOLERANCE);
        assertEquals(0.47f, shifted[1], TOLERANCE);
    }

    /** 分片密度得是正数，否则绘制时会除零。 */
    @Test
    public void meshDivisionsArePositive() {
        assertTrue(FisheyeProjection.MESH_DIVISIONS > 0);
    }

    // ---------------------------------------------------------------- 逐像素重映射用的两个函数

    /**
     * 拆出来的两个函数必须和 {@link FisheyeProjection#sourcePoint} 算的是同一件事 ——
     * 照片走的是它们，后视镜走的是 sourcePoint，两边对不上就是「同一个视野角度、
     * 两个界面却不一样」。
     */
    @Test
    public void thePerPixelHelpersAgreeWithSourcePoint() {
        for (float fov : new float[]{90f, 110f, 140f}) {
            float halfFovTangent = FisheyeProjection.halfFovTangent(fov);
            for (float x = 0f; x <= 1.0001f; x += 0.125f) {
                float planeX = (x * 2f - 1f) * halfFovTangent;
                float planeRadius = Math.abs(planeX);
                float radius = FisheyeProjection.sourceRadius(planeRadius);
                float expected = at(x, 0.5f, fov)[0];
                float actual = 0.5f + Math.signum(planeX) * radius * 0.5f;
                assertEquals("fov=" + fov + " x=" + x, expected, actual, TOLERANCE);
            }
        }
    }

    /** 视野角度越大，像平面越宽 —— 同一个输出位置就采得更靠外。 */
    @Test
    public void aWiderFieldOfViewMeansAWiderImagePlane() {
        assertTrue(FisheyeProjection.halfFovTangent(140f) > FisheyeProjection.halfFovTangent(110f));
        assertTrue(FisheyeProjection.halfFovTangent(110f) > FisheyeProjection.halfFovTangent(90f));
        assertEquals(1f, FisheyeProjection.halfFovTangent(90f), TOLERANCE);
    }

    /** 半宽 1.0 就是 90°：这条定死了「视野」这个词在整个项目里的含义。 */
    @Test
    public void theImagePlaneEdgeIsNinetyDegrees() {
        assertEquals(0f, FisheyeProjection.sourceRadius(0f), TOLERANCE);
        assertEquals(0.5f, FisheyeProjection.sourceRadius(1f), TOLERANCE);
        assertTrue(FisheyeProjection.sourceRadius(1000f) < 1f);
    }

    // ---------------------------------------------------------------- 柱面投影

    private float[] cylindrical(float x, float y, float fov) {
        float[] out = new float[2];
        FisheyeProjection.cylindricalSourcePoint(x, y, fov, out, 0);
        return out;
    }

    @Test
    public void theCylindricalCentreStaysAtTheCentre() {
        float[] p = cylindrical(0.5f, 0.5f, 140f);
        assertEquals(0.5f, p[0], TOLERANCE);
        assertEquals(0.5f, p[1], TOLERANCE);
    }

    /**
     * 两种投影在<b>画面边上</b>看到的角度必须一样 —— 否则「视野 140°」在两个模式下
     * 指的就不是同一件事，用户切一下投影会觉得视野莫名其妙变了。
     */
    @Test
    public void bothProjectionsReachTheSameAngleAtTheEdge() {
        for (float fov : new float[]{90f, 110f, 140f}) {
            assertEquals("fov=" + fov,
                    at(1f, 0.5f, fov)[0], cylindrical(1f, 0.5f, fov)[0], 0.002f);
        }
    }

    /**
     * 中途才见分晓：柱面的横向位置正比于<b>角度</b>，直线的正比于 tan(角度)。
     * 所以同一个输出位置，柱面取到的点离中心更近 —— 边缘那一带于是被摊开，不像直线投影那样挤成一条。
     */
    @Test
    public void theCylindricalMappingIsLinearInAngleNotInTangent() {
        for (float fov : new float[]{110f, 140f}) {
            assertTrue("fov=" + fov,
                    cylindrical(0.75f, 0.5f, fov)[0] < at(0.75f, 0.5f, fov)[0]);
        }
    }

    /** 180°：每边正好 90°，画面边缘刚好落在鱼眼圆上。直线投影到这里早就发散了。 */
    @Test
    public void theCylindricalProjectionReachesTheWholeCircleAtOneEighty() {
        assertEquals(1f, cylindrical(1f, 0.5f, 180f)[0], 0.002f);
        assertEquals(0f, cylindrical(0f, 0.5f, 180f)[0], 0.002f);
    }

    @Test
    public void theCylindricalMappingIsMonotonicAndSymmetric() {
        float previous = -1f;
        for (float x = 0f; x <= 1.0001f; x += 0.1f) {
            float current = cylindrical(x, 0.5f, 140f)[0];
            assertTrue("应当单调递增，x=" + x, current > previous);
            previous = current;
        }
        for (float offset = 0.1f; offset <= 0.5f; offset += 0.1f) {
            float left = cylindrical(0.5f - offset, 0.5f, 140f)[0];
            float right = cylindrical(0.5f + offset, 0.5f, 140f)[0];
            assertEquals(0.5f - left, right - 0.5f, TOLERANCE);
        }
    }

    /** 和直线投影一样：采样不能越过本路，否则取到的是隔壁摄像头的画面。 */
    @Test
    public void theCylindricalSamplingNeverLeavesThisLane() {
        for (float fov = FisheyeProjection.MIN_FOV_DEGREES;
                fov <= FisheyeProjection.MAX_CYLINDRICAL_FOV_DEGREES; fov += 10f) {
            for (float x = -0.5f; x <= 1.5f; x += 0.25f) {
                for (float y = -0.5f; y <= 1.5f; y += 0.25f) {
                    float[] p = cylindrical(x, y, fov);
                    assertTrue("x 越界 fov=" + fov, p[0] >= 0f && p[0] <= 1f);
                    assertTrue("y 越界 fov=" + fov, p[1] >= 0f && p[1] <= 1f);
                }
            }
        }
    }

    /** 上限按投影分开：直线到 140° 就发散得没法看，柱面能到 180°。 */
    @Test
    public void eachProjectionHasItsOwnCeiling() {
        assertEquals(140f, FisheyeProjection.clampFov(
                175f, FisheyeProjection.PROJECTION_RECTILINEAR), TOLERANCE);
        assertEquals(175f, FisheyeProjection.clampFov(
                175f, FisheyeProjection.PROJECTION_CYLINDRICAL), TOLERANCE);
        assertEquals(90f, FisheyeProjection.clampFov(
                10f, FisheyeProjection.PROJECTION_CYLINDRICAL), TOLERANCE);
    }

    /** 认不出来的投影名走直线投影，不能让一个坏值把画面变成别的样子。 */
    @Test
    public void anUnknownProjectionFallsBackToStraightLines() {
        float[] fallback = new float[2];
        FisheyeProjection.sourcePoint(0.75f, 0.5f, 140f, "hyperbolic", fallback, 0);
        assertEquals(at(0.75f, 0.5f, 140f)[0], fallback[0], TOLERANCE);
    }

    /** 设置项里存的字符串就是算法认的那两个 —— 中间只隔着这一个值，写错了界面会静默回到直线。 */
    @Test
    public void theSettingValuesAreExactlyTheProjectionNames() {
        String[] values = com.kooo.evcam.settings.SettingsRegistry.FISHEYE_PROJECTION.values();
        assertEquals(2, values.length);
        assertEquals(FisheyeProjection.PROJECTION_RECTILINEAR, values[0]);
        assertEquals(FisheyeProjection.PROJECTION_CYLINDRICAL, values[1]);
        assertEquals(FisheyeProjection.PROJECTION_RECTILINEAR,
                com.kooo.evcam.settings.SettingsRegistry.FISHEYE_PROJECTION.defaultValue);
    }
}
