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

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上车时切驾驶模式的一遍怎么走（{@link DriveStart}）：钟、车辆信号、写车都是假的，只看什么时候写、写几次、什么时候算了。
 */
public class DriveStartTest {

    /** 假的外面：钟要 {@link #advance} 才走；写车照 {@link #results} 里排好的结果回话（排完了的都算成）。 */
    private static final class FakeWorld implements DriveStart.World {
        long now = 1_000_000L;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        String setting = AppConfig.DRIVE_MODE_AT_START_COMFORT;
        boolean connected = true;
        Integer ignition = Signal.IGNITION_ON;
        String gear = "P";
        Integer driveMode = Signal.DRIVE_MODE_STANDARD;
        final List<Integer> writes = new ArrayList<>();
        final List<CarMoves.Result> results = new ArrayList<>();
        /** 写车的结果先不回，拿在手里（{@link #answer}）。 */
        boolean holdAnswers;
        final List<Runnable> pending = new ArrayList<>();
        /** 没切成的每一次是为什么。 */
        final List<DriveStartRules.Decision> gaveUp = new ArrayList<>();
        /** 切成之后再看的每一次：还是不是写的那个。 */
        final List<Boolean> checks = new ArrayList<>();
        boolean awaitingNextDrive;

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

        @Override
        public String setting() {
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
        public String gear() {
            return gear;
        }

        @Override
        public Integer driveMode() {
            return driveMode;
        }

        @Override
        public void write(int mode, Consumer<CarMoves.Result> done) {
            writes.add(mode);
            CarMoves.Result result = results.isEmpty() ? CarMoves.Result.DONE : results.remove(0);
            Runnable answer = () -> {
                if (result == CarMoves.Result.DONE) {
                    driveMode = mode;
                }
                done.accept(result);
            };
            if (holdAnswers) {
                pending.add(answer);
            } else {
                answer.run();
            }
        }

        void answer() {
            pending.remove(0).run();
        }

        @Override
        public void gaveUp(DriveStartRules.Decision why, int writes, boolean connected, Integer ignition, String gear,
                           long sinceStartMs) {
            gaveUp.add(why);
        }

        @Override
        public void verified(int mode, Integer seen) {
            checks.add(seen != null && seen == mode);
        }

        @Override
        public boolean awaitingNextDrive() {
            return awaitingNextDrive;
        }

        @Override
        public void setAwaitingNextDrive(boolean left) {
            awaitingNextDrive = left;
        }
    }

    private final FakeWorld world = new FakeWorld();
    private final DriveStart start = new DriveStart(world);

    private static final long PLENTY = DriveStartRules.WINDOW_MS * 3;

    @Test
    public void switchesOnceAfterTheScreenLights() {
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS - 1);
        assertTrue("没等够不写", world.writes.isEmpty());
        world.advance(DriveStart.POLL_MS);
        assertEquals(1, world.writes.size());
        assertEquals(Signal.DRIVE_MODE_COMFORT, (int) world.writes.get(0));

        // 之后手动切到运动：这一次上车不再写
        world.driveMode = Signal.DRIVE_MODE_SPORT;
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void eachSettingWritesItsOwnMode() {
        world.setting = AppConfig.DRIVE_MODE_AT_START_SPORT;
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
        assertEquals(Signal.DRIVE_MODE_SPORT, (int) world.writes.get(0));
    }

    @Test
    public void doesNothingWhenTheSettingIsOff() {
        world.setting = AppConfig.DRIVE_MODE_AT_START_OFF;
        start.screenWentLit();
        // 半路打开：这一次上车已经过去了，等下一次
        world.setting = AppConfig.DRIVE_MODE_AT_START_COMFORT;
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());
        assertTrue(world.checks.isEmpty());

        start.screenWentDark();
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void stopsQuietlyWhenTheSettingIsTurnedOffWhileWaiting() {
        world.connected = false;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.setting = AppConfig.DRIVE_MODE_AT_START_OFF;
        world.connected = true;
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void waitsForTheCarThenWrites() {
        world.connected = false;
        world.ignition = null;
        world.gear = null;
        start.screenWentLit();
        world.advance(10_000);
        assertTrue(world.writes.isEmpty());
        world.connected = true;
        world.ignition = Signal.IGNITION_ON;
        world.gear = "P";
        world.advance(DriveStart.POLL_MS);
        assertEquals(1, world.writes.size());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void givesUpWithANoticeWhenTheCarNeverGetsReady() {
        world.ignition = Signal.IGNITION_ACC;
        start.screenWentLit();
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals(Collections.singletonList(DriveStartRules.Decision.TIMED_OUT), world.gaveUp);
        // 之后通电了也不写：这一次上车已经算了
        world.ignition = Signal.IGNITION_ON;
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
    }

    @Test
    public void givesUpOnceTheCarHasLeftPark() {
        world.connected = false;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.connected = true;
        world.gear = "D";
        world.advance(DriveStart.POLL_MS);
        assertTrue(world.writes.isEmpty());
        assertEquals(Collections.singletonList(DriveStartRules.Decision.LEFT_PARK), world.gaveUp);
        // 等红灯时挂回 P 挡也不写
        world.gear = "P";
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals(1, world.gaveUp.size());
    }

    @Test
    public void retriesAFailedWriteThenStops() {
        world.results.add(CarMoves.Result.UNAVAILABLE);
        world.results.add(CarMoves.Result.NO_CHANGE);
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.writes.size());
        world.advance(DriveStartRules.RETRY_MS - 1);
        assertEquals("没隔够不再写", 1, world.writes.size());
        world.advance(PLENTY);
        assertEquals(3, world.writes.size());
        assertTrue("第三次成了", world.gaveUp.isEmpty());
    }

    @Test
    public void tellsOnceWhenEveryWriteFails() {
        for (int i = 0; i < DriveStartRules.MAX_WRITES; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(DriveStartRules.MAX_WRITES, world.writes.size());
        assertEquals(Collections.singletonList(DriveStartRules.Decision.FAILED), world.gaveUp);
    }

    @Test
    public void notesWhetherTheModeHeld() {
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertTrue(world.checks.isEmpty());
        world.advance(DriveStartRules.VERIFY_MS);
        assertEquals(Collections.singletonList(true), world.checks);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void notesWithoutWritingAgainWhenTheCarPutItsOwnModeBack() {
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.driveMode = Signal.DRIVE_MODE_STANDARD;
        world.advance(PLENTY);
        assertEquals(Collections.singletonList(false), world.checks);
        assertEquals(1, world.writes.size());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void leavingTheCarCancelsWhatIsLeft() {
        world.connected = false;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        start.screenWentDark();
        world.connected = true;
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void aWriteAnsweredAfterLeavingIsIgnored() {
        world.holdAnswers = true;
        world.results.add(CarMoves.Result.NO_CHANGE);
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.writes.size());
        start.screenWentDark();
        world.answer();
        world.advance(PLENTY);
        assertEquals("下车之后不再写", 1, world.writes.size());
        assertTrue(world.gaveUp.isEmpty());
        assertTrue(world.checks.isEmpty());
    }

    @Test
    public void everyTimeTheScreenLightsIsANewStart() {
        start.screenWentLit();
        world.advance(PLENTY);
        world.driveMode = Signal.DRIVE_MODE_STANDARD;
        start.screenWentDark();
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(2, world.writes.size());
    }

    @Test
    public void aProcessStartedMidDriveDoesNothing() {
        start.processStarted(false, false);
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void aProcessStartedAfterTheCarWasLeftCountsOnce() {
        start.screenWentDark();
        assertTrue("熄屏记下来，进程没了也还在", world.awaitingNextDrive);

        DriveStart next = new DriveStart(world);
        next.processStarted(false, false);
        assertFalse(world.awaitingNextDrive);
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());

        // 再起来一个进程（行驶中重启）：不再算
        new DriveStart(world).processStarted(false, false);
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void aProcessStartedAfterARebootCounts() {
        start.processStarted(false, true);
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void aProcessStartedWhileDarkWaitsForTheScreen() {
        world.awaitingNextDrive = true;
        start.processStarted(true, true);
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue("留给亮屏那条边", world.awaitingNextDrive);
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }
}
