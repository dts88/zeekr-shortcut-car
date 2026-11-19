/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 每次上车、陡坡缓降能用了就把它打开的一遍怎么走（{@link HillDescent}）：钟、车辆信号、读车写车都是假的，
 * 只看什么时候问、什么时候写、写几次。
 */
public class HillDescentTest {

    /** 假的外面：钟要 {@link #advance} 才走；写车照 {@link #results} 里排好的结果回话（排完了的都算成）。 */
    private static final class FakeWorld implements HillDescent.World {
        long now = 1_000_000L;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean setting = true;
        boolean connected = true;
        Integer ignition = Signal.IGNITION_ON;
        /** 此刻问到能不能用。 */
        Integer available = 0;
        boolean exited;
        int reads;
        int writes;
        final List<CarMoves.Result> results = new ArrayList<>();
        /** 写车的结果先不回，拿在手里（{@link #answer}）。 */
        boolean holdAnswers;
        final List<Runnable> pending = new ArrayList<>();
        /** 写满次数都没成的每一次写了几次。 */
        final List<Integer> gaveUp = new ArrayList<>();
        boolean awaiting;

        @Override
        public long now() {
            return now;
        }

        @Override
        public void later(long delayMs, Runnable action) {
            due.add(new long[] {now + delayMs});
            actions.add(action);
        }

        /** 钟往前走这么久，到点的照到点的先后做。 */
        void advance(long ms) {
            long end = now + ms;
            while (true) {
                int next = -1;
                for (int i = 0; i < due.size(); i++) {
                    if (due.get(i)[0] <= end && (next < 0 || due.get(i)[0] < due.get(next)[0])) {
                        next = i;
                    }
                }
                if (next < 0) {
                    break;
                }
                now = due.remove(next)[0];
                actions.remove(next).run();
            }
            now = end;
        }

        /** 能不能用变成这个，再问这么多次。 */
        void read(Integer value, int times) {
            available = value;
            advance(times * HillDescentRules.POLL_MS);
        }

        @Override
        public boolean setting() {
            return setting;
        }

        @Override
        public boolean linkConnected() {
            return connected;
        }

        @Override
        public Integer ignition() {
            return ignition;
        }

        @Override
        public boolean userExited() {
            return exited;
        }

        @Override
        public void readAvailable(Consumer<Integer> seen) {
            reads++;
            seen.accept(available);
        }

        @Override
        public void restore(Consumer<CarMoves.Result> done) {
            writes++;
            CarMoves.Result result = results.isEmpty() ? CarMoves.Result.DONE : results.remove(0);
            if (holdAnswers) {
                pending.add(() -> done.accept(result));
            } else {
                done.accept(result);
            }
        }

        void answer() {
            pending.remove(0).run();
        }

        @Override
        public void gaveUp(int writes) {
            gaveUp.add(writes);
        }

        @Override
        public boolean awaitingAvailable() {
            return awaiting;
        }

        @Override
        public void setAwaitingAvailable(boolean awaiting) {
            this.awaiting = awaiting;
        }
    }

    private final FakeWorld world = new FakeWorld();
    private final HillDescent hill = new HillDescent(world);

    /** 下车、再上车：新的一次。 */
    private void getIn() {
        hill.stop();
        hill.start();
    }

    @Test
    public void switchesOnOnceWhenItBecomesAvailable() {
        getIn();
        world.read(0, 20);
        assertEquals("用不了时不写", 0, world.writes);
        world.read(1, 1);
        assertEquals("只问到一次能用不写", 0, world.writes);
        world.read(1, 1);
        assertEquals(1, world.writes);
        assertFalse(world.awaiting);

        // 之后手动关掉、它一会儿用不了又能用：这一次上车不再写
        world.read(1, 30);
        world.read(0, 5);
        world.read(1, 30);
        assertEquals(1, world.writes);
    }

    @Test
    public void stopsAskingOnceItIsSwitchedOn() {
        getIn();
        world.read(1, 3);
        assertEquals(1, world.writes);
        int reads = world.reads;
        world.read(1, 600);
        assertEquals("打开过了不再问", reads, world.reads);
    }

    @Test
    public void stopsAskingOnceEveryWriteFailed() {
        for (int i = 0; i < HillDescentRules.MAX_WRITES; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        getIn();
        world.read(1, 60);
        assertEquals(1, world.gaveUp.size());
        int reads = world.reads;
        world.read(1, 600);
        assertEquals(reads, world.reads);
    }

    @Test
    public void doesNotAskWhileTheUserHasExited() {
        world.exited = true;
        getIn();
        world.read(1, 10);
        assertEquals(0, world.reads);
        assertEquals(0, world.writes);
        world.exited = false;
        world.read(1, 3);
        assertEquals(1, world.writes);
    }

    @Test
    public void switchesOnAgainOnTheNextDrive() {
        getIn();
        world.read(1, 3);
        assertEquals(1, world.writes);
        getIn();
        world.read(1, 3);
        assertEquals(2, world.writes);
    }

    @Test
    public void doesNothingUntilTheFirstStartAfterTheSettingIsOn() {
        world.setting = false;
        getIn();
        world.read(1, 5);
        assertEquals(0, world.reads);
        assertFalse(world.awaiting);

        // 半路打开：这一次上车不写
        world.setting = true;
        world.read(1, 10);
        assertEquals(0, world.writes);

        getIn();
        world.read(1, 3);
        assertEquals(1, world.writes);
    }

    @Test
    public void doesNotAskWhileTheCarIsNotPoweredOrNotConnected() {
        world.ignition = Signal.IGNITION_ACC;
        getIn();
        world.read(1, 5);
        assertEquals(0, world.reads);
        world.ignition = Signal.IGNITION_ON;
        world.connected = false;
        world.read(1, 5);
        assertEquals(0, world.reads);
        assertEquals(0, world.writes);
        assertTrue("还在等", world.awaiting);
    }

    @Test
    public void anUnknownAnswerBreaksTheRun() {
        getIn();
        world.read(1, 1);
        world.read(null, 1);
        world.read(1, 1);
        assertEquals(0, world.writes);
        world.read(1, 1);
        assertEquals(1, world.writes);
    }

    @Test
    public void retriesAFailedWriteThenStops() {
        world.results.add(CarMoves.Result.UNAVAILABLE);
        world.results.add(CarMoves.Result.NO_CHANGE);
        getIn();
        world.read(1, 2);
        assertEquals(1, world.writes);
        world.advance(HillDescentRules.RETRY_MS - HillDescentRules.POLL_MS);
        assertEquals("没隔够不再写", 1, world.writes);
        world.advance(60_000);
        assertEquals(3, world.writes);
        assertTrue("第三次成了", world.gaveUp.isEmpty());
    }

    @Test
    public void givesUpAfterTheLastFailedWriteUntilTheNextDrive() {
        for (int i = 0; i < HillDescentRules.MAX_WRITES; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        getIn();
        world.read(1, 60);
        assertEquals(HillDescentRules.MAX_WRITES, world.writes);
        assertEquals(1, world.gaveUp.size());
        assertEquals(HillDescentRules.MAX_WRITES, (int) world.gaveUp.get(0));
        assertFalse(world.awaiting);

        getIn();
        world.read(1, 3);
        assertEquals(HillDescentRules.MAX_WRITES + 1, world.writes);
    }

    @Test
    public void aProcessStartedMidDriveDoesNotSwitchItOnAgain() {
        getIn();
        world.read(1, 3);
        assertEquals(1, world.writes);

        HillDescent next = new HillDescent(world);
        next.processStarted(false, false);
        world.read(1, 10);
        assertEquals(1, world.writes);
    }

    @Test
    public void aProcessStartedAfterTheCarWasLeftStillSwitchesItOn() {
        hill.start();
        hill.stop();
        assertTrue(world.awaiting);

        HillDescent next = new HillDescent(world);
        next.processStarted(false, false);
        world.read(1, 3);
        assertEquals(1, world.writes);
    }

    @Test
    public void aProcessStartedAfterARebootCountsAsANewDrive() {
        HillDescent next = new HillDescent(world);
        next.processStarted(false, true);
        world.read(1, 3);
        assertEquals(1, world.writes);
    }

    @Test
    public void aProcessStartedWhileDarkWaitsForTheScreen() {
        world.awaiting = true;
        HillDescent next = new HillDescent(world);
        next.processStarted(true, false);
        world.read(1, 10);
        assertEquals(0, world.reads);
        next.start();
        world.read(1, 3);
        assertEquals(1, world.writes);
    }

    @Test
    public void aWriteAnsweredAfterLeavingIsIgnored() {
        world.holdAnswers = true;
        getIn();
        world.read(1, 2);
        assertEquals(1, world.writes);
        hill.stop();
        world.answer();
        assertTrue("下车了：下一次上车照样算新的一次", world.awaiting);
        world.advance(60_000);
        assertEquals(1, world.writes);
    }
}
