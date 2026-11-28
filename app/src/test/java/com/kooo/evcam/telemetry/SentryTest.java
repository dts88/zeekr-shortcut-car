/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 哨兵的警戒、报警（{@link Sentry}，项目所有者 2026-11-28「先读取、记录在信息条、log中，确认了之后，再动相机管家」）：
 * 读得出来、记得下来；做判断的地方照旧看不到它们。
 */
public class SentryTest {

    @Test
    public void readsAllFiveStates() {
        for (int v = 0; v <= 4; v++) {
            assertEquals(Integer.valueOf(v), Signal.SENTRY_MODE.decode(v));
        }
        assertNull(Signal.SENTRY_MODE.decode(5));
        assertNull(Signal.SENTRY_MODE.decode(-1));
        assertNull(Signal.SENTRY_MODE.decode(255));
    }

    @Test
    public void decisionsStillSeeOnlyOffOnArmed() {
        assertEquals(Integer.valueOf(0), Sentry.forDecisions(0));
        assertEquals(Integer.valueOf(1), Sentry.forDecisions(1));
        assertEquals(Integer.valueOf(2), Sentry.forDecisions(2));
        assertNull("警戒照旧算读不到", Sentry.forDecisions(Sentry.ALERT));
        assertNull("报警照旧算读不到", Sentry.forDecisions(Sentry.ALARM));
        assertNull(Sentry.forDecisions(null));
    }

    @Test
    public void namesForTheBlackBox() {
        assertEquals("off", Sentry.token(0));
        assertEquals("on", Sentry.token(1));
        assertEquals("armed", Sentry.token(2));
        assertEquals("alert", Sentry.token(3));
        assertEquals("alarm", Sentry.token(4));
        assertEquals("?", Sentry.token(null));
        assertEquals("alert", DarkScreenProbes.text(Signal.SENTRY_MODE, Signal.SENTRY_MODE.decode(3)));
        assertEquals("alarm", DarkScreenProbes.text(Signal.SENTRY_MODE, Signal.SENTRY_MODE.decode(4)));
        assertTrue(Sentry.triggered(3));
        assertTrue(Sentry.triggered(4));
        assertFalse(Sentry.triggered(2));
        assertFalse(Sentry.triggered(null));
    }

    /**
     * 第一步的界线：主代码里直接读哨兵读数的地方只有这几处（显示、记录），做判断的地方都经 {@link Sentry#forDecisions}。
     * 新加一处读它的，要先想清楚是显示还是判断，再加进这里。
     */
    @Test
    public void decisionsGoThroughOnePlace() throws IOException {
        Path root = Paths.get("src/main/java/com/kooo/evcam");
        List<String> raw = new ArrayList<>();
        Pattern read = Pattern.compile("(code\\((?:com\\.kooo\\.evcam\\.telemetry\\.)?Signal\\.SENTRY_MODE\\)|latest\\(\\)\\.sentry\\b)");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = read.matcher(text);
                while (m.find()) {
                    int from = Math.max(0, m.start() - 80);
                    boolean wrapped = text.substring(from, m.start()).contains("Sentry.forDecisions(");
                    if (!wrapped) {
                        raw.add(root.relativize(p).toString().replace('\\', '/'));
                    }
                }
            }
        }
        List<String> shown = Arrays.asList(
                "telemetry/VehicleStateMapper.java",     // 快照：信息条、设置里那一行照实显示
                "zeekr/DiagnosticsCollector.java",       // 诊断报告
                "screen/ScreenState.java",               // 遥控挪车那一段只记录
                "ui/SentryStatusView.java");             // 设置里「熄屏持续录制」下面那一行
        for (String f : raw) {
            if (!shown.contains(f)) {
                fail(f + " 直接读了哨兵读数：做判断的话要经 Sentry.forDecisions，只是显示、记录就加进这个测试的名单");
            }
        }
    }
}
