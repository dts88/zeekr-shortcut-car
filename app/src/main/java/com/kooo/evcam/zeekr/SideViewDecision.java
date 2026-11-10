/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 wysie - https://github.com/wysie
 * Portions Copyright (C) 2026 FerixzTanz - https://github.com/FerixzTanz
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.camera.ChannelRules;

/**
 * 盲区画面：此刻哪一侧该不该显示（左右各判各的，项目所有者 2026-11-04；先后、保留、淡出在 {@link SideViewTrigger}）。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>左灯只显示左侧那一路，右灯只显示右侧那一路；双闪、不打灯、读不到都不显示（{@link #turnLane}）。</li>
 *   <li><b>情境</b>（{@link Mode}，项目所有者 2026-11-09，取代 2026-11-03 起的两个附加项）：哪几种情形显示，一档包含下一档 ——
 *       简洁：往盲区指示灯亮的一侧打转向灯、开门预警亮；标准：再加打转向灯（盲区没车也显示）；丰富：再加盲区指示灯亮（不打转向灯也显示）。
 *       只看同一侧；双闪不算打转向灯。后视镜上的灯（{@link #blindSpotLit}、{@link #doorWarningLit}）不看车速（开门预警是停着车亮的，
 *       盲区灯低速也会亮）。两侧可以同时显示（项目所有者 2026-11-04）。</li>
 *   <li><b>原厂画面优先</b>：挂 R 挡、原厂 360 在显示、原厂画面弹着（360 或侧方小窗）、
 *       泊车辅助开着，任何一样成立都不显示；已经显示着的立刻收。</li>
 *   <li>只打转向灯（盲区没车）时车速低于设定值不显示（默认 30 km/h：再慢原厂自己会弹侧方小窗）；车速读不到不拦。
 *       只在「还没显示」时看车速 —— 显示出来之后减速不收，免得在门槛上来回闪。</li>
 *   <li>灯一灭就不再要（灯灭后再留多久由 {@link SideViewTrigger} 管）。</li>
 * </ul>
 *
 * <p>来源：FerixzTanz 的 PR #4（规则和测试），wysie 在 PR #19 里补了「原厂画面在时不备着」；GPL-3.0。</p>
 *
 * <p>纯 Java，不碰 Android，见 {@code SideViewDecisionTest}。</p>
 */
public final class SideViewDecision {

    /** 不显示。 */
    public static final int NONE = -1;

    private SideViewDecision() {
    }

    /**
     * 情境（项目所有者 2026-11-09）：哪几种情形显示画面，一档包含下一档（每一种情形是 {@link SideViewTrigger.Cause} 的一种）。
     * 三档都有的：往盲区指示灯亮的一侧打转向灯、开门预警亮。
     */
    public enum Mode {
        /** 简洁：只有三档都有的那两种。 */
        MINIMAL(AppConfig.SIDE_VIEW_MODE_MINIMAL),
        /** 标准：再加打转向灯（盲区没车也显示，看最低车速）。默认。 */
        STANDARD(AppConfig.SIDE_VIEW_MODE_STANDARD),
        /** 丰富：再加盲区指示灯亮（不打转向灯也显示）；就是 2026-11-03 起两个附加项都开着的样子。 */
        FULL(AppConfig.SIDE_VIEW_MODE_FULL);

        /** 设置里存的值（{@code SettingsRegistry.SIDE_VIEW_MODE}）。 */
        public final String value;

        Mode(String value) {
            this.value = value;
        }

        /** 设置里存的值对应哪一档；不认得的当标准（默认）。 */
        public static Mode of(String value) {
            for (Mode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return STANDARD;
        }

        /**
         * 以前两个附加项的设置换成哪一档（项目所有者 2026-11-09）：勾过「盲区提醒」的是丰富，别的（只勾了开门预警、都没勾）是标准。
         * 只在升级后第一次读设置时用一次（{@code AppConfig}）。
         */
        public static Mode fromAddOns(boolean blindSpotAlert) {
            return blindSpotAlert ? FULL : STANDARD;
        }

        /** 只打转向灯（盲区没车）也显示：标准、丰富。 */
        boolean showsTurnSignal() {
            return this != MINIMAL;
        }

        /** 只是盲区指示灯亮（没往那一侧打转向灯）也显示：丰富。 */
        boolean showsBlindSpot() {
            return this == FULL;
        }
    }

    /** 一份输入：信号读不到的都是 null。 */
    public static final class Input {
        /** {@code VehicleState.TURN_*}：0 不打、1 左、2 右；null 读不到。 */
        public Integer turnSignal;
        public Boolean hazard;
        public String gear;
        public Boolean stock360Shown;
        public Boolean stockPopupShown;
        public Boolean parkAssistOn;
        public Float speedKmh;
        /** 设置里的门槛，km/h；0 表示不看车速。 */
        public int minSpeedKmh;
        /**
         * 转向灯打的那一侧此刻已经显示着就是那一侧（{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}，{@link SideViewTrigger} 填），
         * 不然是 {@link #NONE}：显示着的不再看车速（{@link #turnLane}）。
         */
        public int showing = NONE;
        /** 后视镜上的盲区指示灯：左、右亮着没有；null 读不到。 */
        public Boolean blindSpotLeft;
        public Boolean blindSpotRight;
        /** 停车后的开门预警（亮的是后视镜上同一盏灯）：左、右亮着没有；null 读不到。 */
        public Boolean doorWarningLeft;
        public Boolean doorWarningRight;
        /** 设置里选的情境（「盲区画面」那一行的三档拖动条）。 */
        public Mode mode = Mode.STANDARD;
        /**
         * 收起前淡出（项目所有者 2026-11-04，{@link SideViewTrigger#FADE_MS}）：装饰动效此刻放不放（{@code ui.MotionPolicy}：
         * 「录制时减少动效」开着而在录、系统关了动画时不放）。不放就和以前一样，留够就收。
         * 边框闪不闪不看它：边框是安全提醒（{@link SideViewAlert}，项目所有者 2026-11-09）。
         */
        public boolean fade;
    }

    /** 打转向灯要显示哪一侧（双闪、不打灯、读不到、原厂画面在都不显示；还没显示时看车速）：情境里「打转向灯」那一种。 */
    static int turnLane(Input in) {
        if (factoryViewActive(in)) {
            return NONE;
        }
        if (Boolean.TRUE.equals(in.hazard) || in.turnSignal == null) {
            return NONE;
        }
        int lane;
        if (in.turnSignal == 1) {
            lane = LaneCycle.LEFT;
        } else if (in.turnSignal == 2) {
            lane = LaneCycle.RIGHT;
        } else {
            return NONE;
        }
        if (in.showing == lane) {
            // 已经显示着同一侧：不再看车速
            return lane;
        }
        if (in.minSpeedKmh > 0 && in.speedKmh != null && in.speedKmh < in.minSpeedKmh) {
            return NONE;
        }
        return lane;
    }

    /** 这一侧后视镜上的灯亮着（盲区指示灯或开门预警）：关闭按钮关掉「灯亮的这一次」照它（{@link SideViewTrigger#dismiss}）。 */
    static boolean lit(Input in, int lane) {
        return blindSpotLit(in, lane) || doorWarningLit(in, lane);
    }

    /** 这一侧的盲区指示灯亮着（读不到算没亮）。 */
    static boolean blindSpotLit(Input in, int lane) {
        return Boolean.TRUE.equals(lane == LaneCycle.LEFT ? in.blindSpotLeft : in.blindSpotRight);
    }

    /** 这一侧的开门预警亮着（读不到算没亮）。 */
    static boolean doorWarningLit(Input in, int lane) {
        return Boolean.TRUE.equals(lane == LaneCycle.LEFT ? in.doorWarningLeft : in.doorWarningRight);
    }

    /** 转向灯打向这一侧（双闪、不打灯、读不到都不算；不看车速）。 */
    static boolean signalsToward(Input in, int lane) {
        if (Boolean.TRUE.equals(in.hazard) || in.turnSignal == null) {
            return false;
        }
        return lane == LaneCycle.LEFT ? in.turnSignal == 1 : in.turnSignal == 2;
    }

    /**
     * 该不该把窗口备着：窗口挂着、隐藏，画面留在环视的会话里，打灯只是显示出来（项目所有者 2026-10-16 定的 B）。
     *
     * <p>只看 D 挡：开车时才会打灯变道，P / N / R 都不必为它开着环视。挡位读不到就不备（打灯时再挂窗口，慢一点而已）。
     * 原厂画面在时不备：原厂要用环视。停着车时开门预警亮了，那时才挂窗口（等环视出画面要一点时间）。</p>
     */
    public static boolean shouldStayReady(Input in) {
        return "D".equals(in.gear) && !factoryViewActive(in);
    }

    /**
     * 环视此刻这个样子，显示出来能不能有画面（通道调度说的，{@link ChannelRules#condition}）。
     *
     * <p>被别的程序拿着（车机自己的后排画面拿走后座舱相机 1 时，相机 2 跟着被挡住，平台笔记 §3.1）、在救、救不回来：
     * 显示出来只会是一块黑框，不显示，窗口照旧备着，环视出画面了下一次读数就显示。在出画面、在起来（刚挂窗口，第一帧还没到）照常显示。
     * 读不到当作在起来。</p>
     */
    public static boolean surroundCanShow(ChannelRules.Condition surround) {
        return surround == null
                || surround == ChannelRules.Condition.STREAMING
                || surround == ChannelRules.Condition.BRINGING_UP;
    }

    /** 原厂有画面在屏幕上（或马上要有）：倒车、360、原厂弹窗、泊车辅助。 */
    public static boolean factoryViewActive(Input in) {
        return "R".equals(in.gear)
                || Boolean.TRUE.equals(in.stock360Shown)
                || Boolean.TRUE.equals(in.stockPopupShown)
                || Boolean.TRUE.equals(in.parkAssistOn);
    }
}
