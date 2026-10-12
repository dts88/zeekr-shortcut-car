package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteFrameTest {

    @Test
    public void eachCellIsSixteenByNine() {
        assertEquals(960, RemoteFrame.CELL_WIDTH * 2);
        assertEquals(540, RemoteFrame.CELL_HEIGHT * 2);
        assertEquals(16f / 9f, (float) RemoteFrame.CELL_WIDTH / RemoteFrame.CELL_HEIGHT, 0.001f);
    }

    @Test
    public void phoneChannelPicksOneLane() {
        assertEquals(0, RemoteFrame.laneForSource("ch1"));
        assertEquals(3, RemoteFrame.laneForSource("ch4"));
        assertEquals(-1, RemoteFrame.laneForSource("drive"));
        assertEquals(-1, RemoteFrame.laneForSource("ch5"));
    }

    @Test
    public void surroundGrabKeepsEachLaneAtLeastAsWideAsACell() {
        int[] vertical = RemoteFrame.grabSize(1280, 5140);
        assertTrue(Math.min(vertical[0], vertical[1]) >= RemoteFrame.CELL_WIDTH);
        int[] horizontal = RemoteFrame.grabSize(5120, 1280);
        assertTrue(Math.min(horizontal[0], horizontal[1]) >= RemoteFrame.CELL_WIDTH);
    }

    @Test
    public void wideCellUsesFullWidthAndAShorterVerticalSpan() {
        float[] window = new float[4];
        RemoteFrame.correctedWindow(16f / 9f, window);
        assertEquals(0f, window[0], 0.001f);
        assertEquals(1f, window[2], 0.001f);
        assertEquals(9f / 16f, window[3], 0.001f);
        assertEquals((1f - 9f / 16f) / 2f, window[1], 0.001f);
    }

    @Test
    public void equalVerticalLanesAreFourBands() {
        float[] lanes = new float[16];
        RemoteFrame.equalVerticalLanes(lanes);
        assertTrue(RemoteFrame.lanesUsable(lanes));
        assertEquals(0f, lanes[0], 0.001f);
        assertEquals(0f, lanes[1], 0.001f);
        assertEquals(1f, lanes[2], 0.001f);
        assertEquals(0.25f, lanes[3], 0.001f);
        assertEquals(0.75f, lanes[13], 0.001f);
        assertEquals(1f, lanes[15], 0.001f);
    }

    @Test
    public void emptyLanesAreNotUsable() {
        assertFalse(RemoteFrame.lanesUsable(null));
        assertFalse(RemoteFrame.lanesUsable(new float[16]));
    }
}
