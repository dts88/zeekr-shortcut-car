package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 前后座舱只用一次（暂时的规矩，项目所有者 2026-10-22）：下次启动的配置怎么改（{@link CabinsOneShot}）。 */
public class CabinsOneShotTest {

    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    /** 没改过的「环视 + 前后座舱」：选择改回「极氪7X（环视）」；改过的那份：关开关。 */
    @Test
    public void anUntouchedMultiPresetRevertsTheSelectionAnEditedOneTurnsTheCabinsOff() {
        assertEquals(CabinsOneShot.Restore.SELECT_SURROUND, CabinsOneShot.restoreFor(
                Profile.PRESET_COMPOSITE_MULTI, Arrays.asList(SUR, REAR, FRONT), true));
        assertEquals(CabinsOneShot.Restore.TURN_OFF, CabinsOneShot.restoreFor(
                Profile.PRESET_COMPOSITE_MULTI, Arrays.asList(SUR, REAR, FRONT), false));
    }

    /** 在「环视」那份里手动开了座舱：关开关（不管改没改过）。 */
    @Test
    public void cabinsTurnedOnInTheSurroundPresetAreTurnedOff() {
        assertEquals(CabinsOneShot.Restore.TURN_OFF, CabinsOneShot.restoreFor(
                Profile.PRESET_COMPOSITE, Arrays.asList(SUR, FRONT), true));
        assertEquals(CabinsOneShot.Restore.TURN_OFF, CabinsOneShot.restoreFor(
                "custom", Collections.singletonList(REAR), false));
    }

    /** 这次运行没开座舱：什么都不改。 */
    @Test
    public void aSurroundOnlyRunChangesNothing() {
        assertEquals(CabinsOneShot.Restore.NONE, CabinsOneShot.restoreFor(
                Profile.PRESET_COMPOSITE_MULTI, Collections.singletonList(SUR), true));
        assertEquals(CabinsOneShot.Restore.NONE, CabinsOneShot.restoreFor(
                Profile.PRESET_COMPOSITE, Collections.emptyList(), true));
        assertFalse(CabinsOneShot.hasCabin(null));
        assertTrue(CabinsOneShot.hasCabin(Arrays.asList(SUR, REAR)));
    }

    /** 改成只有环视：座舱关掉、环视开着（只开座舱的那种也恢复到只有环视）；已经是只有环视的不算改。 */
    @Test
    public void surroundOnlyLeavesTheSurroundOnAndTheCabinsOff() {
        Profile profile = new Profile();
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.enabled = false;
        CameraProfile cabin1 = new CameraProfile(CameraProfile.ROLE_CABIN_1);
        cabin1.enabled = true;
        CameraProfile cabin2 = new CameraProfile(CameraProfile.ROLE_CABIN_2);
        cabin2.enabled = true;
        profile.cameras.addAll(Arrays.asList(composite, cabin1, cabin2));
        assertTrue(CabinsOneShot.surroundOnly(profile));
        assertTrue(composite.enabled);
        assertFalse(cabin1.enabled);
        assertFalse(cabin2.enabled);
        assertFalse("再来一次：没有可改的", CabinsOneShot.surroundOnly(profile));
    }
}
