package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 建管线建哪几路（{@link CameraManagerHolder.Shape}）：照启用的几路，环视一直建（超级镜子要它），座舱启用了才建。
 *
 * <p>2026-10-16 修的就是这里：以前数路数，只开一路座舱时只建了环视；只开两路座舱时最多建 2 路，前座舱被挤掉。</p>
 */
public class CameraManagerHolderShapeTest {

    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    private static CameraManagerHolder.Shape shape(String... enabled) {
        return new CameraManagerHolder.Shape("zeekr_7x", null, new LinkedHashSet<>(Arrays.asList(enabled)));
    }

    @Test
    public void oneCabinBuildsThatCabinAndTheSurround() {
        CameraManagerHolder.Shape rearOnly = shape(REAR);
        assertTrue(rearOnly.cabinRear);
        assertFalse(rearOnly.cabinFront);
        assertEquals(2, rearOnly.count);

        CameraManagerHolder.Shape frontOnly = shape(FRONT);
        assertTrue(frontOnly.cabinFront);
        assertFalse(frontOnly.cabinRear);
        assertEquals(2, frontOnly.count);
    }

    /** 两路座舱：三路都建，前座舱不再被「最多 2 路」挤掉。 */
    @Test
    public void twoCabinsBuildAllThree() {
        CameraManagerHolder.Shape cabins = shape(FRONT, REAR);
        assertTrue(cabins.cabinFront);
        assertTrue(cabins.cabinRear);
        assertEquals(3, cabins.count);
    }

    /** 环视启用的几种：和以前一样。 */
    @Test
    public void surroundProfilesBuildAsBefore() {
        assertEquals(1, shape(SUR).count);
        assertEquals(2, shape(SUR, FRONT).count);
        assertEquals(2, shape(SUR, REAR).count);
        assertEquals(3, shape(SUR, REAR, FRONT).count);
    }

    /** 一路都没启用：只建环视，给超级镜子。 */
    @Test
    public void nothingEnabledBuildsTheSurroundOnly() {
        CameraManagerHolder.Shape none = shape();
        assertFalse(none.cabinFront);
        assertFalse(none.cabinRear);
        assertEquals(1, none.count);
        assertEquals("zeekr_7x/-", none.key());
    }

    /** 主界面接上时启用的几路变了才重建：key 照开的次序写，给的先后不影响。 */
    @Test
    public void theKeyNamesTheEnabledLanesInOpenOrder() {
        assertEquals("zeekr_7x/front+left+back", shape(FRONT, SUR, REAR).key());
        assertEquals("zeekr_7x/left+back", shape(FRONT, REAR).key());
        assertEquals(shape(REAR, FRONT).key(), shape(FRONT, REAR).key());
    }
    @Test public void exteriorSourcesAreIncludedWithoutDroppingOverlaySurround() {
        CameraManagerHolder.Shape sources = shape("roadfront", "roadrear", "manual");
        assertEquals(4, sources.count); assertFalse(sources.cabinFront); assertFalse(sources.cabinRear);
        String previous = sources.key(); sources.sources.put("roadfront", "7");
        assertFalse(previous.equals(sources.key()));
    }
}
