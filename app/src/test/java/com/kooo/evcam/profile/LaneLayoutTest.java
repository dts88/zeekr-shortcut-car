/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** {@link LaneLayout} 的缩放方式：校正关着和开着各一份。 */
public class LaneLayoutTest {

    /** 校正开着默认填充，关着默认适应。 */
    @Test
    public void correctedFitDefaultsToFillAndPlainFitToFit() {
        LaneLayout lane = new LaneLayout();
        assertEquals(LaneLayout.FIT, lane.fit);
        assertEquals(LaneLayout.FILL, lane.fitCorrected);
    }

    /** 这个键出现之前存下的配置读不到它：要落到「填充」，不是「适应」。 */
    @Test
    public void profilesSavedBeforeTheKeyReadAsFill() {
        Map<String, String> old = new HashMap<>();
        old.put("laneIndex", "0");
        old.put("fit", LaneLayout.FIT);
        LaneLayout lane = LaneLayout.fromMap(old);
        assertEquals(LaneLayout.FIT, lane.fit);
        assertEquals(LaneLayout.FILL, lane.fitCorrected);
    }

    @Test
    public void correctedFitSurvivesARoundTrip() {
        LaneLayout lane = LaneLayout.cell(2, 0f, 0.5f, 0.5f, 0.5f);
        lane.fitCorrected = LaneLayout.FIT;
        assertEquals(LaneLayout.FIT, LaneLayout.fromMap(lane.toMap()).fitCorrected);
    }

    @Test
    public void unknownCorrectedFitReadsAsFill() {
        assertEquals(LaneLayout.FILL, LaneLayout.normaliseFitCorrected("stretch"));
        assertEquals(LaneLayout.FILL, LaneLayout.normaliseFitCorrected(null));
        assertEquals(LaneLayout.FIT, LaneLayout.normaliseFitCorrected(LaneLayout.FIT));
    }
}
