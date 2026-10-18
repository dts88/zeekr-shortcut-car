package com.kooo.evcam.overlay;

import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.kooo.evcam.overlay.FloatingAction.*;
import static org.junit.Assert.*;

/** Action sequences matter: a double tap must never first stop recording or take a photo. */
public class FloatingButtonGestureTest {
    private static final class Fixture implements FloatingButtonGesture.Scheduler {
        long now;
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        final List<FloatingAction> actions = new ArrayList<>();
        final FloatingButtonGesture gesture = new FloatingButtonGesture(this, actions::add, 300, 600, 100);
        public void post(Runnable task, long delay) { tasks.put(task, now + delay); }
        public void remove(Runnable task) { tasks.remove(task); }
        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Map.Entry<Runnable, Long> next = tasks.entrySet().stream()
                        .filter(e -> e.getValue() <= end).min(Map.Entry.comparingByValue()).orElse(null);
                if (next == null) break;
                now = next.getValue(); Runnable task = next.getKey(); tasks.remove(task); task.run();
            }
            now = end;
        }
        void down(FloatingAction doubleAction) { gesture.down(now, 50, 50, TOGGLE_RECORDING, doubleAction, TAKE_PHOTO); }
        void tap(FloatingAction doubleAction) { down(doubleAction); advance(30); gesture.up(now); }
        void expect(FloatingAction... expected) { assertArrayEquals(expected, actions.toArray()); }
    }

    @Test public void unassignedDoubleTapPreservesImmediateSingleTaps() {
        Fixture f = new Fixture(); f.tap(NONE); f.expect(TOGGLE_RECORDING);
        f.advance(50); f.tap(NONE); f.advance(1000); f.expect(TOGGLE_RECORDING, TOGGLE_RECORDING);
    }
    @Test public void assignedDoubleTapDelaysSingleUntilConfirmed() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(299); f.expect();
        f.advance(1); f.expect(TOGGLE_RECORDING); f.advance(1000); f.expect(TOGGLE_RECORDING);
    }
    @Test public void doubleTapOnlyDispatchesDoubleAction() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(80); f.tap(TOGGLE_MIRROR);
        f.advance(1000); f.expect(TOGGLE_MIRROR);
    }
    @Test public void slowTapsAreTwoSingles() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(301); f.tap(TOGGLE_MIRROR);
        f.advance(1000); f.expect(TOGGLE_RECORDING, TOGGLE_RECORDING);
    }
    @Test public void distantSecondTouchCannotBeDoubleTap() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(80);
        f.gesture.down(f.now, 250, 50, TOGGLE_RECORDING, TOGGLE_MIRROR, TAKE_PHOTO);
        f.advance(30); f.gesture.up(f.now); f.advance(1000); f.expect(TOGGLE_RECORDING, TOGGLE_RECORDING);
    }
    @Test public void longPressFiresOnceWithoutReleaseTap() {
        Fixture f = new Fixture(); f.down(TOGGLE_MIRROR); f.advance(599); f.expect();
        f.advance(1); f.expect(TAKE_PHOTO); f.gesture.up(f.now); f.advance(1000); f.expect(TAKE_PHOTO);
    }
    @Test public void holdingSecondTouchOnlyFiresLongPress() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(80); f.down(TOGGLE_MIRROR);
        f.advance(600); f.gesture.up(f.now); f.advance(1000); f.expect(TAKE_PHOTO);
    }
    @Test public void cancelPreventsLongPressAndReleaseActions() {
        Fixture f = new Fixture(); f.down(TOGGLE_MIRROR); f.advance(40); f.gesture.cancel();
        f.advance(800); f.gesture.up(f.now); f.advance(1000); f.expect();
    }
    @Test public void hideOrSettingsChangeCancelsPendingSingle() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.gesture.cancel(); f.advance(1000); f.expect();
        f.tap(NONE); f.expect(TOGGLE_RECORDING);
    }
    @Test public void draggingSecondTouchDoesNotTurnFirstTapIntoAnAction() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(80); f.down(TOGGLE_MIRROR);
        f.gesture.cancel(); f.gesture.up(f.now); f.advance(1000); f.expect();
    }
    @Test public void tripleTapIsOneDoubleThenOneSingle() {
        Fixture f = new Fixture(); f.tap(TOGGLE_MIRROR); f.advance(80); f.tap(TOGGLE_MIRROR);
        f.advance(80); f.tap(TOGGLE_MIRROR); f.advance(1000); f.expect(TOGGLE_MIRROR, TOGGLE_RECORDING);
    }
    @Test public void noneCanAlsoDisableTapAndHold() {
        Fixture f = new Fixture(); f.gesture.down(0, 0, 0, NONE, TOGGLE_MIRROR, NONE);
        f.advance(600); f.gesture.up(f.now); f.advance(1000); f.expect(NONE);
    }
}
