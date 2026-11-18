/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.alarm.policy.ArmDelay;

/**
 * 「锁车满 3 分钟且哨兵模式开启时，车辆闪烁双闪或车灯自行亮起」怎么从读数里认出来 —— 纯逻辑，不碰 Android
 * （{@code SentryWatchTest}）。读法照 Fab 的提醒（PR #211）。
 *
 * <ul>
 *   <li><b>布防 = 锁着、哨兵模式开着</b>：哨兵模式的读数是「已布防」（{@link #SENTRY_ARMED}）。车在锁车后约 90 秒自己从「开启」
 *       变过来，解锁那一下回「开启」，挂 D 回「关闭」（{@code telemetry.Signal#SENTRY_MODE}）。锁车以后车辆接口可能读不到
 *       （读成 null）：那时照上一次真读到的算 —— 不然「锁着、哨兵开着」这个最该提醒的时候反而永远不提醒。</li>
 *   <li><b>锁车满 3 分钟</b>：从布防的那一刻起算（{@link ArmDelay}）。进程起来时已经布防着的，不知道布防了多久，照常提醒。</li>
 *   <li><b>双闪</b>：转向指示状态变成「双闪」（{@link #INDICATOR_HAZARD}），和锁定影像的双闪自动锁定同一个读法。</li>
 *   <li><b>车灯自行亮起</b>：日间行车灯、前后位置灯、近光灯里有一盏从不亮变成亮。</li>
 *   <li>只认「变亮」的那一下：一直亮着的不算新的一下。读不到（null）不算熄灭，照上一次真读到的算；
 *       进程起来后头一次读到就亮着的算亮起（不知道之前是不是灭的，宁可报）。</li>
 * </ul>
 *
 * How "Sentry Mode on and the car locked for 3 minutes, then the hazard lights flash or the lights come on
 * by themselves" is recognised from readings. Pure logic. Armed (the Sentry Mode reading
 * {@link #SENTRY_ARMED}) means locked with Sentry Mode on; a missing reading keeps the last real one.
 * The 3 minutes count from arming ({@link ArmDelay}). Only a light coming on counts, never one that stays
 * on; a missing reading is not "off".
 */
public final class SentryWatch {

    /** 哨兵模式「已布防」。Sentry Mode "armed". */
    public static final int SENTRY_ARMED = 2;

    /** 转向指示状态「双闪」。Turn indicator "hazard". */
    public static final int INDICATOR_HAZARD = 3;

    /** 亮起的是什么。What came on. */
    public enum Trigger {
        /** 双闪。Hazard lights. */
        HAZARD,
        /** 车灯自行亮起。Lights on by themselves. */
        LAMPS
    }

    /** 读不到时照这个：最近一次真读到的哨兵模式。The last real Sentry Mode reading. */
    private Integer sentry;
    /** 这一次布防从什么时候起（单调时钟）；-1：没布防，或者进程起来时已经布防着（不知道多久）。 */
    private long armedAtMs = -1;
    private Boolean hazard;
    private final Boolean[] lamps = new Boolean[4];

    /** 重新开始看：之前读到的都不算（提醒刚打开）。Start over: nothing seen before counts. */
    public void reset() {
        sentry = null;
        armedAtMs = -1;
        hazard = null;
        for (int i = 0; i < lamps.length; i++) {
            lamps[i] = null;
        }
    }

    /**
     * 喂一份读数。
     *
     * @param nowMs 单调时钟（{@code SystemClock.elapsedRealtime}）
     * @return 这一份里新亮起的（双闪优先）；没有就 null。布防没有、满没满 3 分钟由调用方问 {@link #armed}、{@link #waitMs}
     */
    public Trigger update(Integer sentryReading, Integer indicator, Boolean drl, Boolean frontPosition,
                          Boolean rearPosition, Boolean lowBeam, long nowMs) {
        boolean first = sentry == null;
        boolean wasArmed = armed();
        if (sentryReading != null) {
            sentry = sentryReading;
        }
        if (!armed()) {
            armedAtMs = -1;
        } else if (!wasArmed) {
            // 进程起来以后头一次读到就已经布防着：不知道布防了多久
            armedAtMs = first ? -1 : nowMs;
        }

        boolean hazardRose = false;
        if (indicator != null) {
            boolean on = indicator == INDICATOR_HAZARD;
            hazardRose = on && !Boolean.TRUE.equals(hazard);
            hazard = on;
        }

        boolean lampsWereOn = anyLampOn();
        keep(0, drl);
        keep(1, frontPosition);
        keep(2, rearPosition);
        keep(3, lowBeam);
        boolean lampsRose = anyLampOn() && !lampsWereOn;

        if (hazardRose) {
            return Trigger.HAZARD;
        }
        return lampsRose ? Trigger.LAMPS : null;
    }

    /** 此刻布防着（锁着、哨兵模式开着）。Armed right now: locked with Sentry Mode on. */
    public boolean armed() {
        return sentry != null && sentry == SENTRY_ARMED;
    }

    /**
     * 锁车满 3 分钟还差多久（毫秒）；满了、或者不知道布防了多久是 0。没布防时没有意义（先问 {@link #armed}）。
     *
     * How long until the car has been locked for 3 minutes; 0 once it has, or when the arming time is unknown.
     */
    public long waitMs(long nowMs) {
        return ArmDelay.remainingMs(armedAtMs < 0 ? -1 : nowMs - armedAtMs);
    }

    private void keep(int index, Boolean reading) {
        if (reading != null) {
            lamps[index] = reading;
        }
    }

    private boolean anyLampOn() {
        for (Boolean lamp : lamps) {
            if (Boolean.TRUE.equals(lamp)) {
                return true;
            }
        }
        return false;
    }
}
