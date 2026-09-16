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

    // ---------------------------------------------------------------- 正向映射（图片回看）

    private float[] forward(float x, float y, float fov) {
        float[] out = new float[2];
        FisheyeProjection.correctedPoint(x, y, fov, out, 0);
        return out;
    }

    @Test
    public void theForwardMappingKeepsTheCentreWhereItIs() {
        float[] p = forward(0.5f, 0.5f, 110f);
        assertEquals(0.5f, p[0], TOLERANCE);
        assertEquals(0.5f, p[1], TOLERANCE);
    }

    /**
     * 正反两个方向必须是同一条曲线 —— 这条成立，照片的校正量才和后视镜一模一样。
     * 一个说「输出点去源图哪里取色」，另一个说「源图这点画到哪里去」，绕一圈得回到原处。
     */
    @Test
    public void theTwoDirectionsAreInverseOfEachOther() {
        for (float fov : new float[]{90f, 110f, 140f}) {
            for (float x = 0.05f; x <= 0.95f; x += 0.1f) {
                float[] corrected = forward(x, 0.5f, fov);
                float[] back = at(corrected[0], corrected[1], fov);
                assertEquals("fov=" + fov + " x=" + x, x, back[0], 0.002f);
                assertEquals("fov=" + fov + " x=" + x, 0.5f, back[1], 0.002f);
            }
        }
    }

    /** 偏离光轴正好 fov/2 的那一圈落在画面边上 —— 视野角度在这边也是字面意义。 */
    @Test
    public void theStatedFieldOfViewLandsOnTheEdge() {
        for (float fov : new float[]{90f, 110f, 140f}) {
            float atHalfFov = 0.5f + (fov / 2f / 90f) * 0.5f;
            assertEquals("fov=" + fov, 1f, forward(atHalfFov, 0.5f, fov)[0], 0.002f);
        }
    }

    /** 视野之外的那一圈落到框外，绘制时必须裁掉，不能画到隔壁那一路上。 */
    @Test
    public void whatLiesBeyondTheFieldOfViewFallsOutsideTheFrame() {
        assertTrue(forward(1f, 0.5f, 110f)[0] > 1f);
        float[] corner = forward(1f, 1f, 110f);
        assertTrue(corner[0] > 1f);
        assertTrue(corner[1] > 1f);
    }

    /** 校正就是把边缘拉开：越靠外推得越远，中间几乎不动。 */
    @Test
    public void theEdgeIsStretchedMoreThanTheMiddle() {
        float middle = forward(0.6f, 0.5f, 110f)[0] - 0.6f;
        float edge = forward(0.9f, 0.5f, 110f)[0] - 0.9f;
        assertTrue("边缘应当被推得更远: " + middle + " vs " + edge, edge > middle);
    }

    /** 视野越小，同一点被推得越远（看到的范围越窄、放得越大）。 */
    @Test
    public void aNarrowerFieldOfViewPushesFurther() {
        assertTrue(forward(0.7f, 0.5f, 90f)[0] > forward(0.7f, 0.5f, 110f)[0]);
        assertTrue(forward(0.7f, 0.5f, 110f)[0] > forward(0.7f, 0.5f, 140f)[0]);
    }

    /** 单调递增，否则画面会在某处翻折。 */
    @Test
    public void theForwardMappingIsMonotonic() {
        float previous = -1f;
        for (float x = 0.5f; x <= 1.0001f; x += 0.05f) {
            float current = forward(x, 0.5f, 110f)[0];
            assertTrue("应当单调递增，x=" + x, current > previous);
            previous = current;
        }
    }

    @Test
    public void theForwardMappingIsSymmetricAboutTheCentre() {
        for (float offset = 0.1f; offset <= 0.5f; offset += 0.1f) {
            float left = forward(0.5f - offset, 0.5f, 110f)[0];
            float right = forward(0.5f + offset, 0.5f, 110f)[0];
            assertEquals(0.5f - left, right - 0.5f, TOLERANCE);
        }
    }

    /** 照片只算一次，网格该比预览密 —— 密度就是这条曲线被逼近得有多准。 */
    @Test
    public void thePhotoMeshIsDenserThanThePreviewMesh() {
        assertTrue(FisheyeProjection.PHOTO_MESH_DIVISIONS > FisheyeProjection.MESH_DIVISIONS);
    }
}
