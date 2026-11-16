/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.media.AudioManager;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraKeeper;
import com.kooo.evcam.telemetry.CarNames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public enum KnobTurn {
    NONE("none", 0, null, null),
    VOLUME("volume", R.string.knob_fn_volume, ButtonAction.Group.MEDIA,
            KnobDials.saving(KnobDials.volume(AudioManager.STREAM_MUSIC))),
    CALL_VOLUME("call_volume", R.string.knob_fn_call_volume, ButtonAction.Group.MEDIA,
            KnobDials.volume(AudioManager.STREAM_VOICE_CALL)),
    TRACK("media_track", R.string.knob_fn_track, ButtonAction.Group.MEDIA, "media_next", "media_previous",
            R.string.smart_button_action_next, R.string.smart_button_action_previous),
    SOUND_EFFECT("sound_effect", R.string.knob_fn_sound_effect, ButtonAction.Group.MEDIA,
            new KnobDials.ListDial("SETTING_FUNC_ACOUSTIC_EFFECT_MODE", CarNames.ALL, new int[] {5, 7, 6, 1, 2, 3, 4, 0},
                    false, new int[] {R.string.knob_v_sound_hifi, R.string.knob_v_sound_bass, R.string.knob_v_sound_voice,
                            R.string.knob_v_sound_jazz, R.string.knob_v_sound_opera, R.string.knob_v_sound_theatre,
                            R.string.knob_v_sound_normal, R.string.quick_off}, "IAudio")),
    TEMPERATURE("climate_temp", R.string.knob_fn_temp, ButtonAction.Group.CLIMATE,
            KnobDials.temperature(true, CarNames.DRIVER, CarNames.PASSENGER)),
    DRIVER_TEMP("driver_temp", R.string.knob_fn_driver_temp, ButtonAction.Group.CLIMATE,
            KnobDials.temperature(false, CarNames.DRIVER)),
    PASSENGER_TEMP("passenger_temp", R.string.knob_fn_passenger_temp, ButtonAction.Group.CLIMATE,
            KnobDials.temperature(false, CarNames.PASSENGER)),
    REAR_TEMP("rear_temp", R.string.knob_fn_rear_temp, ButtonAction.Group.CLIMATE,
            KnobDials.temperature(false, CarNames.ROW_2_LEFT)),
    FAN("fan", R.string.knob_fn_fan, ButtonAction.Group.CLIMATE,
            KnobDials.fan(CarNames.ROW_1, new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, true, false)),
    REAR_FAN("rear_fan", R.string.knob_fn_rear_fan, ButtonAction.Group.CLIMATE,
            KnobDials.fan(CarNames.ROW_2, new int[] {0, 1, 2, 3, 4, 5}, false, true)),
    DRIVER_BLOWING("driver_blowing", R.string.knob_fn_driver_blowing, ButtonAction.Group.CLIMATE,
            KnobDials.blowing(CarNames.DRIVER, new int[] {1, 2, 4, 3, 5, 6, 7},
                    new int[] {R.string.knob_v_face, R.string.knob_v_feet, R.string.knob_v_defrost, R.string.knob_v_face_feet,
                            R.string.knob_v_face_defrost, R.string.knob_v_feet_defrost, R.string.knob_v_all_vents})),
    PASSENGER_BLOWING("passenger_blowing", R.string.knob_fn_passenger_blowing, ButtonAction.Group.CLIMATE,
            KnobDials.blowing(CarNames.PASSENGER, new int[] {1, 2, 3},
                    new int[] {R.string.knob_v_face, R.string.knob_v_feet, R.string.knob_v_face_feet})),
    REAR_BLOWING("rear_blowing", R.string.knob_fn_rear_blowing, ButtonAction.Group.CLIMATE,
            KnobDials.blowing(CarNames.ROW_2, new int[] {1, 2, 3},
                    new int[] {R.string.knob_v_face, R.string.knob_v_feet, R.string.knob_v_face_feet})),
    DRIVER_SWEEP("driver_sweep", R.string.knob_fn_driver_sweep, ButtonAction.Group.CLIMATE,
            KnobDials.sweep(CarNames.DRIVER)),
    PASSENGER_SWEEP("passenger_sweep", R.string.knob_fn_passenger_sweep, ButtonAction.Group.CLIMATE,
            KnobDials.sweep(CarNames.PASSENGER)),
    CIRCULATION("circulation", R.string.smart_button_action_circulation, ButtonAction.Group.CLIMATE,
            new KnobDials.ListDial("HVAC_FUNC_CIRCULATION", CarNames.ALL, new int[] {3, 2, 1}, false,
                    new int[] {R.string.knob_v_circ_auto, R.string.knob_v_circ_outside, R.string.knob_v_circ_inside}, "IHvac")),
    CLIMATE_POWER("climate_power", R.string.smart_button_action_ac, ButtonAction.Group.CLIMATE,
            KnobDials.toggle("HVAC_FUNC_POWER", CarNames.ROW_1, "IHvac")),
    AC("ac", R.string.knob_fn_ac, ButtonAction.Group.CLIMATE, KnobDials.toggle("HVAC_FUNC_AC", CarNames.ALL, "IHvac")),
    RAPID_COOL("rapid_cool", R.string.knob_fn_rapid_cool, ButtonAction.Group.CLIMATE,
            KnobDials.saving(KnobDials.rapidCool())),
    DRIVER_SEAT_HEAT("driver_seat_heat", R.string.smart_button_action_seat_heat_driver, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_HEATING", CarNames.DRIVER, "IHvac"))),
    PASSENGER_SEAT_HEAT("passenger_seat_heat", R.string.smart_button_action_seat_heat_passenger, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_HEATING", CarNames.PASSENGER, "IHvac"))),
    REAR_LEFT_SEAT_HEAT("rear_left_seat_heat", R.string.smart_button_action_seat_heat_rear_left, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_HEATING", CarNames.ROW_2_LEFT, "IHvac"))),
    REAR_RIGHT_SEAT_HEAT("rear_right_seat_heat", R.string.smart_button_action_seat_heat_rear_right, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_HEATING", CarNames.ROW_2_RIGHT, "IHvac"))),
    DRIVER_SEAT_VENT("driver_seat_vent", R.string.smart_button_action_seat_vent_driver, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_VENTILATION", CarNames.DRIVER, "IHvac"))),
    PASSENGER_SEAT_VENT("passenger_seat_vent", R.string.smart_button_action_seat_vent_passenger, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_SEAT_VENTILATION", CarNames.PASSENGER, "IHvac"))),
    DRIVER_MASSAGE("driver_massage", R.string.smart_button_action_massage_driver, ButtonAction.Group.SEATS,
            KnobDials.saving(KnobDials.massage(CarNames.DRIVER))),
    PASSENGER_MASSAGE("passenger_massage", R.string.smart_button_action_massage_passenger, ButtonAction.Group.SEATS,
            KnobDials.saving(KnobDials.massage(CarNames.PASSENGER))),
    STEERING_HEAT("steering_heat", R.string.smart_button_action_steering_heat, ButtonAction.Group.SEATS,
            KnobDials.saving(new KnobDials.LevelDial("HVAC_FUNC_STEERING_WHEEL_HEAT", CarNames.ALL, "IHvac"))),
    DRIVE_MODE("drive_mode", R.string.knob_fn_drive_mode, ButtonAction.Group.DRIVE,
            KnobDials.saving(KnobDials.driveMode())),
    GEAR("gear", R.string.knob_fn_gear, ButtonAction.Group.DRIVE, "gear_up", "gear_down",
            R.string.knob_v_shift_up, R.string.knob_v_shift_down),
    SUSPENSION("suspension", R.string.knob_fn_suspension, ButtonAction.Group.DRIVE, KnobDials.suspension()),
    DAMPING("damping", R.string.knob_fn_damping, ButtonAction.Group.DRIVE,
            KnobDials.saving(new KnobDials.ListDial("SETTING_FUNC_SUSPENSION_DAMPR_MOD_SET", CarNames.ALL,
                    new int[] {0, 1, 2, 4}, true, null, "IVehicle"))),
    REGEN("regen", R.string.knob_fn_regen, ButtonAction.Group.DRIVE,
            KnobDials.saving(new KnobDials.ListDial("SETTING_FUNC_ENERGY_REGENERATION", CarNames.ALL, KnobDials.LEVELS,
                    false, new int[] {R.string.quick_off, R.string.knob_v_regen_low, R.string.knob_v_regen_mid,
                            R.string.knob_v_regen_high}, "IVehicle").active())),
    WINDOWS("windows", R.string.knob_fn_windows, ButtonAction.Group.BODY,
            KnobDials.windows(KnobDials.ALL_OPEN, R.string.knob_v_windows_open)),
    WINDOWS_VENT("windows_vent", R.string.knob_fn_windows_vent, ButtonAction.Group.BODY,
            KnobDials.windows(KnobDials.VENT, R.string.knob_v_windows_vent)),
    FRAGRANCE("fragrance", R.string.knob_fn_fragrance, ButtonAction.Group.LIGHTS, KnobDials.fragrance());

    static final List<KnobTurn> HOME = Collections.unmodifiableList(Arrays.asList(VOLUME, TRACK, SOUND_EFFECT,
            TEMPERATURE, FAN, DRIVER_SEAT_HEAT, DRIVER_SEAT_VENT, DRIVER_MASSAGE, STEERING_HEAT, DRIVE_MODE, SUSPENSION,
            REGEN, WINDOWS));

    public final String key;
    public final ButtonAction.Group group;
    private final int labelRes;
    final KnobDials.Dial dial;
    private final String right;
    private final String left;
    private final int rightShown;
    private final int leftShown;

    KnobTurn(String key, int labelRes, ButtonAction.Group group, KnobDials.Dial dial) {
        this(key, labelRes, group, dial, null, null, 0, 0);
    }

    KnobTurn(String key, int labelRes, ButtonAction.Group group, String right, String left, int rightShown, int leftShown) {
        this(key, labelRes, group, null, right, left, rightShown, leftShown);
    }

    KnobTurn(String key, int labelRes, ButtonAction.Group group, KnobDials.Dial dial, String right, String left,
             int rightShown, int leftShown) {
        this.key = key;
        this.labelRes = labelRes;
        this.group = group;
        this.dial = dial;
        this.right = right;
        this.left = left;
        this.rightShown = rightShown;
        this.leftShown = leftShown;
    }

    public String label(Context context) {
        return labelRes == 0 ? ButtonAction.NONE.label(context) : context.getString(labelRes);
    }

    public static KnobTurn fromKey(String key) {
        for (KnobTurn turn : values()) {
            if (turn.key.equals(key)) {
                return turn;
            }
        }
        return NONE;
    }

    public static List<KnobTurn> home() {
        return HOME;
    }

    public static List<ButtonAction.Group> groups() {
        List<ButtonAction.Group> out = new ArrayList<>();
        for (KnobTurn turn : values()) {
            if (turn.group != null && !out.contains(turn.group)) {
                out.add(turn.group);
            }
        }
        return out;
    }

    public static List<KnobTurn> of(ButtonAction.Group group) {
        List<KnobTurn> out = new ArrayList<>();
        for (KnobTurn turn : values()) {
            if (turn.group == group) {
                out.add(turn);
            }
        }
        return out;
    }

    public void runTS(Context app, int delta, Consumer<String> shown) {
        if (this == NONE || delta == 0) {
            if (shown != null) {
                shown.accept(null);
            }
            return;
        }
        int n = Math.max(1, Math.min(ButtonActions.MAX_STEPS, Math.abs(delta)));
        int signed = delta > 0 ? n : -n;
        if (dial != null) {
            String label = label(app);
            CarMoves.dial(app, key, dial, signed, (result, text) -> {
                String failure = CarMoves.failureText(app, label, result);
                if (failure != null) {
                    Toast.makeText(app, failure, Toast.LENGTH_SHORT).show();
                }
                if (shown != null) {
                    shown.accept(text);
                }
            });
            return;
        }
        ButtonActions.perform(app, ButtonAction.fromKey(signed > 0 ? right : left), CameraKeeper.Why.BUTTON, n);
        if (shown != null) {
            shown.accept(app.getString(signed > 0 ? rightShown : leftShown));
        }
    }

    public void peek(Context app, Consumer<String> shown) {
        if (dial == null) {
            shown.accept(null);
            return;
        }
        CarMoves.dial(app, key, dial, 0, (result, text) -> shown.accept(text));
    }
}
