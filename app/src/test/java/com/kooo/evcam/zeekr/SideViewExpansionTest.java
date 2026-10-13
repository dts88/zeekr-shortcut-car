package com.kooo.evcam.zeekr;

import org.junit.Test;
import static org.junit.Assert.*;

public class SideViewExpansionTest {
    @Test
    public void expandFitsTheScreenAndRestoresExactGeometry() {
        SideViewExpansion expansion = new SideViewExpansion();
        SideViewExpansion.Geometry normal = new SideViewExpansion.Geometry(1400, 200, 400, 400);
        SideViewExpansion.Geometry large = expansion.toggle(normal, 1920, 1080, 8);
        assertTrue(expansion.expanded());
        assertEquals(1064, large.width);
        assertEquals(1064, large.height);
        assertTrue(large.x >= 8 && large.x + large.width <= 1912);
        assertTrue(large.y >= 8 && large.y + large.height <= 1072);
        assertSame(normal, expansion.toggle(large, 1920, 1080, 8));
        assertFalse(expansion.expanded());
    }

    @Test
    public void closingAnExpandedWideMirrorRestoresItsNormalSizeAndPosition() {
        SideViewExpansion expansion = new SideViewExpansion();
        SideViewExpansion.Geometry normal = new SideViewExpansion.Geometry(10, 20, 600, 200);
        SideViewExpansion.Geometry large = expansion.toggle(normal, 1920, 1080, 8);
        assertEquals(3f, (float) large.width / large.height, 0.01f);
        assertSame(normal, expansion.restore());
        assertFalse(expansion.expanded());
        assertNull(expansion.restore());
    }
}
