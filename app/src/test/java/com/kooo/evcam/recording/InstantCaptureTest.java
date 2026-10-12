package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.repair.Mp4Repair;
import com.kooo.evcam.share.UsbExport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class InstantCaptureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void openSegmentIsSnapshottedInsteadOfDropped() {
        assertTrue(InstantCapture.snapshotOpenFile(Mp4Repair.Status.REPAIRABLE));
        assertFalse(InstantCapture.snapshotOpenFile(Mp4Repair.Status.EMPTY));
        assertFalse(InstantCapture.snapshotOpenFile(Mp4Repair.Status.UNKNOWN));
    }

    @Test
    public void instantFolderSitsBesideVideosWhenTheUsbRootCannotBeCreated() throws IOException {
        File stick = tmp.newFile("stick");
        File video = tmp.newFolder("DCIM", "EVCam_Video");
        File dest = InstantCapture.instantFolder(stick, video);
        assertEquals(new File(video.getParentFile(), UsbExport.INSTANT_DIR).getAbsolutePath(),
                dest.getAbsolutePath());
        assertTrue(dest.isDirectory());
    }
}
