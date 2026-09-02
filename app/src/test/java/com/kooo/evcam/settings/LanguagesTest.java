package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
    }

    /** 存坏了的值不该锁到某个语言上，回到跟随系统才是安全的落点。 */
    @Test
    public void unknownValuesFallBackToFollowingTheSystem() {
        assertTrue(Languages.tagsFor("fr").isEmpty());
        assertTrue(Languages.tagsFor("").isEmpty());
        assertTrue(Languages.tagsFor(null).isEmpty());
    }

    /** 这三个值就是设置项里的三档，两边必须对得上。 */
    @Test
    public void theThreeModesAreExactlyTheOnesInTheSetting() {
        String[] values = SettingsRegistry.LANGUAGE.values();
        assertEquals(3, values.length);
        assertEquals(Languages.AUTO, values[0]);
        assertEquals(Languages.CHINESE, values[1]);
        assertEquals(Languages.ENGLISH, values[2]);
        assertEquals(Languages.AUTO, SettingsRegistry.LANGUAGE.defaultValue);
    }
}
