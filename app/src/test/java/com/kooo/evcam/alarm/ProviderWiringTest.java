/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 出口必须换得掉：一个包、一个类、工厂里一行，编排那边一个字都不用改。
 *
 * <h3>这条规矩为什么写成一个测试</h3>
 *
 * <p>「加第三条出口应该很便宜」这句话，写在文档里就等于没有。真正会发生的是一年后有人为了赶一个
 * 功能，在 {@code SentryAlerts} 里加一个 {@code if ("signal".equals(route))}，然后这条规矩就没了，
 * 而没人会注意到 —— 因为加的时候一切都能跑。</p>
 *
 * <p>所以规矩是可执行的：{@code alarm} 包里每一个 {@code implements AlertChannel} 的类，必须在
 * {@link AlertChannels} 里出现一次；反过来，{@link SentryAlerts} 和
 * {@link com.kooo.evcam.alarm.policy.AlertPolicy} 里一个具体出口的名字都不许出现。加第三条出口忘了
 * 接上 → 这里红。为了省事在编排里点名 → 这里也红。</p>
 *
 * <h3>边界（说清楚，不假装覆盖）</h3>
 *
 * <p>钉住的是<b>发送</b>那一路。设置向导里的活体探测（问 ntfy 账号、问机器人叫什么、取会话列表）
 * 现在仍然在 {@link SentryAlerts} 里直接点名各自的 {@code Sender} —— 那是「配好了没有」的问题，
 * 不是「发到哪」的问题，硬塞进 {@link AlertChannel} 会给出七个空实现。这条边界留着，但它有代价：
 * 第三条出口的探测逻辑会想往 {@code SentryAlerts} 里加代码，加的时候要另开一个接口，不许就地堆。</p>
 *
 * <h3>Why this rule is a test</h3>
 *
 * <p>"A third exit should be cheap" written in a document is the same as not written. What actually
 * happens is someone adds {@code if ("signal".equals(route))} to {@code SentryAlerts} to hit a deadline,
 * and the rule is gone with nobody noticing — because everything still runs the day it is added.</p>
 *
 * <p>So the rule is executable: every {@code implements AlertChannel} class in the {@code alarm} package
 * must appear once in {@link AlertChannels}, and neither {@link SentryAlerts} nor
 * {@link com.kooo.evcam.alarm.policy.AlertPolicy} may name a concrete exit. A third exit left unwired
 * fails here; naming one in the orchestrator to save time fails here too.</p>
 *
 * <h3>Boundary (stated, not pretended away)</h3>
 *
 * <p>What is pinned is the <b>sending</b> path. The setup wizards' live probes (ask the ntfy account, ask
 * the bot's name, list chats) still name their {@code Sender} inside {@link SentryAlerts}: that is a
 * "is this configured" question, not a "where does it go" one, and forcing it into {@link AlertChannel}
 * would hand seven implementations a no-op. The boundary stays, with a price: a third exit's probe will
 * want to add code to {@code SentryAlerts}. When that happens, give it its own interface — do not pile it
 * on in place.</p>
 */
public class ProviderWiringTest {

    /** 编排里要干净的两个文件。The two files that must stay clean. */
    private static final String[] ORCHESTRATORS = {
            "SentryAlerts.java",
            "policy/AlertPolicy.java",
    };

    @Test
    public void everyExitInThePackageIsWiredIntoTheFactory() throws IOException {
        File alarmDir = alarmSourceDir();
        List<String> exits = concreteChannelNames(alarmDir);
        assertFalse("一个出口都没找到：这个测试自己坏了，别当成通过", exits.isEmpty());

        String factory = read(new File(alarmDir, "AlertChannels.java"));
        List<String> unwired = new ArrayList<>();
        for (String exit : exits) {
            if (!factory.contains(exit)) {
                unwired.add(exit);
            }
        }
        assertTrue("这些出口没有接进 AlertChannels.of()，生产代码里永远不会被选中：" + unwired,
                unwired.isEmpty());
    }

    @Test
    public void theOrchestratorNamesNoConcreteExit() throws IOException {
        File alarmDir = alarmSourceDir();
        List<String> exits = concreteChannelNames(alarmDir);

        List<String> leaks = new ArrayList<>();
        for (String orchestrator : ORCHESTRATORS) {
            String source = read(new File(alarmDir, orchestrator));
            for (String exit : exits) {
                if (source.contains(exit)) {
                    leaks.add(orchestrator + " 点名了 " + exit);
                }
            }
        }
        assertTrue("发送这条路上不许认出具体出口，只认 AlertChannel（换出口才会是零改动）：" + leaks,
                leaks.isEmpty());
    }

    /**
     * 第三条出口就是一个类：把 {@link AlertChannel} 的六个方法写出来，不碰现有的两个。
     *
     * <p>这里用一个假的实现把接口走一遍，证明它自足 —— 不需要 Telegram 或 ntfy 的任何东西，
     * 也不需要 {@code SentryAlerts} 知道它是谁。</p>
     *
     * A third exit is one class: six methods of {@link AlertChannel}, touching neither existing exit.
     * A fake implementation walks the interface here to show it is self-contained — it needs nothing
     * from Telegram or ntfy, and {@code SentryAlerts} never learns who it is.
     */
    @Test
    public void aThirdExitIsOneClassAndNothingElse() {
        AlertChannel signal = new SignalChannel();
        assertEquals("黑匣子写这个名字", "signal", signal.name());
        assertTrue(signal.ready());
        assertEquals(AlertChannel.Kind.OK, signal.blockedReason());
        assertFalse("能力差异由出口自己回答，不由编排去问名字", signal.supportsClips());

        AlertChannel.Result sent = signal.sendText("车回来了");
        assertTrue(sent.ok);
        assertEquals("成功时界面不该显示任何东西", "", sent.display());

        AlertChannel.Result lost = signal.sendText("");
        assertFalse(lost.ok);
        assertEquals(AlertChannel.Kind.NETWORK, lost.kind);
        assertEquals("失败的那一句就是要显示的那一句", "signal: nobody home", lost.display());
    }

    /** 一个**假**的第三条出口。A fake third exit. */
    private static final class SignalChannel implements AlertChannel {

        @Override
        public String name() {
            return "signal";
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public Kind blockedReason() {
            return Kind.OK;
        }

        @Override
        public boolean supportsClips() {
            return false;
        }

        @Override
        public Result sendPhoto(byte[] jpeg, String caption) {
            return Result.ok();
        }

        @Override
        public Result sendText(String text) {
            if (text == null || text.isEmpty()) {
                return Result.fail(Kind.NETWORK, 0, "signal: nobody home");
            }
            return Result.ok();
        }
    }

    /** 包里所有实现了 {@link AlertChannel} 的类名。Every class in the package implementing it. */
    private static List<String> concreteChannelNames(File dir) throws IOException {
        List<String> names = new ArrayList<>();
        List<File> files = new ArrayList<>();
        collect(dir, files);
        for (File file : files) {
            String name = file.getName();
            if ("AlertChannel.java".equals(name)) {
                continue;
            }
            // 先去注释再看声明：注释里写「implements AlertChannel」的类不是出口，
            // 这个测试第一版就是被自己 javadoc 绊倒的。
            // Strip comments before looking at declarations: a class whose javadoc says
            // "implements AlertChannel" is not an exit — the first version of this test tripped on
            // exactly that.
            String source = stripComments(read(file));
            java.util.regex.Matcher declaration = java.util.regex.Pattern
                    .compile("\\bclass\\s+(\\w+)[^\\{;]*\\bimplements\\b[^\\{;]*\\bAlertChannel\\b")
                    .matcher(source);
            if (declaration.find()) {
                names.add(declaration.group(1));
            }
        }
        assertNotNull(names);
        return names;
    }

    /** 去掉块注释和行注释，只留代码。Drop block and line comments, keep the code. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static void collect(File dir, List<File> into) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else if (child.getName().endsWith(".java")) {
                into.add(child);
            }
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 源码目录。Gradle 跑单测时工作目录是 {@code app/}，但从 IDE 跑可能是仓库根 —— 两种都认。
     *
     * The source directory. Gradle runs unit tests with {@code app/} as the working directory, but an
     * IDE may use the repository root; both are accepted.
     */
    private static File alarmSourceDir() {
        String[] candidates = {
                "src/main/java/com/kooo/evcam/alarm",
                "app/src/main/java/com/kooo/evcam/alarm",
        };
        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        File dir = new File(System.getProperty("user.dir"));
        while (dir != null) {
            File candidate = new File(dir, "app/src/main/java/com/kooo/evcam/alarm");
            if (candidate.isDirectory()) {
                return candidate;
            }
            candidate = new File(dir, "src/main/java/com/kooo/evcam/alarm");
            if (candidate.isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("找不到 alarm 源码目录，工作目录是 " + new File(".").getAbsolutePath());
        return null;
    }
}
