/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

/**
 * 哨兵模式的读数（{@link Signal#SENTRY_MODE}）：哨兵应用自己写的状态，0 关、1 开启（待机）、2 已布防、3 警戒、4 报警
 * （Lab 读车机代码得到）。
 *
 * <p>车上见过（11-26、11-28 布防着，项目所有者确认那时有人从车旁经过）：读数离开「已布防」整 60 秒、同时屏幕亮 60 秒、
 * 点火在 ACC，之后回到已布防。那时应用只认 0–2，别的值当读不到，黑匣子记成「?」。</p>
 *
 * <p><b>分两步</b>（项目所有者 2026-11-28：「先读取、记录在信息条、log中，确认了之后，再动相机管家」）：</p>
 * <ul>
 *   <li>第一步（现在）：警戒、报警读得出来 —— 信息条、设置里「熄屏持续录制」下面那一行、车辆信号页、黑匣子、诊断照实写。</li>
 *   <li>做判断的地方照旧：相机管家（熄屏后环视留不留、开录）、主界面的哨兵键、哨兵提醒看到的还是「读不到」
 *       （{@link #forDecisions}）。车上核对过读数，第二步只改这一处：警戒、报警当成「布防着」。</li>
 * </ul>
 */
public final class Sentry {

    public static final int OFF = 0;
    public static final int ON = 1;
    public static final int ARMED = 2;
    public static final int ALERT = 3;
    public static final int ALARM = 4;

    private Sentry() {
    }

    /**
     * 做判断的地方看到的哨兵读数。第一步：警戒、报警照旧算读不到（null），判断和以前一模一样；读不到本来就照上一次真读到的算。
     */
    public static Integer forDecisions(Integer reading) {
        return reading != null && reading > ARMED ? null : reading;
    }

    /** 警戒或报警。 */
    public static boolean triggered(Integer reading) {
        return reading != null && (reading == ALERT || reading == ALARM);
    }

    /** 黑匣子里的记号：off / on / armed / alert / alarm；读不到「?」；别的数照写。 */
    public static String token(Integer reading) {
        if (reading == null) {
            return "?";
        }
        switch (reading) {
            case OFF:
                return "off";
            case ON:
                return "on";
            case ARMED:
                return "armed";
            case ALERT:
                return "alert";
            case ALARM:
                return "alarm";
            default:
                return String.valueOf(reading);
        }
    }
}
