package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ReleaseNotes} 的单元测试。
 *
 * <p>样本是 GitHub 上真实发布说明的原文（截短），由 {@code release_notes.py} 生成 ——
 * 那个脚本改了结构，这里就该红，而不是等对话框里冒出一堆样板和 Markdown 记号。</p>
 */
public class ReleaseNotesTest {

    /** 1.5.1-alpha 的发布说明：正文 + 验证 / 开始使用 / 安全三段样板。 */
    private static final String PRERELEASE = String.join("\n",
            "## Zeekr Shortcut (Car Version) 1.5.1-alpha",
            "",
            "Download the `.apk` below and sideload it through App Lab.",
            "",
            "## What changed",
            "",
            "- Diagnostics: the vehicle-signal probe now tries every way in and reports what came back,",
            "  rather than trusting the permission check. It reads each property at every area id.",
            "- The reason for the change is the in-app update.",
            "",
            "## Verified on the vehicle",
            "",
            "Confirmed on a ZEEKR 7X: **the composite stream split into four views**.",
            "",
            "> [!WARNING]",
            "> Everything else is **unverified on a vehicle**.",
            "",
            "## Getting started",
            "",
            "1. Plug in a USB drive.",
            "",
            "## Safety",
            "",
            "Experimental, unofficial software.");

    /** 1.0.0 的发布说明：开头一段介绍 + 主要功能 + 样板，末尾有链接。 */
    private static final String STABLE = String.join("\n",
            "## Zeekr Shortcut (Car Version) 1.0.0",
            "",
            "First stable release. Download the `.apk` below and sideload it through App Lab.",
            "",
            "A surround-view dash cam for the ZEEKR 7X head unit.",
            "",
            "## Main features",
            "",
            "- **Dash cam**: records the surround view as a 2×2 grid.",
            "- **Super mirror**: a floating window showing one camera enlarged.",
            "",
            "## Getting started",
            "",
            "1. Plug in a USB drive.",
            "",
            "## Credits",
            "",
            "Released under **GPL-3.0**, based on [EVCam](https://github.com/suyunkai/EVCam) by suyunkai.");

    @Test
    public void keepsWhatChangedAndDropsTheBoilerplate() {
        String text = ReleaseNotes.summarise(PRERELEASE);
        assertTrue(text, text.startsWith("What changed"));
        assertTrue(text, text.contains("• Diagnostics: the vehicle-signal probe"));
        assertTrue(text, text.contains("• The reason for the change is the in-app update."));
        for (String gone : new String[]{"Verified on the vehicle", "WARNING", "Getting started",
                "Plug in a USB drive", "Safety", "Experimental", "Zeekr Shortcut (Car Version)"}) {
            assertFalse("不该出现 " + gone + "：\n" + text, text.contains(gone));
        }
    }

    /** 发布页那句「下载下面的 apk 去 App Lab 装」在应用里是错的 —— 应用自己会下载。 */
    @Test
    public void dropsTheSideloadInstruction() {
        assertFalse(ReleaseNotes.summarise(PRERELEASE).contains("sideload"));
        String stable = ReleaseNotes.summarise(STABLE);
        assertFalse(stable, stable.contains("sideload"));
        assertTrue(stable, stable.startsWith("First stable release."));
    }

    /** CHANGELOG 的要点折行后续行缩进两格，应当接回同一条，而不是变成一个新段落。 */
    @Test
    public void joinsWrappedBulletLines() {
        String text = ReleaseNotes.summarise(PRERELEASE);
        String firstBullet = text.substring(text.indexOf("• Diagnostics"));
        firstBullet = firstBullet.substring(0, firstBullet.indexOf('\n'));
        assertTrue(firstBullet, firstBullet.contains("reports what came back, rather than trusting"));
        assertTrue(firstBullet, firstBullet.contains("at every area id."));
    }

    @Test
    public void stripsMarkdownMarks() {
        String text = ReleaseNotes.summarise(STABLE);
        assertTrue(text, text.contains("• Dash cam: records the surround view"));
        assertFalse(text, text.contains("**"));
        assertFalse(text, text.contains("`"));
        assertFalse(text, text.contains("#"));
        assertFalse("致谢是样板，不该出现", text.contains("EVCam"));

        String linked = ReleaseNotes.summarise(
                "## Title\n\nSee [the guide](https://example.com/guide) and `code`.");
        assertEquals("See the guide and code.", linked);
    }

    @Test
    public void emptyOrBoilerplateOnlyGivesNothing() {
        assertEquals("", ReleaseNotes.summarise(null));
        assertEquals("", ReleaseNotes.summarise("   "));
        assertEquals("", ReleaseNotes.summarise(
                "## Zeekr Shortcut 1.0.1\n\n## Safety\n\nExperimental."));
    }

    /** 一次跨好几版升级：每一版都列出来，新的在前，用版本号隔开。 */
    @Test
    public void combinesSeveralVersionsNewestFirst() {
        String text = ReleaseNotes.combine(Arrays.asList(
                new ReleaseNotes.Entry("1.5.1-alpha", PRERELEASE),
                new ReleaseNotes.Entry("1.0.0", STABLE)));
        int newer = text.indexOf("[1.5.1-alpha]");
        int older = text.indexOf("[1.0.0]");
        assertTrue(text, newer >= 0 && older > newer);
    }

    /** 只有一个版本时，版本号已经在对话框里了，不必再写。 */
    @Test
    public void aSingleVersionIsNotLabelled() {
        String text = ReleaseNotes.combine(
                Arrays.asList(new ReleaseNotes.Entry("1.0.0", STABLE)));
        assertFalse(text, text.contains("[1.0.0]"));
        assertTrue(text, text.startsWith("First stable release."));
    }

    @Test
    public void longNotesAreCappedAndMarked() {
        StringBuilder body = new StringBuilder("## Title\n\n");
        for (int i = 0; i < 400; i++) {
            body.append("- Change number ").append(i).append(" with some words in it\n");
        }
        List<ReleaseNotes.Entry> entries = new ArrayList<>();
        entries.add(new ReleaseNotes.Entry("9.9.9", body.toString()));
        String text = ReleaseNotes.combine(entries);
        assertTrue("长度 " + text.length(), text.length() <= ReleaseNotes.MAX_CHARS + 2);
        assertTrue(text.endsWith("…"));
    }

    @Test
    public void atMostAHandfulOfVersions() {
        List<ReleaseNotes.Entry> entries = new ArrayList<>();
        for (int i = 9; i >= 0; i--) {
            entries.add(new ReleaseNotes.Entry("1." + i + ".0", "## T\n\n- change " + i));
        }
        String text = ReleaseNotes.combine(entries);
        int labels = text.split("\\[1\\.").length - 1;
        assertEquals(ReleaseNotes.MAX_VERSIONS, labels);
    }
}
