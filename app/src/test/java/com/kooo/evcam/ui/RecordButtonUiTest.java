package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.R;

import org.junit.Test;

public class RecordButtonUiTest {

    @Test
    public void idleMarkIsGreyAndRecordingMarkIsRed() {
        assertEquals(R.color.text_tertiary, RecordButtonUi.markColorRes(false));
        assertEquals(R.color.recording, RecordButtonUi.markColorRes(true));
    }

    @Test
    public void floatingButtonFollowsTheRecorderWhenTheActivityFlagIsStale() {
        assertTrue(RecordButtonUi.floatShowsRecording(false, true));
        assertFalse(RecordButtonUi.floatShowsRecording(true, false));
    }
}
