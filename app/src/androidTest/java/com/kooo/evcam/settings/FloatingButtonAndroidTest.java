package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.preference.ListPreference;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.overlay.FloatingButtonGesture;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.settings.SettingsPreferenceFragment;
import com.kooo.evcam.settings.SettingsShellFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static com.kooo.evcam.overlay.FloatingAction.*;
import static org.junit.Assert.*;

/** Real overlay touch adapter and preference persistence; action sink never opens a camera. */
@RunWith(AndroidJUnit4.class)
public class FloatingButtonAndroidTest {
    private Context target() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private void main(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private static class WindowFixture extends RecordingFloatingService {
        void attach(Context context) { attachBaseContext(context); }
    }
    private static void set(Object service, String key, Object value) throws Exception {
        Field f = RecordingFloatingService.class.getDeclaredField(key); f.setAccessible(true); f.set(service, value);
    }
    private static Object get(Object service, String key) throws Exception {
        Field f = RecordingFloatingService.class.getDeclaredField(key); f.setAccessible(true); return f.get(service);
    }
    private static void call(Object service, String method) {
        try {
            Method m = RecordingFloatingService.class.getDeclaredMethod(method); m.setAccessible(true); m.invoke(service);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private final class Fixture implements AutoCloseable {
        WindowFixture service;
        View view;
        final List<FloatingAction> actions = new ArrayList<>();
        final AppConfig config = new AppConfig(target());
        final String oldTap = config.getFloatingTapAction(), oldDouble = config.getFloatingDoubleTapAction(),
                oldHold = config.getFloatingLongPressAction();
        final boolean oldLock = config.isFloatingPositionLocked();
        Fixture() throws Exception {
            try (ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("appops set " + target().getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                 java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) { while (in.read() != -1) {} }
            config.setFloatingTapAction(TOGGLE_RECORDING.key); config.setFloatingDoubleTapAction(TOGGLE_MIRROR.key);
            config.setFloatingLongPressAction(TAKE_PHOTO.key);
            main(() -> {
                try {
                    service = new WindowFixture(); service.attach(new ContextThemeWrapper(target(), R.style.Theme_Cam));
                    Handler handler = new Handler(Looper.getMainLooper());
                    set(service, "appConfig", config); set(service, "mainHandler", handler);
                    set(service, "windowManager", target().getSystemService(Context.WINDOW_SERVICE));
                    set(service, "screenWidth", 2560); set(service, "screenHeight", 1600);
                    set(service, "gestures", new FloatingButtonGesture(new FloatingButtonGesture.Scheduler() {
                        public void post(Runnable r, long delay) { handler.postDelayed(r, delay); }
                        public void remove(Runnable r) { handler.removeCallbacks(r); }
                    }, actions::add, ViewConfiguration.getDoubleTapTimeout(), 600,
                            ViewConfiguration.get(target()).getScaledDoubleTapSlop()));
                    call(service, "createFloatingWindow"); view = (View) get(service, "floatingContainer");
                } catch (Exception e) { throw new AssertionError(e); }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            main(() -> assertTrue(view.isAttachedToWindow()));
        }
        void event(int action, float x, float y) {
            main(() -> {
                long now = SystemClock.uptimeMillis();
                MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
                try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
            });
        }
        void tap() { event(MotionEvent.ACTION_DOWN, 20, 20); event(MotionEvent.ACTION_UP, 20, 20); }
        void expect(FloatingAction... expected) { main(() -> assertArrayEquals(expected, actions.toArray())); }
        public void close() {
            main(() -> call(service, "hideFloatingWindow"));
            config.setFloatingTapAction(oldTap); config.setFloatingDoubleTapAction(oldDouble);
            config.setFloatingLongPressAction(oldHold); config.setFloatingPositionLocked(oldLock);
        }
    }
    @Test public void realOverlayDoubleTapAndHoldNeverFireSingleAction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tap(); f.expect(); SystemClock.sleep(50); f.tap();
            SystemClock.sleep(400); f.expect(TOGGLE_MIRROR);
            f.event(MotionEvent.ACTION_DOWN, 20, 20); SystemClock.sleep(700);
            f.event(MotionEvent.ACTION_UP, 20, 20); SystemClock.sleep(400);
            f.expect(TOGGLE_MIRROR, TAKE_PHOTO);
            f.config.setFloatingDoubleTapAction(NONE.key);
            f.tap(); f.expect(TOGGLE_MIRROR, TAKE_PHOTO, TOGGLE_RECORDING);
        }
    }
    @Test public void realOverlayDragCancelAndHideSuppressPendingActions() throws Exception {
        try (Fixture f = new Fixture()) {
            f.config.setFloatingPositionLocked(true);
            f.event(MotionEvent.ACTION_DOWN, 20, 20); f.event(MotionEvent.ACTION_MOVE, 60, 60);
            f.event(MotionEvent.ACTION_UP, 60, 60); SystemClock.sleep(700); f.expect();
            f.event(MotionEvent.ACTION_DOWN, 20, 20); f.event(MotionEvent.ACTION_CANCEL, 20, 20);
            SystemClock.sleep(700); f.expect();
            f.config.setFloatingPositionLocked(false);
            f.event(MotionEvent.ACTION_DOWN, 20, 20); f.event(MotionEvent.ACTION_MOVE, 60, 60);
            f.event(MotionEvent.ACTION_UP, 60, 60); SystemClock.sleep(700); f.expect();
            f.tap(); main(() -> call(f.service, "hideFloatingWindow")); SystemClock.sleep(400); f.expect();
        }
    }
    @Test public void settingsExposeThreeActionsAndPersistOptionalDoubleTap() {
        Context context = target(); AppConfig config = new AppConfig(context);
        String oldDouble = config.getFloatingDoubleTapAction(); boolean oldEnabled = config.isRecordingFloatingEnabled();
        try {
            context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().remove("floating_double_tap_action")
                    .putBoolean("first_launch", false).putBoolean("language_chosen", true)
                    .putBoolean("rail_side_chosen", true).commit();
            assertEquals(NONE.key, config.getFloatingDoubleTapAction());
            String preservedTap = config.getFloatingTapAction(), preservedHold = config.getFloatingLongPressAction();
            config.setRecordingFloatingEnabled(true);
            try (ActivityScenario<SettingsTestHostActivity> scenario = ActivityScenario.launch(SettingsTestHostActivity.class)) {
                scenario.onActivity(activity -> {
                    SettingsShellFragment shell = new SettingsShellFragment();
                    activity.findViewById(R.id.fragment_container).setVisibility(View.VISIBLE);
                    activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, shell).commitNow();
                    shell.getChildFragmentManager().executePendingTransactions(); shell.showSection("screen_floating");
                    shell.getChildFragmentManager().executePendingTransactions();
                    SettingsPreferenceFragment page = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                            .findFragmentById(R.id.settings_detail);
                    assertNotNull(page.findPreference("pref_floating_tap"));
                    assertNotNull(page.findPreference("pref_floating_long_press"));
                    ListPreference doubleTap = page.findPreference("pref_floating_double_tap");
                    assertTrue(doubleTap.isEnabled()); assertEquals(NONE.key, doubleTap.getValue());
                    assertEquals(6, doubleTap.getEntryValues().length);
                    assertTrue(doubleTap.callChangeListener(LOCK_FOOTAGE.key)); doubleTap.setValue(LOCK_FOOTAGE.key);
                    assertEquals(LOCK_FOOTAGE.key, new AppConfig(context).getFloatingDoubleTapAction());
                    assertEquals(preservedTap, config.getFloatingTapAction());
                    assertEquals(preservedHold, config.getFloatingLongPressAction());
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(200);
                android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new java.io.File(context.getCacheDir(), "double-tap-settings.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } catch (java.io.IOException e) { throw new AssertionError(e); }
                finally { screenshot.recycle(); }
                scenario.recreate();
                scenario.onActivity(activity -> {
                    SettingsShellFragment shell = (SettingsShellFragment) activity.getSupportFragmentManager()
                            .findFragmentById(R.id.fragment_container);
                    SettingsPreferenceFragment page = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                            .findFragmentById(R.id.settings_detail);
                    assertEquals(LOCK_FOOTAGE.key, ((ListPreference) page.findPreference("pref_floating_double_tap")).getValue());
                });
            }
            context.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                    .putString("floating_double_tap_action", "unknown-future-action").commit();
            assertEquals(NONE.key, new AppConfig(context).getFloatingDoubleTapAction());
        } finally { config.setFloatingDoubleTapAction(oldDouble); config.setRecordingFloatingEnabled(oldEnabled); }
    }
}
