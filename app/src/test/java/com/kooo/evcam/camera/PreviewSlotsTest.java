package com.kooo.evcam.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link PreviewSlots} 的单元测试：布局和要等的画布只看启用了哪几路，不数路数（2026-10-16）。
 */
public class PreviewSlotsTest {

    private static Set<String> enabled(String... keys) {
        return new LinkedHashSet<>(Arrays.asList(keys));
    }

    @Test
    public void singleLayoutOnlyWhenTheSurroundIsTheOnlyLane() {
        assertTrue(PreviewSlots.singleLayout(enabled("front")));
        assertFalse(PreviewSlots.singleLayout(enabled("front", "back", "left")));
        assertFalse(PreviewSlots.singleLayout(enabled("front", "left")));
        assertFalse(PreviewSlots.singleLayout(Collections.emptySet()));
    }

    /** 只开一路座舱：以前当成「只有环视」摆四宫格，那一路座舱没有画布。 */
    @Test
    public void aSingleCabinGetsTheMultiLayoutAndItsOwnCanvas() {
        assertFalse(PreviewSlots.singleLayout(enabled("back")));
        assertFalse(PreviewSlots.singleLayout(enabled("left")));
        assertFalse(PreviewSlots.canStartCamera(0, enabled("left")));
        assertTrue(PreviewSlots.canStartCamera(1, enabled("left")));
    }

    /** 只开两路座舱：两块座舱画布都要等，环视没开不在里面。 */
    @Test
    public void twoCabinsWaitForBothCabinCanvases() {
        assertFalse(PreviewSlots.singleLayout(enabled("back", "left")));
        assertFalse(PreviewSlots.canStartCamera(1, enabled("back", "left")));
        assertTrue(PreviewSlots.canStartCamera(2, enabled("back", "left")));
    }

    @Test
    public void cameraStartsOnlyWhenEveryEnabledLaneIsReady() {
        assertFalse(PreviewSlots.canStartCamera(2, enabled("front", "back", "left")));
        assertTrue(PreviewSlots.canStartCamera(3, enabled("front", "back", "left")));
        assertTrue(PreviewSlots.canStartCamera(1, enabled("front")));
    }

    /** 一路都没启用：没有画布可等。 */
    @Test
    public void nothingEnabledWaitsForNoCanvas() {
        assertTrue(PreviewSlots.canStartCamera(0, Collections.emptySet()));
    }
}
