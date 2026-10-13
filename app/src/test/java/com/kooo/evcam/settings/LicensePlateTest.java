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

    // ---------------------------------------------------------------- 信息条前灯组车牌上的数字

    /** 项目所有者给的三个例子：只留数字，超过四位留最后四位。走 getLicensePlate 那条路（先清洗）。 */
    @Test
    public void infoBarDigitsOwnerExamples() {
        assertEquals("2345", LicensePlate.infoBarDigits(LicensePlate.sanitize("粤B12345")));
        assertEquals("1234", LicensePlate.infoBarDigits(LicensePlate.sanitize("SGX1234A")));
        assertEquals("89", LicensePlate.infoBarDigits(LicensePlate.sanitize("WXY 89")));
    }

    /** 没清洗的原始串也一样：汉字、字母、空格、连字符都去掉。 */
    @Test
    public void infoBarDigitsIgnoresEverythingButDigits() {
        assertEquals("2345", LicensePlate.infoBarDigits("粤B12345"));
        assertEquals("89", LicensePlate.infoBarDigits("WXY 89"));
        assertEquals("1234", LicensePlate.infoBarDigits("京 A-1·2 3-4"));
    }

    /** 不到四位、刚好四位原样；超过四位只留最后四位（数字在前在后、中间夹字母都一样）。 */
    @Test
    public void infoBarDigitsKeepsTheLastFour() {
        assertEquals("7", LicensePlate.infoBarDigits("A7"));
        assertEquals("1234", LicensePlate.infoBarDigits("1234"));
        assertEquals("5678", LicensePlate.infoBarDigits("12345678"));
        assertEquals("0123", LicensePlate.infoBarDigits("9A0B1C2D3"));
        assertEquals("0808", LicensePlate.infoBarDigits("苏E80808"));
    }

    /** 没填、只有字母、只有被过滤掉的字符：空串 —— 车牌照旧空着。 */
    @Test
    public void infoBarDigitsEmptyWhenNoDigits() {
        assertEquals("", LicensePlate.infoBarDigits(null));
        assertEquals("", LicensePlate.infoBarDigits(""));
        assertEquals("", LicensePlate.infoBarDigits("ABC"));
        assertEquals("", LicensePlate.infoBarDigits(LicensePlate.sanitize("京·")));
    }

    /** 只认 ASCII 的 0–9：全角数字之类不算（清洗过的车牌里本来也不会有）。 */
    @Test
    public void infoBarDigitsAsciiOnly() {
        assertEquals("", LicensePlate.infoBarDigits("１２３"));
        assertEquals("24", LicensePlate.infoBarDigits("１2３4"));
    }
}
