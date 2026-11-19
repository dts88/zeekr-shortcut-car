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
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * 上车时切驾驶模式的一遍怎么走（{@link DriveStart}）：钟、车辆信号、写车都是假的，只看什么时候写、写几次、什么时候算了。
 */
public class DriveStartTest {

    private static final String SAME = "same as published";
    private static final Integer SAME_IGNITION = Integer.MIN_VALUE;

    /** 假的外面：钟要 {@link #advance} 才走；写车照 {@link #results} 里排好的结果回话（排完了的都算成）。 */
    private static final class FakeWorld implements DriveStart.World {
        long now = 1_000_000L;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        String setting = AppConfig.DRIVE_MODE_AT_START_COMFORT;
        boolean connected = true;
        Integer ignition = Signal.IGNITION_ON;
        /** 发布出来的挡位（主线程上判的时候看的）。 */
        String gear = "P";
        /** 写车的线程临写之前从车上现读的挡位；{@link #SAME} 是和发布出来的一样，null 是读不到。 */
        String liveGear = SAME;
        /** 同上，现读的点火；{@link #SAME_IGNITION} 是和发布出来的一样。 */
        Integer liveIgnition = SAME_IGNITION;
        Integer driveMode = Signal.DRIVE_MODE_STANDARD;
        boolean exited;
        /** 交给写车的线程几次。 */
        int requests;
        /** 真写到车上的（轮到时还要的）。 */
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
        public boolean userExited() {
            return exited;
        }

        /** 轮到写的时候（{@link #answer}，没拿在手里的就是当场）先拿现读的点火、挡位问还要不要：不要了不写，是 MOVING。 */
        @Override
        public void write(int mode, BiPredicate<Integer, String> stillWanted, Consumer<CarMoves.Result> done) {
            requests++;
            Runnable answer = () -> {
                if (!stillWanted.test(SAME_IGNITION.equals(liveIgnition) ? ignition : liveIgnition,
                        SAME.equals(liveGear) ? gear : liveGear)) {
                    done.accept(CarMoves.Result.MOVING);
                    return;
                }
                writes.add(mode);
                CarMoves.Result result = results.isEmpty() ? CarMoves.Result.DONE : results.remove(0);
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
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        start.screenWentDark();
        world.answer();
        world.advance(PLENTY);
        assertTrue("排着的那一件轮到时已经下车了：不写", world.writes.isEmpty());
        assertEquals(1, world.requests);
        assertTrue(world.gaveUp.isEmpty());
        assertTrue(world.checks.isEmpty());
    }

    /** 写车的线程被前面的一件占着（比如关门等十几秒），排队时挂出了 P 挡：轮到时不写。 */
    @Test
    public void shiftingOutOfParkWhileTheWriteIsQueuedWritesNothing() {
        world.holdAnswers = true;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        world.gear = "D";
        world.answer();
        assertTrue(world.writes.isEmpty());
        world.advance(DriveStartRules.RETRY_MS);
        assertEquals(Collections.singletonList(DriveStartRules.Decision.LEFT_PARK), world.gaveUp);
        world.gear = "P";
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals(1, world.requests);
    }

    /**
     * 发布出来的挡位还是 P、车上现读的已经是 D（刚挂出去，发布晚几十毫秒）：不写。之后发布出来的跟上了，这一次上车就不切了。
     */
    @Test
    public void aStalePublishedGearDoesNotLetTheWriteThrough() {
        world.liveGear = "D";
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        assertTrue(world.writes.isEmpty());
        world.gear = "D";
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals(Collections.singletonList(DriveStartRules.Decision.LEFT_PARK), world.gaveUp);
    }

    /** 临写之前从车上读到 D 挡，隔一会儿再试之前又挂回了 P 挡：读到过挂出 P 挡，这一次上车就不切了。 */
    @Test
    public void aKnownShiftOutOfParkEndsTheDriveEvenIfParkComesBack() {
        world.liveGear = "D";
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        assertEquals(Collections.singletonList(DriveStartRules.Decision.LEFT_PARK), world.gaveUp);
        world.liveGear = SAME;
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals("不再试", 1, world.requests);
    }

    /** 发布出来的点火还是 ON、车上现读的已经是 ACC（刚断电），挡位 P：不写。 */
    @Test
    public void aStalePublishedIgnitionDoesNotLetTheWriteThrough() {
        world.liveIgnition = Signal.IGNITION_ACC;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        assertTrue(world.writes.isEmpty());
        world.liveIgnition = null;
        world.advance(DriveStartRules.RETRY_MS);
        assertTrue("读不到点火也不写", world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());
    }

    /** 等到快到时限才排上的那一件，轮到时已经过了时限：不写。 */
    @Test
    public void aWriteQueuedBeforeTheDeadlineIsDroppedAfterIt() {
        world.holdAnswers = true;
        world.connected = false;
        start.screenWentLit();
        world.advance(DriveStartRules.WINDOW_MS - DriveStart.POLL_MS);
        world.connected = true;
        world.advance(DriveStart.POLL_MS);
        assertEquals(1, world.requests);
        world.advance(DriveStartRules.RETRY_MS);
        world.answer();
        assertTrue(world.writes.isEmpty());
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertEquals(Collections.singletonList(DriveStartRules.Decision.TIMED_OUT), world.gaveUp);
    }

    /** 排着的时候设置关掉了、改成了别的模式：排着的那一件不写；改成别的模式的，再判时照新的写。 */
    @Test
    public void aQueuedWriteIsDroppedWhenTheSettingChanges() {
        world.holdAnswers = true;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertEquals(1, world.requests);
        world.setting = AppConfig.DRIVE_MODE_AT_START_OFF;
        world.answer();
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());

        world.setting = AppConfig.DRIVE_MODE_AT_START_COMFORT;
        start.screenWentDark();
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.setting = AppConfig.DRIVE_MODE_AT_START_SPORT;
        world.answer();
        assertTrue("排着的是舒适，不写", world.writes.isEmpty());
        world.holdAnswers = false;
        world.advance(DriveStartRules.RETRY_MS);
        assertEquals(Collections.singletonList(Signal.DRIVE_MODE_SPORT), world.writes);
    }

    /** 临写之前现读不到挡位：不写；一直读不到，隔一会儿再试、到头算了，一笔都没写。 */
    @Test
    public void anUnreadableLiveGearWritesNothing() {
        world.liveGear = null;
        start.screenWentLit();
        world.advance(PLENTY);
        assertTrue(world.writes.isEmpty());
        assertTrue("隔一会儿才再试，不是连着试", world.requests <= DriveStartRules.WINDOW_MS / DriveStartRules.RETRY_MS + 1);
        assertEquals(Collections.singletonList(DriveStartRules.Decision.TIMED_OUT), world.gaveUp);
    }

    /** 现读不到挡位的那一下过去了：下一次试就写。 */
    @Test
    public void writesOnceTheLiveGearReadsParkAgain() {
        world.liveGear = null;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        assertTrue(world.writes.isEmpty());
        world.liveGear = SAME;
        world.advance(DriveStartRules.RETRY_MS);
        assertEquals(1, world.writes.size());
    }

    /** 排队时车断电了：轮到时不写，也不算写过一次；通电了照常写。 */
    @Test
    public void losingPowerWhileTheWriteIsQueuedWritesNothingAndCostsNoAttempt() {
        world.holdAnswers = true;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.ignition = Signal.IGNITION_ACC;
        world.answer();
        assertTrue(world.writes.isEmpty());
        assertTrue(world.gaveUp.isEmpty());

        world.holdAnswers = false;
        for (int i = 0; i < DriveStartRules.MAX_WRITES - 1; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        world.ignition = Signal.IGNITION_ON;
        world.advance(DriveStartRules.WINDOW_MS - DriveStartRules.SETTLE_MS);
        assertEquals("没写的那一次不占次数", DriveStartRules.MAX_WRITES, world.writes.size());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void writesNothingWhileTheUserHasExitedAndCatchesUpWhenTheyReturn() {
        world.exited = true;
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(0, world.requests);
        assertTrue("这一次上车先记着", world.awaitingNextDrive);
        assertTrue(world.gaveUp.isEmpty());

        world.exited = false;
        start.userReturned();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
        assertFalse(world.awaitingNextDrive);

        // 再叫一次（没有记着的上车）：不写
        start.userReturned();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void returningWhileTheScreenIsDarkWaitsForTheScreen() {
        start.screenWentDark();
        start.userReturned();
        world.advance(PLENTY);
        assertEquals(0, world.requests);
        start.screenWentLit();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
    }

    @Test
    public void exitingWhileWaitingStopsQuietly() {
        world.connected = false;
        start.screenWentLit();
        world.advance(DriveStartRules.SETTLE_MS);
        world.exited = true;
        world.connected = true;
        world.advance(PLENTY);
        assertEquals(0, world.requests);
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void aProcessStartedWhileExitedCatchesUpWhenTheUserOpensTheApp() {
        world.exited = true;
        world.awaitingNextDrive = true;
        DriveStart next = new DriveStart(world);
        next.processStarted(false, false);
        world.advance(PLENTY);
        assertEquals(0, world.requests);
        world.exited = false;
        next.userReturned();
        world.advance(PLENTY);
        assertEquals(1, world.writes.size());
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
