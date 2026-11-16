/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.telemetry.CarNames;
import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 按键动作：悬浮按钮的点击 / 长按、灵动贴的单击 / 双击从这同一份里选（项目所有者 2026-11-15：两边合成一份列表，
 * 原有的全部保留），分组（ui-spec §十一）：
 * <ul>
 *   <li>不使用；应用功能（原来悬浮按钮的那几样，项目所有者 2026-10-30）；驾驶模式三项、手排模式（2026-10-31：驾驶模式向上、向下，
 *       升挡、降挡）。</li>
 *   <li>2026-11-04 项目所有者定加上这些（2026-11-05 起）：一键运动、语音与媒体、座椅与方向盘、空调、
 *       车身、灯光与香氛、场景。写车的那些怎么做在 {@link CarMoves}，不碰车的在 {@link MediaMove}。这台车没有的功能也留着，
 *       给别的地区的车用（项目所有者）。</li>
 *   <li>2026-11-15 项目所有者要了关门六项（「车身」）：关闭所有车门、四扇各一项、关闭后备箱。只关不开；
 *       没有电动门的车用不了，选项照留，给有电动门的车用（{@link CarMoves#closeDoors}）。</li>
 * </ul>
 *
 * <p>应用功能那几项从 {@link FloatingAction} 长出来，存的也是它的 key（悬浮按钮原来存的就是这些 key，合成一份后照样认）。
 * 按下去怎么做在 {@link ButtonActions}（两边同一份）。升挡、降挡只改模拟声浪的转速和声音（{@code sound.DriveSound#shift}），不碰车。</p>
 *
 * <p>存的是 key：配置是可以被手改的，按一下不该做出没选过的事。灵动贴认不出来的当「不使用」（{@link #fromKey}），
 * 悬浮按钮认不出来的当「打开主界面」（{@link #forFloating}，悬浮按钮原来的默认）。</p>
 */
public final class ButtonAction {

    /** 设置里选动作时的组（先选组，再在组里选）。 */
    public enum Group {
        APP(R.string.smart_button_group_app),
        DRIVE(R.string.smart_button_group_drive),
        MEDIA(R.string.smart_button_group_media),
        SEATS(R.string.smart_button_group_seats),
        CLIMATE(R.string.smart_button_group_climate),
        BODY(R.string.smart_button_group_body),
        LIGHTS(R.string.smart_button_group_lights),
        SCENES(R.string.smart_button_group_scenes);

        public final int labelRes;

        Group(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** 不使用：按了什么都不做（刚绑定的灵动贴两种按法都是它）。不在哪一组里。 */
    public static final ButtonAction NONE = new ButtonAction("none", null, R.string.smart_button_action_none);

    /** 驾驶模式从低到高（{@link #nextDriveMode}）。 */
    private static final int[] DRIVE_MODES = {Signal.DRIVE_MODE_COMFORT, Signal.DRIVE_MODE_STANDARD, Signal.DRIVE_MODE_SPORT};

    private static final List<ButtonAction> ALL = build();

    public final String key;
    /** 在哪一组；「不使用」是 null。 */
    public final Group group;
    /** 名字；悬浮按钮那几项、驾驶模式三项另有（{@link #label}）。 */
    private final int labelRes;
    /** 悬浮按钮的那件事；别的是 null。 */
    public final FloatingAction floating;
    /** 驾驶模式要写成的值（{@link Signal#DRIVE_MODE_COMFORT} 这几个）；别的是 0。 */
    public final int driveMode;
    /** 驾驶模式走一级：+1 向上、−1 向下（{@link #nextDriveMode}）；别的是 0。 */
    public final int driveModeStep;
    /** 模拟声浪换一挡：+1 升挡、−1 降挡；别的是 0。 */
    public final int gearStep;
    /** 写车的（{@link CarMoves}）；别的是 null。 */
    final CarMoves.Job car;
    final java.util.function.IntFunction<CarMoves.Job> stepCar;
    /** 不碰车的媒体、语音（{@link MediaMove}）；别的是 null。 */
    final MediaMove media;

    private ButtonAction(String key, Group group, int labelRes, FloatingAction floating, int driveMode,
                              int driveModeStep, int gearStep, CarMoves.Job car,
                              java.util.function.IntFunction<CarMoves.Job> stepCar, MediaMove media) {
        this.key = key;
        this.group = group;
        this.labelRes = labelRes;
        this.floating = floating;
        this.driveMode = driveMode;
        this.driveModeStep = driveModeStep;
        this.gearStep = gearStep;
        this.car = car;
        this.stepCar = stepCar;
        this.media = media;
    }

    private ButtonAction(String key, Group group, int labelRes) {
        this(key, group, labelRes, null, 0, 0, 0, null, null, null);
    }

    private static ButtonAction car(String key, Group group, int labelRes, CarMoves.Job job) {
        return new ButtonAction(key, group, labelRes, null, 0, 0, 0, job, null, null);
    }

    private static ButtonAction stepCar(String key, Group group, int labelRes,
                                        java.util.function.IntFunction<CarMoves.Job> job) {
        return new ButtonAction(key, group, labelRes, null, 0, 0, 0, null, job, null);
    }

    private static ButtonAction media(String key, int labelRes, MediaMove move) {
        return new ButtonAction(key, Group.MEDIA, labelRes, null, 0, 0, 0, null, null, move);
    }

    private static ButtonAction drive(String key, int labelRes, int driveMode, int driveModeStep, int gearStep) {
        return new ButtonAction(key, Group.DRIVE, labelRes, null, driveMode, driveModeStep, gearStep, null, null, null);
    }

    private static List<ButtonAction> build() {
        List<ButtonAction> all = new ArrayList<>();
        all.add(NONE);
        for (FloatingAction action : FloatingAction.values()) {
            all.add(new ButtonAction(action.key, Group.APP, 0, action, 0, 0, 0, null, null, null));
        }

        // 驾驶：驾驶模式三项、手排模式（2026-10-30、31），一键运动（超级省电时不执行）
        all.add(drive("drive_mode_comfort", 0, Signal.DRIVE_MODE_COMFORT, 0, 0));
        all.add(drive("drive_mode_standard", 0, Signal.DRIVE_MODE_STANDARD, 0, 0));
        all.add(drive("drive_mode_sport", 0, Signal.DRIVE_MODE_SPORT, 0, 0));
        all.add(drive("drive_mode_up", R.string.smart_button_action_drive_up, 0, 1, 0));
        all.add(drive("drive_mode_down", R.string.smart_button_action_drive_down, 0, -1, 0));
        all.add(car("sport_one_touch", Group.DRIVE, R.string.smart_button_action_sport, CarMoves.saving(CarMoves.sport())));
        all.add(drive("gear_up", R.string.smart_button_action_gear_up, 0, 0, 1));
        all.add(drive("gear_down", R.string.smart_button_action_gear_down, 0, 0, -1));

        // 语音与媒体（音量在超级省电时不执行）
        all.add(media("voice_assistant", R.string.smart_button_action_voice, MediaMove.VOICE));
        all.add(media("media_play_pause", R.string.smart_button_action_play_pause, MediaMove.PLAY_PAUSE));
        all.add(media("media_previous", R.string.smart_button_action_previous, MediaMove.PREVIOUS));
        all.add(media("media_next", R.string.smart_button_action_next, MediaMove.NEXT));
        all.add(media("media_mute", R.string.smart_button_action_mute, MediaMove.MUTE));
        all.add(stepCar("volume_up", Group.MEDIA, R.string.smart_button_action_volume_up,
                steps -> CarMoves.saving(CarMoves.volume(steps))));
        all.add(stepCar("volume_down", Group.MEDIA, R.string.smart_button_action_volume_down,
                steps -> CarMoves.saving(CarMoves.volume(-steps))));

        // 座椅与方向盘（超级省电时不执行；座椅恢复还要车停稳）
        all.add(car("steering_wheel_heat", Group.SEATS, R.string.smart_button_action_steering_heat,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_STEERING_WHEEL_HEAT", CarNames.ALL))));
        all.add(car("seat_vent_driver", Group.SEATS, R.string.smart_button_action_seat_vent_driver,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_VENTILATION", CarNames.DRIVER))));
        all.add(car("seat_vent_passenger", Group.SEATS, R.string.smart_button_action_seat_vent_passenger,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_VENTILATION", CarNames.PASSENGER))));
        all.add(car("seat_heat_driver", Group.SEATS, R.string.smart_button_action_seat_heat_driver,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.DRIVER))));
        all.add(car("seat_heat_passenger", Group.SEATS, R.string.smart_button_action_seat_heat_passenger,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.PASSENGER))));
        all.add(car("seat_heat_rear_left", Group.SEATS, R.string.smart_button_action_seat_heat_rear_left,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.ROW_2_LEFT))));
        all.add(car("seat_heat_rear_right", Group.SEATS, R.string.smart_button_action_seat_heat_rear_right,
                CarMoves.saving(CarMoves.levels("IHvac", "HVAC_FUNC_SEAT_HEATING", CarNames.ROW_2_RIGHT))));
        all.add(car("seat_heat_rear", Group.SEATS, R.string.smart_button_action_seat_heat_rear,
                CarMoves.saving(CarMoves.rearSeatsHeat())));
        all.add(car("seat_massage_driver", Group.SEATS, R.string.smart_button_action_massage_driver,
                CarMoves.saving(CarMoves.massage(CarNames.DRIVER))));
        all.add(car("seat_massage_passenger", Group.SEATS, R.string.smart_button_action_massage_passenger,
                CarMoves.saving(CarMoves.massage(CarNames.PASSENGER))));
        all.add(car("seat_restore", Group.SEATS, R.string.smart_button_action_seat_restore,
                CarMoves.standstill(CarMoves.saving(CarMoves.write("ISeat", "SETTING_FUNC_SEAT_RESTORE_DFTAG", CarNames.ALL, 1)))));

        // 空调
        all.add(car("climate_power", Group.CLIMATE, R.string.smart_button_action_ac,
                CarMoves.toggle("IHvac", "HVAC_FUNC_POWER", CarNames.ROW_1)));
        all.add(stepCar("climate_temp_up", Group.CLIMATE, R.string.smart_button_action_temp_up, CarMoves::temperature));
        all.add(stepCar("climate_temp_down", Group.CLIMATE, R.string.smart_button_action_temp_down,
                steps -> CarMoves.temperature(-steps)));
        all.add(car("climate_circulation", Group.CLIMATE, R.string.smart_button_action_circulation, CarMoves.circulation()));
        all.add(car("defrost_front", Group.CLIMATE, R.string.smart_button_action_defrost_front,
                CarMoves.toggle("IHvac", "HVAC_FUNC_DEFROST_FRONT_MAX", CarNames.ALL)));
        all.add(car("defrost_rear", Group.CLIMATE, R.string.smart_button_action_defrost_rear,
                CarMoves.toggle("IHvac", "HVAC_FUNC_DEFROST_REAR", CarNames.ROW_1_CENTER)));

        // 车身（关门、后视镜、充电口盖要车停稳；关后备箱、后视镜、充电口盖的是我们加的）
        all.add(car("central_lock", Group.BODY, R.string.smart_button_action_central_lock,
                CarMoves.toggleAny("SETTING_FUNC_CENTRAL_LOCK", CarNames.ALL, "IVehicle", "ISafety")));
        all.add(car("close_all_doors", Group.BODY, R.string.smart_button_action_close_all_doors,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER,
                        Signal.DOOR_REAR_LEFT, Signal.DOOR_REAR_RIGHT))));
        all.add(car("close_driver_door", Group.BODY, R.string.smart_button_action_close_driver_door,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_DRIVER))));
        all.add(car("close_passenger_door", Group.BODY, R.string.smart_button_action_close_passenger_door,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_PASSENGER))));
        all.add(car("close_rear_left_door", Group.BODY, R.string.smart_button_action_close_rear_left_door,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_REAR_LEFT))));
        all.add(car("close_rear_right_door", Group.BODY, R.string.smart_button_action_close_rear_right_door,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_REAR_RIGHT))));
        all.add(car("close_tailgate", Group.BODY, R.string.smart_button_action_close_tailgate,
                CarMoves.standstill(CarMoves.closeDoors(Signal.DOOR_TRUNK))));
        all.add(car("mirror_fold", Group.BODY, R.string.smart_button_action_mirrors,
                CarMoves.standstill(CarMoves.mirrors())));
        all.add(car("charging_cap", Group.BODY, R.string.smart_button_action_charging_cap,
                CarMoves.standstill(CarMoves.toggle("IBcm", "BCM_FUNC_CHARGING_CAP", CarNames.ROW_2))));
        all.add(car("view_360", Group.BODY, R.string.smart_button_action_view_360,
                CarMoves.onUnlessOn("IPAS", "PAS_FUNC_PAC_ACTIVATION", CarNames.ALL)));

        // 灯光与香氛
        all.add(car("welcome_light", Group.LIGHTS, R.string.smart_button_action_welcome_light,
                CarMoves.toggle("ILamp", "SETTING_FUNC_WELCOME_GOODBY_LIGHT_ONOFF", CarNames.ALL)));
        all.add(car("charging_light", Group.LIGHTS, R.string.smart_button_action_charging_light,
                CarMoves.onUnlessOn("ICharging", "CHARGE_FUNC_CHARGING_LIGHT_SWITCH", CarNames.ALL)));
        all.add(car("driving_light", Group.LIGHTS, R.string.smart_button_action_driving_light, CarMoves.drivingLights()));
        all.add(car("rear_fog_flash", Group.LIGHTS, R.string.smart_button_action_rear_fog_flash, CarMoves.rearFogFlash()));
        all.add(car("fragrance", Group.LIGHTS, R.string.smart_button_action_fragrance, CarMoves.fragrance()));

        // 场景
        all.add(car("theater_driver", Group.SCENES, R.string.smart_button_action_theater_driver,
                CarMoves.toggle("ISceneMode", "SCENE_FUNC_THEATER_MODE", CarNames.ALL)));
        all.add(car("theater_passenger", Group.SCENES, R.string.smart_button_action_theater_passenger,
                CarMoves.toggle("ISceneMode", "SCENE_FUNC_THEATER_MODE_PASS", CarNames.ALL)));
        all.add(car("camping", Group.SCENES, R.string.smart_button_action_camping,
                CarMoves.toggle("IVehicle", "SETTING_FUNC_CAMPING_MOD_SWITCH", CarNames.ALL)));
        all.add(car("sentry", Group.SCENES, R.string.smart_button_action_sentry, CarMoves.sentryToggle()));
        return Collections.unmodifiableList(all);
    }

    /** 能选的全部，按设置里列出的先后（「不使用」最前，后面按组）。 */
    public static List<ButtonAction> all() {
        return ALL;
    }

    /** 一组里能选的，按列出的先后。 */
    public static List<ButtonAction> of(Group group) {
        List<ButtonAction> out = new ArrayList<>();
        for (ButtonAction action : ALL) {
            if (action.group == group) {
                out.add(action);
            }
        }
        return out;
    }

    /** 悬浮按钮存的 key → 动作：选了「不使用」就是它，认不出来的（含没存过）回到「打开主界面」。 */
    public static ButtonAction forFloating(String key) {
        ButtonAction action = fromKey(key);
        return action != NONE || NONE.key.equals(key) ? action : fromKey(FloatingAction.OPEN_APP.key);
    }

    public boolean stepwise() {
        return driveModeStep != 0 || gearStep != 0 || stepCar != null
                || media == MediaMove.PREVIOUS || media == MediaMove.NEXT;
    }

    /** 存下的 key → 动作；认不出来的是 {@link #NONE}。 */
    public static ButtonAction fromKey(String key) {
        if (key != null) {
            for (ButtonAction action : ALL) {
                if (action.key.equals(key)) {
                    return action;
                }
            }
        }
        return NONE;
    }

    /**
     * 驾驶模式从 {@code current} 往 {@code step}（+1 向上、−1 向下）走一级到哪一种：舒适、标准、运动，到头就还是它（不循环）；
     * 不在这三种里（雪地、读不到）返回 0。
     */
    public static int nextDriveMode(Integer current, int step) {
        if (current != null) {
            for (int i = 0; i < DRIVE_MODES.length; i++) {
                if (DRIVE_MODES[i] == current) {
                    return DRIVE_MODES[Math.max(0, Math.min(DRIVE_MODES.length - 1, i + step))];
                }
            }
        }
        return 0;
    }

    /** 驾驶模式值在界面上的名字（{@code vi_v_drive_*}，和「车辆信号」页同一份）；不是驾驶模式返回 0。 */
    public int driveModeLabelRes() {
        if (driveMode == Signal.DRIVE_MODE_COMFORT) {
            return R.string.vi_v_drive_comfort;
        }
        if (driveMode == Signal.DRIVE_MODE_STANDARD) {
            return R.string.vi_v_drive_standard;
        }
        if (driveMode == Signal.DRIVE_MODE_SPORT) {
            return R.string.vi_v_drive_sport;
        }
        return 0;
    }

    /** 设置里显示的名字：悬浮按钮那几项的原名、「切换到运动模式」（模式名和「车辆信号」页同一份），别的照各自的名字。 */
    public String label(Context context) {
        if (floating != null) {
            return context.getString(floating.labelRes());
        }
        if (driveMode != 0) {
            return context.getString(R.string.smart_button_action_drive, context.getString(driveModeLabelRes()));
        }
        return context.getString(labelRes);
    }
}
