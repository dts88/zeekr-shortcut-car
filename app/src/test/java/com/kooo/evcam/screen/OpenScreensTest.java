/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 开着的界面停车时一处关掉（{@link OpenScreens}，lifecycle-spec §3.3「停车」第 1 步，项目所有者 2026-11-21：
 * 「最小化」说的是运行资源最小，界面留在后台不算放开）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class OpenScreensTest {

    @Before
    public void install() {
        OpenScreens.install((Application) RuntimeEnvironment.getApplication());
    }

    /** 停车：在眼前的、在后台的都关掉，是关掉（finish）不是退到后台；已经在关的不再算。 */
    @Test
    public void parkingClosesEveryOpenScreen() {
        ActivityController<Activity> inFront = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> behind = Robolectric.buildActivity(Activity.class).setup();
        behind.pause().stop();
        assertEquals(2, OpenScreens.count());

        assertEquals(2, OpenScreens.close(false, "parked"));
        assertTrue(inFront.get().isFinishing());
        assertTrue(behind.get().isFinishing());
        assertEquals("已经在关的不再算", 0, OpenScreens.close(false, "parked"));

        inFront.pause().stop().destroy();
        behind.destroy();
        assertEquals("销毁了就不记着", 0, OpenScreens.count());
    }

    /** 系统催内存：只关不在眼前的，在眼前的那一个人可能正在用。 */
    @Test
    public void memoryPressureLeavesTheScreenInFrontAlone() {
        ActivityController<Activity> inFront = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> behind = Robolectric.buildActivity(Activity.class).setup();
        behind.pause().stop();

        assertEquals(1, OpenScreens.close(true, "trim-memory 20"));
        assertFalse(inFront.get().isFinishing());
        assertTrue(behind.get().isFinishing());

        // 它也离开了眼前：下一次催就轮到它
        inFront.pause().stop();
        assertEquals(1, OpenScreens.close(true, "trim-memory 40"));
        assertTrue(inFront.get().isFinishing());
    }

    /** 没有开着的界面：什么都不做。 */
    @Test
    public void nothingOpenNothingToClose() {
        assertEquals(0, OpenScreens.count());
        assertEquals(0, OpenScreens.close(false, "parked"));
        assertEquals(0, OpenScreens.close(true, "trim-memory 80"));
    }

    /** 界面重建（换日夜、换语言）：旧的销毁了摘掉，新的记上，还是一个。 */
    @Test
    public void aRebuiltScreenIsCountedOnce() {
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class).setup();
        assertEquals(1, OpenScreens.count());
        screen.recreate();
        assertEquals(1, OpenScreens.count());
        assertEquals(1, OpenScreens.close(false, "parked"));
    }

    /** 换了一个应用对象（测试里每一条各建一个）：重新装、从头记，上一个留下的不算。 */
    @Test
    public void installingOnAnotherApplicationStartsOver() {
        Robolectric.buildActivity(Activity.class).setup();
        assertEquals(1, OpenScreens.count());
        OpenScreens.install((Application) RuntimeEnvironment.getApplication());
        assertEquals("同一个应用：不重装", 1, OpenScreens.count());
        OpenScreens.install(new Application());
        assertEquals(0, OpenScreens.count());
    }
}
