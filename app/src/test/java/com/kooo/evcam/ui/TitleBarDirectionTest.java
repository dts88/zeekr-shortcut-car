/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ① 标题区（高度是 {@code title_bar_height} 的那一行）在从右往左的语言里不镜像：菜单 / 返回键永远在左上，
 * 和主界面的菜单键同一个坐标（ui-spec §四 第 1 条、§十七，项目所有者 2026-11-19）。
 *
 * <p>按布局文件逐个查，以后新加的界面也算在内：标题区那个元素自己要写 {@code layoutDirection="ltr"}。</p>
 */
public class TitleBarDirectionTest {

    /** 一个元素的开始标签（到第一个 {@code >} 为止）。 */
    private static final Pattern TAG = Pattern.compile("<[A-Za-z][^>]*>", Pattern.DOTALL);

    @Test
    public void everyTitleBarStaysLeftToRight() throws IOException {
        File layouts = findLayouts();
        assumeTrue("定位不到 res/layout，跳过", layouts != null);
        int seen = 0;
        List<String> mirrored = new ArrayList<>();
        File[] files = layouts.listFiles((d, n) -> n.endsWith(".xml"));
        for (File file : files) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher tag = TAG.matcher(text);
            while (tag.find()) {
                String start = tag.group();
                if (!start.contains("android:layout_height=\"@dimen/title_bar_height\"")) {
                    continue;
                }
                seen++;
                if (!start.contains("android:layoutDirection=\"ltr\"")) {
                    mirrored.add(file.getName());
                }
            }
        }
        assertTrue("一个标题区都没找到，测试没钉住东西", seen >= 6);
        assertTrue("这些布局的标题区会跟着镜像，菜单 / 返回键跑到右上: " + mirrored, mirrored.isEmpty());
    }

    private static File findLayouts() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            for (File root : new File[]{dir, new File(dir, "app")}) {
                File layouts = new File(root, "src/main/res/layout");
                if (layouts.isDirectory()) {
                    return layouts;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
