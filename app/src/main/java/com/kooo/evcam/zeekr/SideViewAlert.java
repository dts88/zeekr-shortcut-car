/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

/**
 * 盲区画面的边框：这一侧后视镜上的灯亮着时，显示着的那一侧画面描一圈边。只是画：不碰相机、不改哪一侧显示不显示
 * （那是 {@link SideViewDecision}、{@link SideViewTrigger} 的事），画面淡出时边框跟着淡。
 *
 * <h3>规则：照车上那盏灯走</h3>
 *
 * <ul>
 *   <li>盲区指示灯亮、没往这一侧打转向灯：<b>黄、常亮</b>（{@link #CAUTION}）—— 车上那盏灯这时常亮（7X 用户手册「盲区辅助」）；
 *       颜色同行驶信息条箭头的边框（项目所有者 2026-11-03：盲区指示灯黄）。</li>
 *   <li>盲区指示灯亮、转向灯打向这一侧：<b>红、闪</b>（{@link #WARNING}）—— 车上那盏灯这时闪（手册），要变过去的那条道上有车，
 *       是更急的那一级（ISO 17387 的两级提醒；Tesla 的盲区画面这时在画面那一侧画红条）。双闪不算往哪一侧打灯。</li>
 *   <li>开门预警亮：<b>红、闪</b> —— 同行驶信息条（开门预警红：停车后才亮，更急）。</li>
 *   <li>读不到（null）、设置里没开的那一样：不描边，和显示规则一样（{@link SideViewDecision#lit}）。</li>
 *   <li>不画绿色、不画「安全」：灯灭了不等于可以变道。</li>
 *   <li>闪是亮 / 暗交替（{@link #BLINK_HALF_MS}，约 2 Hz），暗的那一半照样看得见。装饰动效放不了时（{@code ui.MotionPolicy}，
 *       同收起前的淡出：「录制时减少动效」开着而在录、系统关了动画）不闪、常亮 —— 红和黄本身已经分得开。</li>
 * </ul>
 *
 * <p>纯 Java，不碰 Android，见 {@code SideViewAlertTest}；画在 {@link SideViewEdge}。</p>
 */
public final class SideViewAlert {

    /** 不描边。 */
    public static final int NONE = 0;
    /** 黄、常亮：这一侧盲区指示灯亮着。 */
    public static final int CAUTION = 1;
    /** 红、闪：往亮着盲区指示灯的这一侧打着转向灯，或者这一侧开门预警亮着。 */
    public static final int WARNING = 2;

    /** 黄：同行驶信息条盲区指示灯的那一档（{@code InfoBarRenderer.LEFT_YELLOW}）。 */
    static final int YELLOW = 0xFFFFD54F;
    /** 红：同行驶信息条开门预警的那一档（{@code InfoBarRenderer.WARN}）。 */
    static final int RED = 0xFFF0555A;
    /** 闪：亮、暗各这么久（窗口本来就每 250 ms 看一次画面新不新，跟着它换）。 */
    static final long BLINK_HALF_MS = 250L;
    /** 闪的暗那一半的不透明度（0–255）：约 35%，看得见、和亮的那一半分得开。 */
    static final int DIM_ALPHA = 0x59;

    private SideViewAlert() {
    }

    /** 这一侧此刻描什么边：{@link #NONE}、{@link #CAUTION}、{@link #WARNING}。 */
    public static int level(SideViewDecision.Input in, int lane) {
        if (doorWarningLit(in, lane)) {
            return WARNING;
        }
        if (SideViewDecision.blindSpotLit(in, lane)) {
            return signalsToward(in, lane) ? WARNING : CAUTION;
        }
        return NONE;
    }

    /** 这一侧开门预警亮着，而且设置里开着（黑匣子那一行照它写原因）。 */
    static boolean doorWarningLit(SideViewDecision.Input in, int lane) {
        return in.useDoorWarning
                && Boolean.TRUE.equals(lane == LaneCycle.LEFT ? in.doorWarningLeft : in.doorWarningRight);
    }

    /** 转向灯打向这一侧（双闪、不打灯、读不到都不算）。 */
    static boolean signalsToward(SideViewDecision.Input in, int lane) {
        if (Boolean.TRUE.equals(in.hazard) || in.turnSignal == null) {
            return false;
        }
        return lane == LaneCycle.LEFT ? in.turnSignal == 1 : in.turnSignal == 2;
    }

    /** 这一级闪不闪：只有红的闪，装饰动效放不了时也不闪。 */
    static boolean blinks(int level, boolean motion) {
        return level == WARNING && motion;
    }

    /**
     * 此刻用什么颜色画（ARGB）：0 是不画。闪的暗那一半同色、不透明度降到 {@link #DIM_ALPHA}。
     *
     * @param bright 闪的亮那一半（不闪时不看）
     */
    static int color(int level, boolean motion, boolean bright) {
        int color = level == WARNING ? RED : level == CAUTION ? YELLOW : 0;
        if (color == 0 || bright || !blinks(level, motion)) {
            return color;
        }
        return (color & 0x00FFFFFF) | (DIM_ALPHA << 24);
    }

    /** HUD 上的边框多粗（像素）：画面短边的 4%，至少 4 px —— HUD 是投影，太细看不清。 */
    static float hudStrokePx(int width, int height) {
        return Math.max(4f, Math.round(Math.min(width, height) * 0.04f));
    }
}
