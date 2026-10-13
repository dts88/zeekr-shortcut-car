package com.kooo.evcam.zeekr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SideViewRotationTest {
    @Test
    public void zeroRotationKeepsExistingFraming() {
        assertEquals(1f, SideViewRotation.coverScale(0, 500, 500), 0f);
        assertEquals(1f, SideViewRotation.coverScale(0, 1200, 400), 0f);
    }

    @Test
    public void rotatedImageCoversWindowCornersForSquareAndWideWindows() {
        for (int[] size : new int[][]{{500, 500}, {1200, 400}, {400, 1200}}) {
            for (int angle : new int[]{-90, -45, -15, 0, 15, 45, 90}) {
                double scale = SideViewRotation.coverScale(angle, size[0], size[1]);
                double c = Math.cos(Math.toRadians(angle));
                double s = Math.sin(Math.toRadians(angle));
                for (int xSign : new int[]{-1, 1}) {
                    for (int ySign : new int[]{-1, 1}) {
                        double x = xSign * size[0] / 2.0;
                        double y = ySign * size[1] / 2.0;
                        double sourceX = (c * x + s * y) / scale;
                        double sourceY = (-s * x + c * y) / scale;
                        assertTrue(Math.abs(sourceX) <= size[0] / 2.0 + 0.001);
                        assertTrue(Math.abs(sourceY) <= size[1] / 2.0 + 0.001);
                    }
                }
            }
        }
    }
}
