package com.kooo.evcam.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link MotionPolicy} 的判断规则。 */
public class MotionPolicyTest {

    @Test
    public void idleAlwaysAnimatesWhenSystemAllows() {
        assertTrue(MotionPolicy.allows(false, true, 1f));
        assertTrue(MotionPolicy.allows(false, false, 1f));
    }

    @Test
    public void recordingSuppressesOnlyWhenTheSettingIsOn() {
        assertFalse(MotionPolicy.allows(true, true, 1f));
        assertTrue(MotionPolicy.allows(true, false, 1f));
    }

    @Test
    public void systemAnimationsOffWinsOverEverything() {
        // 用户在系统里关了动画，这个应用不能自作主张再开
        assertFalse(MotionPolicy.allows(false, false, 0f));
        assertFalse(MotionPolicy.allows(true, false, 0f));
    }
}
