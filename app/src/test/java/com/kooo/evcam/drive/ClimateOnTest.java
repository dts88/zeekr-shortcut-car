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
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 前排空调重新打开后恢复循环模式、二排空调的一遍怎么走（{@link ClimateOn}）：钟、车辆信号、读车写车都是假的，
 * 只看什么时候读、什么时候写、写几次。
 */
public class ClimateOnTest {

    /** 假的外面：钟要 {@link #advance} 才走；写车照 {@link #results} 里排好的结果回话（排完了的都算成）。 */
    private static final class FakeWorld implements ClimateOn.World {
        long now = 1_000_000L;
        final List<long[]> due = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        String circulation = AppConfig.CIRCULATION_AT_CLIMATE_ON_INNER;
        boolean rear = true;
        boolean connected = true;
        Integer ignition = Signal.IGNITION_ON;
        /** 前排空调的电源此刻读到什么。 */
        Integer power = 1;
        int reads;
        /** 每一次写车要的是什么：「循环模式,二排」。 */
        final List<String> restores = new ArrayList<>();
        final List<CarMoves.Result> results = new ArrayList<>();
        /** 只给二排空调排的结果（排了就不看 {@link #results} 里的）。 */
        final List<CarMoves.Result> rearResults = new ArrayList<>();
        boolean exited;
        /** 写车的结果先不回，拿在手里（{@link #answer}）。 */
        boolean holdAnswers;
        final List<Runnable> pending = new ArrayList<>();
        int turnedOff;
        /** 写满次数都没成的每一次写了几次。 */
        final List<Integer> gaveUp = new ArrayList<>();
        boolean circulationPending;
        boolean rearPending;

        /** 确认关过，还有没恢复的。 */
        boolean awaiting() {
            return circulationPending || rearPending;
        }

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

        /** 前排空调的电源变成这个，再读这么多次。 */
        void read(Integer value, int times) {
            power = value;
            advance(times * ClimateOnRules.POLL_MS);
        }

        @Override
        public String circulationSetting() {
            return circulation;
        }

        @Override
        public boolean rearSetting() {
            return rear;
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
        public void readFrontPower(Consumer<Integer> seen) {
            reads++;
            seen.accept(power);
        }

        @Override
        public boolean userExited() {
            return exited;
        }

        @Override
        public void restore(int circulation, boolean rear, BiConsumer<CarMoves.Result, CarMoves.Result> done) {
            restores.add(circulation + "," + rear);
            CarMoves.Result both = results.isEmpty() ? CarMoves.Result.DONE : results.remove(0);
            CarMoves.Result first = circulation != 0 ? both : null;
            CarMoves.Result second = !rear ? null : rearResults.isEmpty() ? both : rearResults.remove(0);
            if (holdAnswers) {
                pending.add(() -> done.accept(first, second));
            } else {
                done.accept(first, second);
            }
        }

        void answer() {
            pending.remove(0).run();
        }

        @Override
        public void turnedOff() {
            turnedOff++;
        }

        @Override
        public void gaveUp(int writes) {
            gaveUp.add(writes);
        }

        @Override
        public boolean circulationPending() {
            return circulationPending;
        }

        @Override
        public boolean rearPending() {
            return rearPending;
        }

        @Override
        public void setPending(boolean circulation, boolean rear) {
            circulationPending = circulation;
            rearPending = rear;
        }
    }

    private final FakeWorld world = new FakeWorld();
    private final ClimateOn climate = new ClimateOn(world);

    /** 开始读，先读掉开头那一次（读到的是 {@link FakeWorld#power} 此刻的值）。 */
    private void start() {
        climate.start();
    }

    @Test
    public void restoresOnceAfterTheClimateGoesOffAndComesBackOn() {
        start();
        world.read(1, 5);
        assertTrue("一直开着不写", world.restores.isEmpty());

        world.read(0, 2);
        assertEquals(1, world.turnedOff);
        assertTrue(world.awaiting());
        assertTrue("关着不写", world.restores.isEmpty());

        world.read(1, 1);
        assertTrue("只读到一次开着不写", world.restores.isEmpty());
        world.read(1, 1);
        assertEquals(1, world.restores.size());
        assertEquals(CarMoves.CIRCULATION_INNER + ",true", world.restores.get(0));
        assertFalse(world.awaiting());

        // 之后手动改回去：空调没再关过，不再写
        world.read(1, 30);
        assertEquals(1, world.restores.size());
        assertEquals(1, world.turnedOff);
    }

    @Test
    public void everyOffAndOnRestoresAgain() {
        start();
        world.read(0, 2);
        world.read(1, 2);
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(2, world.restores.size());
        assertEquals(2, world.turnedOff);
    }

    @Test
    public void anOffShorterThanTwoReadingsIsMissed() {
        start();
        world.read(1, 2);
        world.read(0, 1);
        world.read(1, 10);
        assertTrue(world.restores.isEmpty());
        assertEquals(0, world.turnedOff);
    }

    @Test
    public void passesOnlyTheSettingsThatAreOn() {
        world.circulation = AppConfig.CIRCULATION_AT_CLIMATE_ON_OUTER;
        world.rear = false;
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(CarMoves.CIRCULATION_OUTER + ",false", world.restores.get(0));

        world.circulation = AppConfig.CIRCULATION_AT_CLIMATE_ON_OFF;
        world.rear = true;
        world.read(0, 2);
        world.read(1, 2);
        assertEquals("0,true", world.restores.get(1));
    }

    @Test
    public void doesNotReadTheCarWhileBothSettingsAreOff() {
        world.circulation = AppConfig.CIRCULATION_AT_CLIMATE_ON_OFF;
        world.rear = false;
        world.circulationPending = true;
        world.rearPending = true;
        start();
        world.read(0, 5);
        world.read(1, 5);
        assertEquals(0, world.reads);
        assertTrue(world.restores.isEmpty());
        assertFalse("关着时不记着在等", world.awaiting());

        // 打开设置：从头看，空调此刻开着不写
        world.rear = true;
        world.read(1, 5);
        assertTrue(world.reads > 0);
        assertTrue(world.restores.isEmpty());
    }

    @Test
    public void doesNotReadWhileTheCarIsNotPoweredOrNotConnected() {
        world.ignition = Signal.IGNITION_ACC;
        start();
        world.read(0, 5);
        assertEquals(0, world.reads);
        world.ignition = Signal.IGNITION_ON;
        world.connected = false;
        world.read(0, 5);
        assertEquals(0, world.reads);
        assertEquals(0, world.turnedOff);
    }

    @Test
    public void aGapInPowerStartsTheCountAgain() {
        start();
        world.read(0, 1);
        world.ignition = Signal.IGNITION_ACC;
        world.advance(ClimateOnRules.POLL_MS);
        world.ignition = Signal.IGNITION_ON;
        world.read(0, 1);
        assertEquals("中间断过，两次关着不算连着", 0, world.turnedOff);
        world.read(0, 1);
        assertEquals(1, world.turnedOff);
    }

    @Test
    public void retriesAFailedWriteThenStops() {
        world.results.add(CarMoves.Result.UNAVAILABLE);
        world.results.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        world.advance(ClimateOnRules.RETRY_MS - ClimateOnRules.POLL_MS);
        assertEquals("没隔够不再写", 1, world.restores.size());
        world.advance(60_000);
        assertEquals(3, world.restores.size());
        assertTrue("第三次成了", world.gaveUp.isEmpty());
        assertFalse(world.awaiting());
    }

    @Test
    public void givesUpAfterTheLastFailedWriteUntilTheNextOffAndOn() {
        for (int i = 0; i < ClimateOnRules.MAX_WRITES; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        start();
        world.read(0, 2);
        world.read(1, 60);
        assertEquals(ClimateOnRules.MAX_WRITES, world.restores.size());
        assertEquals(1, world.gaveUp.size());
        assertEquals(ClimateOnRules.MAX_WRITES, (int) world.gaveUp.get(0));
        assertFalse(world.awaiting());

        world.read(0, 2);
        world.read(1, 2);
        assertEquals(ClimateOnRules.MAX_WRITES + 1, world.restores.size());
    }

    @Test
    public void goingOffAgainStartsTheWriteCountAgain() {
        for (int i = 0; i < ClimateOnRules.MAX_WRITES + 1; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        start();
        world.read(0, 2);
        world.read(1, 2);
        world.advance(ClimateOnRules.RETRY_MS);
        assertEquals(2, world.restores.size());
        world.read(0, 2);
        world.read(1, 60);
        assertEquals("关过之后又是最多这么多次", 2 + ClimateOnRules.MAX_WRITES, world.restores.size());
    }

    /** 循环模式做成了、二排空调没成：再写只写二排空调，循环模式之后手动改了的不动。 */
    @Test
    public void aPartialFailureRetriesOnlyWhatFailed() {
        world.rearResults.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(CarMoves.CIRCULATION_INNER + ",true", world.restores.get(0));
        assertTrue("还有一样没成", world.awaiting());
        world.advance(ClimateOnRules.RETRY_MS);
        assertEquals(2, world.restores.size());
        assertEquals("0,true", world.restores.get(1));
        assertFalse(world.awaiting());

        // 下一次关掉再打开：两样又都写
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(CarMoves.CIRCULATION_INNER + ",true", world.restores.get(2));
    }

    /** 写了没成之后只读到一次关着：不算又关过，写的次数接着算，一共还是最多那么多次。 */
    @Test
    public void oneOffReadingDoesNotStartTheWriteCountAgain() {
        for (int i = 0; i < 10; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        for (int i = 0; i < 5; i++) {
            world.read(0, 1);
            world.read(1, 5);
        }
        assertEquals(ClimateOnRules.MAX_WRITES, world.restores.size());
        assertEquals(1, world.gaveUp.size());
    }

    /** 一样恢复了、另一样没成，这时熄屏再亮屏：空调没再关过，只写没成的那一样。 */
    @Test
    public void aPartialFailureIsRememberedAcrossTheScreenGoingDark() {
        world.rearResults.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(CarMoves.CIRCULATION_INNER + ",true", world.restores.get(0));
        climate.stop();
        climate.start();
        world.read(1, 2);
        assertEquals(2, world.restores.size());
        assertEquals("循环模式已经恢复过，不再写", "0,true", world.restores.get(1));
        assertFalse(world.awaiting());
    }

    /** 同上，写车的结果是熄屏之后才回来的：循环模式做成了照样记下，亮屏后只写二排空调。 */
    @Test
    public void aResultDeliveredAfterTheScreenWentDarkStillCounts() {
        world.holdAnswers = true;
        world.rearResults.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        climate.stop();
        world.answer();
        assertFalse(world.circulationPending);
        assertTrue(world.rearPending);

        world.holdAnswers = false;
        climate.start();
        world.read(1, 2);
        assertEquals(2, world.restores.size());
        assertEquals("0,true", world.restores.get(1));
    }

    /** 结果回来之前空调又确认关过一回：那是上一回的结果，不算数，这一回两样都写。 */
    @Test
    public void aResultFromBeforeTheClimateWentOffAgainIsNotCounted() {
        world.holdAnswers = true;
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        climate.stop();
        climate.start();
        world.read(0, 2);
        world.answer();
        assertTrue(world.circulationPending);
        assertTrue(world.rearPending);
        world.holdAnswers = false;
        world.read(1, 2);
        assertEquals(CarMoves.CIRCULATION_INNER + ",true", world.restores.get(1));
    }

    /** 同上，换成进程重启。 */
    @Test
    public void aPartialFailureIsRememberedByANewProcess() {
        world.rearResults.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        climate.stop();

        ClimateOn next = new ClimateOn(world);
        next.start();
        world.advance(2 * ClimateOnRules.POLL_MS);
        assertEquals(2, world.restores.size());
        assertEquals("0,true", world.restores.get(1));
    }

    /** 二排空调没成、等着再写的时候把它的设置关掉了：不再写它（循环模式的设置还开着也一样）。 */
    @Test
    public void aPendingRetryDropsASettingThatWasSwitchedOff() {
        world.rearResults.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        world.rear = false;
        world.read(1, 60);
        assertEquals(1, world.restores.size());
        assertFalse(world.awaiting());
        assertTrue(world.gaveUp.isEmpty());
    }

    /** 写满了次数当场算了，不用再读到一次开着。 */
    @Test
    public void givesUpAtTheLastFailedWriteWithoutAnotherReading() {
        for (int i = 0; i < ClimateOnRules.MAX_WRITES; i++) {
            world.results.add(CarMoves.Result.NO_CHANGE);
        }
        start();
        world.read(0, 2);
        while (world.restores.size() < ClimateOnRules.MAX_WRITES) {
            world.read(1, 1);
        }
        assertEquals(1, world.gaveUp.size());
        assertFalse(world.awaiting());
    }

    @Test
    public void doesNotReadWhileTheUserHasExited() {
        world.exited = true;
        start();
        world.read(0, 5);
        world.read(1, 5);
        assertEquals(0, world.reads);
        assertTrue(world.restores.isEmpty());

        world.exited = false;
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
    }

    @Test
    public void stopsReadingWhileTheScreenIsDarkAndRemembersItWasOff() {
        start();
        world.read(0, 2);
        assertTrue(world.awaiting());
        climate.stop();
        int reads = world.reads;
        world.read(1, 30);
        assertEquals("熄屏不读", reads, world.reads);
        assertTrue(world.restores.isEmpty());

        climate.start();
        world.read(1, 2);
        assertEquals(1, world.restores.size());
    }

    @Test
    public void aNewProcessStillRestoresWhenTheClimateWasLeftOff() {
        start();
        world.read(0, 2);
        climate.stop();

        ClimateOn next = new ClimateOn(world);
        world.power = 1;
        next.start();
        world.advance(2 * ClimateOnRules.POLL_MS);
        assertEquals(1, world.restores.size());
        assertFalse(world.awaiting());
    }

    @Test
    public void aWriteAnsweredAfterTheScreenWentDarkIsIgnored() {
        world.holdAnswers = true;
        world.results.add(CarMoves.Result.NO_CHANGE);
        start();
        world.read(0, 2);
        world.read(1, 2);
        assertEquals(1, world.restores.size());
        climate.stop();
        world.answer();
        world.advance(60_000);
        assertEquals(1, world.restores.size());
        assertTrue(world.gaveUp.isEmpty());
    }

    @Test
    public void doesNotReadAgainWhileAWriteIsOut() {
        world.holdAnswers = true;
        start();
        world.read(0, 2);
        world.read(1, 2);
        int reads = world.reads;
        world.advance(10 * ClimateOnRules.POLL_MS);
        assertEquals(reads, world.reads);
        world.answer();
        world.advance(3 * ClimateOnRules.POLL_MS);
        assertTrue(world.reads > reads);
        assertEquals(1, world.restores.size());
    }
}
