package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SideViewAim}：放大、往后、往上，左右两路对称。 */
public class SideViewAimTest {

    private static final float EPS = 1e-4f;

    @Test
    public void noZoomShowsTheWholeLane() {
        RearViewGeometry.Viewport v = SideViewAim.viewport(LaneCycle.LEFT, 100, 100, 100);
        assertEquals(0f, v.x, EPS);
        assertEquals(0f, v.y, EPS);
        assertEquals(1f, v.width, EPS);
        assertEquals(1f, v.height, EPS);
    }

    @Test
    public void centredWhenNotAimed() {
        RearViewGeometry.Viewport v = SideViewAim.viewport(LaneCycle.LEFT, 200, 0, 0);
        assertEquals(0.5f, v.width, EPS);
        assertEquals(0.25f, v.x, EPS);
        assertEquals(0.25f, v.y, EPS);
    }

    @Test
    public void fullyBackGoesToOppositeEdgesForLeftAndRight() {
        RearViewGeometry.Viewport left = SideViewAim.viewport(LaneCycle.LEFT, 200, 100, 0);
        RearViewGeometry.Viewport right = SideViewAim.viewport(LaneCycle.RIGHT, 200, 100, 0);
        assertEquals("左侧车尾在左边", 0f, left.x, EPS);
        assertEquals("右侧车尾在右边", 0.5f, right.x, EPS);
    }

    @Test
    public void upMovesTowardTheTop() {
        RearViewGeometry.Viewport up = SideViewAim.viewport(LaneCycle.LEFT, 200, 0, 100);
        RearViewGeometry.Viewport down = SideViewAim.viewport(LaneCycle.LEFT, 200, 0, -100);
        assertEquals(0f, up.y, EPS);
        assertEquals(0.5f, down.y, EPS);
    }

    @Test
    public void staysInsideTheLaneEvenOutOfRange() {
        RearViewGeometry.Viewport v = SideViewAim.viewport(LaneCycle.RIGHT, 160, 500, -500);
        assertTrue(v.x >= -EPS && v.x + v.width <= 1f + EPS);
        assertTrue(v.y >= -EPS && v.y + v.height <= 1f + EPS);
    }
}
