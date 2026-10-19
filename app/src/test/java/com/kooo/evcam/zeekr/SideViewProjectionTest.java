package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SideViewProjection}：转动虚拟相机后，画面中心落到鱼眼原图的哪里。
 *
 * <p>来源：FerixzTanz 的 PR #4、wysie 的 PR #19（GPL-3.0）。</p>
 */
public class SideViewProjectionTest {

    private static final float EPS = 1e-4f;

    /**
     * 逐点夹住的那个映射（PR #4 原来的画法）：视野夹在范围里，每个点夹在 0..1。
     * 画面现在走 {@link SideViewFraming}；这里留着它，测朝向，也给 {@code SideViewFramingTest} 对照。
     */
    static void clampedPoint(float u, float v, float fovDegrees, float backDegrees, float upDegrees,
                             int lane, float aspectRatio, float[] out, int offset) {
        float fov = Math.max(SideViewProjection.MIN_FOV_DEGREES,
                Math.min(SideViewProjection.MAX_FOV_DEGREES, fovDegrees));
        double t = Math.tan(Math.toRadians(fov) / 2.0);
        new SideViewProjection.Rays(backDegrees, upDegrees, lane).map(u, v, t, aspectRatio, out, offset);
        out[offset] = Math.max(0f, Math.min(1f, out[offset]));
        out[offset + 1] = Math.max(0f, Math.min(1f, out[offset + 1]));
    }

    private static float[] center(float back, float up, int lane) {
        float[] out = new float[2];
        clampedPoint(0.5f, 0.5f, 90f, back, up, lane, 1f, out, 0);
        return out;
    }

    @Test
    public void notTurnedLooksAtTheFisheyeCentre() {
        float[] c = center(0f, 0f, LaneCycle.LEFT);
        assertEquals(0.5f, c[0], EPS);
        assertEquals(0.5f, c[1], EPS);
    }

    @Test
    public void ninetyDegreesBackIsTheLaneEdge() {
        // 偏 90° = 原图半径 0.5；只转 80° 封顶，所以是 80/90 × 0.5
        float[] left = center(90f, 0f, LaneCycle.LEFT);
        float[] right = center(90f, 0f, LaneCycle.RIGHT);
        float expected = 80f / 90f * 0.5f;
        assertEquals("左侧车尾在左边", 0.5f - expected, left[0], EPS);
        assertEquals("右侧车尾在右边", 0.5f + expected, right[0], EPS);
        assertEquals(0.5f, left[1], EPS);
    }

    @Test
    public void fortyFiveBackIsAQuarterOfTheWay() {
        float[] c = center(45f, 0f, LaneCycle.RIGHT);
        assertEquals(0.75f, c[0], EPS);
    }

    @Test
    public void upMovesTowardTheTop() {
        float[] up = center(0f, 30f, LaneCycle.LEFT);
        float[] down = center(0f, -30f, LaneCycle.LEFT);
        assertEquals(0.5f - 30f / 90f * 0.5f, up[1], EPS);
        assertEquals(0.5f + 30f / 90f * 0.5f, down[1], EPS);
    }

    @Test
    public void wideViewReachesFurtherOut() {
        float[] narrow = new float[2];
        float[] wide = new float[2];
        clampedPoint(1f, 0.5f, 60f, 0f, 0f, LaneCycle.RIGHT, 1f, narrow, 0);
        clampedPoint(1f, 0.5f, 120f, 0f, 0f, LaneCycle.RIGHT, 1f, wide, 0);
        assertTrue(wide[0] > narrow[0]);
    }

    @Test
    public void wideWindowKeepsHorizontalFovAndCropsVertically() {
        float[] square = new float[2];
        float[] wide = new float[2];
        clampedPoint(0.5f, 1f, 90f, 0f, 0f, LaneCycle.LEFT, 1f, square, 0);
        clampedPoint(0.5f, 1f, 90f, 0f, 0f, LaneCycle.LEFT, 3f, wide, 0);
        assertTrue(wide[1] < square[1]);
        clampedPoint(1f, 0.5f, 90f, 0f, 0f, LaneCycle.LEFT, 1f, square, 0);
        clampedPoint(1f, 0.5f, 90f, 0f, 0f, LaneCycle.LEFT, 3f, wide, 0);
        assertEquals(square[0], wide[0], EPS);
        assertEquals(square[1], wide[1], EPS);
    }

    @Test
    public void invalidWindowAspectFallsBackToSquare() {
        float[] expected = new float[2];
        float[] actual = new float[2];
        clampedPoint(0.8f, 0.9f, 90f, 10f, 15f, LaneCycle.RIGHT, 1f, expected, 0);
        for (float aspect : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            clampedPoint(0.8f, 0.9f, 90f, 10f, 15f, LaneCycle.RIGHT, aspect, actual, 0);
            assertEquals(expected[0], actual[0], EPS);
            assertEquals(expected[1], actual[1], EPS);
        }
    }
}
