/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 「人碰了界面」只认界面真的收到的触摸和按键（lifecycle-spec §3.3）：各界面在 {@code dispatchTouchEvent}、
 * {@code dispatchKeyEvent} 里报（{@code ScreenState.touched} / {@code keyed}），不用 {@code onUserInteraction} ——
 * 界面被别的界面盖住、要离开前台时安卓也叫它，那不是人碰的。
 *
 * <p>照源码查：以后新加的界面也算在内。</p>
 */
public class PersonInputSourceTest {

    @Test
    public void noScreenTakesOnUserInteractionAsATouch() throws IOException {
        File root = javaRoot();
        assumeTrue("定位不到源码目录，跳过", root != null);
        List<String> offenders = new ArrayList<>();
        for (Path path : javaFiles(root)) {
            if (read(path).contains("void onUserInteraction(")) {
                offenders.add(root.toPath().relativize(path).toString().replace(File.separatorChar, '/'));
            }
        }
        assertTrue("这些界面用 onUserInteraction 当「人碰了界面」：别的界面盖上来时安卓也叫它，改在 dispatchTouchEvent、"
                + "dispatchKeyEvent 里叫 ScreenState.touched / keyed: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyScreenReportsTouchesAndKeys() throws IOException {
        File root = javaRoot();
        assumeTrue("定位不到源码目录，跳过", root != null);
        int screens = 0;
        List<String> silent = new ArrayList<>();
        for (Path path : javaFiles(root)) {
            String source = read(path);
            if (!source.contains(" extends AppCompatActivity")) {
                continue;
            }
            screens++;
            if (!source.contains("ScreenState.touched(event,") || !source.contains("ScreenState.keyed(event,")) {
                silent.add(root.toPath().relativize(path).toString().replace(File.separatorChar, '/'));
            }
        }
        assertTrue("一个界面都没找到，测试没钉住东西", screens >= 5);
        assertTrue("这些界面上的触摸、按键没报给 ScreenState（人在界面上操作却不算人在车上）: " + silent,
                silent.isEmpty());
    }

    private static File javaRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            for (File module : new File[] {dir, new File(dir, "app")}) {
                File java = new File(module, "src/main/java/com/kooo/evcam");
                if (java.isDirectory()) {
                    return java;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static List<Path> javaFiles(File root) throws IOException {
        try (Stream<Path> stream = Files.walk(root.toPath())) {
            return stream.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
