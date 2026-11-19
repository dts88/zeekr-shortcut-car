/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * {@link Languages#tagsFor} 的单元测试。
 *
 * <p>要钉住的是「跟随系统」必须映射成<b>空</b>：填一个具体语言就等于永远锁死，
 * 那不叫跟随。</p>
 */
public class LanguagesTest {

    @Test
    public void followSystemMeansNoLocaleAtAll() {
        assertTrue(Languages.tagsFor(Languages.AUTO).isEmpty());
    }

    @Test
    public void explicitChoicesMapToLanguageTags() {
        assertEquals("zh-CN", Languages.tagsFor(Languages.CHINESE));
        assertEquals("en", Languages.tagsFor(Languages.ENGLISH));
        assertEquals("ms", Languages.tagsFor(Languages.MALAY));
        assertEquals("ar-u-nu-latn", Languages.tagsFor(Languages.ARABIC));
    }

    /**
     * 界面数字一律 0–9（ui-text-style §4）：阿拉伯语本来写 ٠–٩，带上 nu-latn 才是 0–9。
     * 第一句先确认不带它时真的不是 0–9，这条测试才钉得住东西。
     */
    @Test
    public void arabicWritesDigits0to9() {
        assertNotEquals("2026", String.format(Locale.forLanguageTag("ar-EG"), "%d", 2026));
        assertEquals("2026", String.format(Locale.forLanguageTag(Languages.tagsFor(Languages.ARABIC)), "%d", 2026));
    }

    /**
     * 跟随系统：系统写 0–9 的照旧不指定（真正跟随）；阿拉伯语的车机用它自己的语言，数字 0–9。
     * 选了语言的不看系统。
     */
    @Test
    public void followingAnArabicSystemStillWritesDigits0to9() {
        assertEquals("", Languages.tagsFor(Languages.AUTO, Locale.US));
        assertEquals("", Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("zh-CN")));
        assertEquals("", Languages.tagsFor(Languages.AUTO, null));
        String tags = Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("ar-EG"));
        assertEquals("ar-EG-u-nu-latn", tags);
        assertEquals("12.5", String.format(Locale.forLanguageTag(tags), "%.1f", 12.5));
        assertEquals("en", Languages.tagsFor(Languages.ENGLISH, Locale.forLanguageTag("ar-EG")));
        assertEquals("ar-u-nu-latn", Languages.tagsFor(Languages.ARABIC, Locale.US));
    }

    /**
     * 跟随系统而系统语言没有翻译：用英文，不落到默认资源的中文（项目所有者 2026-11-19）。
     * 有翻译的照旧（中文、马来文写 0–9，是空）；选了语言的不看系统。截图空壳只套数字那一条。
     */
    @Test
    public void followingAnUntranslatedSystemUsesEnglish() {
        assertEquals("en", Languages.tagsFor(Languages.AUTO, Locale.FRANCE));
        assertEquals("en", Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("fa-IR")));
        assertEquals("en", Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("he-IL")));
        assertEquals("", Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("ms-MY")));
        assertEquals("", Languages.tagsFor(Languages.AUTO, Locale.forLanguageTag("zh-TW")));
        assertEquals("zh-CN", Languages.tagsFor(Languages.CHINESE, Locale.FRANCE));
        assertEquals("", Languages.digits0to9(Locale.forLanguageTag("he-IL")));
    }

    /** 存坏了的值不该锁到某个语言上，回到跟随系统才是安全的落点。 */
    @Test
    public void unknownValuesFallBackToFollowingTheSystem() {
        assertTrue(Languages.tagsFor("fr").isEmpty());
        assertTrue(Languages.tagsFor("").isEmpty());
        assertTrue(Languages.tagsFor(null).isEmpty());
    }

    /** 这几个值就是设置项里的那几档，两边必须对得上。 */
    @Test
    public void theModesAreExactlyTheOnesInTheSetting() {
        String[] values = SettingsRegistry.LANGUAGE.values();
        assertEquals(5, values.length);
        assertEquals(Languages.AUTO, values[0]);
        assertEquals(Languages.CHINESE, values[1]);
        assertEquals(Languages.ENGLISH, values[2]);
        assertEquals(Languages.MALAY, values[3]);
        assertEquals(Languages.ARABIC, values[4]);
        assertEquals(Languages.AUTO, SettingsRegistry.LANGUAGE.defaultValue);
    }
}
