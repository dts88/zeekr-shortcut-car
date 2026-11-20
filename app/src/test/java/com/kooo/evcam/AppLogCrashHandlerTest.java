/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * 崩溃处理器进程里只装一次（{@link AppLog#installCrashHandler}）。以前主界面每创建一次就再装一层：装过两层以后「原来的处理器」
 * 指向我们自己那一层，崩溃时自己叫自己，到不了系统的处理器 —— 进程崩了却结束不了。
 */
public class AppLogCrashHandlerTest {

    @Test
    public void installingAgainDoesNotStackAnotherHandler() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler marker = (thread, throwable) -> {
        };
        try {
            Thread.setDefaultUncaughtExceptionHandler(marker);
            AppLog.installCrashHandler(null);
            Thread.UncaughtExceptionHandler first = Thread.getDefaultUncaughtExceptionHandler();
            assertNotSame("第一次装上了我们的", marker, first);
            AppLog.installCrashHandler(null);
            AppLog.installCrashHandler(null);
            assertSame("再叫几次都还是那一个", first, Thread.getDefaultUncaughtExceptionHandler());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }
}
