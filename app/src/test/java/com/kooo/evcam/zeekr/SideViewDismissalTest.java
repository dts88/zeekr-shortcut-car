package com.kooo.evcam.zeekr;

import org.junit.Test;
import static org.junit.Assert.*;

/** {@link SideViewDismissal}。来源：wysie 的 PR #19（GPL-3.0）。 */
public class SideViewDismissalTest {
    @Test
    public void repeatedUpdatesDoNotReopenTheDismissedActivation() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismiss(1);
        for (int i = 0; i < 20; i++) {
            assertTrue(dismissal.suppress(1, false));
        }
    }

    @Test
    public void turningOffThenSignallingAgainRearmsTheSameSide() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismiss(1);
        assertFalse(dismissal.suppress(0, false));
        assertFalse(dismissal.suppress(1, false));
    }

    @Test
    public void switchingSidesIsAFreshActivation() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismiss(1);
        assertFalse(dismissal.suppress(2, false));
    }

    @Test
    public void missingTelemetryCannotReopenADismissedView() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismiss(2);
        assertTrue(dismissal.suppress(null, null));
        assertTrue(dismissal.suppress(2, false));
        assertFalse(dismissal.suppress(0, false));
    }

    @Test
    public void hazardsEndTheDismissedActivationWithoutRequestingASide() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismiss(2);
        assertFalse(dismissal.suppress(2, true));
        assertFalse(dismissal.suppress(2, false));
    }

    @Test
    public void aDismissedLightStaysQuietWhileLitAndRearmsWhenOut() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismissLight(LaneCycle.LEFT);
        assertTrue(dismissal.suppressLight(LaneCycle.LEFT, true));
        assertFalse("另一侧不受影响", dismissal.suppressLight(LaneCycle.RIGHT, true));
        assertFalse("灭了清掉", dismissal.suppressLight(LaneCycle.LEFT, false));
        assertFalse("再亮是新的一次", dismissal.suppressLight(LaneCycle.LEFT, true));
    }

    /** 两侧都关掉了：各自压着、各自灭了才清（2026-11-04 起两侧可以同时显示）。 */
    @Test
    public void bothSidesCanBeDismissedOnTheirOwn() {
        SideViewDismissal dismissal = new SideViewDismissal();
        dismissal.dismissLight(LaneCycle.LEFT);
        dismissal.dismissLight(LaneCycle.RIGHT);
        assertTrue(dismissal.suppressLight(LaneCycle.LEFT, true));
        assertTrue("关右边不会让左边又弹出来", dismissal.suppressLight(LaneCycle.RIGHT, true));
        assertFalse(dismissal.suppressLight(LaneCycle.LEFT, false));
        assertTrue("左边灭了，右边照旧压着", dismissal.suppressLight(LaneCycle.RIGHT, true));
    }
}
