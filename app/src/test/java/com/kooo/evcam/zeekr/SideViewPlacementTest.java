package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link SideViewPlacement}：左灯在左、右灯在右，大小固定，放得进屏幕。 */
public class SideViewPlacementTest {

    @Test
    public void leftOnTheLeftRightOnTheRightSameSize() {
        SideViewPlacement.Rect left = SideViewPlacement.compute(2560, 1440, LaneCycle.LEFT);
        SideViewPlacement.Rect right = SideViewPlacement.compute(2560, 1440, LaneCycle.RIGHT);
        assertEquals(518, left.height);
        assertEquals(691, left.width);
        assertEquals(left.width, right.width);
        assertEquals(left.height, right.height);
        assertEquals(77, left.left);
        assertEquals(2560 - 691 - 77, right.left);
        assertEquals("上下居中", (1440 - 518) / 2, left.top);
        assertEquals(left.top, right.top);
    }

    @Test
    public void staysOnANarrowScreen() {
        for (int lane : new int[]{LaneCycle.LEFT, LaneCycle.RIGHT}) {
            SideViewPlacement.Rect r = SideViewPlacement.compute(600, 1400, lane);
            assertTrue(r.width <= 600);
            assertTrue(r.left >= 0 && r.left + r.width <= 600);
            assertTrue(r.top >= 0 && r.top + r.height <= 1400);
        }
    }

    @Test
    public void degenerateScreenStillGivesAWindow() {
        SideViewPlacement.Rect r = SideViewPlacement.compute(0, 0, LaneCycle.RIGHT);
        assertTrue(r.width >= 1 && r.height >= 1);
    }
}
