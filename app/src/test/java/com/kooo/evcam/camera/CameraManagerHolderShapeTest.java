package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 建管线建哪几路（{@link CameraManagerHolder.Shape}）：照启用的几路，环视一直建（超级镜子要它），座舱启用了才建；
 * 超级镜子的切换范围是「后视 + 后座舱」时后座舱也建（2026-10-19）。
 *
 * <p>2026-10-16 修的就是这里：以前数路数，只开一路座舱时只建了环视；只开两路座舱时最多建 2 路，前座舱被挤掉。</p>
 */
public class CameraManagerHolderShapeTest {

    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    private static CameraManagerHolder.Shape shape(String... enabled) {
        return new CameraManagerHolder.Shape("zeekr_7x", null, new LinkedHashSet<>(Arrays.asList(enabled)), false);
    }

    /** 超级镜子要后座舱（切换范围是「后视 + 后座舱」）。 */
    private static CameraManagerHolder.Shape mirrorCabin(String... enabled) {
        return new CameraManagerHolder.Shape("zeekr_7x", null, new LinkedHashSet<>(Arrays.asList(enabled)), true);
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

    /** 超级镜子要后座舱、配置里没启用它：照样建，但不算启用的（主界面预览、拍照、录像都不要它）。 */
    @Test
    public void theMirrorBuildsTheRearCabinWithoutEnablingIt() {
        CameraManagerHolder.Shape surround = mirrorCabin(SUR);
        assertTrue(surround.cabinRear);
        assertFalse(surround.enabled.contains(REAR));
        assertEquals(2, surround.count);
        assertEquals(3, mirrorCabin(SUR, FRONT).count);
    }

    /** 配置里已经启用了后座舱：超级镜子要不要它都建这三路，不多建一路。 */
    @Test
    public void anEnabledRearCabinIsBuiltOnce() {
        assertEquals(shape(SUR, REAR).count, mirrorCabin(SUR, REAR).count);
        assertEquals(shape(SUR, REAR).key(), mirrorCabin(SUR, REAR).key());
    }

    /**
     * 只为超级镜子建的后座舱写进 key（变了谁来要都重建），不写进 lanesKey（启用的几路没变，主界面的布局不变）。
     */
    @Test
    public void theMirrorCabinChangesTheKeyButNotTheLanes() {
        assertEquals("zeekr_7x/front/mirror:left", mirrorCabin(SUR).key());
        assertEquals(shape(SUR).lanesKey(), mirrorCabin(SUR).lanesKey());
        assertFalse(shape(SUR).key().equals(mirrorCabin(SUR).key()));
    }

    /**
     * 照哪几路启用的建（2026-10-20 审查，车主批）：主界面接上时照它摆的格子（配置改了、主界面还没重建也一样），
     * 后台的调用方照手里那一份，还没有过管线时照此刻的配置。
     */
    @Test
    public void theMainScreenLayoutDecidesTheEnabledLanes() {
        java.util.Set<String> laidOut = new LinkedHashSet<>(Arrays.asList(SUR, FRONT));
        java.util.Set<String> built = new LinkedHashSet<>(Arrays.asList(SUR));
        java.util.Set<String> profile = new LinkedHashSet<>(Arrays.asList(SUR, REAR));
        assertEquals(laidOut, CameraManagerHolder.lanesFor(laidOut, built, profile));
        assertEquals(laidOut, CameraManagerHolder.lanesFor(laidOut, null, profile));
        assertEquals(built, CameraManagerHolder.lanesFor(null, built, profile));
        assertEquals(profile, CameraManagerHolder.lanesFor(null, null, profile));
    }

    /**
     * 为超级镜子当场重建只在「要后座舱而这一份里没有」时（谁来要都重建）；换出这一档、或者这一份里已经有后座舱，后台的调用方都不重建 ——
     * 设置里来回切，一份管线最多为它重建一次。只为它建、现在没人要的后座舱，和开的几路变了一样，等主界面接上时照此刻的设置重建。
     */
    @Test
    public void theMirrorRebuildsOnlyForAMissingRearCabin() {
        String surroundLanes = shape(SUR).lanesKey();
        assertEquals(CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), false, "zeekr_7x", surroundLanes, false));
        assertEquals(CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), true, "zeekr_7x", surroundLanes, false));
        assertEquals("这一份里已经有后座舱", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), false, "zeekr_7x", surroundLanes, true));
        assertEquals("换出这一档：多出来的后座舱不算", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(SUR), false, "zeekr_7x", surroundLanes, true));
        assertEquals("主界面接上时去掉没人要的后座舱", CameraManagerHolder.Rebuild.LANES,
                CameraManagerHolder.rebuildFor(shape(SUR), true, "zeekr_7x", surroundLanes, true));
        assertEquals("配置里启用了后座舱：不是只为超级镜子建的", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(SUR, REAR), true, "zeekr_7x", shape(SUR, REAR).lanesKey(), true));
        assertEquals("开的几路也变了：照样当场补后座舱", CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR, FRONT), false, "zeekr_7x", surroundLanes, false));
        assertEquals(CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR, FRONT), true, "zeekr_7x", surroundLanes, false));
        assertEquals("主界面接上时按开的几路", CameraManagerHolder.Rebuild.LANES,
                CameraManagerHolder.rebuildFor(shape(SUR, FRONT), true, "zeekr_7x", surroundLanes, false));
        assertEquals("只是开的几路变了：后台调用方不重建", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(SUR, FRONT), false, "zeekr_7x", surroundLanes, false));
        assertEquals(CameraManagerHolder.Rebuild.CAR_MODEL,
                CameraManagerHolder.rebuildFor(shape(SUR), false, "zeekr_7x_multi", surroundLanes, true));
    }
}
