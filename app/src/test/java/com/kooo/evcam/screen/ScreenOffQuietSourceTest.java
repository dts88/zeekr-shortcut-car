/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 人下车以后界面这一样只归停车管（{@link Parked}，lifecycle-spec §3.3「停车」第 1 步、「主界面」；项目所有者 2026-11-21）：
 * 主界面不再自己排「熄屏 15 秒退后台」，熄屏以后不弹提示（屏幕黑着没人看得到），系统催内存交给停车那一处。
 *
 * <p>照源码和文字资源查：以后有人把那一步、那两句加回来，这里会拦住。</p>
 */
public class ScreenOffQuietSourceTest {

    /** 主界面自己不排熄屏后的那一步：没有那个计时，也不因熄屏叫自己退到后台。 */
    @Test
    public void theMainScreenDoesNotLeaveByItselfAfterScreenOff() throws IOException {
        File module = module();
        assumeTrue("定位不到源码目录，跳过", module != null);
        String main = read(new File(module, "src/main/java/com/kooo/evcam/MainActivity.java"));
        assertFalse("熄屏后的那一步归 Parked", main.contains("scheduleBackgroundTask"));
        assertFalse(main.contains("screenOffBackgroundRunnable"));
        String listener = between(main, "public void onScreenOff() {", "public void onScreenOn() {");
        assertTrue("熄屏那一刻主界面在前的记号照旧记", listener.contains("setUiLeftForScreenOff(true)"));
        assertFalse("熄屏的监听里不退后台", listener.contains("moveTaskToBack"));
        assertFalse("熄屏的监听里不排计时", listener.contains("postDelayed"));
    }

    /** 因熄屏停录不提示：那一格直接返回，不走下面的提示。 */
    @Test
    public void stoppingForScreenOffShowsNoMessage() throws IOException {
        File module = module();
        assumeTrue("定位不到源码目录，跳过", module != null);
        String main = read(new File(module, "src/main/java/com/kooo/evcam/MainActivity.java"));
        String branch = between(main, "case SCREEN_OFF:", "case NO_DATA:");
        assertTrue(branch.contains("return;"));
        assertFalse(branch.contains("getString("));
    }

    /** 那两句提示的文字四种语言里都没有了。 */
    @Test
    public void theTwoMessagesAreGoneInEveryLanguage() throws IOException {
        File module = module();
        assumeTrue("定位不到源码目录，跳过", module != null);
        for (String folder : new String[] {"values", "values-en", "values-ms", "values-ar"}) {
            String strings = read(new File(module, "src/main/res/" + folder + "/strings.xml"));
            assertFalse(folder, strings.contains("msg_screen_off_stopped"));
            assertFalse(folder, strings.contains("msg_screen_off_background"));
        }
    }

    /** 应用入口：开着的界面一起来就记着；系统催内存交给停车那一处判。 */
    @Test
    public void theApplicationWiresScreensAndMemoryPressureToParking() throws IOException {
        File module = module();
        assumeTrue("定位不到源码目录，跳过", module != null);
        String app = read(new File(module, "src/main/java/com/kooo/evcam/ZeekrShortcutApp.java"));
        assertTrue(app.contains("OpenScreens.install(this)"));
        String trim = between(app, "public void onTrimMemory(int level) {", "\n    }");
        assertTrue(trim.contains("Parked.memoryTight(level)"));
    }

    /**
     * 停车那一下关掉的主界面到销毁完之前还查得到：接回界面时它不算还在（另起一个、带上记号），
     * 它销毁时也只摘自己设在管线上的回调（新界面可能已经接上了）。
     */
    @Test
    public void aClosingMainScreenCountsAsGone() throws IOException {
        File module = module();
        assumeTrue("定位不到源码目录，跳过", module != null);
        String recovery = read(new File(module, "src/main/java/com/kooo/evcam/recovery/Recovery.java"));
        assertTrue(recovery.contains("main != null && !main.isFinishing()"));
        assertFalse("接回、带到前面都问 mainScreenAlive",
                recovery.contains("boolean alive = MainActivity.getInstance() != null"));
        String main = read(new File(module, "src/main/java/com/kooo/evcam/MainActivity.java"));
        assertTrue(main.contains("cameraManager.claimUiCallbacks(this)"));
        assertFalse("摘回调要说是谁摘：只摘自己设的", main.contains("detachUiCallbacks()"));
    }

    private static String between(String source, String from, String to) {
        int a = source.indexOf(from);
        assertTrue("找不到 " + from, a >= 0);
        int b = source.indexOf(to, a + from.length());
        assertTrue("找不到 " + to, b > a);
        return source.substring(a, b);
    }

    private static File module() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            for (File module : new File[] {dir, new File(dir, "app")}) {
                if (new File(module, "src/main/java/com/kooo/evcam").isDirectory()) {
                    return module;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
