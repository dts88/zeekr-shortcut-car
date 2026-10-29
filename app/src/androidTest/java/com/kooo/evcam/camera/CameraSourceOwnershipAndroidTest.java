package com.kooo.evcam.camera;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Ownership lookup only: no camera open, recording, or owner-device state changes. */
@RunWith(AndroidJUnit4.class)
public class CameraSourceOwnershipAndroidTest {
    private Context context() {
        assumeTrue(Build.PRODUCT.contains("sdk") || Build.HARDWARE.contains("ranchu"));
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    private void main(Runnable run) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(run);
    }
    private static final class Source extends SingleCamera {
        boolean held = true, open = true;
        Source(Context context, String id) { super(context, id, null); }
        @Override public boolean holdsInService() { return held; }
        @Override public boolean holdsOrIsOpening() { return held; }
        @Override public boolean isCameraOpened() { return open; }
    }
    private static final class Sources extends MultiCameraManager {
        final List<SingleCamera> entries = new ArrayList<>();
        Sources(Context context) { super(context); }
        @Override List<SingleCamera> initializedCameras() { return entries; }
    }

    @Test public void roadAndManualOwnershipAndDiagnosticsUseAllInitializedSources() {
        Context context = context();
        main(() -> {
            CameraManagerHolder holder = CameraManagerHolder.getInstance();
            assertNull("Disposable test process must not have an active app pipeline", holder.getCameraManager());
            Sources manager = new Sources(context);
            Source surround = new Source(context, "3"), front = new Source(context, "0"),
                    rear = new Source(context, "1"), manual = new Source(context, "12");
            manager.entries.addAll(Arrays.asList(surround, front, rear, manual));
            try {
                Field current = CameraManagerHolder.class.getDeclaredField("cameraManager");
                current.setAccessible(true); current.set(holder, manager);
                for (String id : new String[]{"3", "0", "1", "12"}) {
                    assertTrue(id, CameraAvailabilityWatch.weHoldOrOpen(id));
                }
                assertFalse(CameraAvailabilityWatch.weHoldOrOpen("9"));
                String details = CameraContention.describeUs();
                assertTrue(details, details.contains("open=[3,0,1,12]"));
                Method ids = CameraAvailabilityWatch.class.getDeclaredMethod("ourCameraIds");
                ids.setAccessible(true);
                assertEquals(Arrays.asList("3", "0", "1", "12"), ids.invoke(null));

                // An externally taken/lost road source must still count as someone else's.
                front.held = false; front.open = false;
                assertFalse(CameraAvailabilityWatch.weHoldOrOpen("0"));
                assertTrue(CameraAvailabilityWatch.weHoldOrOpen("1"));
                rear.open = false; // Still opening/closing normally: service ownership remains ours.
                assertTrue(CameraAvailabilityWatch.weHoldOrOpen("1"));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally { holder.release(); }
        });
    }

    @Test public void membershipSnapshotIncludesExtraRolesDeduplicatesAndClearsOnRelease() {
        Context context = context();
        main(() -> {
            MultiCameraManager manager = new MultiCameraManager(context);
            try {
                Map<String, String> ids = new LinkedHashMap<>();
                ids.put("front", "3"); ids.put("roadfront", "0"); ids.put("roadrear", "1");
                ids.put("manual", "0"); ids.put("back", null);
                manager.initCameras(ids, Collections.emptyMap());
                List<SingleCamera> snapshot = manager.initializedCameras();
                List<String> actual = new ArrayList<>();
                for (SingleCamera source : snapshot) actual.add(source.getCameraId());
                assertEquals(Arrays.asList("3", "0", "1"), actual);
                try { snapshot.clear(); fail("Membership must be immutable across callback threads"); }
                catch (UnsupportedOperationException expected) { }
                manager.release();
                assertTrue(manager.initializedCameras().isEmpty());
                assertEquals("Published snapshots must remain stable", 3, snapshot.size());
            } finally { if (!manager.isReleased()) manager.release(); }
        });
    }
}
