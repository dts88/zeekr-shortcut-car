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
}
