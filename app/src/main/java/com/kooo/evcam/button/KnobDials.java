/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.CarNames;
import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

final class KnobDials {

    interface Dial {
        CarMoves.Result turn(CarMoves.Car c, int delta);

        String shown(CarMoves.Car c, Context app, int delta);

        default Positions positions(CarMoves.Car c, Context app) {
            return null;
        }
    }

    static final class Positions {
        final String[] labels;
        final int index;

        Positions(String[] labels, int index) {
            this.labels = labels;
            this.index = index;
        }
    }

    static final long SUSPENSION_SETTLE_MS = 500;
    static final long WINDOW_GAP_MS = 3;
    static final int PERSONAL = 0x40;
    static final String[] WINDOWS = {CarNames.WINDOW_ROW_1_LEFT, CarNames.WINDOW_ROW_1_RIGHT,
            CarNames.WINDOW_ROW_2_LEFT, CarNames.WINDOW_ROW_2_RIGHT};
    static final float[] ALL_OPEN = {100, 100, 100, 100};
    static final float[] VENT = {12, 12, 20, 20};
    static final float[] ALL_CLOSED = {0, 0, 0, 0};

    static final int[] LEVELS = {0, 1, 2, 3};
    static final int[] AUTO_FAN = {0, 4, 1, 2, 3, 5};
    static final int[] AUTO_FAN_NAMES = {R.string.quick_off, R.string.knob_v_fan_very_low, R.string.knob_v_fan_low,
            R.string.knob_v_fan_mid, R.string.knob_v_fan_high, R.string.knob_v_fan_very_high};
    static final int[] DRIVE_MODES = {0x48, 0x03, 0x02, 0x01, 0x47, 0x44, 0x0D, 0x0A, 0x42, 0x09, 0x0B, 0x13, 0x45, 0x40};
    static final int[] DRIVE_MODE_NAMES = {R.string.knob_v_sport_plus, R.string.vi_v_drive_sport,
            R.string.vi_v_drive_standard, R.string.vi_v_drive_comfort, R.string.knob_v_adaptive, R.string.knob_v_mountain,
            R.string.knob_v_sand, R.string.knob_v_mud, R.string.knob_v_grass, R.string.vi_v_drive_snow, R.string.knob_v_rock,
            R.string.knob_v_offroad, R.string.knob_v_wet, R.string.knob_v_personal};

    private static volatile ListDial driveModes;

    private KnobDials() {
    }

    static class ListDial implements Dial {
        final String field;
        final String[] ifaces;
        final String zoneName;
        final int[] steps;
        final boolean raw;
        final int[] names;
        boolean needsActive;

        ListDial(String field, String zoneName, int[] steps, boolean raw, int[] names, String... ifaces) {
            this.field = field;
            this.zoneName = zoneName;
            this.steps = steps;
            this.raw = raw;
            this.names = names;
            this.ifaces = ifaces;
        }

        ListDial active() {
            needsActive = true;
            return this;
        }

        int value(int id, int i) {
            return raw || steps[i] == 0 ? steps[i] : id + steps[i];
        }

        int index(int id, Integer v) {
            if (v != null) {
                for (int i = 0; i < steps.length; i++) {
                    if (value(id, i) == v) {
                        return i;
                    }
                }
            }
            return -1;
        }

        String label(Context app, int i) {
            return names != null && names[i] != 0 ? app.getString(names[i]) : String.valueOf(steps[i]);
        }

        Integer id(CarMoves.Car c) {
            return c.function(field, ifaces);
        }

        int zone(CarMoves.Car c) {
            return c.zone(zoneName);
        }

        Integer read(CarMoves.Car c, int id, int zone) {
            return c.read(id, zone);
        }

        void before(CarMoves.Car c, int id, int zone, int target) {
        }

        CarMoves.Result apply(CarMoves.Car c, int id, int zone, int target) {
            return c.set(id, zone, target);
        }

        void after(CarMoves.Car c, int id, int zone, int target) {
        }

        @Override
        public CarMoves.Result turn(CarMoves.Car c, int delta) {
            Integer id = id(c);
            if (id == null) {
                return CarMoves.Result.UNAVAILABLE;
            }
            return stepTS(c, id, zone(c), delta);
        }

        CarMoves.Result stepTS(CarMoves.Car c, int id, int zone, int delta) {
            if (needsActive) {
                String status = c.access.status(id, zone);
                if (!CarMoves.active(status)) {
                    c.note(field + " " + status);
                    return CarMoves.Result.UNAVAILABLE;
                }
            }
            Integer cur = read(c, id, zone);
            int i = index(id, cur);
            int to = i < 0 ? 0 : Math.max(0, Math.min(steps.length - 1, i + delta));
            if (to == i) {
                c.note(field + " stays " + CarMoves.hex(cur));
                return CarMoves.Result.DONE;
            }
            int target = value(id, to);
            before(c, id, zone, target);
            CarMoves.Result r = apply(c, id, zone, target);
            after(c, id, zone, target);
            return r;
        }

        @Override
        public String shown(CarMoves.Car c, Context app, int delta) {
            Integer id = id(c);
            if (id == null) {
                return null;
            }
            return shownAt(c, app, id, zone(c));
        }

        String shownAt(CarMoves.Car c, Context app, int id, int zone) {
            int i = index(id, read(c, id, zone));
            return i < 0 ? null : label(app, i);
        }

        @Override
        public Positions positions(CarMoves.Car c, Context app) {
            Integer id = id(c);
            return id == null ? null : positionsAt(c, app, id, zone(c));
        }

        Positions positionsAt(CarMoves.Car c, Context app, int id, int zone) {
            String[] all = new String[steps.length];
            for (int i = 0; i < steps.length; i++) {
                all[i] = label(app, i);
            }
            return new Positions(all, index(id, read(c, id, zone)));
        }
    }

    static class LevelDial extends ListDial {
        LevelDial(String field, String zoneName, String... ifaces) {
            super(field, zoneName, LEVELS, false, null, ifaces);
        }

        @Override
        String label(Context app, int i) {
            return i == 0 ? app.getString(R.string.quick_off) : app.getString(R.string.knob_v_level, steps[i]);
        }
    }

    static Dial saving(Dial dial) {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                return c.powerSaving() ? CarMoves.Result.POWER_SAVING : dial.turn(c, delta);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                return dial.shown(c, app, delta);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                return dial.positions(c, app);
            }
        };
    }

    static Dial volume(int stream) {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                return CarMoves.volume(delta, stream).run(c);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                if (delta == 0) {
                    return null;
                }
                return delta > 0 ? "+" + Math.min(delta, CarMoves.VOLUME_UP_MAX) : "-" + -delta;
            }
        };
    }

    static Dial temperature(boolean open, String... zoneNames) {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                return CarMoves.temperature(delta, open, zoneNames).run(c);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                Integer id = c.function(CarMoves.TEMP_FIELD, "IHvac");
                Float t = id == null ? null : c.access.readFloat(id, c.zone(zoneNames[0]));
                return t == null ? null : String.format(Locale.US, "%.1f°", t);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                Integer id = c.function(CarMoves.TEMP_FIELD, "IHvac");
                Float t = id == null ? null : c.access.readFloat(id, c.zone(zoneNames[0]));
                return t == null ? null : temperatures(t);
            }
        };
    }

    static Positions temperatures(float now) {
        int n = Math.round((CarMoves.TEMP_MAX - CarMoves.TEMP_MIN) / CarMoves.TEMP_STEP) + 1;
        String[] all = new String[n];
        for (int i = 0; i < n; i++) {
            all[i] = String.format(Locale.US, "%.1f°", CarMoves.TEMP_MIN + CarMoves.TEMP_STEP * i);
        }
        int at = Math.round((now - CarMoves.TEMP_MIN) / CarMoves.TEMP_STEP);
        return new Positions(all, Math.max(0, Math.min(n - 1, at)));
    }

    static Dial fan(String zoneName, int[] manualSteps, boolean openFirst, boolean closeAtZero) {
        ListDial manual = new ListDial("HVAC_FUNC_FAN_SPEED", zoneName, manualSteps, false, null, "IHvac") {
            @Override
            String label(Context app, int i) {
                return i == 0 ? app.getString(R.string.quick_off) : String.valueOf(steps[i]);
            }

            @Override
            void before(CarMoves.Car c, int id, int zone, int target) {
                if (openFirst && target != 0) {
                    CarMoves.openClimate(c, zone);
                }
            }
        };
        ListDial auto = new ListDial("HVAC_FUNC_AUTO_FAN_SETTING", zoneName, AUTO_FAN, false, AUTO_FAN_NAMES, "IHvac") {
            @Override
            void after(CarMoves.Car c, int id, int zone, int target) {
                Integer power = c.function("HVAC_FUNC_POWER", "IHvac");
                if (closeAtZero && target == 0 && power != null) {
                    c.write(power, zone, 0);
                }
            }
        };
        return new Dial() {
            ListDial pick(CarMoves.Car c) {
                Integer id = c.function("HVAC_FUNC_AUTO", "IHvac");
                Integer v = id == null ? null : c.read(id, c.zone(zoneName));
                return v != null && v == 1 ? auto : manual;
            }

            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                return pick(c).turn(c, delta);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                return pick(c).shown(c, app, delta);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                return pick(c).positions(c, app);
            }
        };
    }

    static Dial blowing(String zoneName, int[] steps, int[] names) {
        return new ListDial("HVAC_FUNC_BLOWING_MODE", zoneName, steps, false, names, "IHvac");
    }

    static Dial sweep(String zoneName) {
        return new ListDial("HVAC_FUNC_SWEEPING_MODE", zoneName, new int[] {0, 1, 3, 4, 5}, true,
                new int[] {R.string.knob_v_sweep_free, R.string.knob_v_sweep_swing, R.string.knob_v_sweep_focus,
                        R.string.knob_v_sweep_avoid, R.string.knob_v_sweep_natural}, "IHvac") {
            @Override
            void before(CarMoves.Car c, int id, int zone, int target) {
                CarMoves.openClimate(c, c.zone(CarNames.ROW_1));
                Integer blowing = c.function("HVAC_FUNC_BLOWING_MODE", "IHvac");
                Integer flow = blowing == null ? null : c.read(blowing, zone);
                if (blowing != null && flow != null && flow == blowing + 2) {
                    c.write(blowing, zone, blowing + 3);
                }
                Integer vent = c.function("HVAC_FUNC_SEAT_AIRVENT_SWITCH", "IHvac");
                if (vent != null) {
                    c.write(vent, zone, 1);
                }
            }
        };
    }

    static Dial toggle(String field, String zoneName, String... ifaces) {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                Integer id = c.function(field, ifaces);
                return id == null ? CarMoves.Result.UNAVAILABLE : c.set(id, c.zone(zoneName), delta > 0 ? 1 : 0);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                Integer id = c.function(field, ifaces);
                Integer v = id == null ? null : c.read(id, c.zone(zoneName));
                return onOff(app, v, 1, 0);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                Integer id = c.function(field, ifaces);
                return id == null ? null : onOff(app, c.read(id, c.zone(zoneName)), 1);
            }
        };
    }

    static Dial rapidCool() {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                Integer id = c.function("HVAC_FUNC_RAPID_COOLG_REQ", "IHvac");
                return id == null ? CarMoves.Result.UNAVAILABLE : c.set(id, c.all(), delta > 0 ? 0 : 1);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                Integer id = c.function("HVAC_FUNC_RAPID_COOLG_REQ", "IHvac");
                return onOff(app, id == null ? null : c.read(id, c.all()), 0, 1);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                Integer id = c.function("HVAC_FUNC_RAPID_COOLG_REQ", "IHvac");
                return id == null ? null : onOff(app, c.read(id, c.all()), 0);
            }
        };
    }

    static Positions onOff(Context app, Integer v, int on) {
        String[] all = {app.getString(R.string.quick_off), app.getString(R.string.quick_on)};
        return new Positions(all, v == null ? -1 : v == on ? 1 : 0);
    }

    static String onOff(Context app, Integer v, int on, int off) {
        if (v == null) {
            return null;
        }
        return v == on ? app.getString(R.string.quick_on) : v == off ? app.getString(R.string.quick_off) : null;
    }

    static Dial massage(String zoneName) {
        return new LevelDial("HVAC_FUNC_SEAT_MASSAGE", zoneName, "IHvac") {
            Integer switchId(CarMoves.Car c) {
                return c.function("HVAC_FUNC_SEAT_MASSAGE_SWITCH", "IHvac");
            }

            @Override
            Integer read(CarMoves.Car c, int id, int zone) {
                Integer sw = switchId(c);
                Integer on = sw == null ? null : c.read(sw, zone);
                return on != null && on == 0 ? Integer.valueOf(0) : c.read(id, zone);
            }

            @Override
            CarMoves.Result apply(CarMoves.Car c, int id, int zone, int target) {
                Integer sw = switchId(c);
                if (target == 0) {
                    return sw == null ? CarMoves.Result.UNAVAILABLE : c.set(sw, zone, 0);
                }
                Integer on = sw == null ? null : c.read(sw, zone);
                if (sw != null && on != null && on == 0) {
                    c.write(sw, zone, 1);
                }
                return c.set(id, zone, target);
            }
        };
    }

    static Dial fragrance() {
        ListDial level = new ListDial("HVAC_FUNC_AIR_FRAGRANCE_LEVEL", CarNames.ALL, LEVELS, false,
                new int[] {R.string.quick_off, R.string.knob_v_fragrance_light, R.string.knob_v_fragrance_medium,
                        R.string.knob_v_fragrance_strong}, "IFragrance", "IHvac");
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                Integer id = level.id(c);
                int slot = slot(c);
                if (id == null || slot < 0) {
                    return CarMoves.Result.UNAVAILABLE;
                }
                return slot == 0 ? CarMoves.Result.NOT_FITTED : level.stepTS(c, id, slot, delta);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                Integer id = level.id(c);
                int slot = slot(c);
                return id == null || slot <= 0 ? null : level.shownAt(c, app, id, slot);
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                Integer id = level.id(c);
                int slot = slot(c);
                return id == null || slot <= 0 ? null : level.positionsAt(c, app, id, slot);
            }
        };
    }

    static int slot(CarMoves.Car c) {
        Integer slots = c.function("HVAC_FUNC_AIR_FRAGRANCE_SLOT", "IFragrance");
        Integer type = c.function("HVAC_FUNC_AIR_FRAGRANCE_TYPE_ID", "IFragrance");
        if (slots == null || type == null) {
            return -1;
        }
        boolean fitted = false;
        int slot = 0;
        for (int n = 1; n <= 3; n++) {
            int s = slots + n;
            fitted |= CarMoves.fitted(c.access.read(type, s));
            Integer inUse = c.access.read(slots, s);
            if (inUse != null && inUse == 1 && slot == 0) {
                slot = s;
            }
        }
        if (!fitted) {
            return 0;
        }
        return slot == 0 ? slots + 1 : slot;
    }

    static Dial suspension() {
        return new ListDial("SETTING_FUNC_SUSPENSION_HEIGHT_ADJUST", CarNames.ALL, new int[] {1, 2, 3}, false,
                new int[] {R.string.knob_v_height_very_high, R.string.knob_v_height_high, R.string.knob_v_height_standard},
                "IVehicle") {
            @Override
            void before(CarMoves.Car c, int id, int zone, int target) {
                int mode = Signal.DRIVE_MODE.id;
                boolean accepted = c.access.write(mode, mode + PERSONAL);
                c.note("drive mode personal " + (accepted ? "ok" : "refused"));
                c.sleeper.sleep(SUSPENSION_SETTLE_MS);
            }
        }.active();
    }

    static Dial driveMode() {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                ListDial list = driveModes(c);
                return list.stepTS(c, Signal.DRIVE_MODE.id, c.all(), delta);
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                return driveModes(c).shownAt(c, app, Signal.DRIVE_MODE.id, c.all());
            }

            @Override
            public Positions positions(CarMoves.Car c, Context app) {
                return driveModes(c).positionsAt(c, app, Signal.DRIVE_MODE.id, c.all());
            }
        };
    }

    static ListDial driveModes(CarMoves.Car c) {
        ListDial cached = driveModes;
        if (cached != null) {
            return cached;
        }
        ListDial list = driveModeList(n -> {
            Integer v = c.access.config(n);
            return v == null ? -1 : v;
        });
        if (c.access.config(1) != null) {
            driveModes = list;
        }
        return list;
    }

    static ListDial driveModeList(IntUnaryOperator cfg) {
        List<Integer> steps = new ArrayList<>();
        List<Integer> names = new ArrayList<>();
        for (int i = 0; i < DRIVE_MODES.length; i++) {
            if (listed(DRIVE_MODES[i], cfg)) {
                steps.add(DRIVE_MODES[i]);
                names.add(DRIVE_MODE_NAMES[i]);
            }
        }
        int[] s = new int[steps.size()];
        int[] n = new int[names.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = steps.get(i);
            n[i] = names.get(i);
        }
        return new ListDial("", CarNames.ALL, s, false, n) {
            @Override
            Integer read(CarMoves.Car c, int id, int zone) {
                return c.access.read(id);
            }

            @Override
            CarMoves.Result apply(CarMoves.Car c, int id, int zone, int target) {
                return c.setPlain(id, target);
            }
        }.active();
    }

    static boolean listed(int mode, IntUnaryOperator cfg) {
        switch (mode) {
            case 0x48:
                return in(cfg.applyAsInt(599), 3, 4)
                        || (cfg.applyAsInt(1) == 163 && in(cfg.applyAsInt(695), 1, 2, 3) && cfg.applyAsInt(746) == 16);
            case 0x47:
                return cfg.applyAsInt(610) != 1;
            case 0x44:
                return in(cfg.applyAsInt(813), 4, 9, 16, 20, 21);
            case 0x0D:
                return cfg.applyAsInt(605) != 1;
            case 0x0A:
                return cfg.applyAsInt(607) != 1;
            case 0x42:
                return in(cfg.applyAsInt(813), 5, 6, 7, 8, 9, 16, 17);
            case 0x09:
                return cfg.applyAsInt(606) != 1;
            case 0x0B:
                return cfg.applyAsInt(608) != 1;
            case 0x13:
                return cfg.applyAsInt(604) == 2;
            case 0x45:
                return in(cfg.applyAsInt(813), 2, 3, 5, 6, 8, 9, 19, 20);
            case 0x40:
                return cfg.applyAsInt(603) != 1;
            default:
                return true;
        }
    }

    static boolean in(int v, int... set) {
        for (int x : set) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    static Dial windows(float[] open, int openName) {
        return new Dial() {
            @Override
            public CarMoves.Result turn(CarMoves.Car c, int delta) {
                Integer id = c.function("BCM_FUNC_WINDOW_POS", "IBcm");
                if (id == null) {
                    return CarMoves.Result.UNAVAILABLE;
                }
                float[] to = delta > 0 ? open : ALL_CLOSED;
                int written = 0;
                for (int i = 0; i < WINDOWS.length; i++) {
                    int zone = c.names.window(WINDOWS[i]);
                    String status = c.access.status(id, zone);
                    if (!CarMoves.active(status)) {
                        c.note("window " + CarMoves.hex(zone) + " " + status);
                        continue;
                    }
                    if (written > 0) {
                        c.sleeper.sleep(WINDOW_GAP_MS);
                    }
                    boolean accepted = c.access.writeFloat(id, zone, to[i]);
                    c.note(String.format(Locale.US, "window %s -> %.0f %s", CarMoves.hex(zone), to[i],
                            accepted ? "ok" : "refused"));
                    if (accepted) {
                        written++;
                    }
                }
                return written > 0 ? CarMoves.Result.DONE : CarMoves.Result.UNAVAILABLE;
            }

            @Override
            public String shown(CarMoves.Car c, Context app, int delta) {
                if (delta == 0) {
                    return null;
                }
                return app.getString(delta > 0 ? openName : R.string.knob_v_windows_close);
            }
        };
    }
}
