package com.kooo.evcam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileSizes;

import org.junit.Test;

import java.util.Arrays;

/**
 * 前座舱只用一次（暂时的规矩，项目所有者 2026-10-22 定、10-23 改成只管前座舱，10-30 开关照实显示）：
 * 离车、或者下一次启动时那份配置怎么改（{@link FrontCabinOneShot}）。
 */
public class FrontCabinOneShotTest {

    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;

    /** 规矩管的是配置里前座舱那一路的角色（槽位和角色的对照在 ProfileSizes）。 */
    @Test
    public void theRuleIsAboutTheFrontCabinRole() {
        assertTrue(ProfileSizes.roleForCameraKey(FRONT).equals(FrontCabinOneShot.ROLE));
        assertFalse(ProfileSizes.roleForCameraKey(REAR).equals(FrontCabinOneShot.ROLE));
    }

    /** 只关前座舱的开关：环视、后座舱、别的参数都不动；已经关着的不算改。 */
    @Test
    public void frontCabinOffTouchesOnlyTheFrontCabinSwitch() {
        Profile profile = new Profile();
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.enabled = true;
        CameraProfile rear = new CameraProfile(ProfileSizes.roleForCameraKey(REAR));
        rear.enabled = true;
        CameraProfile front = new CameraProfile(FrontCabinOneShot.ROLE);
        front.enabled = true;
        profile.cameras.addAll(Arrays.asList(composite, rear, front));

        assertTrue(FrontCabinOneShot.frontCabinOff(profile));
        assertFalse(front.enabled);
        assertTrue("环视不动", composite.enabled);
        assertTrue("后座舱不动", rear.enabled);
        assertFalse("再来一次：没有可改的", FrontCabinOneShot.frontCabinOff(profile));
    }

    /** 只开了前座舱的那份：关掉之后就一路都不开（只关这一个开关，不替用户开环视）。 */
    @Test
    public void aFrontCabinOnlyProfileEndsUpWithNothingOn() {
        Profile profile = new Profile();
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.enabled = false;
        CameraProfile front = new CameraProfile(FrontCabinOneShot.ROLE);
        front.enabled = true;
        profile.cameras.addAll(Arrays.asList(composite, front));

        assertTrue(FrontCabinOneShot.frontCabinOff(profile));
        assertFalse(composite.enabled);
        assertFalse(front.enabled);
    }

    /** 配置里没有前座舱那一路：没有可改的。 */
    @Test
    public void aProfileWithoutTheFrontCabinIsLeftAlone() {
        Profile profile = new Profile();
        CameraProfile composite = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        composite.enabled = true;
        profile.cameras.add(composite);

        assertFalse(FrontCabinOneShot.frontCabinOff(profile));
        assertTrue(composite.enabled);
    }
}
