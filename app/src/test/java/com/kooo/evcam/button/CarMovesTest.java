/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.CarAccess;
import com.kooo.evcam.telemetry.CarNames;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 灵动贴写车的那些动作（{@link CarMoves}）：换法、执行前的两样检查、读回怎么算。车辆接口、常量名都是假的
 * （号码随便编的，只看规则）。
 */
public class CarMovesTest {

    private static final int ALL = 0x80000000;
    private static final int DRIVER = 1;
    private static final int PASSENGER = 4;
    private static final int ROW_2_LEFT = 0x10;
    private static final int ROW_2_RIGHT = 0x40;

    private static final int HEAT = 0x1000;
    private static final int VENT = 0x2000;
    private static final int MASSAGE_SWITCH = 0x3000;
    private static final int MASSAGE = 0x3100;
    private static final int CIRCULATION = 0x4000;
    private static final int POWER_SAVING = 0x5000;
    private static final int LOCK = 0x6000;
    private static final int DAY_LIGHT = 0x7000;
    private static final int POSITION_LIGHT = 0x7100;
    private static final int FRAGRANCE_SWITCH = 0x8000;
    private static final int FRAGRANCE_LEVEL = 0x8100;
    private static final int FRAGRANCE_SLOT = 0x8200;
    private static final int FRAGRANCE_TYPE = 0x8300;
    private static final int VIEW_360 = 0x9000;
    private static final int MIRROR = 0xA000;

    /** 假的车辆接口：值按「号@区域」存；写了照写进去（{@link #applies}），也可以不受理。 */
    private static class FakeCar implements CarAccess {
        final Map<String, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean acceptZoned = true;
        boolean acceptPlain = true;
        /** 受理了也不变（这台车没有这个功能）。 */
        boolean applies = true;
        Float speed;

        void set(int id, int zone, int value) {
            values.put(id + "@" + zone, value);
        }

        Integer get(int id, int zone) {
            return values.get(id + "@" + zone);
        }

        @Override
        public Integer read(int id) {
            return values.get(String.valueOf(id));
        }

        @Override
        public Integer read(int id, int zone) {
            return values.get(id + "@" + zone);
        }

        @Override
        public boolean write(int id, int value) {
            writes.add(id + "=" + value);
            if (!acceptPlain) {
                return false;
            }
            if (applies) {
                values.put(String.valueOf(id), value);
            }
            return true;
        }

        @Override
        public boolean write(int id, int zone, int value) {
            writes.add(id + "@" + zone + "=" + value);
            if (!acceptZoned) {
                return false;
            }
            if (applies) {
                values.put(id + "@" + zone, value);
            }
            return true;
        }

        @Override
        public Float sensor(int type) {
            return type == Signal.SPEED.id ? speed : null;
        }
    }

    private static final CarMoves.Names NAMES = new CarMoves.Names() {
        private final Map<String, Integer> functions = new HashMap<>();

        {
            functions.put("IHvac.HVAC_FUNC_SEAT_HEATING", HEAT);
            functions.put("IHvac.HVAC_FUNC_SEAT_VENTILATION", VENT);
            functions.put("IHvac.HVAC_FUNC_SEAT_MASSAGE_SWITCH", MASSAGE_SWITCH);
            functions.put("IHvac.HVAC_FUNC_SEAT_MASSAGE", MASSAGE);
            functions.put("IHvac.HVAC_FUNC_CIRCULATION", CIRCULATION);
            functions.put("IVehicle.SETTING_FUNC_POWER_SAVING_MODE", POWER_SAVING);
            functions.put("ISafety.SETTING_FUNC_CENTRAL_LOCK", LOCK);
            functions.put("ILamp.BCM_FUNC_AUX_DAY_TI_RUNNG_LI_SWITCH", DAY_LIGHT);
            functions.put("ILamp.BCM_FUNC_AUX_POSN_LAMP_SWITCH", POSITION_LIGHT);
            functions.put("IHvac.HVAC_FUNC_AIR_FRAGRANCE_SWITCH", FRAGRANCE_SWITCH);
            functions.put("IFragrance.HVAC_FUNC_AIR_FRAGRANCE_LEVEL", FRAGRANCE_LEVEL);
            functions.put("IFragrance.HVAC_FUNC_AIR_FRAGRANCE_SLOT", FRAGRANCE_SLOT);
            functions.put("IFragrance.HVAC_FUNC_AIR_FRAGRANCE_TYPE_ID", FRAGRANCE_TYPE);
            functions.put("IPAS.PAS_FUNC_PAC_ACTIVATION", VIEW_360);
            functions.put("IBcm.BCM_FUNC_FOLD_REAR_MIRROR", MIRROR);
        }

        @Override
        public Integer function(String iface, String field) {
            return functions.get(iface + "." + field);
        }

        @Override
        public int zone(String name) {
            switch (name) {
                case CarNames.DRIVER:
                    return DRIVER;
                case CarNames.PASSENGER:
                    return PASSENGER;
                case CarNames.ROW_2_LEFT:
                    return ROW_2_LEFT;
                case CarNames.ROW_2_RIGHT:
                    return ROW_2_RIGHT;
                default:
                    return ALL;
            }
        }
    };

    private final Map<String, Integer> memory = new HashMap<>();

    private CarMoves.Result run(FakeCar fake, CarMoves.Job job) {
        CarMoves.Car car = new CarMoves.Car(fake, NAMES, ms -> { }, new CarMoves.Memory() {
            @Override
            public int get(String key, int fallback) {
                Integer v = memory.get(key);
                return v == null ? fallback : v;
            }

            @Override
            public void put(String key, int value) {
                memory.put(key, value);
            }
        }, null);
        return job.run(car);
    }

    // ---- 换法

    @Test
    public void toggleSwapsZeroAndOne() {
        FakeCar car = new FakeCar();
        car.set(LOCK, ALL, 0);
        CarMoves.Job lock = CarMoves.toggleAny("SETTING_FUNC_CENTRAL_LOCK", CarNames.ALL, "IVehicle", "ISafety");
        assertEquals(CarMoves.Result.DONE, run(car, lock));
        assertEquals(Integer.valueOf(1), car.get(LOCK, ALL));
        assertEquals(CarMoves.Result.DONE, run(car, lock));
        assertEquals(Integer.valueOf(0), car.get(LOCK, ALL));
    }

    /** 读到别的（比如哨兵锁车后的那个值）不写。 */
    @Test
    public void toggleLeavesOtherValuesAlone() {
        FakeCar car = new FakeCar();
        car.set(LOCK, ALL, 2);
        assertEquals(CarMoves.Result.UNAVAILABLE,
                run(car, CarMoves.toggleAny("SETTING_FUNC_CENTRAL_LOCK", CarNames.ALL, "ISafety")));
        assertTrue(car.writes.isEmpty());
    }

    /** 这台车机上没有这个常量：做不了，一个号都不写。 */
    @Test
    public void aMissingFunctionIsUnavailable() {
        FakeCar car = new FakeCar();
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.toggle("IBcm", "BCM_FUNC_CHARGING_CAP", CarNames.ROW_2)));
        assertTrue(car.writes.isEmpty());
    }

    /** 后视镜：读到几写几；读到的变了算成了，没变是没生效。 */
    @Test
    public void mirrorsWriteWhatTheyReadAndCountAChange() {
        FakeCar car = new FakeCar();
        car.applies = false;
        car.set(MIRROR, ALL, 1);
        assertEquals(CarMoves.Result.NO_CHANGE, run(car, CarMoves.mirrors()));
        assertEquals(MIRROR + "@" + ALL + "=1", car.writes.get(0));
        car.writes.clear();
        car.set(MIRROR, ALL, 2);
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.mirrors()));
        assertTrue(car.writes.isEmpty());
    }

    /** 写进去以后车上真的折了、读到的跟着变：算成了。 */
    @Test
    public void mirrorsCountAFlippedReading() {
        FakeCar car = new FakeCar() {
            @Override
            public boolean write(int id, int zone, int value) {
                writes.add(id + "@" + zone + "=" + value);
                set(id, zone, value == 1 ? 0 : 1);
                return true;
            }
        };
        car.set(MIRROR, ALL, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.mirrors()));
        assertEquals(Integer.valueOf(1), car.get(MIRROR, ALL));
    }

    /** 加热、通风：关 → 3 → 2 → 1 → 关，读到别的写 3 档。 */
    @Test
    public void levelsGoOffThreeTwoOne() {
        FakeCar car = new FakeCar();
        CarMoves.Job heat = CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.DRIVER);
        car.set(HEAT, DRIVER, 0);
        int[] expected = {HEAT + 3, HEAT + 2, HEAT + 1, 0, HEAT + 3};
        for (int want : expected) {
            assertEquals(CarMoves.Result.DONE, run(car, heat));
            assertEquals(Integer.valueOf(want), car.get(HEAT, DRIVER));
        }
        car.set(HEAT, DRIVER, 255);
        run(car, heat);
        assertEquals(Integer.valueOf(HEAT + 3), car.get(HEAT, DRIVER));
    }

    /** 车机受理了、车上没变：这台车多半没有这个功能（座椅加热），提示一句，选项照留。 */
    @Test
    public void anAcceptedWriteThatChangesNothingIsReported() {
        FakeCar car = new FakeCar();
        car.applies = false;
        car.set(HEAT, PASSENGER, 0);
        assertEquals(CarMoves.Result.NO_CHANGE, run(car, CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.PASSENGER)));
    }

    @Test
    public void aRefusedWriteIsUnavailable() {
        FakeCar car = new FakeCar();
        car.acceptZoned = false;
        car.set(VENT, DRIVER, 0);
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_VENTILATION", CarNames.DRIVER)));
    }

    /** 全车：带「全车」区域写不进，再不带区域写；读也一样。 */
    @Test
    public void wholeCarFallsBackToNoZone() {
        FakeCar car = new FakeCar();
        car.acceptZoned = false;
        car.values.put(String.valueOf(VIEW_360), 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.onUnlessOn("IPAS", "PAS_FUNC_PAC_ACTIVATION", CarNames.ALL)));
        assertEquals(Integer.valueOf(1), car.read(VIEW_360));
        assertEquals(VIEW_360 + "@" + ALL + "=1", car.writes.get(0));
        assertEquals(VIEW_360 + "=1", car.writes.get(1));
    }

    /** 360、充电灯语：是 1 写 0，别的都写 1。 */
    @Test
    public void onUnlessOnTurnsOffOnlyWhenOn() {
        FakeCar car = new FakeCar();
        CarMoves.Job view = CarMoves.onUnlessOn("IPAS", "PAS_FUNC_PAC_ACTIVATION", CarNames.ALL);
        car.set(VIEW_360, ALL, 1);
        run(car, view);
        assertEquals(Integer.valueOf(0), car.get(VIEW_360, ALL));
        car.set(VIEW_360, ALL, 255);
        run(car, view);
        assertEquals(Integer.valueOf(1), car.get(VIEW_360, ALL));
    }

    /** 按摩：关着就打开、调到 3 档；之后往下降一档；1 档时关开关。 */
    @Test
    public void massageStartsAtThreeAndSwitchesOffAfterOne() {
        FakeCar car = new FakeCar();
        CarMoves.Job massage = CarMoves.massage(CarNames.DRIVER);
        car.set(MASSAGE_SWITCH, DRIVER, 0);
        assertEquals(CarMoves.Result.DONE, run(car, massage));
        assertEquals(Integer.valueOf(1), car.get(MASSAGE_SWITCH, DRIVER));
        assertEquals(Integer.valueOf(MASSAGE + 3), car.get(MASSAGE, DRIVER));
        run(car, massage);
        assertEquals(Integer.valueOf(MASSAGE + 2), car.get(MASSAGE, DRIVER));
        run(car, massage);
        assertEquals(Integer.valueOf(MASSAGE + 1), car.get(MASSAGE, DRIVER));
        run(car, massage);
        assertEquals(Integer.valueOf(0), car.get(MASSAGE_SWITCH, DRIVER));
    }

    /** 循环模式：自动 → 外 → 内 → 自动，读到别的写外循环。 */
    @Test
    public void circulationGoesAutoOutsideInside() {
        FakeCar car = new FakeCar();
        CarMoves.Job circulation = CarMoves.circulation();
        car.set(CIRCULATION, ALL, CIRCULATION + 3);
        int[] expected = {CIRCULATION + 2, CIRCULATION + 1, CIRCULATION + 3};
        for (int want : expected) {
            run(car, circulation);
            assertEquals(Integer.valueOf(want), car.get(CIRCULATION, ALL));
        }
        car.set(CIRCULATION, ALL, 0);
        run(car, circulation);
        assertEquals(Integer.valueOf(CIRCULATION + 2), car.get(CIRCULATION, ALL));
    }

    /** 二排座椅加热：照左边换档，两侧写同一档。 */
    @Test
    public void rearSeatsHeatTogether() {
        FakeCar car = new FakeCar();
        car.set(HEAT, ROW_2_LEFT, HEAT + 3);
        car.set(HEAT, ROW_2_RIGHT, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.rearSeatsHeat()));
        assertEquals(Integer.valueOf(HEAT + 2), car.get(HEAT, ROW_2_LEFT));
        assertEquals(Integer.valueOf(HEAT + 2), car.get(HEAT, ROW_2_RIGHT));
    }

    /** 行车灯语：照位置灯那个开关，两个开关一起换。 */
    @Test
    public void drivingLightsFollowThePositionLamp() {
        FakeCar car = new FakeCar();
        car.set(POSITION_LIGHT, ALL, 1);
        car.set(DAY_LIGHT, ALL, 1);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.drivingLights()));
        assertEquals(Integer.valueOf(0), car.get(POSITION_LIGHT, ALL));
        assertEquals(Integer.valueOf(0), car.get(DAY_LIGHT, ALL));
    }

    /** 香氛：三个香槽都没装（0、255、读不到）提示没装、不写。 */
    @Test
    public void fragranceNeedsAFittedSlot() {
        FakeCar car = new FakeCar();
        car.set(FRAGRANCE_TYPE, FRAGRANCE_SLOT + 1, 0);
        car.set(FRAGRANCE_TYPE, FRAGRANCE_SLOT + 2, 255);
        assertEquals(CarMoves.Result.NOT_FITTED, run(car, CarMoves.fragrance()));
        assertTrue(car.writes.isEmpty());
    }

    /** 香氛：关着就打开、调到淡雅；开着就往下一档，到关把开关也关掉。 */
    @Test
    public void fragranceTurnsOnAtTheLightestThenSteps() {
        FakeCar car = new FakeCar();
        car.set(FRAGRANCE_TYPE, FRAGRANCE_SLOT + 2, 7);
        car.set(FRAGRANCE_SLOT, FRAGRANCE_SLOT + 2, 1);
        car.set(FRAGRANCE_SWITCH, 0, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.fragrance()));
        assertEquals(Integer.valueOf(1), car.get(FRAGRANCE_SWITCH, 0));
        assertEquals(Integer.valueOf(FRAGRANCE_LEVEL + 1), car.get(FRAGRANCE_LEVEL, FRAGRANCE_SLOT + 2));
        car.set(FRAGRANCE_LEVEL, FRAGRANCE_SLOT + 2, FRAGRANCE_LEVEL + 3);
        run(car, CarMoves.fragrance());
        assertEquals(Integer.valueOf(0), car.get(FRAGRANCE_LEVEL, FRAGRANCE_SLOT + 2));
        assertEquals(Integer.valueOf(0), car.get(FRAGRANCE_SWITCH, 0));
    }

    /** 一键运动：不在运动就记下现在的、切运动；在运动就回到记下的，没记过回标准。 */
    @Test
    public void sportRemembersTheModeItLeft() {
        FakeCar car = new FakeCar();
        int id = Signal.DRIVE_MODE.id;
        car.values.put(String.valueOf(id), Signal.DRIVE_MODE_COMFORT);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sport()));
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_SPORT), car.read(id));
        run(car, CarMoves.sport());
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_COMFORT), car.read(id));

        memory.clear();
        car.values.put(String.valueOf(id), Signal.DRIVE_MODE_SPORT);
        run(car, CarMoves.sport());
        assertEquals(Integer.valueOf(Signal.DRIVE_MODE_STANDARD), car.read(id));
    }

    /** 驾驶模式照车上验证过的不带区域写。 */
    @Test
    public void driveModeWritesWithoutAZone() {
        FakeCar car = new FakeCar();
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.driveMode(Signal.DRIVE_MODE_SPORT)));
        assertEquals(Signal.DRIVE_MODE.id + "=" + Signal.DRIVE_MODE_SPORT, car.writes.get(0));
    }

    // ---- 执行前的检查

    /** 超级省电时不执行；读不到超级省电当没开。 */
    @Test
    public void powerSavingBlocks() {
        FakeCar car = new FakeCar();
        car.set(HEAT, DRIVER, 0);
        CarMoves.Job heat = CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.DRIVER));
        car.set(POWER_SAVING, ALL, 1);
        assertEquals(CarMoves.Result.POWER_SAVING, run(car, heat));
        assertTrue(car.writes.isEmpty());
        car.values.remove(POWER_SAVING + "@" + ALL);
        assertEquals(CarMoves.Result.DONE, run(car, heat));
    }

    /** 要车停稳：车速读得到、不超过 1 km/h；读不到也不执行。 */
    @Test
    public void standstillNeedsAKnownLowSpeed() {
        FakeCar car = new FakeCar();
        car.set(LOCK, ALL, 0);
        CarMoves.Job job = CarMoves.standstill(CarMoves.toggleAny("SETTING_FUNC_CENTRAL_LOCK", CarNames.ALL, "ISafety"));
        car.speed = null;
        assertEquals(CarMoves.Result.MOVING, run(car, job));
        car.speed = 0.3f;
        assertEquals(CarMoves.Result.MOVING, run(car, job));
        assertTrue(car.writes.isEmpty());
        car.speed = 0.2f;
        assertEquals(CarMoves.Result.DONE, run(car, job));
    }

    // ---- 纯函数

    @Test
    public void flipOnlyKnowsZeroAndOne() {
        assertEquals(Integer.valueOf(1), CarMoves.flipped(0));
        assertEquals(Integer.valueOf(0), CarMoves.flipped(1));
        assertNull(CarMoves.flipped(2));
        assertNull(CarMoves.flipped(null));
    }

    @Test
    public void nextWrapsAndFallsBack() {
        assertEquals(2, CarMoves.next(1, 9, 1, 2, 3));
        assertEquals(1, CarMoves.next(3, 9, 1, 2, 3));
        assertEquals(9, CarMoves.next(5, 9, 1, 2, 3));
        assertEquals(9, CarMoves.next(null, 9, 1, 2, 3));
    }

    @Test
    public void fragranceTypesThatCount() {
        assertTrue(CarMoves.fitted(3));
        assertEquals(false, CarMoves.fitted(0));
        assertEquals(false, CarMoves.fitted(255));
        assertEquals(false, CarMoves.fitted(-65535));
        assertEquals(false, CarMoves.fitted(null));
    }
}
