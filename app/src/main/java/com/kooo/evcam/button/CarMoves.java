/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 jlunnn - https://github.com/jlunnn
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.telemetry.CarAccess;
import com.kooo.evcam.telemetry.CarNames;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 灵动贴写车的那些动作：驾驶模式（项目所有者 2026-10-30）和 2026-11-04 车主定的那一套。
 * 每一项读什么、怎么换下一个值、什么时候不执行照 zeekr-shortcut-lab 3.5.x 在车上试过的（Lab findings §2.92、§2.93），
 * 代码在这里按本项目重写；功能号、区域按系统里的常量名取（{@link CarNames}），这台车机上没有的就是做不了。
 *
 * <ul>
 *   <li>按了先读现在的值，照规则写下一个；写完每 {@link #BACK_STEP_MS} 读回一次，{@link #BACK_MS} 内变成写的值才算成了（{@link Result#DONE}；
 *       关门等得久一些，{@link #DOOR_BACK_MS}）。
 *       车机受理了、车没变是 {@link Result#NO_CHANGE}：这台车多半没有这个功能（比如这台车的座椅加热，Lab 3.5.3 车上），选项照留，
 *       给别的地区的车用（项目所有者 2026-11-04）。</li>
 *   <li>执行前看：超级省电时座椅、方向盘、音量、一键运动不执行（{@link #saving}）。座椅恢复会动座椅，后视镜、充电口盖、关门
 *       会动车身，要车停稳才执行：车速读得到、不超过 {@link #STANDSTILL_KMH}（{@link #standstill}；座椅恢复的是 Lab 加的，关车门的
 *       照 Lab 车上用的做法，后视镜、充电口盖、关后备箱的是我们加的）。不看挡位，按了就写、不恢复（项目所有者 2026-10-27、11-04）。</li>
 *   <li>全车的读写先带「全车」区域，车机不认（读不到、写不进）再不带区域（Lab：驾驶模式以前就是不带区域写通的）。
 *       驾驶模式、一键运动照本项目车上验证过的不带区域读写（{@link Car#setPlain}）。</li>
 *   <li>一件一件做，在自己的线程上（{@code SmartButtonCar}）：读回要等，不占主线程，也不占车辆信号的线程（{@link CarAccess}）。
 *       每件一行黑匣子「灵动贴动作」：哪一件、结果、读到写下的值。结果交给主线程（提示在 {@code SmartButtons}）。</li>
 * </ul>
 *
 * <p>判断和换值都在 {@link Car} 和下面的纯函数里，测试换上假的车辆接口（{@code CarMovesTest}）。</p>
 */
final class CarMoves {

    /** 读回最多等多久（Lab 3.5.4：座椅通风这些车上慢的要 600 毫秒）。 */
    static final long BACK_MS = 1000;
    static final long BACK_STEP_MS = 100;
    /** 超过这个车速（km/h）不算停稳。 */
    static final double STANDSTILL_KMH = 1.0;
    /** 香氛刚打开时隔这么久再看一眼开关，没开上再写一次。 */
    static final long FRAGRANCE_RECHECK_MS = 400;
    /**
     * 关门读回最多等多久：门、后备箱从开到关要几秒，1 秒肯定不够。电动门、电动尾门关上要多久车上还没量过
     * （Lab 2026-11-15：等后备箱的实测再改），先放宽到 15 秒，读到关好就停。
     */
    static final long DOOR_BACK_MS = 15_000;
    /** 关门读回多久看一次。 */
    static final long DOOR_STEP_MS = 200;
    /** 几扇一起关时，两扇之间隔多久写：车自己的设置页逐扇关就是隔 200 ms（Lab findings §2.139）。 */
    static final long DOOR_GAP_MS = 200;
    static final int VOLUME_UP_MAX = 5;
    static final long VOLUME_GAP_MS = 40;
    static final String TEMP_FIELD = "HVAC_FUNC_TEMP";
    static final float TEMP_STEP = 0.5f;
    static final float TEMP_MIN = 15.5f;
    static final float TEMP_MAX = 28.5f;
    /** 一键运动记下的原来的驾驶模式（运行状态，在 {@code smart_button_state} 里）。 */
    static final String SPORT_SAVED = "sport_saved_mode";

    /** 后雾灯闪烁：闪几下（照 jlunnn 的 7X-U，fog_count 默认 3）。 */
    static final int FOG_FLASHES = 3;
    /** 后雾灯闪烁：亮多久 ms（7X-U 的 fog_on_ms 默认 350）。 */
    static final long FOG_ON_MS = 350;
    /** 后雾灯闪烁：灭多久 ms（7X-U 的 fog_off_ms 默认 350）。 */
    static final long FOG_OFF_MS = 350;

    /** 一件的结果。 */
    enum Result {
        /** 读回变成写的值了。 */
        DONE,
        /** 车机受理了，车上没变。 */
        NO_CHANGE,
        /** 做不了：车辆接口没连上、这台车机没有这个功能号、读到的值不认识、车机不受理；关门时门停在半路（受阻）。 */
        UNAVAILABLE,
        /** 超级省电模式下不执行。 */
        POWER_SAVING,
        /** 车没停稳（或者读不到车速）不执行。 */
        MOVING,
        /** 香氛没装。 */
        NOT_FITTED
    }

    /** 一件写车的事。 */
    interface Job {
        Result run(Car car);
    }

    /** 功能号、区域从哪来（真的是 {@link CarNames}；测试里换成假的）。 */
    interface Names {
        Integer function(String iface, String field);

        int zone(String name);

        default int window(String name) {
            return CarNames.window(name);
        }
    }

    /** 记一个数（一键运动记下原来的模式）。 */
    interface Memory {
        int get(String key, int fallback);

        void put(String key, int value);
    }

    /** 等一会儿（真的是 {@link SystemClock#sleep}；测试里不等）。 */
    interface Sleeper {
        void sleep(long ms);
    }

    private static final Names SYSTEM_NAMES = new Names() {
        @Override
        public Integer function(String iface, String field) {
            return CarNames.function(iface, field);
        }

        @Override
        public int zone(String name) {
            return CarNames.zone(name);
        }
    };

    private CarMoves() {
    }

    // ================================================================= 做（自己的线程）

    /** 写车的线程、主线程：第一次排的时候才建（动作表在类加载时就建好了，用不着它们）。 */
    private static Handler handler;
    private static Handler main;

    private static synchronized Handler handler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("SmartButtonCar");
            thread.start();
            handler = new Handler(thread.getLooper());
            main = new Handler(Looper.getMainLooper());
        }
        return handler;
    }

    /** 排一件（主线程上叫），黑匣子记成灵动贴按的（主界面的哨兵键也走这里）。 */
    static void run(Context app, String key, Job job, Consumer<Result> done) {
        run(app, CameraKeeper.Why.BUTTON, key, job, done);
    }

    /**
     * 排一件（主线程上叫）：在写车的线程上做，结果在主线程上交给 {@code done}。{@code key} 是动作的名字，黑匣子用；
     * {@code why} 是谁按的：悬浮按钮按的记「悬浮按钮动作」，别的照旧记「灵动贴动作」。
     */
    static void run(Context app, CameraKeeper.Why why, String key, Job job, Consumer<Result> done) {
        Handler h = handler();
        Handler back = main;
        h.post(() -> {
            CarAccess access = Telemetry.get().carAccess();
            Result result;
            String detail;
            if (access == null) {
                result = Result.UNAVAILABLE;
                detail = "car interface not connected";
            } else {
                Car car = new Car(access, SYSTEM_NAMES, SystemClock::sleep, memory(app), app);
                try {
                    result = job.run(car);
                } catch (RuntimeException e) {
                    result = Result.UNAVAILABLE;
                    car.note("failed: " + e);
                }
                detail = car.detail();
            }
            BlackBox.note((why == CameraKeeper.Why.FLOATING ? "悬浮按钮动作 " : "灵动贴动作 ") + key + "：" + result + "（" + detail + "）");
            Result r = result;
            back.post(() -> done.accept(r));
        });
    }

    private static final java.util.Map<String, Turning> turning = new java.util.HashMap<>();

    private static final class Turning {
        int delta;
        java.util.function.BiConsumer<Result, String> done;
    }

    static void dial(Context app, String key, KnobDials.Dial dial, int delta,
                     java.util.function.BiConsumer<Result, String> done) {
        if (delta != 0) {
            synchronized (turning) {
                Turning t = turning.get(key);
                if (t != null) {
                    t.delta += delta;
                    t.done = done;
                    return;
                }
                t = new Turning();
                t.delta = delta;
                t.done = done;
                turning.put(key, t);
            }
        }
        Handler h = handler();
        Handler back = main;
        h.post(() -> {
            CarAccess access = Telemetry.get().carAccess();
            Car car = access == null ? null : new Car(access, SYSTEM_NAMES, SystemClock::sleep, memory(app), app);
            Result result = car == null ? Result.UNAVAILABLE : Result.DONE;
            String text = null;
            int total = 0;
            int writes = 0;
            java.util.function.BiConsumer<Result, String> finish = done;
            if (delta != 0) {
                for (Turning got = claim(key); got.delta != 0; got = claim(key)) {
                    finish = got.done;
                    total += got.delta;
                    writes++;
                    if (car != null) {
                        try {
                            result = dial.turn(car, got.delta);
                        } catch (RuntimeException e) {
                            result = Result.UNAVAILABLE;
                            car.note("failed: " + e);
                        }
                    }
                }
            }
            if (car != null) {
                try {
                    text = dial.shown(car, app, total);
                } catch (RuntimeException e) {
                    car.note("failed: " + e);
                }
            }
            if (delta != 0) {
                String detail = car == null ? "car interface not connected" : car.detail();
                BlackBox.note("X旋钮动作 " + key + " " + total + (writes > 1 ? "（并成 " + writes + " 次写）" : "")
                        + "：" + result + "（" + detail + "）");
            }
            Result r = result;
            String t = text;
            java.util.function.BiConsumer<Result, String> callback = finish;
            back.post(() -> callback.accept(r, t));
        });
    }

    private static Turning claim(String key) {
        synchronized (turning) {
            Turning t = turning.get(key);
            Turning got = new Turning();
            if (t == null) {
                return got;
            }
            got.delta = t.delta;
            got.done = t.done;
            t.delta = 0;
            if (got.delta == 0) {
                turning.remove(key);
            }
            return got;
        }
    }

    static void look(Context app, KnobDials.Dial dial, Consumer<KnobDials.Positions> seen) {
        Handler h = handler();
        Handler back = main;
        h.post(() -> {
            CarAccess access = Telemetry.get().carAccess();
            KnobDials.Positions positions = null;
            if (access != null) {
                Car car = new Car(access, SYSTEM_NAMES, SystemClock::sleep, memory(app), app);
                try {
                    positions = dial.positions(car, app);
                } catch (RuntimeException e) {
                    positions = null;
                }
            }
            KnobDials.Positions p = positions;
            back.post(() -> seen.accept(p));
        });
    }

    private static Memory memory(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(SmartButtons.STATE, Context.MODE_PRIVATE);
        return new Memory() {
            @Override
            public int get(String key, int fallback) {
                return prefs.getInt(key, fallback);
            }

            @Override
            public void put(String key, int value) {
                prefs.edit().putInt(key, value).apply();
            }
        };
    }

    static int glow() {
        CarAccess access = Telemetry.get().carAccess();
        return access == null ? -1 : glow(new Car(access, SYSTEM_NAMES, SystemClock::sleep, null, null));
    }

    static final int KEEP_TINT = -2;
    static final int[] THEMES = {0xE9947D, 0xD6ADF1, 0x78E8C9, 0xED9049, 0x76ABE8, 0xD1E259};

    static int glow(Car car) {
        Integer zones = car.function("SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES", "IAmbienceLight");
        Integer on = zones == null ? null : car.access.read(zones);
        if (on == null || on != 1) {
            return -1;
        }
        Integer main = car.function("SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR", "IAmbienceLight", "IVehicle");
        Integer mode = main == null ? null : car.access.read(main);
        if (main != null && mode != null && mode == main + 4) {
            return KEEP_TINT;
        }
        if (main != null && mode != null && mode == main + 1) {
            Integer theme = car.function("SETTING_FUNC_AMBIENCE_LIGHT_THEME_COLOR", "IAmbienceLight");
            Integer n = theme == null ? null : car.access.read(theme);
            return THEMES[n != null && n >= 1 && n <= THEMES.length ? n - 1 : 0];
        }
        for (String field : new String[] {"SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES_COLOR_SET",
                "SETTING_FUNC_AMBIENCE_LIGHT_TOPZONES_COLOR_SET"}) {
            Integer id = car.function(field, "IAmbienceLight");
            Integer color = id == null ? null : car.access.read(id);
            if (color != null && (color >>> 24) != 0) {
                return color & 0xFFFFFF;
            }
        }
        return -1;
    }

    // ================================================================= 读写车（写车的线程）

    /** 一件事里的读写：全车的带不带区域、读回、执行前的两样检查，读到写下的记一笔（ASCII，进黑匣子）。 */
    static final class Car {
        final CarAccess access;
        final Names names;
        final Sleeper sleeper;
        final Memory memory;
        /** 音量那两件要；测试里是 null。 */
        final Context app;
        private final StringBuilder detail = new StringBuilder();

        Car(CarAccess access, Names names, Sleeper sleeper, Memory memory, Context app) {
            this.access = access;
            this.names = names;
            this.sleeper = sleeper;
            this.memory = memory;
            this.app = app;
        }

        void note(String text) {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(text);
        }

        String detail() {
            return detail.toString();
        }

        int zone(String name) {
            return names.zone(name);
        }

        int all() {
            return names.zone(CarNames.ALL);
        }

        /** 功能号；这台车机没有就记一笔、返回 null。按 {@code ifaces} 的次序找（同一个常量在两个接口上都有的）。 */
        Integer function(String field, String... ifaces) {
            for (String iface : ifaces) {
                Integer id = names.function(iface, field);
                if (id != null) {
                    return id;
                }
            }
            note("no " + field);
            return null;
        }

        /** 读：全车先带「全车」区域，读不到再不带区域；别的区域只带区域读。 */
        Integer read(int id, int zone) {
            if (zone == all()) {
                Integer v = access.read(id, zone);
                return v != null ? v : access.read(id);
            }
            return access.read(id, zone);
        }

        /** 写（不读回）：全车先带「全车」区域，车机不受理再不带区域。车机受理了返回 true。 */
        boolean write(int id, int zone, int value) {
            boolean accepted = access.write(id, zone, value);
            if (!accepted && zone == all()) {
                accepted = access.write(id, value);
            }
            note(String.format(Locale.US, "write %s@%s=%s %s", hex(id), hex(zone), hex(value), accepted ? "ok" : "refused"));
            return accepted;
        }

        /** 写一个值、读回来：{@link #BACK_MS} 内变成写的值是 {@link Result#DONE}，没变是 {@link Result#NO_CHANGE}，车机不受理是做不了。 */
        Result set(int id, int zone, int value) {
            Integer before = read(id, zone);
            if (!write(id, zone, value)) {
                return Result.UNAVAILABLE;
            }
            return back(() -> read(id, zone), value, before);
        }

        /** 不带区域写、读回（驾驶模式：本项目车上验证过的写法）。 */
        Result setPlain(int id, int value) {
            Integer before = access.read(id);
            boolean accepted = access.write(id, value);
            note(String.format(Locale.US, "write %s=%s %s", hex(id), hex(value), accepted ? "ok" : "refused"));
            if (!accepted) {
                return Result.UNAVAILABLE;
            }
            return back(() -> access.read(id), value, before);
        }

        /** 读回：{@link #BACK_MS} 内读到的跟写之前的不一样了算成了（读、写意思反着的那种号用，{@link #mirrors}）。 */
        Result changed(java.util.function.Supplier<Integer> reader, int before) {
            Integer seen = null;
            for (long n = BACK_MS / BACK_STEP_MS, waited = BACK_STEP_MS; n > 0; n--, waited += BACK_STEP_MS) {
                sleeper.sleep(BACK_STEP_MS);
                seen = reader.get();
                if (seen != null && seen != before) {
                    note("changed to " + hex(seen) + " after " + waited + " ms");
                    return Result.DONE;
                }
            }
            note("still " + hex(seen) + " after " + BACK_MS + " ms");
            return Result.NO_CHANGE;
        }

        private Result back(java.util.function.Supplier<Integer> reader, int value, Integer before) {
            Integer seen = null;
            for (long n = BACK_MS / BACK_STEP_MS, waited = BACK_STEP_MS; n > 0; n--, waited += BACK_STEP_MS) {
                sleeper.sleep(BACK_STEP_MS);
                seen = reader.get();
                if (seen != null && seen == value) {
                    note("back after " + waited + " ms");
                    return Result.DONE;
                }
            }
            note("before " + hex(before) + ", still " + hex(seen) + " after " + BACK_MS + " ms");
            return Result.NO_CHANGE;
        }

        /**
         * 写了关的那几扇：{@link #DOOR_BACK_MS} 内都读到 0（关好了）算成了。到时有一扇读到号加 1（受阻、停在半路，
         * Lab findings §2.139）是做不了；还开着是没生效。关门途中一直读 1，不提前停。
         */
        Result closed(List<Signal> doors) {
            for (long waited = DOOR_STEP_MS; waited <= DOOR_BACK_MS; waited += DOOR_STEP_MS) {
                sleeper.sleep(DOOR_STEP_MS);
                if (allClosed(doors)) {
                    note("closed after " + waited + " ms");
                    return Result.DONE;
                }
            }
            StringBuilder still = new StringBuilder();
            boolean stopped = false;
            for (Signal door : doors) {
                Integer v = read(door.id, door.zone);
                stopped |= v != null && v == door.id + 1;
                still.append(' ').append(hex(door.zone)).append('=').append(hex(v));
            }
            note((stopped ? "stopped halfway" : "not closed") + " after " + DOOR_BACK_MS + " ms:" + still);
            return stopped ? Result.UNAVAILABLE : Result.NO_CHANGE;
        }

        private boolean allClosed(List<Signal> doors) {
            for (Signal door : doors) {
                Integer v = read(door.id, door.zone);
                if (v == null || v != 0) {
                    return false;
                }
            }
            return true;
        }

        /** 超级省电开着（读不到当没开：这台车机没有这个号，就没有这件事）。 */
        boolean powerSaving() {
            Integer id = names.function("IVehicle", "SETTING_FUNC_POWER_SAVING_MODE");
            if (id == null) {
                return false;
            }
            Integer v = read(id, all());
            return v != null && v == 1;
        }

        /** 车停稳了：车速读得到、不超过 {@link #STANDSTILL_KMH}。 */
        boolean standingStill() {
            Float mps = access.sensor(Signal.SPEED.id);
            note("speed " + (mps == null ? "?" : String.format(Locale.US, "%.1f km/h", mps * 3.6f)));
            return mps != null && mps * 3.6 <= STANDSTILL_KMH;
        }
    }

    // ================================================================= 执行前的检查

    /** 超级省电时不执行（座椅、方向盘、音量、一键运动）。 */
    static Job saving(Job job) {
        return c -> c.powerSaving() ? Result.POWER_SAVING : job.run(c);
    }

    /** 车没停稳不执行（座椅恢复、后视镜、充电口盖、关门）。 */
    static Job standstill(Job job) {
        return c -> c.standingStill() ? job.run(c) : Result.MOVING;
    }

    // ================================================================= 几种换法

    /** 哨兵模式的开关：灵动贴的「哨兵模式」（互换）和主界面录制键旁的「开启哨兵模式」（只开）写的是同一个。 */
    private static final String SENTRY_IFACE = "IVehicle";
    private static final String SENTRY_FIELD = "SETTING_FUNC_VSTD_MODE_STS";

    static Job sentryToggle() {
        return toggle(SENTRY_IFACE, SENTRY_FIELD, CarNames.ALL);
    }

    /** 哨兵模式只开不关：读到开着（1）或布防（2）不写、算做成；别的写 1、读回。 */
    static Job sentryOn() {
        return c -> {
            Integer id = c.function(SENTRY_FIELD, SENTRY_IFACE);
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(CarNames.ALL);
            Integer cur = c.read(id, zone);
            if (cur != null && (cur == 1 || cur == 2)) {
                c.note("already " + hex(cur));
                return Result.DONE;
            }
            return c.set(id, zone, 1);
        };
    }

    /**
     * 哨兵模式只关不开（主界面那个键亮着时再点一下：反悔）：读到关着（0）不写、算做成；开着（1）写 0、读回；
     * 读到别的（锁车后布防的那个值、读不到）不写，同 {@link #toggle}。
     */
    static Job sentryOff() {
        return c -> {
            Integer id = c.function(SENTRY_FIELD, SENTRY_IFACE);
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(CarNames.ALL);
            Integer cur = c.read(id, zone);
            if (cur != null && cur == 0) {
                c.note("already " + hex(cur));
                return Result.DONE;
            }
            if (cur == null || cur != 1) {
                c.note(SENTRY_FIELD + " read " + hex(cur) + ", not 0/1");
                return Result.UNAVAILABLE;
            }
            return c.set(id, zone, 0);
        };
    }

    /** 没做成时提示哪一句（做成了是 null：车上看得到）。灵动贴的写车动作、主界面的「开启哨兵模式」共用。 */
    static String failureText(Context app, String label, Result result) {
        switch (result) {
            case DONE:
                return null;
            case NO_CHANGE:
                return app.getString(com.kooo.evcam.R.string.msg_car_action_no_change, label);
            case POWER_SAVING:
                return app.getString(com.kooo.evcam.R.string.msg_car_action_power_saving);
            case MOVING:
                return app.getString(com.kooo.evcam.R.string.msg_car_action_standstill);
            case NOT_FITTED:
                return app.getString(com.kooo.evcam.R.string.msg_car_action_no_fragrance);
            case UNAVAILABLE:
            default:
                return app.getString(com.kooo.evcam.R.string.msg_car_action_unavailable, label);
        }
    }

    /** 0 / 1 互换（读到别的不写：比如哨兵锁车后的那个值）。 */
    static Job toggle(String iface, String field, String zoneName) {
        return toggleAny(field, zoneName, iface);
    }

    /** 同 {@link #toggle}，常量在几个接口上都有的（中控锁），按 {@code ifaces} 的次序找。 */
    static Job toggleAny(String field, String zoneName, String... ifaces) {
        return c -> {
            Integer id = c.function(field, ifaces);
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(zoneName);
            Integer cur = c.read(id, zone);
            Integer next = flipped(cur);
            if (next == null) {
                c.note(field + " read " + hex(cur) + ", not 0/1");
                return Result.UNAVAILABLE;
            }
            return c.set(id, zone, next);
        };
    }

    /**
     * 后视镜折叠 / 展开（读到几就写几，这个号读、写的意思像是反着的，Lab findings §2.92；这台车上还没试）：
     * 只认 0 / 1；写完读到的变了算成了。
     */
    static Job mirrors() {
        return c -> {
            Integer id = c.function("BCM_FUNC_FOLD_REAR_MIRROR", "IBcm");
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.all();
            Integer cur = c.read(id, zone);
            if (cur == null || (cur != 0 && cur != 1)) {
                c.note("mirror read " + hex(cur) + ", not 0/1");
                return Result.UNAVAILABLE;
            }
            if (!c.write(id, zone, cur)) {
                return Result.UNAVAILABLE;
            }
            return c.changed(() -> c.read(id, zone), cur);
        };
    }

    /**
     * 关门（项目所有者 2026-11-15）：只关不开，一扇或几扇（「关闭所有车门」是四扇侧门，后备箱单独一项）。
     * 号、区域用车辆信号表里车上对过的那几项（{@link Signal#DOOR_DRIVER} 这些，信息条读的也是它们）。
     * 照 Lab findings §2.93、§2.139：读到 0 是关着、1 是开着（关门途中也是 1）、号加 1 是受阻或停在半路；开着、停在半路的写 0；
     * 车机说这一扇不是 active（没有电动门的车、没有电动门的那一扇）就不写。
     * 项目所有者的车侧门用不了（副驾那扇是 notavailable），选项照留，给有电动门的车用（项目所有者 2026-11-15）。
     *
     * <p>几扇一起关时一扇一扇写，两扇之间隔 {@link #DOOR_GAP_MS}（同车自己的设置页）。
     * 读不到、读到别的值的那一扇跳过（几扇一起关时不影响别的）；一扇都读不到是做不了。都关着算做成；
     * 有开着的、一扇都写不了是做不了；写了的 {@link #DOOR_BACK_MS} 内都读到关好算做成，到时停在半路是做不了，还开着是没生效。</p>
     */
    static Job closeDoors(Signal... doors) {
        return c -> {
            List<Signal> closing = new ArrayList<>();
            int known = 0;
            int open = 0;
            for (Signal door : doors) {
                String which = "door " + hex(door.zone);
                Integer cur = c.read(door.id, door.zone);
                if (cur == null || (cur != 0 && cur != 1 && cur != door.id + 1)) {
                    c.note(which + " read " + hex(cur));
                    continue;
                }
                known++;
                if (cur == 0) {
                    c.note(which + " closed");
                    continue;
                }
                open++;
                String status = c.access.status(door.id, door.zone);
                if (!active(status)) {
                    c.note(which + " " + (status == null ? "status ?" : status));
                    continue;
                }
                if (!closing.isEmpty()) {
                    c.sleeper.sleep(DOOR_GAP_MS);
                }
                if (c.write(door.id, door.zone, 0)) {
                    closing.add(door);
                }
            }
            if (known == 0) {
                return Result.UNAVAILABLE;
            }
            if (open == 0) {
                return Result.DONE;
            }
            if (closing.isEmpty()) {
                return Result.UNAVAILABLE;
            }
            return c.closed(closing);
        };
    }

    /** 是 1 写 0，别的都写 1（360、充电灯语）。 */
    static Job onUnlessOn(String iface, String field, String zoneName) {
        return c -> {
            Integer id = c.function(field, iface);
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(zoneName);
            Integer cur = c.read(id, zone);
            return c.set(id, zone, cur != null && cur == 1 ? 0 : 1);
        };
    }

    /** 加热、通风：关 → 3 档 → 2 档 → 1 档 → 关；读到别的写 3 档。档位的值是功能号加 3、2、1。 */
    static Job levels(String iface, String field, String zoneName) {
        return c -> {
            Integer id = c.function(field, iface);
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(zoneName);
            return c.set(id, zone, nextLevel(id, c.read(id, zone)));
        };
    }

    /** 二排座椅加热两侧一起：照左边换到下一档，两侧写同一档。 */
    static Job rearSeatsHeat() {
        return c -> {
            Integer id = c.function("HVAC_FUNC_SEAT_HEATING", "IHvac");
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int left = c.zone(CarNames.ROW_2_LEFT);
            int v = nextLevel(id, c.read(id, left));
            Result l = c.set(id, left, v);
            Result r = c.set(id, c.zone(CarNames.ROW_2_RIGHT), v);
            return l == Result.DONE ? r : l;
        };
    }

    /** 座椅按摩：开关关着就打开、调到 3 档；已经 1 档就关开关；不然往下一档。 */
    static Job massage(String zoneName) {
        return c -> {
            Integer sw = c.function("HVAC_FUNC_SEAT_MASSAGE_SWITCH", "IHvac");
            Integer level = c.function("HVAC_FUNC_SEAT_MASSAGE", "IHvac");
            if (sw == null || level == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.zone(zoneName);
            Integer on = c.read(sw, zone);
            Integer cur = c.read(level, zone);
            if (on == null || on == 0) {
                c.write(sw, zone, 1);
                return c.set(level, zone, level + 3);
            }
            if (cur != null && cur == level + 1) {
                return c.set(sw, zone, 0);
            }
            return c.set(level, zone, nextLevel(level, cur));
        };
    }

    /** 循环模式：自动 → 外循环 → 内循环 → 自动；读到别的写外循环。值是功能号加 3（自动）、2（外）、1（内）。 */
    static Job circulation() {
        return c -> {
            Integer id = c.function("HVAC_FUNC_CIRCULATION", "IHvac");
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.all();
            return c.set(id, zone, next(c.read(id, zone), id + 2, id + 3, id + 2, id + 1));
        };
    }

    /** 写一个固定的值（座椅恢复写 1）。 */
    static Job write(String iface, String field, String zoneName, int value) {
        return c -> {
            Integer id = c.function(field, iface);
            return id == null ? Result.UNAVAILABLE : c.set(id, c.zone(zoneName), value);
        };
    }

    /** 行车灯语：照位置灯那个开关现在的状态，日行灯、位置灯两个开关一起换（是 1 写 0，别的写 1）。 */
    static Job drivingLights() {
        return c -> {
            Integer day = c.function("BCM_FUNC_AUX_DAY_TI_RUNNG_LI_SWITCH", "ILamp");
            Integer position = c.function("BCM_FUNC_AUX_POSN_LAMP_SWITCH", "ILamp");
            if (day == null || position == null) {
                return Result.UNAVAILABLE;
            }
            int zone = c.all();
            Integer cur = c.read(position, zone);
            int v = cur != null && cur == 1 ? 0 : 1;
            c.write(day, zone, v);
            return c.set(position, zone, v);
        };
    }

    /**
     * 后雾灯闪烁（jlunnn 的 PR #160，项目所有者 2026-11-06 收录：从他的 7X-U 搬来，他在 7X 车上实测过）：
     * {@link Signal#REAR_FOG} 不带区域写，闪三下（亮 {@link #FOG_ON_MS}、灭 {@link #FOG_OFF_MS}，最后回到 0）。
     * 这是 hazard 式的行车灯语，不看车速、不看省电（跟行车灯语一样）；定时一串写，读回不像别的那样等满一秒，
     * 闪的时候每步读一笔记在 detail 里：有一下读到 1 就算闪成了；车机全部拒绝是做不了、受理了但一直读回原值是没生效。
     */
    static Job rearFogFlash() {
        return c -> {
            int id = Signal.REAR_FOG.id;
            Integer before = c.access.read(id);
            c.note("before " + hex(before));
            boolean accepted = false;
            boolean sawOn = false;
            for (int i = 0; i < FOG_FLASHES; i++) {
                boolean on = c.access.write(id, 1);
                accepted |= on;
                c.sleeper.sleep(FOG_ON_MS);
                Integer mid = c.access.read(id);
                boolean off = c.access.write(id, 0);
                accepted |= off;
                c.sleeper.sleep(FOG_OFF_MS);
                Integer after = c.access.read(id);
                sawOn |= mid != null && mid == 1;
                c.note("flash " + (i + 1) + ": on " + (on ? "ok" : "refused") + "->" + hex(mid)
                        + ", off " + (off ? "ok" : "refused") + "->" + hex(after));
            }
            if (!accepted) {
                return Result.UNAVAILABLE;
            }
            return sawOn ? Result.DONE : Result.NO_CHANGE;
        };
    }

    /**
     * 香氛：三个香槽有一个装了才算有。开关关着就打开、在用的那个香槽调到淡雅（{@link #FRAGRANCE_RECHECK_MS} 后开关还关着
     * 就再写一次）；开着就往下一档：淡雅 → 适中 → 馥郁 → 关，到关把开关也关掉。香槽、档位的值是各自的功能号加 1、2、3。
     */
    static Job fragrance() {
        return c -> {
            Integer sw = c.function("HVAC_FUNC_AIR_FRAGRANCE_SWITCH", "IHvac");
            Integer level = c.function("HVAC_FUNC_AIR_FRAGRANCE_LEVEL", "IFragrance", "IHvac");
            Integer slots = c.function("HVAC_FUNC_AIR_FRAGRANCE_SLOT", "IFragrance");
            Integer type = c.function("HVAC_FUNC_AIR_FRAGRANCE_TYPE_ID", "IFragrance");
            if (sw == null || level == null || slots == null || type == null) {
                return Result.UNAVAILABLE;
            }
            boolean fitted = false;
            int slot = 0;
            for (int n = 1; n <= 3; n++) {
                int s = slots + n;
                fitted |= fitted(c.access.read(type, s));
                Integer inUse = c.access.read(slots, s);
                if (inUse != null && inUse == 1 && slot == 0) {
                    slot = s;
                }
            }
            if (!fitted) {
                return Result.NOT_FITTED;
            }
            if (slot == 0) {
                slot = slots + 1;
            }
            Integer on = c.access.read(sw, 0);
            if (on == null || on == 0) {
                c.write(level, slot, level + 1);
                c.write(sw, 0, 1);
                c.sleeper.sleep(FRAGRANCE_RECHECK_MS);
                Integer again = c.access.read(sw, 0);
                if (again == null || again == 0) {
                    c.write(sw, 0, 1);
                    c.write(level, slot, level + 1);
                }
                return c.back(() -> c.access.read(sw, 0), 1, on);
            }
            int v = next(c.access.read(level, slot), level + 1, 0, level + 1, level + 2, level + 3);
            Result r = c.set(level, slot, v);
            if (v == 0) {
                c.write(sw, 0, 0);
            }
            return r;
        };
    }

    /** 切到一种驾驶模式（不带区域，本项目车上验证过的写法）。 */
    static Job driveMode(int mode) {
        return c -> c.setPlain(Signal.DRIVE_MODE.id, mode);
    }

    /** 一键运动：不在运动就记下现在的模式、切运动；已经是运动就回到记下的（没记过是标准）。 */
    static Job sport() {
        return c -> {
            int id = Signal.DRIVE_MODE.id;
            Integer cur = c.access.read(id);
            if (cur != null && cur == Signal.DRIVE_MODE_SPORT) {
                return c.setPlain(id, c.memory.get(SPORT_SAVED, Signal.DRIVE_MODE_STANDARD));
            }
            if (cur != null) {
                c.memory.put(SPORT_SAVED, cur);
            }
            return c.setPlain(id, Signal.DRIVE_MODE_SPORT);
        };
    }

    static Job volume(int steps) {
        return volume(steps, AudioManager.STREAM_MUSIC);
    }

    static Job volume(int steps, int stream) {
        return c -> {
            AudioManager audio = c.app == null ? null : c.app.getSystemService(AudioManager.class);
            if (audio == null || steps == 0) {
                return Result.UNAVAILABLE;
            }
            int n = steps > 0 ? Math.min(steps, VOLUME_UP_MAX) : -steps;
            int direction = steps > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            for (int i = 0; i < n; i++) {
                audio.adjustSuggestedStreamVolume(direction, stream, AudioManager.FLAG_SHOW_UI);
                if (i + 1 < n) {
                    c.sleeper.sleep(VOLUME_GAP_MS);
                }
            }
            c.note("volume " + (steps > 0 ? "+" : "-") + n + " via the suggested stream " + stream);
            return Result.DONE;
        };
    }

    static Job temperature(int steps) {
        return temperature(steps, true, CarNames.DRIVER, CarNames.PASSENGER);
    }

    static Job temperature(int steps, boolean open, String... zoneNames) {
        return c -> {
            Integer id = c.function(TEMP_FIELD, "IHvac");
            if (id == null) {
                return Result.UNAVAILABLE;
            }
            int first = c.zone(zoneNames[0]);
            Float now = c.access.readFloat(id, first);
            if (now == null) {
                c.note("temperature unreadable");
                return Result.UNAVAILABLE;
            }
            float to = nextTemperature(now, steps);
            c.note(String.format(Locale.US, "temperature %.1f -> %.1f", now, to));
            if (to == now) {
                return Result.DONE;
            }
            if (open) {
                for (String name : zoneNames) {
                    openClimate(c, c.zone(name));
                }
            }
            boolean accepted = true;
            for (String name : zoneNames) {
                accepted &= c.access.writeFloat(id, c.zone(name), to);
            }
            c.note(accepted ? "written" : "refused");
            if (!accepted) {
                return Result.UNAVAILABLE;
            }
            for (long n = BACK_MS / BACK_STEP_MS, waited = BACK_STEP_MS; n > 0; n--, waited += BACK_STEP_MS) {
                c.sleeper.sleep(BACK_STEP_MS);
                Float seen = c.access.readFloat(id, first);
                if (seen != null && Math.abs(seen - to) < 0.01f) {
                    c.note("back after " + waited + " ms");
                    return Result.DONE;
                }
            }
            c.note("not back after " + BACK_MS + " ms");
            return Result.NO_CHANGE;
        };
    }

    private static void openClimate(Car c, int zone) {
        Integer power = c.function("HVAC_FUNC_POWER", "IHvac");
        if (power == null) {
            return;
        }
        Integer on = c.read(power, zone);
        if (on != null && on == 0) {
            c.write(power, zone, 1);
        }
    }

    // ================================================================= 纯函数

    static float nextTemperature(float now, int steps) {
        float to = Math.round((now + TEMP_STEP * steps) * 2) / 2f;
        return Math.max(TEMP_MIN, Math.min(TEMP_MAX, to));
    }

    /** 车机说能用：{@code active}（枚举的名字；带着类名的也认）。别的（notactive、notavailable、error、问不了）都不算。 */
    static boolean active(String status) {
        if (status == null) {
            return false;
        }
        String s = status.trim().toLowerCase(Locale.US);
        return s.equals("active") || s.endsWith(".active");
    }

    /** 0 → 1、1 → 0；别的是 null（不认识，不写）。 */
    static Integer flipped(Integer cur) {
        if (cur == null) {
            return null;
        }
        return cur == 0 ? Integer.valueOf(1) : cur == 1 ? Integer.valueOf(0) : null;
    }

    /** 在 {@code order} 里往后挪一个（到头回到第一个）；现在的值不在里面（或者读不到）是 {@code unknown}。 */
    static int next(Integer cur, int unknown, int... order) {
        if (cur != null) {
            for (int i = 0; i < order.length; i++) {
                if (order[i] == cur) {
                    return order[(i + 1) % order.length];
                }
            }
        }
        return unknown;
    }

    /** 档位（关 → 3 → 2 → 1 → 关）：功能号是 {@code id}，档位的值是它加 3、2、1；读到别的是 3 档。 */
    static int nextLevel(int id, Integer cur) {
        return next(cur, id + 3, 0, id + 3, id + 2, id + 1);
    }

    /** 香槽装了没有：不为 0 就算；255、负数这些占位值不算（这台车上读到过 -65535，Lab 3.5.3）。 */
    static boolean fitted(Integer type) {
        return type != null && type > 0 && type != 255;
    }

    static String hex(Integer v) {
        return v == null ? "?" : String.format(Locale.US, "0x%X", v);
    }
}
