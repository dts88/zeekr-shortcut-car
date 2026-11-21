/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.ChannelRules;

import org.junit.Test;

/**
 * 什么时候该停车（{@link Parked#due}，项目所有者 2026-11-21：人下车后依次放开相机以外的资源）：相机黑满 30 秒先放，
 * 相机以外的再晚 10 秒；车机要睡当场放；不知道什么时候黑的（进程起来时就黑着）算该了。
 */
public class ParkedTest {

    private static final long AT = ChannelRules.HOLD_MS + Parked.AFTER_CAMERAS_MS;

    /**
     * 放开的次序：界面最先（项目所有者 2026-11-21：「最小化」说的是运行资源最小，界面留在后台不算放开），然后悬浮窗、蓝牙、
     * 车辆信号，前台服务最后。
     */
    @Test
    public void screensAreReleasedFirstAndTheForegroundServiceLast() {
        assertArrayEquals(new Parked.Release[] {Parked.Release.SCREENS, Parked.Release.OVERLAYS,
                Parked.Release.BLUETOOTH, Parked.Release.TELEMETRY, Parked.Release.FOREGROUND_SERVICE},
                Parked.Release.values());
    }

    /**
     * 停车以后再关一次界面只在停车着做：车机要睡那一下（停车着又开出来的界面），系统催内存时（只关不在眼前的）。
     * 人在车上时系统催内存、行驶中安卓熄屏（那时不停车）、车机自己醒来都不关界面。
     */
    @Test
    public void screensAreClosedAgainOnlyWhileParked() {
        assertTrue(Parked.closesScreensWhenHeadUnitSleeps(true, ScreenTransitions.Edge.HEAD_UNIT_SLEEPS));
        assertFalse("没停车（在开车）", Parked.closesScreensWhenHeadUnitSleeps(false, ScreenTransitions.Edge.HEAD_UNIT_SLEEPS));
        assertFalse("车机自己醒来", Parked.closesScreensWhenHeadUnitSleeps(true, ScreenTransitions.Edge.WOKE_WHILE_DARK));
        assertFalse(Parked.closesScreensWhenHeadUnitSleeps(true, ScreenTransitions.Edge.DARK));
        assertFalse(Parked.closesScreensWhenHeadUnitSleeps(true, ScreenTransitions.Edge.LIT));
        assertFalse("没跨边", Parked.closesScreensWhenHeadUnitSleeps(true, null));
        assertTrue(Parked.closesHiddenScreensWhenMemoryIsTight(true));
        assertFalse("人在车上时系统催内存：不动", Parked.closesHiddenScreensWhenMemoryIsTight(false));
    }

    /** 亮着（人在车上）不停车。 */
    @Test
    public void notWhileSomeoneIsInTheCar() {
        assertFalse(Parked.due(false, -1, false, false));
        assertFalse(Parked.due(false, 10 * 60_000L, false, false));
        assertEquals(-1, Parked.untilDue(false, -1, false, false));
    }

    /** 熄屏后先等相机（30 秒），再晚 10 秒才轮到相机以外的：这期间回来，什么都没放开过。 */
    @Test
    public void afterTheCamerasHaveHadTheirTurn() {
        assertTrue("相机以外的排在相机后面", AT > ChannelRules.HOLD_MS);
        assertFalse(Parked.due(true, 0, false, false));
        assertFalse("相机刚开始放", Parked.due(true, ChannelRules.HOLD_MS, false, false));
        assertFalse(Parked.due(true, AT - 1, false, false));
        assertTrue(Parked.due(true, AT, false, false));
        assertTrue(Parked.due(true, 8 * 3_600_000L, false, false));
        assertEquals(AT, Parked.untilDue(true, 0, false, false));
        assertEquals(1, Parked.untilDue(true, AT - 1, false, false));
        assertEquals(0, Parked.untilDue(true, AT, false, false));
    }

    /** 黑着时安卓也熄屏了（车机要睡）：当场放，不等 —— 等的话多半要到下一次醒来才做得了。 */
    @Test
    public void atOnceWhenTheHeadUnitGoesToSleep() {
        assertTrue(Parked.due(true, 0, true, false));
        assertTrue(Parked.due(true, 5_000, true, false));
        assertEquals(0, Parked.untilDue(true, 5_000, true, false));
    }

    /** 不知道什么时候黑的（进程起来时没有人的证据）：算该了，从停车起。 */
    @Test
    public void atOnceWhenTheStartOfTheDarkIsUnknown() {
        assertTrue(Parked.due(true, -1, false, false));
        assertEquals(0, Parked.untilDue(true, -1, false, false));
    }

    /**
     * 在开车时不停车（和相机管家同一个「在开车」）：行驶中被判成熄屏（车辆信号读不到太久、安卓熄屏）时，悬浮按钮、灵动贴留着，
     * 人还能碰一下把它叫回来。不开了还黑着，照原来的时刻算。
     */
    @Test
    public void notWhileDriving() {
        assertFalse(Parked.due(true, AT, false, true));
        assertFalse("行驶中安卓熄屏也不停车", Parked.due(true, 5_000, true, true));
        assertFalse(Parked.due(true, -1, false, true));
        assertEquals("不用等：不开了那一下再看", -1, Parked.untilDue(true, 0, false, true));
        assertEquals(-1, Parked.untilDue(true, AT, true, true));
        assertTrue("挂回 P 还黑着", Parked.due(true, AT, false, false));
        assertEquals(10_000, Parked.untilDue(true, AT - 10_000, false, false));
    }
}
