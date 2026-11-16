/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 jlunnn - https://github.com/jlunnn
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    private static final int SENTRY = 0xB000;
    private static final int TEMP = 0xC000;
    private static final int CLIMATE_POWER = 0xC100;
    private static final int AMBIENT_ON = 0xD000;
    private static final int AMBIENT_MODE = 0xD300;
    private static final int AMBIENT_THEME = 0xD400;
    private static final int ROW_1 = 0x8;
    private static final int ROW_2 = 0x80;
    private static final int AUTO = 0xC200;
    private static final int AC_SWITCH = 0xC300;
    private static final int FAN_SPEED = 0xC400;
    private static final int AUTO_FAN = 0xC500;
    private static final int BLOWING = 0xC600;
    private static final int SWEEPING = 0xC700;
    private static final int AIRVENT = 0xC800;
    private static final int RAPID_COOL = 0xC900;
    private static final int SUSPENSION = 0xCA00;
    private static final int REGEN = 0xCC00;
    private static final int WINDOW_POS = 0xCD00;
    private static final int AMBIENT_MAIN = 0xD100;
    private static final int AMBIENT_TOP = 0xD200;

    /** 假的车辆接口：值按「号@区域」存；写了照写进去（{@link #applies}），也可以不受理。 */
    private static class FakeCar implements CarAccess {
        final Map<String, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean acceptZoned = true;
        boolean acceptPlain = true;
        /** 受理了也不变（这台车没有这个功能）。 */
        boolean applies = true;
        Float speed;
        /** 车机说「号@区域」能不能用；没放的是 active，放 null 是问不了。 */
        final Map<String, String> statuses = new HashMap<>();

        void set(int id, int zone, int value) {
            values.put(id + "@" + zone, value);
        }

        Integer get(int id, int zone) {
            return values.get(id + "@" + zone);
        }

        final Map<String, Float> floats = new HashMap<>();
        final Map<Integer, Integer> configs = new HashMap<>();

        @Override
        public Integer config(int n) {
            return configs.get(n);
        }

        @Override
        public Float readFloat(int id, int zone) {
            return floats.get(id + "@" + zone);
        }

        @Override
        public boolean writeFloat(int id, int zone, float value) {
            writes.add(id + "@" + zone + "=" + value);
            if (!acceptZoned) {
                return false;
            }
            if (applies) {
                floats.put(id + "@" + zone, value);
            }
            return true;
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
        public String status(int id, int zone) {
            String key = id + "@" + zone;
            return statuses.containsKey(key) ? statuses.get(key) : "active";
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
            functions.put("IVehicle.SETTING_FUNC_VSTD_MODE_STS", SENTRY);
            functions.put("IHvac.HVAC_FUNC_TEMP", TEMP);
            functions.put("IHvac.HVAC_FUNC_POWER", CLIMATE_POWER);
            functions.put("IAmbienceLight.SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES", AMBIENT_ON);
            functions.put("IAmbienceLight.SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR", AMBIENT_MODE);
            functions.put("IAmbienceLight.SETTING_FUNC_AMBIENCE_LIGHT_THEME_COLOR", AMBIENT_THEME);
            functions.put("IHvac.HVAC_FUNC_AUTO", AUTO);
            functions.put("IHvac.HVAC_FUNC_AC", AC_SWITCH);
            functions.put("IHvac.HVAC_FUNC_FAN_SPEED", FAN_SPEED);
            functions.put("IHvac.HVAC_FUNC_AUTO_FAN_SETTING", AUTO_FAN);
            functions.put("IHvac.HVAC_FUNC_BLOWING_MODE", BLOWING);
            functions.put("IHvac.HVAC_FUNC_SWEEPING_MODE", SWEEPING);
            functions.put("IHvac.HVAC_FUNC_SEAT_AIRVENT_SWITCH", AIRVENT);
            functions.put("IHvac.HVAC_FUNC_RAPID_COOLG_REQ", RAPID_COOL);
            functions.put("IVehicle.SETTING_FUNC_SUSPENSION_HEIGHT_ADJUST", SUSPENSION);
            functions.put("IVehicle.SETTING_FUNC_ENERGY_REGENERATION", REGEN);
            functions.put("IBcm.BCM_FUNC_WINDOW_POS", WINDOW_POS);
            functions.put("IAmbienceLight.SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES_COLOR_SET", AMBIENT_MAIN);
            functions.put("IAmbienceLight.SETTING_FUNC_AMBIENCE_LIGHT_TOPZONES_COLOR_SET", AMBIENT_TOP);
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
                case CarNames.ROW_1:
                    return ROW_1;
                case CarNames.ROW_2:
                    return ROW_2;
                default:
                    return ALL;
            }
        }
    };

    private final Map<String, Integer> memory = new HashMap<>();

    private CarMoves.Result run(FakeCar fake, CarMoves.Job job) {
        return run(fake, job, ms -> { });
    }

    private CarMoves.Result run(FakeCar fake, CarMoves.Job job, CarMoves.Sleeper sleeper) {
        CarMoves.Car car = new CarMoves.Car(fake, NAMES, sleeper, new CarMoves.Memory() {
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

    private static CarMoves.Car car(FakeCar fake) {
        return new CarMoves.Car(fake, NAMES, ms -> { }, null, null);
    }

    @Test
    public void fanStepsWithinItsTableAndOpensTheClimate() {
        FakeCar f = new FakeCar();
        f.set(AUTO, ROW_1, 0);
        f.set(CLIMATE_POWER, ROW_1, 0);
        f.set(FAN_SPEED, ROW_1, 0);
        assertEquals(CarMoves.Result.DONE, KnobTurn.FAN.dial.turn(car(f), 2));
        assertEquals(Integer.valueOf(FAN_SPEED + 2), f.get(FAN_SPEED, ROW_1));
        assertEquals(Integer.valueOf(1), f.get(CLIMATE_POWER, ROW_1));
        assertEquals(CarMoves.Result.DONE, KnobTurn.FAN.dial.turn(car(f), 20));
        assertEquals(Integer.valueOf(FAN_SPEED + 9), f.get(FAN_SPEED, ROW_1));
        int writes = f.writes.size();
        assertEquals(CarMoves.Result.DONE, KnobTurn.FAN.dial.turn(car(f), 1));
        assertEquals(writes, f.writes.size());
    }

    @Test
    public void autoClimateTurnsTheAutoFanTable() {
        FakeCar f = new FakeCar();
        f.set(AUTO, ROW_1, 1);
        f.set(AUTO_FAN, ROW_1, AUTO_FAN + 1);
        assertEquals(CarMoves.Result.DONE, KnobTurn.FAN.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(AUTO_FAN + 2), f.get(AUTO_FAN, ROW_1));
        f.set(AUTO, ROW_2, 1);
        f.set(AUTO_FAN, ROW_2, AUTO_FAN + 4);
        f.set(CLIMATE_POWER, ROW_2, 1);
        assertEquals(CarMoves.Result.DONE, KnobTurn.REAR_FAN.dial.turn(car(f), -1));
        assertEquals(Integer.valueOf(0), f.get(AUTO_FAN, ROW_2));
        assertEquals(Integer.valueOf(0), f.get(CLIMATE_POWER, ROW_2));
    }

    @Test
    public void anUnknownValueGoesToTheFirst() {
        FakeCar f = new FakeCar();
        f.set(CIRCULATION, ALL, 99);
        assertEquals(CarMoves.Result.DONE, KnobTurn.CIRCULATION.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(CIRCULATION + 3), f.get(CIRCULATION, ALL));
    }

    @Test
    public void airDirectionPreparesTheVents() {
        FakeCar f = new FakeCar();
        f.set(CLIMATE_POWER, ROW_1, 0);
        f.set(CLIMATE_POWER, DRIVER, 0);
        f.set(BLOWING, DRIVER, BLOWING + 2);
        f.set(SWEEPING, DRIVER, 0);
        assertEquals(CarMoves.Result.DONE, KnobTurn.DRIVER_SWEEP.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(1), f.get(SWEEPING, DRIVER));
        assertEquals(Integer.valueOf(1), f.get(CLIMATE_POWER, ROW_1));
        assertEquals(Integer.valueOf(1), f.get(CLIMATE_POWER, DRIVER));
        assertEquals(Integer.valueOf(BLOWING + 3), f.get(BLOWING, DRIVER));
        assertEquals(Integer.valueOf(1), f.get(AIRVENT, DRIVER));
    }

    @Test
    public void massageUsesItsSwitch() {
        FakeCar f = new FakeCar();
        f.set(MASSAGE_SWITCH, DRIVER, 0);
        f.set(MASSAGE, DRIVER, MASSAGE + 2);
        assertEquals(CarMoves.Result.DONE, KnobTurn.DRIVER_MASSAGE.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(1), f.get(MASSAGE_SWITCH, DRIVER));
        assertEquals(Integer.valueOf(MASSAGE + 1), f.get(MASSAGE, DRIVER));
        assertEquals(CarMoves.Result.DONE, KnobTurn.DRIVER_MASSAGE.dial.turn(car(f), -1));
        assertEquals(Integer.valueOf(0), f.get(MASSAGE_SWITCH, DRIVER));
        assertEquals(Integer.valueOf(MASSAGE + 1), f.get(MASSAGE, DRIVER));
    }

    @Test
    public void suspensionNeedsTheCarToSayActive() {
        FakeCar f = new FakeCar();
        f.statuses.put(SUSPENSION + "@" + ALL, "notavailable");
        f.set(SUSPENSION, ALL, SUSPENSION + 3);
        assertEquals(CarMoves.Result.UNAVAILABLE, KnobTurn.SUSPENSION.dial.turn(car(f), -1));
        assertTrue(f.writes.isEmpty());
        f.statuses.remove(SUSPENSION + "@" + ALL);
        assertEquals(CarMoves.Result.DONE, KnobTurn.SUSPENSION.dial.turn(car(f), -1));
        assertEquals(Integer.valueOf(SUSPENSION + 2), f.get(SUSPENSION, ALL));
        assertEquals(Signal.DRIVE_MODE.id + "=" + (Signal.DRIVE_MODE.id + 0x40), f.writes.get(0));
    }

    @Test
    public void energyRecoveryNeedsTheCarToSayActive() {
        FakeCar f = new FakeCar();
        f.statuses.put(REGEN + "@" + ALL, "notactive");
        f.set(REGEN, ALL, 0);
        assertEquals(CarMoves.Result.UNAVAILABLE, KnobTurn.REGEN.dial.turn(car(f), 1));
        assertTrue(f.writes.isEmpty());
    }

    @Test
    public void driveModesFollowTheCarConfig() {
        int[] here = KnobDials.driveModeList(n -> n == 605 || n == 607 || n == 608 || n == 610 ? 1 : 0).steps;
        assertTrue(java.util.Arrays.equals(new int[] {0x03, 0x02, 0x01, 0x09, 0x40}, here));
        int[] unknown = KnobDials.driveModeList(n -> -1).steps;
        assertTrue(java.util.Arrays.equals(new int[] {0x03, 0x02, 0x01, 0x47, 0x0D, 0x0A, 0x09, 0x0B, 0x40}, unknown));
    }

    @Test
    public void driveModeStepsWithThePlainWrite() {
        FakeCar f = new FakeCar();
        int id = Signal.DRIVE_MODE.id;
        f.values.put(String.valueOf(id), id + 3);
        assertEquals(CarMoves.Result.DONE, KnobTurn.DRIVE_MODE.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(id + 2), f.values.get(String.valueOf(id)));
        assertEquals(id + "=" + (id + 2), f.writes.get(0));
    }

    @Test
    public void windowsSkipTheOnesTheCarCannotMove() {
        FakeCar f = new FakeCar();
        f.statuses.put(WINDOW_POS + "@" + 0x100, "notavailable");
        f.statuses.put(WINDOW_POS + "@" + 0x200, "notavailable");
        assertEquals(CarMoves.Result.DONE, KnobTurn.WINDOWS_VENT.dial.turn(car(f), 1));
        assertEquals(Float.valueOf(12f), f.floats.get(WINDOW_POS + "@" + 0x10));
        assertEquals(Float.valueOf(12f), f.floats.get(WINDOW_POS + "@" + 0x20));
        assertNull(f.floats.get(WINDOW_POS + "@" + 0x100));
        f.statuses.put(WINDOW_POS + "@" + 0x10, "notavailable");
        f.statuses.put(WINDOW_POS + "@" + 0x20, "notavailable");
        assertEquals(CarMoves.Result.UNAVAILABLE, KnobTurn.WINDOWS.dial.turn(car(f), -1));
    }

    @Test
    public void togglesFollowTheDirection() {
        FakeCar f = new FakeCar();
        f.set(AC_SWITCH, ALL, 0);
        assertEquals(CarMoves.Result.DONE, KnobTurn.AC.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(1), f.get(AC_SWITCH, ALL));
        f.set(RAPID_COOL, ALL, 1);
        assertEquals(CarMoves.Result.DONE, KnobTurn.RAPID_COOL.dial.turn(car(f), 1));
        assertEquals(Integer.valueOf(0), f.get(RAPID_COOL, ALL));
    }

    @Test
    public void oneZoneTemperatureLeavesTheClimateAlone() {
        FakeCar f = new FakeCar();
        f.floats.put(TEMP + "@" + DRIVER, 22f);
        f.set(CLIMATE_POWER, DRIVER, 0);
        assertEquals(CarMoves.Result.DONE, KnobTurn.DRIVER_TEMP.dial.turn(car(f), 2));
        assertEquals(Float.valueOf(23f), f.floats.get(TEMP + "@" + DRIVER));
        assertEquals(Integer.valueOf(0), f.get(CLIMATE_POWER, DRIVER));
        assertNull(f.floats.get(TEMP + "@" + PASSENGER));
    }

    @Test
    public void knobFragranceNeedsAFittedSlot() {
        assertEquals(CarMoves.Result.NOT_FITTED, KnobTurn.FRAGRANCE.dial.turn(car(new FakeCar()), 1));
    }

    @Test
    public void shownValuesWithoutLabels() {
        FakeCar f = new FakeCar();
        f.floats.put(TEMP + "@" + DRIVER, 22.5f);
        assertEquals("22.5\u00b0", KnobTurn.TEMPERATURE.dial.shown(car(f), null, 0));
        assertEquals("+5", KnobTurn.VOLUME.dial.shown(car(f), null, 8));
        assertEquals("-2", KnobTurn.VOLUME.dial.shown(car(f), null, -2));
        assertNull(KnobTurn.VOLUME.dial.shown(car(f), null, 0));
    }

    private static int glow(FakeCar fake) {
        return CarMoves.glow(new CarMoves.Car(fake, NAMES, ms -> { }, null, null));
    }

    @Test
    public void glowNeedsTheLightOn() {
        FakeCar car = new FakeCar();
        car.values.put(String.valueOf(AMBIENT_MAIN), 0xFF00FFCB);
        assertEquals(-1, glow(car));
        car.values.put(String.valueOf(AMBIENT_ON), 0);
        assertEquals(-1, glow(car));
        car.values.put(String.valueOf(AMBIENT_ON), 1);
        assertEquals(0x00FFCB, glow(car));
    }

    @Test
    public void glowFollowsTheThemeAndKeepsTheColorForMusic() {
        FakeCar car = new FakeCar();
        car.values.put(String.valueOf(AMBIENT_ON), 1);
        car.values.put(String.valueOf(AMBIENT_MAIN), 0xFF00FFCB);
        car.values.put(String.valueOf(AMBIENT_MODE), AMBIENT_MODE + 3);
        assertEquals(0x00FFCB, glow(car));
        car.values.put(String.valueOf(AMBIENT_MODE), AMBIENT_MODE + 1);
        car.values.put(String.valueOf(AMBIENT_THEME), 3);
        assertEquals(0x78E8C9, glow(car));
        car.values.put(String.valueOf(AMBIENT_THEME), 9);
        assertEquals(0xE9947D, glow(car));
        car.values.put(String.valueOf(AMBIENT_MODE), AMBIENT_MODE + 4);
        assertEquals(CarMoves.KEEP_TINT, glow(car));
        car.values.put(String.valueOf(AMBIENT_ON), 0);
        assertEquals(-1, glow(car));
    }

    @Test
    public void glowFallsBackToTheTop() {
        FakeCar car = new FakeCar();
        car.values.put(String.valueOf(AMBIENT_ON), 1);
        car.values.put(String.valueOf(AMBIENT_TOP), 0xFF123456);
        assertEquals(0x123456, glow(car));
        car.values.put(String.valueOf(AMBIENT_MAIN), 255);
        assertEquals(0x123456, glow(car));
        car.values.remove(String.valueOf(AMBIENT_TOP));
        assertEquals(-1, glow(car));
    }

    @Test
    public void temperatureStepsHalfADegreeWithinItsRange() {
        assertEquals(22.5f, CarMoves.nextTemperature(22f, 1), 0.001f);
        assertEquals(21f, CarMoves.nextTemperature(22f, -2), 0.001f);
        assertEquals(28.5f, CarMoves.nextTemperature(28f, 5), 0.001f);
        assertEquals(15.5f, CarMoves.nextTemperature(16f, -3), 0.001f);
        assertEquals(22.5f, CarMoves.nextTemperature(22.3f, 0), 0.001f);
    }

    @Test
    public void temperatureWritesBothFrontZonesAndOpensTheClimateFirst() {
        FakeCar car = new FakeCar();
        car.floats.put(TEMP + "@" + DRIVER, 22f);
        car.set(CLIMATE_POWER, DRIVER, 1);
        car.set(CLIMATE_POWER, PASSENGER, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.temperature(2)));
        assertEquals(Float.valueOf(23f), car.floats.get(TEMP + "@" + DRIVER));
        assertEquals(Float.valueOf(23f), car.floats.get(TEMP + "@" + PASSENGER));
        assertEquals(Integer.valueOf(1), car.get(CLIMATE_POWER, PASSENGER));
        assertTrue(car.writes.indexOf(CLIMATE_POWER + "@" + PASSENGER + "=1") < car.writes.indexOf(TEMP + "@" + DRIVER + "=23.0"));
    }

    @Test
    public void temperatureLeavesTheCarAloneWhenItCannotOrNeedNot() {
        FakeCar unread = new FakeCar();
        assertEquals(CarMoves.Result.UNAVAILABLE, run(unread, CarMoves.temperature(1)));
        assertTrue(unread.writes.isEmpty());

        FakeCar top = new FakeCar();
        top.floats.put(TEMP + "@" + DRIVER, 28.5f);
        assertEquals(CarMoves.Result.DONE, run(top, CarMoves.temperature(1)));
        assertTrue(top.writes.isEmpty());

        FakeCar stuck = new FakeCar();
        stuck.applies = false;
        stuck.floats.put(TEMP + "@" + DRIVER, 22f);
        assertEquals(CarMoves.Result.NO_CHANGE, run(stuck, CarMoves.temperature(-1)));
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

    /** 主界面的「开启哨兵模式」只开不关：关着写 1；开着、布防不写，算做成；写了没变是没生效；车机不受理是做不了。 */
    @Test
    public void sentryOnOnlyTurnsOn() {
        FakeCar car = new FakeCar();
        car.set(SENTRY, ALL, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sentryOn()));
        assertEquals(Integer.valueOf(1), car.get(SENTRY, ALL));
        assertEquals(SENTRY + "@" + ALL + "=1", car.writes.get(0));
        for (int already : new int[] {1, 2}) {
            car.writes.clear();
            car.set(SENTRY, ALL, already);
            assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sentryOn()));
            assertTrue("已经开着（" + already + "）不写", car.writes.isEmpty());
            assertEquals(Integer.valueOf(already), car.get(SENTRY, ALL));
        }
        car.set(SENTRY, ALL, 0);
        car.applies = false;
        assertEquals(CarMoves.Result.NO_CHANGE, run(car, CarMoves.sentryOn()));
        car.acceptZoned = false;
        car.acceptPlain = false;
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.sentryOn()));
    }

    /** 主界面那个键亮着时再点一下（反悔）只关不开：开着写 0；关着不写，算做成；布防、读不到不写；写了没变是没生效。 */
    @Test
    public void sentryOffOnlyTurnsOff() {
        FakeCar car = new FakeCar();
        car.set(SENTRY, ALL, 1);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sentryOff()));
        assertEquals(Integer.valueOf(0), car.get(SENTRY, ALL));
        assertEquals(SENTRY + "@" + ALL + "=0", car.writes.get(0));
        car.writes.clear();
        assertEquals("已经关着不写", CarMoves.Result.DONE, run(car, CarMoves.sentryOff()));
        assertTrue(car.writes.isEmpty());
        car.set(SENTRY, ALL, 2);
        assertEquals("布防的那个值不写", CarMoves.Result.UNAVAILABLE, run(car, CarMoves.sentryOff()));
        assertTrue(car.writes.isEmpty());
        car.set(SENTRY, ALL, 1);
        car.applies = false;
        assertEquals(CarMoves.Result.NO_CHANGE, run(car, CarMoves.sentryOff()));
    }

    /** 灵动贴的「哨兵模式」照旧是 0 / 1 互换，和「开启哨兵模式」写的是同一个。 */
    @Test
    public void sentryToggleFlipsTheSameSwitch() {
        FakeCar car = new FakeCar();
        car.set(SENTRY, ALL, 1);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sentryToggle()));
        assertEquals(Integer.valueOf(0), car.get(SENTRY, ALL));
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.sentryToggle()));
        assertEquals(Integer.valueOf(1), car.get(SENTRY, ALL));
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

    // ---- 后雾灯闪烁（jlunnn 的 PR #160，从 7X-U 搬来）

    /** 闪三下：亮灭各 350ms，三次「亮=1、灭=0」，完结回 0；有一下读到 1 算成了。 */
    @Test
    public void rearFogFlashesThreeTimes() {
        FakeCar car = new FakeCar();
        car.set(Signal.REAR_FOG.id, ALL, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.rearFogFlash()));
        assertEquals(6, car.writes.size());                       // 每下一开一关，三下六笔
        assertEquals(Signal.REAR_FOG.id + "=1", car.writes.get(0));            // 亮
        assertEquals(Signal.REAR_FOG.id + "=0", car.writes.get(1));            // 灭
        assertEquals(Signal.REAR_FOG.id + "=0", car.writes.get(5));            // 完结回 0
    }

    /** 车机不受理（车机没这个号、写不进）：做不了，不数成。 */
    @Test
    public void rearFogRefusedIsUnavailable() {
        FakeCar car = new FakeCar();
        car.acceptPlain = false;
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.rearFogFlash()));
    }

    /** 受理了但车上一直没亮：没生效（这台车多半没这功能，照实报）。 */
    @Test
    public void rearFogNeverLitIsNoChange() {
        FakeCar car = new FakeCar();
        car.applies = false;                                      // 写了也不变，读到的一直是 null
        assertEquals(CarMoves.Result.NO_CHANGE, run(car, CarMoves.rearFogFlash()));
    }

    // ---- 关门（项目所有者 2026-11-15）

    private static final int DOOR = Signal.DOOR_DRIVER.id;

    private static CarMoves.Job allDoors() {
        return CarMoves.closeDoors(Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER, Signal.DOOR_REAR_LEFT, Signal.DOOR_REAR_RIGHT);
    }

    /** 开着的、停在半路的写 0，本来关着的不写；读回都关好了算成了。 */
    @Test
    public void closeDoorsClosesOnlyTheOpenOnes() {
        FakeCar car = new FakeCar();
        car.set(DOOR, Signal.DOOR_DRIVER.zone, 1);
        car.set(DOOR, Signal.DOOR_PASSENGER.zone, 0);
        car.set(DOOR, Signal.DOOR_REAR_LEFT.zone, DOOR + 1);
        car.set(DOOR, Signal.DOOR_REAR_RIGHT.zone, 0);
        assertEquals(CarMoves.Result.DONE, run(car, allDoors()));
        assertEquals(2, car.writes.size());
        assertEquals(DOOR + "@" + Signal.DOOR_DRIVER.zone + "=0", car.writes.get(0));
        assertEquals(DOOR + "@" + Signal.DOOR_REAR_LEFT.zone + "=0", car.writes.get(1));
    }

    /** 几扇一起关：一扇一扇写，两扇之间隔 200 ms（车自己的设置页逐扇关就是这样）。 */
    @Test
    public void doorsAreWrittenOneAtATime() {
        FakeCar car = new FakeCar();
        car.set(DOOR, Signal.DOOR_DRIVER.zone, 1);
        car.set(DOOR, Signal.DOOR_PASSENGER.zone, 0);
        car.set(DOOR, Signal.DOOR_REAR_LEFT.zone, 1);
        car.set(DOOR, Signal.DOOR_REAR_RIGHT.zone, 1);
        assertEquals(CarMoves.Result.DONE, run(car, allDoors(), ms -> car.writes.add("sleep " + ms)));
        assertEquals(DOOR + "@" + Signal.DOOR_DRIVER.zone + "=0", car.writes.get(0));
        assertEquals("sleep " + CarMoves.DOOR_GAP_MS, car.writes.get(1));
        assertEquals(DOOR + "@" + Signal.DOOR_REAR_LEFT.zone + "=0", car.writes.get(2));
        assertEquals("sleep " + CarMoves.DOOR_GAP_MS, car.writes.get(3));
        assertEquals(DOOR + "@" + Signal.DOOR_REAR_RIGHT.zone + "=0", car.writes.get(4));
    }

    /** 都关着：一个都不写，算做成（按了没事可做）。 */
    @Test
    public void closedDoorsNeedNothing() {
        FakeCar car = new FakeCar();
        car.set(DOOR, Signal.DOOR_TRUNK.zone, 0);
        assertEquals(CarMoves.Result.DONE, run(car, CarMoves.closeDoors(Signal.DOOR_TRUNK)));
        assertTrue(car.writes.isEmpty());
    }

    /** 车机说这一扇不是 active（没有电动门）、问不了：不写；开着的一扇都写不了是做不了。 */
    @Test
    public void doorsTheCarCannotMoveAreLeftAlone() {
        FakeCar car = new FakeCar();
        String passenger = DOOR + "@" + Signal.DOOR_PASSENGER.zone;
        car.set(DOOR, Signal.DOOR_PASSENGER.zone, 1);
        for (String status : new String[] {"notavailable", "notactive", "error", null}) {
            car.statuses.put(passenger, status);
            assertEquals(String.valueOf(status), CarMoves.Result.UNAVAILABLE,
                    run(car, CarMoves.closeDoors(Signal.DOOR_PASSENGER)));
            assertTrue(car.writes.isEmpty());
        }
    }

    /** 四扇一起：写不了的那扇跳过，别的照关；写了的关好了算做成。 */
    @Test
    public void allDoorsClosesWhatItCan() {
        FakeCar car = new FakeCar();
        car.set(DOOR, Signal.DOOR_DRIVER.zone, 1);
        car.set(DOOR, Signal.DOOR_PASSENGER.zone, 1);
        car.statuses.put(DOOR + "@" + Signal.DOOR_PASSENGER.zone, "notavailable");
        assertEquals(CarMoves.Result.DONE, run(car, allDoors()));
        assertEquals(1, car.writes.size());
        assertEquals(DOOR + "@" + Signal.DOOR_DRIVER.zone + "=0", car.writes.get(0));
        assertEquals(Integer.valueOf(1), car.get(DOOR, Signal.DOOR_PASSENGER.zone));
    }

    /** 读不到、读到不认识的值：那一扇不写；一扇都读不到是做不了。 */
    @Test
    public void unreadableDoorsAreSkipped() {
        FakeCar car = new FakeCar();
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, allDoors()));
        car.set(DOOR, Signal.DOOR_REAR_RIGHT.zone, 255);
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.closeDoors(Signal.DOOR_REAR_RIGHT)));
        car.set(DOOR, Signal.DOOR_DRIVER.zone, 0);
        assertEquals("读得到的都关着", CarMoves.Result.DONE, run(car, allDoors()));
        assertTrue(car.writes.isEmpty());
    }

    /** 关门要几秒：读回等得比 1 秒久，读到关好就停；车机受理了、门一直没关上是没生效。 */
    @Test
    public void closingWaitsForTheDoorToGetThere() {
        int[] reads = {0};
        FakeCar slow = new FakeCar() {
            @Override
            public boolean write(int id, int zone, int value) {
                writes.add(id + "@" + zone + "=" + value);
                return true;
            }

            @Override
            public Integer read(int id, int zone) {
                // 写了以后读到第 20 下（约 4 秒）才关好
                if (!writes.isEmpty() && ++reads[0] >= 20) {
                    set(id, zone, 0);
                }
                return super.read(id, zone);
            }
        };
        slow.set(DOOR, Signal.DOOR_TRUNK.zone, 1);
        assertEquals(CarMoves.Result.DONE, run(slow, CarMoves.closeDoors(Signal.DOOR_TRUNK)));
        assertTrue(20L * CarMoves.DOOR_STEP_MS > CarMoves.BACK_MS);

        FakeCar stuck = new FakeCar();
        stuck.applies = false;
        stuck.set(DOOR, Signal.DOOR_TRUNK.zone, 1);
        assertEquals(CarMoves.Result.NO_CHANGE, run(stuck, CarMoves.closeDoors(Signal.DOOR_TRUNK)));
        assertEquals(1, stuck.writes.size());
    }

    /** 关到一半受阻、停在半路（读到号加 1）：到时是做不了，不说「此车可能没有该功能」。 */
    @Test
    public void aDoorThatStopsHalfwayIsUnavailable() {
        FakeCar car = new FakeCar() {
            @Override
            public boolean write(int id, int zone, int value) {
                writes.add(id + "@" + zone + "=" + value);
                set(id, zone, id + 1);
                return true;
            }
        };
        car.set(DOOR, Signal.DOOR_TRUNK.zone, 1);
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.closeDoors(Signal.DOOR_TRUNK)));
        assertEquals(1, car.writes.size());
        car.writes.clear();
        assertEquals("停在半路的再按一下再写一次关", CarMoves.Result.UNAVAILABLE,
                run(car, CarMoves.closeDoors(Signal.DOOR_TRUNK)));
        assertEquals(DOOR + "@" + Signal.DOOR_TRUNK.zone + "=0", car.writes.get(0));
    }

    /** 车机不受理写：做不了。 */
    @Test
    public void aRefusedCloseIsUnavailable() {
        FakeCar car = new FakeCar();
        car.acceptZoned = false;
        car.set(DOOR, Signal.DOOR_DRIVER.zone, 1);
        assertEquals(CarMoves.Result.UNAVAILABLE, run(car, CarMoves.closeDoors(Signal.DOOR_DRIVER)));
    }

    /** 车机回的可用状态：只有 active 算能用（枚举名带类名的也认）。 */
    @Test
    public void onlyActiveCounts() {
        assertTrue(CarMoves.active("active"));
        assertTrue(CarMoves.active("FunctionStatus.active"));
        assertFalse(CarMoves.active("notactive"));
        assertFalse(CarMoves.active("FunctionStatus.notactive"));
        assertFalse(CarMoves.active("notavailable"));
        assertFalse(CarMoves.active("error"));
        assertFalse(CarMoves.active(null));
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
