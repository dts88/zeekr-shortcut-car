/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * 地点与自动化跑起来的那一个（{@link Automations}），对着假的车。上车、空调重新打开、陡坡缓降那几种情形照 Calvin 的
 * DriveStartTest、ClimateOnTest、HillDescentTest（PR 261），现在它们是三条规则。
 */
public class AutomationsTest {

    /** 假的外面：钟一下一下往前走，车上的值放在 {@link #car} 里。 */
    static final class FakeWorld implements Automations.World {
        long now;
        private long seq;
        private final List<Object[]> tasks = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        boolean link = true;
        Integer ignition = Signal.IGNITION_ON;
        String gear = "P";
        /** 写车的线程上现读到的挡位；null 就照 {@link #gear}。 */
        String liveGear;
        Integer battery = 80;
        int minute = 10 * 60;
        int weekday;
        boolean exited;
        final Map<Setting, Integer> car = new EnumMap<>(Setting.class);
        final Map<Setting, Integer> availability = new EnumMap<>(Setting.class);
        CarMoves.Result result = CarMoves.Result.DONE;
        final List<String> writes = new ArrayList<>();
        final List<String> restores = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        boolean awaiting;
        final Map<Setting, Integer> confirmed = new EnumMap<>(Setting.class);
        boolean readingsWanted;
        final List<Place> places = new ArrayList<>();
        Double lat;
        Double lon;
        Float accuracy = 10f;
        Long fixAt;
        Float speed = 0f;

        /** 一次新的定位（此刻定的）。 */
        void at(double latitude, double longitude) {
            lat = latitude;
            lon = longitude;
            fixAt = now;
        }

        /** 往前走 {@code ms}，到点的事照先后做。 */
        void run(long ms) {
            long end = now + ms;
            while (true) {
                Object[] next = null;
                for (Object[] t : tasks) {
                    if ((long) t[0] <= end && (next == null || (long) t[0] < (long) next[0]
                            || ((long) t[0] == (long) next[0] && (long) t[1] < (long) next[1]))) {
                        next = t;
                    }
                }
                if (next == null) {
                    break;
                }
                tasks.remove(next);
                now = (long) next[0];
                ((Runnable) next[2]).run();
            }
            now = end;
        }

        boolean noted(String part) {
            for (String n : notes) {
                if (n.contains(part)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void later(long delayMs, Runnable action) {
            tasks.add(new Object[]{now + delayMs, seq++, action});
        }

        @Override
        public List<Rule> rules() {
            return rules;
        }

        @Override
        public boolean linkConnected() {
            return link;
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
        public Integer battery() {
            return battery;
        }

        @Override
        public int minuteOfDay() {
            return minute;
        }

        @Override
        public int weekday() {
            return weekday;
        }

        @Override
        public boolean userExited() {
            return exited;
        }

        @Override
        public void read(Setting setting, Consumer<Integer> seen) {
            later(0, () -> seen.accept(car.get(setting)));
        }

        @Override
        public void available(Setting setting, Consumer<Integer> seen) {
            later(0, () -> seen.accept(availability.getOrDefault(setting, 1)));
        }

        @Override
        public void write(String key, Setting setting, int value, BiPredicate<Integer, String> stillWanted,
                          Consumer<CarMoves.Outcome> done) {
            later(0, () -> {
                Integer before = car.get(setting);
                if (before != null && before == value) {
                    done.accept(new CarMoves.Outcome(CarMoves.Result.DONE, before));
                    return;
                }
                if (!stillWanted.test(ignition, liveGear != null ? liveGear : gear)) {
                    done.accept(new CarMoves.Outcome(CarMoves.Result.MOVING, before));
                    return;
                }
                writes.add(setting.key + "=" + setting.value(value));
                if (result == CarMoves.Result.DONE) {
                    car.put(setting, value);
                }
                done.accept(new CarMoves.Outcome(result, before));
            });
        }

        @Override
        public void restore(String key, Setting setting, int ours, int previous) {
            restores.add(setting.key + "=" + setting.value(previous));
            Integer cur = car.get(setting);
            if (cur != null && cur == ours) {
                car.put(setting, previous);
            }
        }

        @Override
        public void note(String line) {
            notes.add(line);
        }

        @Override
        public boolean awaitingNextDrive() {
            return awaiting;
        }

        @Override
        public void setAwaitingNextDrive(boolean awaiting) {
            this.awaiting = awaiting;
        }

        @Override
        public Integer confirmed(Setting setting) {
            return confirmed.get(setting);
        }

        @Override
        public void setConfirmed(Setting setting, Integer value) {
            confirmed.put(setting, value);
        }

        @Override
        public void wantReadings(boolean want) {
            readingsWanted = want;
        }

        @Override
        public List<Place> places() {
            return places;
        }

        @Override
        public Automations.Position position() {
            return new Automations.Position(lat, lon, accuracy, fixAt, speed);
        }
    }

    private int ids;

    private Rule rule(Trigger trigger, List<Condition> conditions, boolean restore, Change... changes) {
        return new Rule("r" + (++ids), null, true, trigger, conditions, Arrays.asList(changes), restore);
    }

    private Rule rule(Trigger trigger, Change... changes) {
        return rule(trigger, Collections.emptyList(), false, changes);
    }

    // ================================================================= 上车（Calvin：上车时切驾驶模式）

    @Test
    public void getInWritesOnceAfterSettling() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(RunRules.SETTLE_MS - 100);
        assertTrue("先等 2 秒", w.writes.isEmpty());
        w.run(600);
        assertEquals(Collections.singletonList("drive_mode=comfort"), w.writes);
        w.run(30_000);
        assertEquals("一次上车只写一次", 1, w.writes.size());
        assertFalse("没被改回去，不记", w.noted("改回去了"));
    }

    @Test
    public void nothingIsWrittenWithoutRules() {
        FakeWorld w = new FakeWorld();
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
        assertFalse("上车用掉了", w.awaiting);
    }

    @Test
    public void leavingParkBeforeTheWriteSkipsIt() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.gear = "D";
        new Automations(w).screenWentLit();
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.noted("已挂出 P 挡"));
        w.gear = "P";
        w.run(30_000);
        assertTrue("挂回 P 挡也不写", w.writes.isEmpty());
    }

    /** 临写之前从车上现读到不是 P：不写，这一次就此不写（不等发布出来的挡位跟上）。 */
    @Test
    public void theGearReadFromTheCarJustBeforeWritingEndsIt() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.liveGear = "D";
        new Automations(w).screenWentLit();
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.noted("临写前读到已挂出 P 挡"));
        w.liveGear = null;
        w.run(30_000);
        assertTrue(w.writes.isEmpty());
    }

    @Test
    public void notReadyWithinThirtySecondsIsDropped() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.link = false;
        new Automations(w).screenWentLit();
        w.run(RunRules.WINDOW_MS + 1_000);
        assertTrue(w.noted("没就绪"));
        w.link = true;
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
    }

    @Test
    public void retriesThreeTimesThreeSecondsApart() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.result = CarMoves.Result.NO_CHANGE;
        new Automations(w).screenWentLit();
        w.run(RunRules.SETTLE_MS + 100);
        assertEquals(1, w.writes.size());
        w.run(RunRules.RETRY_MS - 600);
        assertEquals("不到 3 秒不再写", 1, w.writes.size());
        w.run(20_000);
        assertEquals(RunRules.MAX_WRITES, w.writes.size());
        assertTrue(w.noted("都没成"));
    }

    @Test
    public void processStartCountsOnlyAfterLeavingOrAReboot() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        new Automations(w).processStarted(false, false);
        w.run(10_000);
        assertTrue("行驶中重启应用不算上车", w.writes.isEmpty());

        w.awaiting = true;
        new Automations(w).processStarted(false, false);
        w.run(10_000);
        assertEquals(1, w.writes.size());
        assertFalse(w.awaiting);

        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        new Automations(w).processStarted(false, true);
        w.run(10_000);
        assertEquals("车机开过机", 2, w.writes.size());

        new Automations(w).processStarted(true, true);
        w.run(10_000);
        assertEquals("屏幕黑着不算", 2, w.writes.size());
    }

    @Test
    public void userExitHoldsTheGetInUntilTheAppIsOpenedAgain() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.exited = true;
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.awaiting);
        w.exited = false;
        a.userReturned();
        w.run(5_000);
        assertEquals(1, w.writes.size());
    }

    @Test
    public void leavingTheCarEndsTheGetIn() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        a.screenWentDark();
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.awaiting);
    }

    // ================================================================= 车辆状态（Calvin：空调重新打开后恢复）

    @Test
    public void climateBackOnRestoresCirculationAndRearClimate() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.state(Setting.FRONT_CLIMATE, "on"),
                Change.of(Setting.CIRCULATION, "inner"), Change.of(Setting.REAR_CLIMATE, "on")));
        w.car.put(Setting.FRONT_CLIMATE, 1);
        w.car.put(Setting.CIRCULATION, CarMoves.CIRCULATION_AUTO);
        w.car.put(Setting.REAR_CLIMATE, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(3_000);
        assertTrue("第一次读到不算变化", w.writes.isEmpty());

        w.car.put(Setting.FRONT_CLIMATE, 0);
        w.run(3_000);
        w.car.put(Setting.FRONT_CLIMATE, 1);
        w.run(3_000);
        assertEquals(Arrays.asList("circulation=inner", "rear_climate=on"), w.writes);

        // 空调关着时下车、进程没了；回来打开空调照样恢复（确认过的值存着）
        w.writes.clear();
        w.car.put(Setting.FRONT_CLIMATE, 0);
        w.car.put(Setting.CIRCULATION, CarMoves.CIRCULATION_AUTO);
        w.car.put(Setting.REAR_CLIMATE, 0);
        w.run(3_000);
        a.screenWentDark();
        w.car.put(Setting.FRONT_CLIMATE, 1);
        new Automations(w).processStarted(false, false);
        w.run(3_000);
        assertEquals(Arrays.asList("circulation=inner", "rear_climate=on"), w.writes);
    }

    /** 读一次关着不算（车机没数据时也读成 0）；读不到不算变化。 */
    @Test
    public void oneReadingIsNotAChange() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.state(Setting.FRONT_CLIMATE, "on"), Change.of(Setting.REAR_CLIMATE, "on")));
        w.car.put(Setting.FRONT_CLIMATE, 1);
        w.car.put(Setting.REAR_CLIMATE, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(3_000);
        w.car.put(Setting.FRONT_CLIMATE, 0);
        w.run(RunRules.WATCH_MS);
        w.car.put(Setting.FRONT_CLIMATE, null);
        w.run(2 * RunRules.WATCH_MS);
        w.car.put(Setting.FRONT_CLIMATE, 1);
        w.run(5_000);
        assertTrue(w.writes.isEmpty());
    }

    /** 没有开着的规则要看：不读车，也不要电量。 */
    @Test
    public void nothingIsWatchedWithoutRulesThatNeedIt() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.REAR_CLIMATE, "on")).withEnabled(false));
        w.rules.add(rule(Trigger.state(Setting.FRONT_CLIMATE, "on"), Change.of(Setting.REAR_CLIMATE, "on"))
                .withEnabled(false));
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(10_000);
        assertTrue(w.confirmed.isEmpty());
        assertFalse(w.readingsWanted);
    }

    /** 自动化写的值不算车辆状态的变化：不连锁；人改的照样算。 */
    @Test
    public void ownWritesDoNotTriggerOtherRules() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "sport")));
        w.rules.add(rule(Trigger.state(Setting.DRIVE_MODE, "sport"), Change.of(Setting.REAR_CLIMATE, "on")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.car.put(Setting.REAR_CLIMATE, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(8_000);
        assertEquals(Collections.singletonList("drive_mode=sport"), w.writes);
        assertTrue(w.noted("是自动化写的"));

        w.run(RunRules.OWN_WRITE_MS);
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        w.run(3_000);
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_SPORT);
        w.run(3_000);
        assertEquals(Arrays.asList("drive_mode=sport", "rear_climate=on"), w.writes);
    }

    // ================================================================= 挡位、结束时恢复

    @Test
    public void reversingTurnsLowSpeedBrakingOffAndBackOn() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("R"), Change.of(Setting.LOW_SPEED_BRAKING, "off")));
        w.car.put(Setting.LOW_SPEED_BRAKING, 1);
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "R";
        a.readingsChanged();
        w.run(100);
        assertEquals(Collections.singletonList("low_speed_braking=off"), w.writes);
        assertTrue(w.restores.isEmpty());

        w.gear = "D";
        a.readingsChanged();
        w.run(100);
        assertEquals("安全功能：没勾也恢复", Collections.singletonList("low_speed_braking=on"), w.restores);
        assertEquals(1, (int) w.car.get(Setting.LOW_SPEED_BRAKING));
    }

    /**
     * 只在人在车上时运作（项目所有者 2026-11-22）：熄屏时挂挡（车主遥控挪车，车里没人）不触发；人上车以后照常。
     */
    @Test
    public void aGearChangeWithNobodyInTheCarDoesNotFire() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("P"), Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.ignition = Signal.IGNITION_DRIVING;
        Automations a = new Automations(w);
        a.processStarted(true, false);
        a.readingsChanged();
        w.gear = "D";
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(10_000);
        assertTrue("车里没人：不写车", w.writes.isEmpty());

        a.screenWentLit();
        w.gear = "D";
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(10_000);
        assertEquals(Collections.singletonList("sentry=on"), w.writes);
    }

    /** 熄屏那一下：还在走的、等着恢复的一律放下，不写车 —— 关掉的安全功能也不在人下车以后去恢复（车自己会重新打开）。 */
    @Test
    public void leavingTheCarDropsEverythingWithoutWriting() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("R"), Change.of(Setting.LOW_SPEED_BRAKING, "off")));
        w.car.put(Setting.LOW_SPEED_BRAKING, 1);
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "R";
        a.readingsChanged();
        w.run(100);
        assertEquals(Collections.singletonList("low_speed_braking=off"), w.writes);

        a.screenWentDark();
        w.gear = "P";
        a.readingsChanged();
        w.run(10_000);
        assertTrue("人下车了：不恢复", w.restores.isEmpty());
        assertEquals(1, w.writes.size());
    }

    /** 本来就是关着的（人自己关的）：不写，也不恢复。 */
    @Test
    public void nothingIsRestoredWhenItWasAlreadySo() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("R"), Change.of(Setting.LOW_SPEED_BRAKING, "off")));
        w.car.put(Setting.LOW_SPEED_BRAKING, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "R";
        a.readingsChanged();
        w.run(100);
        w.gear = "D";
        a.readingsChanged();
        w.run(100);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.restores.isEmpty());
    }

    /** 没勾恢复的普通动作：离开那一挡不恢复。 */
    @Test
    public void untickedRestoreLeavesItAlone() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("P"), Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.gear = "D";
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(100);
        assertEquals(Collections.singletonList("sentry=on"), w.writes);
        w.gear = "D";
        a.readingsChanged();
        w.run(100);
        assertTrue(w.restores.isEmpty());
    }

    // ================================================================= 条件

    @Test
    public void conditionsAreCheckedOnce() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("P"), Collections.singletonList(Condition.batteryAtLeast(20)), false,
                Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.battery = 10;
        w.gear = "D";
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(1_000);
        assertTrue(w.writes.isEmpty());
        assertTrue(w.noted("条件 1（battery>=20）不成立"));
        w.battery = 50;
        w.run(10_000);
        assertTrue("判一次就算了", w.writes.isEmpty());
    }

    @Test
    public void anUnreadableConditionWaitsThenGivesUp() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("P"), Collections.singletonList(Condition.batteryAtLeast(20)), false,
                Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.battery = null;
        w.gear = "D";
        Automations a = new Automations(w);
        a.screenWentLit();
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(5_000);
        assertTrue(w.writes.isEmpty());
        w.battery = 60;
        w.run(1_000);
        assertEquals("读到了就判", Collections.singletonList("sentry=on"), w.writes);

        FakeWorld never = new FakeWorld();
        never.rules.add(rule(Trigger.gear("P"), Collections.singletonList(Condition.batteryAtLeast(20)), false,
                Change.of(Setting.SENTRY, "on")));
        never.battery = null;
        never.gear = "D";
        Automations b = new Automations(never);
        b.screenWentLit();
        b.readingsChanged();
        never.gear = "P";
        b.readingsChanged();
        never.run(RunRules.WINDOW_MS + 1_000);
        assertTrue(never.noted("读不到"));
    }

    @Test
    public void timeAndWeekdayConditions() {
        Condition night = Condition.timeBetween(22 * 60, 6 * 60);
        Condition weekdays = Condition.weekdays(0x1F);
        FakeWorld w = new FakeWorld();
        Condition.Facts facts = new Condition.Facts() {
            @Override
            public String gear() {
                return w.gear;
            }

            @Override
            public Integer battery() {
                return w.battery;
            }

            @Override
            public Integer state(Setting setting) {
                return null;
            }

            @Override
            public int minuteOfDay() {
                return w.minute;
            }

            @Override
            public int weekday() {
                return w.weekday;
            }

            @Override
            public Boolean inPlace(String id) {
                return null;
            }
        };
        w.minute = 23 * 60;
        assertTrue(night.holds(facts));
        w.minute = 5 * 60 + 59;
        assertTrue(night.holds(facts));
        w.minute = 6 * 60;
        assertFalse(night.holds(facts));
        w.weekday = 4;
        assertTrue(weekdays.holds(facts));
        w.weekday = 5;
        assertFalse(weekdays.holds(facts));
    }

    /** 要读电量的规则开着、屏幕亮着才要电量（浮点读数要登记完整的车辆信号）。 */
    @Test
    public void batteryIsWantedOnlyWhileARuleNeedsIt() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.gear("P"), Collections.singletonList(Condition.batteryAtLeast(20)), false,
                Change.of(Setting.SENTRY, "on")));
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(2_000);
        assertTrue(w.readingsWanted);
        a.screenWentDark();
        assertFalse(w.readingsWanted);
    }

    // ================================================================= 等能用（Calvin：陡坡缓降）

    @Test
    public void hillDescentWaitsUntilItCanBeSwitchedOn() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.HILL_DESCENT, "on")));
        w.car.put(Setting.HILL_DESCENT, 0);
        w.availability.put(Setting.HILL_DESCENT, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(RunRules.WINDOW_MS + 10_000);
        assertTrue("车机说不能用时不写，也不受 30 秒的时限", w.writes.isEmpty());
        w.availability.put(Setting.HILL_DESCENT, 1);
        w.run(4_000);
        assertEquals(Collections.singletonList("hill_descent=on"), w.writes);

        // 之后人关掉、又能用了：这一次上车不再写
        w.car.put(Setting.HILL_DESCENT, 0);
        w.run(10_000);
        assertEquals(1, w.writes.size());
    }

    @Test
    public void leavingTheCarStopsWaitingForAvailability() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.HILL_DESCENT, "on")));
        w.car.put(Setting.HILL_DESCENT, 0);
        w.availability.put(Setting.HILL_DESCENT, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(5_000);
        a.screenWentDark();
        w.availability.put(Setting.HILL_DESCENT, 1);
        w.run(10_000);
        assertTrue(w.writes.isEmpty());
    }

    // ================================================================= 先后

    /** 同一下有几条改同一项：照列表的先后写，后面的为准。 */
    @Test
    public void rulesRunInListOrder() {
        FakeWorld w = new FakeWorld();
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "comfort")));
        w.rules.add(rule(Trigger.getIn(), Change.of(Setting.DRIVE_MODE, "sport")));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        new Automations(w).screenWentLit();
        w.run(5_000);
        assertEquals(Arrays.asList("drive_mode=comfort", "drive_mode=sport"), w.writes);
        assertEquals(Signal.DRIVE_MODE_SPORT, (int) w.car.get(Setting.DRIVE_MODE));
    }

    // ================================================================= 地点

    /** 地点「家」：中心 (10, 20)，半径 200 m（余量 25 m）。纬度差 0.001° 约 111 m。 */
    private static final Place HOME = Place.of("home", "家", 10d, 20d, 200);
    private static final double INSIDE = 10.0005;   // 约 56 m
    private static final double EDGE = 10.0019;     // 约 211 m：误差 10 m 时判不清
    private static final double OUTSIDE = 10.0100;  // 约 1.1 km

    private FakeWorld placeWorld(Rule rule) {
        FakeWorld w = new FakeWorld();
        w.places.add(HOME);
        w.rules.add(rule);
        return w;
    }

    /** 亮屏后第一次判出来的只当起点；之后从外到里是到达，从里到外是离开，到达那几次跟着结束、恢复。 */
    @Test
    public void arrivingAndLeavingAPlace() {
        FakeWorld w = placeWorld(rule(Trigger.arrive("home"), Collections.emptyList(), true,
                Change.of(Setting.REAR_CLIMATE, "on")));
        w.car.put(Setting.REAR_CLIMATE, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        assertTrue("要地点：登记完整的车辆信号（定位）", w.readingsWanted);
        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        assertTrue(w.noted("地点 1：不在（这一趟的起点"));
        w.run(1_000);
        assertTrue(w.writes.isEmpty());

        w.at(INSIDE, 20d);
        a.readingsChanged();
        w.run(2_000);
        assertTrue(w.noted("地点 1：到达"));
        assertEquals(Collections.singletonList("rear_climate=on"), w.writes);

        w.at(EDGE, 20d);
        a.readingsChanged();
        w.run(1_000);
        assertTrue("在边上（没出 r + m）还算在", w.restores.isEmpty());

        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        w.run(1_000);
        assertTrue(w.noted("地点 1：离开"));
        assertEquals("离开时恢复", Collections.singletonList("rear_climate=off"), w.restores);
        assertFalse("黑匣子不写地点名", w.noted("家"));
    }

    @Test
    public void startingInsideIsNotAnArrival() {
        FakeWorld w = placeWorld(rule(Trigger.arrive("home"), Change.of(Setting.REAR_CLIMATE, "on")));
        w.car.put(Setting.REAR_CLIMATE, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        w.at(INSIDE, 20d);
        a.readingsChanged();
        w.run(5_000);
        assertTrue(w.noted("地点 1：在里面（这一趟的起点"));
        assertTrue(w.writes.isEmpty());
    }

    /** 判不清（误差大、在边上）不变：不当成到达，也不当成离开。 */
    @Test
    public void anUnsureFixChangesNothing() {
        FakeWorld w = placeWorld(rule(Trigger.leave("home"), Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        w.at(INSIDE, 20d);
        a.readingsChanged();
        w.accuracy = 5_000f;
        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        w.run(2_000);
        assertTrue("误差 5 km：判不清，不算离开", w.writes.isEmpty());
        w.accuracy = 10f;
        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        w.run(2_000);
        assertEquals(Collections.singletonList("sentry=on"), w.writes);
    }

    /** 熄屏忘掉这一趟判出来的：下一次亮屏在外面，是新的起点，不算离开。 */
    @Test
    public void darkForgetsWhereWeWere() {
        FakeWorld w = placeWorld(rule(Trigger.leave("home"), Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        w.at(INSIDE, 20d);
        a.readingsChanged();
        a.screenWentDark();
        assertFalse(w.readingsWanted);
        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        a.screenWentLit();
        w.run(1_000);
        a.readingsChanged();
        w.run(5_000);
        assertTrue(w.writes.isEmpty());
    }

    /** 「停在常用地点以外就开启哨兵模式」：挂入 P 挡 + 不在家。在家不开；判不清等，一直判不清算了。 */
    @Test
    public void notInPlacesCondition() {
        FakeWorld w = placeWorld(rule(Trigger.gear("P"),
                Collections.singletonList(Condition.notInPlaces(Collections.singletonList("home"))), false,
                Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.gear = "D";
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        w.at(INSIDE, 20d);
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(2_000);
        assertTrue("在家：不开", w.writes.isEmpty());
        assertTrue(w.noted("不成立"));

        w.gear = "D";
        a.readingsChanged();
        w.at(OUTSIDE, 20d);
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(2_000);
        assertEquals(Collections.singletonList("sentry=on"), w.writes);
    }

    @Test
    public void aPlaceConditionWaitsForAFix() {
        FakeWorld w = placeWorld(rule(Trigger.gear("P"),
                Collections.singletonList(Condition.inPlaces(Collections.singletonList("home"))), false,
                Change.of(Setting.SENTRY, "on")));
        w.car.put(Setting.SENTRY, 0);
        w.gear = "D";
        Automations a = new Automations(w);
        a.screenWentLit();
        w.run(1_000);
        a.readingsChanged();
        w.gear = "P";
        a.readingsChanged();
        w.run(3_000);
        assertTrue("还没有定位：等", w.writes.isEmpty());
        assertFalse(w.noted("没做"));
        w.at(INSIDE, 20d);
        a.readingsChanged();
        w.run(1_000);
        assertEquals(Collections.singletonList("sentry=on"), w.writes);
    }

    /** 关掉安全功能的动作配「离开某个地点」（不会结束、恢复不了）：整条不跑。 */
    @Test
    public void aSafetyOffRuleNeedsATriggerThatEnds() {
        assertFalse(rule(Trigger.leave("home"), Change.of(Setting.LOW_SPEED_BRAKING, "off")).complete());
        assertFalse(rule(Trigger.getIn(), Change.of(Setting.LOW_SPEED_BRAKING, "off")).complete());
        assertTrue(rule(Trigger.arrive("home"), Change.of(Setting.LOW_SPEED_BRAKING, "off")).complete());
        assertFalse("离开没有结束：勾了恢复也不恢复",
                rule(Trigger.leave("home"), Collections.emptyList(), true, Change.of(Setting.SENTRY, "on")).restores());
    }

    /** 黑匣子写第几条、什么触发；不写名字。 */
    @Test
    public void notesNameTheRuleByNumberOnly() {
        FakeWorld w = new FakeWorld();
        w.rules.add(new Rule("x", "家门口", true, Trigger.getIn(), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.DRIVE_MODE, "comfort")), false));
        w.car.put(Setting.DRIVE_MODE, Signal.DRIVE_MODE_STANDARD);
        new Automations(w).screenWentLit();
        w.run(5_000);
        assertTrue(w.noted("#1 get-in"));
        assertFalse(w.noted("家门口"));
    }
}
