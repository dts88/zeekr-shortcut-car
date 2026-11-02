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
        assertEquals(475, left.height);
        assertEquals("宽 ÷ 高 = 2.2", 1045, left.width);
        assertEquals(left.width, right.width);
        assertEquals(left.height, right.height);
        assertEquals(77, left.left);
        assertEquals(2560 - 1045 - 77, right.left);
        assertEquals("上下居中", (1440 - 475) / 2, left.top);
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

    // ------------------------------------------------------------------ 设置里存的位置和大小（2026-11-03）

    @Test
    public void aSavedSpecMirrorsAndRoundTrips() {
        SideViewPlacement.Spec spec = new SideViewPlacement.Spec(250, 100, 200);
        SideViewPlacement.Rect left = SideViewPlacement.compute(2560, 1440, LaneCycle.LEFT, spec);
        SideViewPlacement.Rect right = SideViewPlacement.compute(2560, 1440, LaneCycle.RIGHT, spec);
        assertEquals(360, left.height);
        assertEquals("宽 ÷ 高 = 2.2", 792, left.width);
        assertEquals(256, left.left);
        assertEquals(288, left.top);
        assertEquals("一样大", left.width, right.width);
        assertEquals("一样高", left.top, right.top);
        assertEquals("以中线为轴左右相反", 2560 - 256 - 792, right.left);

        SideViewPlacement.Spec saved = SideViewPlacement.toSpec(2560, 1440, left);
        SideViewPlacement.Rect again = SideViewPlacement.compute(2560, 1440, LaneCycle.LEFT, saved);
        assertEquals(left.left, again.left);
        assertEquals(left.top, again.top);
        assertEquals(left.width, again.width);
        assertEquals(left.height, again.height);
    }

    @Test
    public void theLeftOneNeverCrossesTheCenterLine() {
        SideViewPlacement.Spec spec = new SideViewPlacement.Spec(330, 900, -1);
        SideViewPlacement.Rect left = SideViewPlacement.compute(2560, 1440, LaneCycle.LEFT, spec);
        SideViewPlacement.Rect right = SideViewPlacement.compute(2560, 1440, LaneCycle.RIGHT, spec);
        assertEquals("最多叠在正中", (2560 - left.width) / 2, left.left);
        assertTrue("和右边那个叠在一起（差一个像素是取整）", Math.abs(left.left - right.left) <= 1);
    }

    @Test
    public void tooSmallIsRaisedToTheMinimum() {
        SideViewPlacement.Rect left = SideViewPlacement.compute(2560, 1440, LaneCycle.LEFT,
                new SideViewPlacement.Spec(10, 0, 0));
        assertEquals(Math.round(1440 * SideViewPlacement.MIN_HEIGHT_PERMILLE / 1000f), left.height);
    }

    @Test
    public void theDefaultIsTheOldFixedPlace() {
        assertTrue(SideViewPlacement.Spec.DEFAULT.isDefault());
        SideViewPlacement.Rect a = SideViewPlacement.compute(1920, 720, LaneCycle.RIGHT);
        SideViewPlacement.Rect b = SideViewPlacement.compute(1920, 720, LaneCycle.RIGHT, SideViewPlacement.Spec.DEFAULT);
        assertEquals(a.left, b.left);
        assertEquals(a.top, b.top);
        assertEquals(a.width, b.width);
    }
}
