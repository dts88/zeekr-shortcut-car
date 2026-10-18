package com.kooo.evcam.overlay;

import java.util.function.Consumer;

/** Main-thread gesture arbitration, independent of Android windows/camera actions. */
public final class FloatingButtonGesture {
    public interface Scheduler {
        void post(Runnable task, long delayMs);
        void remove(Runnable task);
    }

    private final Scheduler scheduler;
    private final Consumer<FloatingAction> perform;
    private final long doubleTapMs, longPressMs;
    private final float doubleSlopSquared;
    private boolean down, secondTap;
    private FloatingAction tap, doubleTap, hold, pendingTap;
    private long firstUp;
    private float firstX, firstY, x, y;

    public FloatingButtonGesture(Scheduler scheduler, Consumer<FloatingAction> perform,
                                 long doubleTapMs, long longPressMs, float doubleSlop) {
        this.scheduler = scheduler;
        this.perform = perform;
        this.doubleTapMs = doubleTapMs;
        this.longPressMs = longPressMs;
        doubleSlopSquared = doubleSlop * doubleSlop;
    }

    private final Runnable confirmTap = this::confirmTap;
    private void confirmTap() {
        FloatingAction action = pendingTap;
        pendingTap = null;
        if (action != null) perform.accept(action);
    }
    private final Runnable longPress = this::longPress;
    private void longPress() {
        if (!down) return;
        down = false; // Lifting after a hold cannot also dispatch a tap.
        secondTap = false;
        perform.accept(hold);
    }

    /** Actions are captured at gesture start; later preference writes cannot reinterpret it. */
    public void down(long time, float x, float y, FloatingAction tap,
                     FloatingAction doubleTap, FloatingAction hold) {
        scheduler.remove(longPress);
        float dx = x - firstX, dy = y - firstY;
        boolean matches = pendingTap != null && doubleTap != FloatingAction.NONE
                && doubleTap == this.doubleTap && tap == this.tap && hold == this.hold
                && time >= firstUp && time - firstUp <= doubleTapMs
                && dx * dx + dy * dy <= doubleSlopSquared;
        scheduler.remove(confirmTap);
        if (pendingTap != null && !matches) confirmTap.run();
        pendingTap = null;
        this.tap = tap; this.doubleTap = doubleTap; this.hold = hold;
        this.x = x; this.y = y;
        secondTap = matches;
        down = true;
        scheduler.post(longPress, longPressMs);
    }

    public void up(long time) {
        scheduler.remove(longPress);
        if (!down) return;
        down = false;
        if (secondTap) {
            secondTap = false;
            perform.accept(doubleTap);
        } else if (doubleTap == FloatingAction.NONE) {
            perform.accept(tap); // No double assignment: preserve immediate single taps.
        } else {
            firstUp = time; firstX = x; firstY = y;
            pendingTap = tap;
            scheduler.post(confirmTap, doubleTapMs);
        }
    }

    /** Drag, cancel, multiple pointers, hidden window or changed settings: no deferred actions. */
    public void cancel() {
        scheduler.remove(longPress);
        scheduler.remove(confirmTap);
        down = false; secondTap = false; pendingTap = null;
    }
}
