package com.kooo.evcam.zeekr;

import static org.junit.Assert.*;

import org.junit.Test;

public class MirrorSideViewTest {
    @Test
    public void cancellingTheIndicatorReturnsToTheOriginalCamera() {
        MirrorSideView view = new MirrorSideView();
        assertEquals(LaneCycle.REAR, view.displayLane(LaneCycle.REAR));
        view.show(LaneCycle.LEFT, 400, 50, false);
        assertEquals(LaneCycle.LEFT, view.displayLane(LaneCycle.REAR));
        view.clear();
        assertEquals(LaneCycle.REAR, view.displayLane(LaneCycle.REAR));
        assertEquals(SideViewDecision.NONE, view.lane());
    }

    @Test
    public void switchingIndicatorsKeepsTheOriginalPlacement() {
        MirrorSideView view = new MirrorSideView();
        view.show(LaneCycle.LEFT, 400, 50, false);
        view.show(LaneCycle.RIGHT, 900, 90, true);
        assertEquals(LaneCycle.RIGHT, view.displayLane(LaneCycle.FRONT));
        MirrorSideView.Placement restored = view.clear();
        assertEquals(400, restored.x);
        assertEquals(50, restored.y);
        assertFalse(restored.docked);
        assertEquals(LaneCycle.FRONT, view.displayLane(LaneCycle.FRONT));
    }

    @Test
    public void aDockedMirrorReturnsToTheSameDockedPosition() {
        MirrorSideView view = new MirrorSideView();
        view.show(LaneCycle.RIGHT, -500, 60, true);
        MirrorSideView.Placement restored = view.clear();
        assertEquals(-500, restored.x);
        assertEquals(60, restored.y);
        assertTrue(restored.docked);
        assertFalse(view.active());
    }

    @Test
    public void repeatedClearsDoNotChangeNormalPresentation() {
        MirrorSideView view = new MirrorSideView();
        assertNull(view.clear());
        view.show(LaneCycle.LEFT, 200, 80, false);
        assertNotNull(view.clear());
        assertNull(view.clear());
        assertEquals(LaneCycle.RIGHT, view.displayLane(LaneCycle.RIGHT));
    }

    @Test
    public void aNewIndicatorCapturesTheLatestNormalPosition() {
        MirrorSideView view = new MirrorSideView();
        view.show(LaneCycle.LEFT, 200, 80, false);
        view.clear();
        view.show(LaneCycle.RIGHT, 500, 150, false);
        MirrorSideView.Placement restored = view.clear();
        assertEquals(500, restored.x);
        assertEquals(150, restored.y);
    }

    @Test(expected = IllegalArgumentException.class)
    public void onlySideCamerasCanOverrideTheMirror() {
        new MirrorSideView().show(LaneCycle.FRONT, 0, 0, false);
    }
}
