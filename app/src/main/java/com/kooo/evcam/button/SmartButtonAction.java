package com.kooo.evcam.button;

import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灵动贴单击、双击能选的动作（项目所有者 2026-10-30）：不使用、悬浮按钮能选的全部、驾驶模式三项；
 * 手排模式（2026-10-31）：驾驶模式向上、向下，升挡、降挡。
 *
 * <p>悬浮按钮那几项直接从 {@link FloatingAction} 长出来，存的也是它的 key：悬浮按钮加一个动作，灵动贴跟着就有，
 * 做的也是同一件事（{@code overlay.QuickActions}）。系统原生功能只放车上验证过写得进去的驾驶模式（舒适 / 标准 / 运动，
 * Lab findings §2.56；门锁、车窗、座椅、哨兵这些不做成按键动作）。升挡、降挡只改模拟声浪的转速和声音（{@code sound.DriveSound#shift}），
 * 不碰车。</p>
 *
 * <p>认不出来的 key 一律当「不使用」：配置是可以被手改的，按一下不该做出没选过的事。</p>
 */
public final class SmartButtonAction {

    /** 不使用：按了什么都不做（刚连接的灵动贴两种按法都是它）。 */
    public static final SmartButtonAction NONE = new SmartButtonAction("none", null, 0, 0, 0);

    private static final List<SmartButtonAction> ALL = build();

    /** 驾驶模式从低到高（{@link #nextDriveMode}）。 */
    private static final int[] DRIVE_MODES = {Signal.DRIVE_MODE_COMFORT, Signal.DRIVE_MODE_STANDARD, Signal.DRIVE_MODE_SPORT};

    public final String key;
    /** 悬浮按钮的那件事；驾驶模式和「不使用」是 null。 */
    public final FloatingAction floating;
    /** 驾驶模式要写成的值（{@link Signal#DRIVE_MODE_COMFORT} 这几个）；别的是 0。 */
    public final int driveMode;
    /** 驾驶模式走一级：+1 向上、−1 向下（{@link #nextDriveMode}）；别的是 0。 */
    public final int driveModeStep;
    /** 模拟声浪换一挡：+1 升挡、−1 降挡；别的是 0。 */
    public final int gearStep;

    private SmartButtonAction(String key, FloatingAction floating, int driveMode, int driveModeStep, int gearStep) {
        this.key = key;
        this.floating = floating;
        this.driveMode = driveMode;
        this.driveModeStep = driveModeStep;
        this.gearStep = gearStep;
    }

    private static List<SmartButtonAction> build() {
        List<SmartButtonAction> all = new ArrayList<>();
        all.add(NONE);
        for (FloatingAction action : FloatingAction.values()) {
            all.add(new SmartButtonAction(action.key, action, 0, 0, 0));
        }
        all.add(new SmartButtonAction("drive_mode_comfort", null, Signal.DRIVE_MODE_COMFORT, 0, 0));
        all.add(new SmartButtonAction("drive_mode_standard", null, Signal.DRIVE_MODE_STANDARD, 0, 0));
        all.add(new SmartButtonAction("drive_mode_sport", null, Signal.DRIVE_MODE_SPORT, 0, 0));
        all.add(new SmartButtonAction("drive_mode_up", null, 0, 1, 0));
        all.add(new SmartButtonAction("drive_mode_down", null, 0, -1, 0));
        all.add(new SmartButtonAction("gear_up", null, 0, 0, 1));
        all.add(new SmartButtonAction("gear_down", null, 0, 0, -1));
        return Collections.unmodifiableList(all);
    }

    /** 能选的全部，按设置里列出的先后。 */
    public static List<SmartButtonAction> all() {
        return ALL;
    }

    /** 存下的 key → 动作；认不出来的是 {@link #NONE}。 */
    public static SmartButtonAction fromKey(String key) {
        if (key != null) {
            for (SmartButtonAction action : ALL) {
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
            return com.kooo.evcam.R.string.vi_v_drive_comfort;
        }
        if (driveMode == Signal.DRIVE_MODE_STANDARD) {
            return com.kooo.evcam.R.string.vi_v_drive_standard;
        }
        if (driveMode == Signal.DRIVE_MODE_SPORT) {
            return com.kooo.evcam.R.string.vi_v_drive_sport;
        }
        return 0;
    }

    /** 设置里显示的名字：「不使用」、悬浮按钮那几项的原名、「切换到运动模式」（模式名和「车辆信号」页同一份）、手排模式那几项。 */
    public String label(android.content.Context context) {
        if (floating != null) {
            return context.getString(floating.labelRes());
        }
        if (driveMode != 0) {
            return context.getString(com.kooo.evcam.R.string.smart_button_action_drive,
                    context.getString(driveModeLabelRes()));
        }
        if (driveModeStep != 0) {
            return context.getString(driveModeStep > 0 ? com.kooo.evcam.R.string.smart_button_action_drive_up
                    : com.kooo.evcam.R.string.smart_button_action_drive_down);
        }
        if (gearStep != 0) {
            return context.getString(gearStep > 0 ? com.kooo.evcam.R.string.smart_button_action_gear_up
                    : com.kooo.evcam.R.string.smart_button_action_gear_down);
        }
        return context.getString(com.kooo.evcam.R.string.smart_button_action_none);
    }
}
