package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Handler;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A late completion must not keep a stalled replacement session looking alive. */
@RunWith(AndroidJUnit4.class)
public class CaptureSessionProgressAndroidTest {
    private static Field field(String name) throws Exception {
        Field field = SingleCamera.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static final class Session extends CameraCaptureSession {
        @Override public void abortCaptures() { }
        @Override public int capture(CaptureRequest r, CaptureCallback c, Handler h) { return 0; }
        @Override public int captureBurst(List<CaptureRequest> r, CaptureCallback c, Handler h) { return 0; }
        @Override public void close() { }
        @Override public void finalizeOutputConfigurations(List<OutputConfiguration> c) { }
        @Override public CameraDevice getDevice() { return null; }
        @Override public Surface getInputSurface() { return null; }
        @Override public boolean isReprocessable() { return false; }
        @Override public void prepare(Surface s) { }
        @Override public int setRepeatingBurst(List<CaptureRequest> r, CaptureCallback c, Handler h) { return 0; }
        @Override public int setRepeatingRequest(CaptureRequest r, CaptureCallback c, Handler h) { return 0; }
        @Override public void stopRepeating() { }
    }
    @Test public void replacedSessionCannotAdvanceCurrentProgressOrFrameCounters() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SingleCamera camera = new SingleCamera(context, "session-test", null);
        Session previous = new Session(), current = new Session();
        field("captureSession").set(camera, current);
        field("lastProgressUptimeMs").setLong(camera, 17);
        field("lastCaptureUptimeMs").setLong(camera, 19);
        CameraCaptureSession.CaptureCallback callback = (CameraCaptureSession.CaptureCallback)
                field("activeCaptureCallback").get(camera);
        try {
            // The result is deliberately absent: a stale callback must return before reading it.
            callback.onCaptureCompleted(previous, null, null);
            callback.onCaptureStarted(previous, null, 10, 1);
            assertEquals(17, field("lastProgressUptimeMs").getLong(camera));
            assertEquals(19, field("lastCaptureUptimeMs").getLong(camera));
            assertEquals(0, field("firstFrameAt").getLong(camera));
            assertEquals(0, camera.sessionCounts().results);
            assertEquals(0, camera.sessionCounts().starts);
            callback.onCaptureStarted(current, null, 20, 2);
            assertEquals("Current-session evidence remains enabled", 1, camera.sessionCounts().starts);
        } finally { field("captureSession").set(camera, null); }
    }
}
