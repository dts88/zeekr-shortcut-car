package com.kooo.evcam.button;

import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灵动贴单击、双击能选的动作（项目所有者 2026-10-30）：不使用、悬浮按钮能选的全部、驾驶模式三项。
 *
 * <p>悬浮按钮那几项直接从 {@link FloatingAction} 长出来，存的也是它的 key：悬浮按钮加一个动作，灵动贴跟着就有，
 * 做的也是同一件事（{@code overlay.QuickActions}）。系统原生功能只放车上验证过写得进去的驾驶模式（舒适 / 标准 / 运动，
 * Lab findings §2.56；门锁、车窗、座椅、哨兵这些不做成按键动作）。</p>
 *
 * <p>认不出来的 key 一律当「不使用」：配置是可以被手改的，按一下不该做出没选过的事。</p>
 */
public final class SmartButtonAction {

    /** 不使用：按了什么都不做（刚连接的灵动贴两种按法都是它）。 */
    public static final SmartButtonAction NONE = new SmartButtonAction("none", null, 0);

    private static final List<SmartButtonAction> ALL = build();

    public final String key;
    /** 悬浮按钮的那件事；驾驶模式和「不使用」是 null。 */
    public final FloatingAction floating;
    /** 驾驶模式要写成的值（{@link Signal#DRIVE_MODE_COMFORT} 这几个）；别的是 0。 */
    public final int driveMode;

    private SmartButtonAction(String key, FloatingAction floating, int driveMode) {
        this.key = key;
        this.floating = floating;
        this.driveMode = driveMode;
    }

    private static List<SmartButtonAction> build() {
        List<SmartButtonAction> all = new ArrayList<>();
        all.add(NONE);
        for (FloatingAction action : FloatingAction.values()) {
            all.add(new SmartButtonAction(action.key, action, 0));
        }
        all.add(new SmartButtonAction("drive_mode_comfort", null, Signal.DRIVE_MODE_COMFORT));
        all.add(new SmartButtonAction("drive_mode_standard", null, Signal.DRIVE_MODE_STANDARD));
        all.add(new SmartButtonAction("drive_mode_sport", null, Signal.DRIVE_MODE_SPORT));
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

    /** 设置里显示的名字：「不使用」、悬浮按钮那几项的原名、「切换到运动模式」（模式名和「车辆信号」页同一份）。 */
    public String label(android.content.Context context) {
        if (floating != null) {
            return context.getString(floating.labelRes());
        }
        if (driveMode != 0) {
            return context.getString(com.kooo.evcam.R.string.smart_button_action_drive,
                    context.getString(driveModeLabelRes()));
        }
        return context.getString(com.kooo.evcam.R.string.smart_button_action_none);
    }
}
