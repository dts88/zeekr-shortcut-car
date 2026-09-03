package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LicensePlate} 的单元测试。
 *
 * <p>清洗规则要钉住，因为界面上显示的、存下来的、录进画面的必须是同一个串 ——
 * 显示一个、录进去另一个，是这个项目反复踩过的那类问题。</p>
 */
public class LicensePlateTest {

    /** 小写转大写：车机软键盘上敲小写太常见了。 */
    @Test
    public void lowercaseBecomesUppercase() {
        assertEquals("ABC123", LicensePlate.sanitize("abc123"));
    }

    /** 空格、连字符、汉字之类一律去掉。 */
    @Test
    public void nonAlphanumericIsDropped() {
        assertEquals("A12345", LicensePlate.sanitize("京 A-12345"));
        assertEquals("AB12", LicensePlate.sanitize("A.B*1 2"));
    }

    /** 超过十位截断。 */
    @Test
    public void longerThanTenIsTruncated() {
        assertEquals(LicensePlate.MAX_LENGTH,
                LicensePlate.sanitize("ABCDEFGHIJKLMN").length());
        assertEquals("ABCDEFGHIJ", LicensePlate.sanitize("ABCDEFGHIJKLMN"));
    }

    /** null 和空串不能抛。 */
    @Test
    public void emptyInputYieldsEmptyOutput() {
        assertEquals("", LicensePlate.sanitize(null));
        assertEquals("", LicensePlate.sanitize(""));
        assertEquals("", LicensePlate.sanitize("   "));
    }

    /** 清洗完还剩东西才算填了。 */
    @Test
    public void usableOnlyWhenSomethingSurvives() {
        assertTrue(LicensePlate.isUsable("苏E88888"));
        assertFalse("全是被过滤掉的字符，等于没填", LicensePlate.isUsable("京·"));
    }

    /** 已经合法的串原样通过。 */
    @Test
    public void alreadyValueIsUnchanged() {
        assertEquals("A88888", LicensePlate.sanitize("A88888"));
    }
}
