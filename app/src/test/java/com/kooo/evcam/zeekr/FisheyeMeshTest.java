package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FisheyeMesh} 算网格那一部分的单元测试。
 *
 * <p>超级后视镜、主界面预览、视频回看都靠这张网格。它算错的表现是「画面偏了一点、
 * 格子之间错开了」，在车上很难说清是哪一步的问题，所以钉在这里。</p>
 */
public class FisheyeMeshTest {

    private static final float TOLERANCE = 0.01f;

    /** 合成流第二路（后）在一块 1000×4000 的子视图里的位置：竖排四格里的第二格。 */
    private static final float LANE_LEFT = 0f;
    private static final float LANE_TOP = 1000f;
    private static final float LANE_WIDTH = 1000f;
    private static final float LANE_HEIGHT = 1000f;

    private static FisheyeMesh mesh(float fov, String projection, float strength) {
        FisheyeMesh mesh = new FisheyeMesh();
        mesh.setCorrection(fov, projection, strength);
        return mesh;
    }

    private static float[] expected(float u, float v, float fov, String projection, float strength) {
        float[] p = new float[2];
        FisheyeProjection.sourcePoint(u, v, fov, projection, strength, p, 0);
        return new float[]{LANE_LEFT + p[0] * LANE_WIDTH, LANE_TOP + p[1] * LANE_HEIGHT};
    }

    /** 每个角点就是那一点的反投影，再换到这一路在子视图里的位置 —— 和后视镜原来的逐角计算一致。 */
    @Test
    public void everyCornerIsTheProjectionOfThatPoint() {
        for (String projection : new String[]{FisheyeProjection.PROJECTION_RECTILINEAR,
                FisheyeProjection.PROJECTION_CYLINDRICAL, FisheyeProjection.PROJECTION_STEREOGRAPHIC}) {
            FisheyeMesh mesh = mesh(140f, projection, 0.8f);
            int n = 12;
            mesh.prepare(n, LANE_LEFT, LANE_TOP, LANE_WIDTH, LANE_HEIGHT, 0f, 0f, 1f, 1f);
            for (int row = 0; row <= n; row++) {
                for (int column = 0; column <= n; column++) {
                    float[] want = expected((float) column / n, (float) row / n, 140f, projection, 0.8f);
                    assertEquals(projection + " x @" + row + "," + column,
                            want[0], mesh.sourceX(row, column), TOLERANCE);
                    assertEquals(projection + " y @" + row + "," + column,
                            want[1], mesh.sourceY(row, column), TOLERANCE);
                }
            }
        }
    }

    /** 正中间那个角点落在这一路的正中间：光心假设在画面正中。 */
    @Test
    public void theMiddleCornerIsTheMiddleOfTheLane() {
        FisheyeMesh mesh = mesh(110f, FisheyeProjection.PROJECTION_RECTILINEAR, 1f);
        mesh.prepare(16, LANE_LEFT, LANE_TOP, LANE_WIDTH, LANE_HEIGHT, 0f, 0f, 1f, 1f);
        assertEquals(LANE_LEFT + LANE_WIDTH / 2f, mesh.sourceX(8, 8), TOLERANCE);
        assertEquals(LANE_TOP + LANE_HEIGHT / 2f, mesh.sourceY(8, 8), TOLERANCE);
    }

    /**
     * 合成流四路上下紧挨着：网格上任何一点越出这一路，取到的就是隔壁摄像头。
     * 取景窗故意伸到这一路外面去也一样。
     */
    @Test
    public void noCornerLeavesTheLane() {
        for (String projection : new String[]{FisheyeProjection.PROJECTION_RECTILINEAR,
                FisheyeProjection.PROJECTION_CYLINDRICAL, FisheyeProjection.PROJECTION_STEREOGRAPHIC}) {
            FisheyeMesh mesh = mesh(180f, projection, 1f);
            int n = FisheyeMesh.MAX_DIVISIONS;
            mesh.prepare(n, LANE_LEFT, LANE_TOP, LANE_WIDTH, LANE_HEIGHT, -0.3f, -0.3f, 1.6f, 1.6f);
            for (int row = 0; row <= n; row++) {
                for (int column = 0; column <= n; column++) {
                    float x = mesh.sourceX(row, column);
                    float y = mesh.sourceY(row, column);
                    assertTrue(projection + " x=" + x, x >= LANE_LEFT && x <= LANE_LEFT + LANE_WIDTH);
                    assertTrue(projection + " y=" + y, y >= LANE_TOP && y <= LANE_TOP + LANE_HEIGHT);
                }
            }
        }
    }

    /** 取景窗是在校正之后的画面里取的：窗口的左上角就是校正后画面里那一点。 */
    @Test
    public void theWindowIsTakenFromTheCorrectedPicture() {
        FisheyeMesh mesh = mesh(120f, FisheyeProjection.PROJECTION_RECTILINEAR, 1f);
        mesh.prepare(10, LANE_LEFT, LANE_TOP, LANE_WIDTH, LANE_HEIGHT, 0.25f, 0.3f, 0.5f, 0.4f);
        float[] topLeft = expected(0.25f, 0.3f, 120f, FisheyeProjection.PROJECTION_RECTILINEAR, 1f);
        float[] bottomRight = expected(0.75f, 0.7f, 120f, FisheyeProjection.PROJECTION_RECTILINEAR, 1f);
        assertEquals(topLeft[0], mesh.sourceX(0, 0), TOLERANCE);
        assertEquals(topLeft[1], mesh.sourceY(0, 0), TOLERANCE);
        assertEquals(bottomRight[0], mesh.sourceX(10, 10), TOLERANCE);
        assertEquals(bottomRight[1], mesh.sourceY(10, 10), TOLERANCE);
    }

    /** 视野按投影夹：直线投影上限 140°，另外两种 180° —— 界面显示多少，生效的就是多少。 */
    @Test
    public void theFieldOfViewIsClampedPerProjection() {
        assertEquals(FisheyeProjection.MAX_FOV_DEGREES,
                mesh(170f, FisheyeProjection.PROJECTION_RECTILINEAR, 1f).fovDegrees(), 0.001f);
        assertEquals(170f, mesh(170f, FisheyeProjection.PROJECTION_CYLINDRICAL, 1f).fovDegrees(), 0.001f);
        assertEquals(FisheyeProjection.MIN_FOV_DEGREES,
                mesh(10f, FisheyeProjection.PROJECTION_STEREOGRAPHIC, 1f).fovDegrees(), 0.001f);
        assertEquals(FisheyeProjection.PROJECTION_RECTILINEAR, mesh(110f, null, 1f).projection());
        assertEquals(1f, mesh(110f, null, 3f).strength(), 0.001f);
        assertEquals(0f, mesh(110f, null, -1f).strength(), 0.001f);
    }

    /** 格数跟着屏幕上的大小走，夹在上下限之间：四宫格每路约 800px 是 16 格。 */
    @Test
    public void divisionsFollowTheSizeOnScreen() {
        assertEquals(FisheyeMesh.MIN_DIVISIONS, FisheyeMesh.divisionsFor(0f));
        assertEquals(FisheyeMesh.MIN_DIVISIONS, FisheyeMesh.divisionsFor(200f));
        assertEquals(16, FisheyeMesh.divisionsFor(800f));
        assertEquals(FisheyeMesh.MAX_DIVISIONS, FisheyeMesh.divisionsFor(1600f));
        assertEquals(FisheyeMesh.MAX_DIVISIONS, FisheyeMesh.divisionsFor(100000f));
        assertTrue(FisheyeMesh.MIN_DIVISIONS > 0);
    }
}
