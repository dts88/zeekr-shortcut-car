package com.kooo.evcam.zeekr;

import static org.junit.Assert.*;

import org.junit.Test;

/** {@link SideViewFraming}。来源：wysie 的 PR #19（GPL-3.0）。 */
public class SideViewFramingTest {
    private static SideViewFraming frame(float fov, float back, float up, int lane, float aspect) {
        SideViewFraming frame = new SideViewFraming();
        frame.configure(fov, back, up, lane, aspect);
        return frame;
    }

    @Test
    public void eightyDegreesBackNoLongerCollapsesTilesOntoTheImageEdge() {
        // 逐点夹住时，视野 90° / 往后 80° / 往上 15° 有 19 格（共 100 格）挤成了线
        float[][] legacy = new float[121][2];
        for (int r = 0; r <= 10; r++) {
            for (int c = 0; c <= 10; c++) {
                SideViewProjectionTest.clampedPoint(c / 10f, r / 10f, 90, 80, 15,
                        LaneCycle.LEFT, 1f, legacy[r * 11 + c], 0);
            }
        }
        int collapsed = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 10; c++) {
                if (area(legacy[r * 11 + c], legacy[r * 11 + c + 1],
                        legacy[(r + 1) * 11 + c + 1]) == 0.0
                        && area(legacy[r * 11 + c], legacy[(r + 1) * 11 + c + 1],
                        legacy[(r + 1) * 11 + c]) == 0.0) {
                    collapsed++;
                }
            }
        }
        assertEquals(19, collapsed);
        SideViewFraming frame = frame(90, 80, 15, LaneCycle.LEFT, 1);
        assertTrue(frame.effectiveFovDegrees() > 20);
        assertTrue(frame.effectiveFovDegrees() < 21);
        assertValidGrid(frame, 10);
    }

    @Test
    public void preservesRequestedViewWhenItAlreadyFits() {
        SideViewFraming frame = frame(90, 0, 0, LaneCycle.LEFT, 1);
        assertEquals(90, frame.effectiveFovDegrees(), 0.0001);
        float[] old = new float[2];
        float[] now = new float[2];
        for (int r = 0; r <= 10; r++) {
            for (int c = 0; c <= 10; c++) {
                SideViewProjectionTest.clampedPoint(c / 10f, r / 10f, 90, 0, 0, LaneCycle.LEFT, 1f, old, 0);
                frame.sourcePoint(c / 10f, r / 10f, now);
                assertArrayEquals(old, now, 0.000001f);
            }
        }
    }

    @Test
    public void fittingPreservesAimRatherThanMovingTheCameraCentre() {
        for (int lane : new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}) {
            for (float back : new float[]{-80, -50, 0, 50, 80}) {
                float[] expected = new float[2];
                float[] actual = new float[2];
                SideViewProjectionTest.clampedPoint(0.5f, 0.5f, 90, back, 15, lane, 1f, expected, 0);
                frame(90, back, 15, lane, 1).sourcePoint(0.5f, 0.5f, actual);
                assertArrayEquals(expected, actual, 0.000001f);
            }
        }
    }

    @Test
    public void bothSidesAndWindowShapesStayInBoundsWithoutDegenerateOrFoldedTiles() {
        for (int lane : new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}) {
            for (float fov : new float[]{60, 90, 130}) {
                for (float back : new float[]{-80, -50, 0, 50, 80}) {
                    for (float up : new float[]{-60, -15, 0, 15, 60}) {
                        for (float aspect : new float[]{0.2f, 1, 4f / 3f, 3, 5}) {
                            SideViewFraming frame = frame(fov, back, up, lane, aspect);
                            assertTrue(frame.effectiveFovDegrees() > 0);
                            assertTrue(frame.effectiveFovDegrees() <= fov + 0.001);
                            assertValidGrid(frame, 24);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void leftAndRightFramingAreSymmetric() {
        SideViewFraming left = frame(90, 80, 15, LaneCycle.LEFT, 1);
        SideViewFraming right = frame(90, 80, 15, LaneCycle.RIGHT, 1);
        assertEquals(left.effectiveFovDegrees(), right.effectiveFovDegrees(), 0.0001);
        float[] l = new float[2], r = new float[2];
        for (int row = 0; row <= 10; row++) {
            for (int col = 0; col <= 10; col++) {
                left.sourcePoint(col / 10f, row / 10f, l);
                right.sourcePoint(1f - col / 10f, row / 10f, r);
                assertEquals(1f, l[0] + r[0], 0.000001f);
                assertEquals(l[1], r[1], 0.000001f);
            }
        }
    }

    @Test
    public void cachedFramingRecomputesAfterConfigurationOrWindowShapeChanges() {
        SideViewFraming frame = frame(90, 80, 15, LaneCycle.LEFT, 1);
        assertFalse(frame.configure(90, 80, 15, LaneCycle.LEFT, 1));
        assertTrue(frame.configure(90, 50, 15, LaneCycle.LEFT, 1));
        assertTrue(frame.effectiveFovDegrees() > 80);
        assertTrue(frame.configure(90, 50, 15, LaneCycle.LEFT, 3));
        assertTrue(frame.configure(90, 50, 15, LaneCycle.RIGHT, 3));
        assertTrue(frame.configure(60, 50, 15, LaneCycle.RIGHT, 3));
        assertTrue(frame.configure(60, 50, 0, LaneCycle.RIGHT, 3));
    }

    @Test
    public void invalidInputsUseFiniteDefaults() {
        for (float aspect : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            SideViewFraming frame = frame(Float.NaN, Float.NaN, Float.NaN, LaneCycle.LEFT, aspect);
            assertEquals(60, frame.effectiveFovDegrees(), 0.001);
            assertValidGrid(frame, 10);
        }
    }

    private static void assertValidGrid(SideViewFraming frame, int n) {
        float[][] points = new float[(n + 1) * (n + 1)][2];
        for (int r = 0; r <= n; r++) {
            for (int c = 0; c <= n; c++) {
                float[] p = points[r * (n + 1) + c];
                frame.sourcePoint(c / (float) n, r / (float) n, p);
                assertTrue("x=" + p[0], Float.isFinite(p[0]) && p[0] > 0 && p[0] < 1);
                assertTrue("y=" + p[1], Float.isFinite(p[1]) && p[1] > 0 && p[1] < 1);
            }
        }
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                float[] a = points[r * (n + 1) + c];
                float[] b = points[r * (n + 1) + c + 1];
                float[] d = points[(r + 1) * (n + 1) + c];
                float[] e = points[(r + 1) * (n + 1) + c + 1];
                // 四个三角形面积都为正：没有挤成一点的角、没有折过去的格，四点变换不会退化，转到最大角度也一样
                assertTrue(area(a, b, e) > 1e-10);
                assertTrue(area(b, e, d) > 1e-10);
                assertTrue(area(e, d, a) > 1e-10);
                assertTrue(area(d, a, b) > 1e-10);
            }
        }
    }

    private static double area(float[] a, float[] b, float[] c) {
        return (double) (b[0] - a[0]) * (c[1] - a[1])
                - (double) (b[1] - a[1]) * (c[0] - a[0]);
    }
}
