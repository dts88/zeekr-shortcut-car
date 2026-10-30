package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;
import com.kooo.evcam.profile.Profile;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * 建管线建哪几路（{@link CameraManagerHolder.Shape}）：照启用的几路，环视一直建（超级镜子要它），座舱启用了才建；
 * 超级镜子的切换范围是「后视 + 后座舱」时后座舱也建（2026-10-19）。配置改动回到主界面时生效（2026-10-30）：
 * 主界面接上时建管线、录像用到的变了才换管线，只是名字、各格的摆法变了不换。
 *
 * <p>2026-10-16 修的就是这里：以前数路数，只开一路座舱时只建了环视；只开两路座舱时最多建 2 路，前座舱被挤掉。</p>
 */
public class CameraManagerHolderShapeTest {

    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    private static CameraManagerHolder.Shape shape(String... enabled) {
        return shape(null, false, enabled);
    }

    /** 超级镜子要后座舱（切换范围是「后视 + 后座舱」）。 */
    private static CameraManagerHolder.Shape mirrorCabin(String... enabled) {
        return shape(null, true, enabled);
    }

    private static CameraManagerHolder.Shape shape(Profile profile, boolean mirrorCabin, String... enabled) {
        return new CameraManagerHolder.Shape("zeekr_7x", profile, new LinkedHashSet<>(Arrays.asList(enabled)),
                mirrorCabin, null, null, null);
    }

    /** 一份三路都在、环视开着的配置；环视那一格一个摆位。 */
    private static Profile profile() {
        Profile profile = new Profile();
        profile.id = Profile.PRESET_COMPOSITE_MULTI;
        profile.name = "三路";
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.lanes.add(new LaneLayout());
        CameraProfile cabin1 = new CameraProfile(CameraProfile.ROLE_CABIN_1);
        cabin1.enabled = false;
        CameraProfile cabin2 = new CameraProfile(CameraProfile.ROLE_CABIN_2);
        cabin2.enabled = false;
        profile.cameras.addAll(Arrays.asList(composite, cabin1, cabin2));
        return profile;
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

    /** key 照开的次序写，给的先后不影响。 */
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
     * 只为超级镜子建的后座舱写进 key（建管线用到的），不写进 lanesKey、configKey（启用的几路没变，主界面的布局不变）。
     */
    @Test
    public void theMirrorCabinChangesTheKeyButNotTheLanes() {
        assertEquals("zeekr_7x/front/mirror:left", mirrorCabin(SUR).key());
        assertEquals(shape(SUR).lanesKey(), mirrorCabin(SUR).lanesKey());
        assertEquals(shape(SUR).configKey(), mirrorCabin(SUR).configKey());
        assertFalse(shape(SUR).key().equals(mirrorCabin(SUR).key()));
        assertFalse(shape(SUR).pipelineKey().equals(mirrorCabin(SUR).pipelineKey()));
    }

    /**
     * 为超级镜子当场重建只在「要后座舱而这一份里没有」时（谁来要都重建）；换出这一档、或者这一份里已经有后座舱，后台的调用方都不重建 ——
     * 设置里来回切，一份管线最多为它重建一次。只为它建、现在没人要的后座舱，和配置变了一样，等主界面接上时去掉。
     */
    @Test
    public void theMirrorRebuildsOnlyForAMissingRearCabin() {
        CameraManagerHolder.Shape surround = shape(SUR);
        CameraManagerHolder.Shape withMirror = mirrorCabin(SUR);
        assertEquals(CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), surround, false));
        assertEquals("主界面接上时：建的变了，一样当场重建", CameraManagerHolder.Rebuild.CONFIG,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), surround, true));
        assertEquals("这一份里已经有后座舱", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR), withMirror, false));
        assertEquals("换出这一档：多出来的后座舱不算", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(SUR), withMirror, false));
        assertEquals("主界面接上时去掉没人要的后座舱", CameraManagerHolder.Rebuild.CONFIG,
                CameraManagerHolder.rebuildFor(shape(SUR), withMirror, true));
        assertEquals("配置里启用了后座舱：不是只为超级镜子建的", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(SUR, REAR), mirrorCabin(SUR, REAR), true));
        assertEquals("后台的调用方：开的几路照生效的，照样当场补后座舱", CameraManagerHolder.Rebuild.MIRROR_CABIN,
                CameraManagerHolder.rebuildFor(mirrorCabin(SUR, FRONT), shape(SUR, FRONT), false));
    }

    /**
     * 配置变了（开的几路、车型、选的哪份配置、各路参数、映射）只在主界面接上时换管线（回到主界面时生效，2026-10-30）；
     * 后台的调用方照用生效的那一份。
     */
    @Test
    public void configChangesRebuildOnlyWhenTheMainScreenAttaches() {
        CameraManagerHolder.Shape effective = shape(profile(), false, SUR);

        CameraManagerHolder.Shape lanes = shape(profile(), false, SUR, FRONT);
        assertEquals(CameraManagerHolder.Rebuild.CONFIG, CameraManagerHolder.rebuildFor(lanes, effective, true));
        assertEquals(CameraManagerHolder.Rebuild.NO, CameraManagerHolder.rebuildFor(lanes, effective, false));

        CameraManagerHolder.Shape carModel = new CameraManagerHolder.Shape("zeekr_7x_multi", profile(),
                new LinkedHashSet<>(Arrays.asList(SUR)), false, null, null, null);
        assertEquals("换了车型", CameraManagerHolder.Rebuild.CONFIG, CameraManagerHolder.rebuildFor(carModel, effective, true));
        assertEquals("后台的调用方不因为换了车型重建", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(carModel, effective, false));

        Profile bitrate = profile();
        bitrate.camera(CameraProfile.ROLE_COMPOSITE).record.bitrate = "high";
        assertEquals("各路参数", CameraManagerHolder.Rebuild.CONFIG,
                CameraManagerHolder.rebuildFor(shape(bitrate, false, SUR), effective, true));

        Profile other = profile();
        other.id = Profile.PRESET_COMPOSITE;
        assertEquals("选了另一份配置", CameraManagerHolder.Rebuild.CONFIG,
                CameraManagerHolder.rebuildFor(shape(other, false, SUR), effective, true));

        CameraManagerHolder.Shape mapping = new CameraManagerHolder.Shape("zeekr_7x", profile(),
                new LinkedHashSet<>(Arrays.asList(SUR)), false, "2", null, null);
        assertEquals("摄像头映射", CameraManagerHolder.Rebuild.CONFIG,
                CameraManagerHolder.rebuildFor(mapping, effective, true));
        assertEquals(CameraManagerHolder.Rebuild.NO, CameraManagerHolder.rebuildFor(mapping, effective, false));

        assertEquals("一样的配置不重建", CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(profile(), false, SUR), effective, true));
    }

    /** 只是名字、各格的摆法变了：管线不换（pipelineKey 一样），主界面照新的重新摆（configKey 不一样）。 */
    @Test
    public void nameAndCellChangesKeepThePipeline() {
        CameraManagerHolder.Shape effective = shape(profile(), false, SUR);

        Profile rotated = profile();
        rotated.camera(CameraProfile.ROLE_COMPOSITE).lanes.get(0).rotation = 90;
        CameraManagerHolder.Shape cells = shape(rotated, false, SUR);
        assertEquals(effective.pipelineKey(), cells.pipelineKey());
        assertFalse(effective.configKey().equals(cells.configKey()));
        assertEquals(CameraManagerHolder.Rebuild.NO, CameraManagerHolder.rebuildFor(cells, effective, true));

        Profile renamed = profile();
        renamed.name = "改了名字";
        CameraManagerHolder.Shape name = shape(renamed, false, SUR);
        assertEquals(effective.pipelineKey(), name.pipelineKey());
        assertFalse(effective.configKey().equals(name.configKey()));
    }

    /** 离车去掉前座舱：启用的几路拿掉它、配置换成关掉前座舱的那份 —— 和存下的（开关已经关掉）对得上，主界面接上时不重建。 */
    @Test
    public void retiringTheFrontCabinMatchesTheStoredConfig() {
        Profile inUse = profile();
        inUse.camera(CameraProfile.ROLE_CABIN_1).enabled = true;
        CameraManagerHolder.Shape effective = shape(inUse, false, SUR, FRONT);

        Profile stored = profile();   // 前座舱的开关已经关掉
        CameraManagerHolder.Shape retired = effective.withoutFrontCabin(FRONT, stored);
        assertFalse(retired.cabinFront);
        assertEquals(shape(stored, false, SUR).pipelineKey(), retired.pipelineKey());
        assertEquals(shape(stored, false, SUR).configKey(), retired.configKey());
        assertEquals(CameraManagerHolder.Rebuild.NO,
                CameraManagerHolder.rebuildFor(shape(stored, false, SUR), retired, true));
    }
}
