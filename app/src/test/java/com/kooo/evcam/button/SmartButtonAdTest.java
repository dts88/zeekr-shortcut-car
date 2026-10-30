package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 灵动贴广播的读法（{@link SmartButtonAd}）：格式照 zeekr-shortcut-lab 车上抓的那几份（findings §2.61、§2.64）——
 * 「16 位服务 UUID 表」那一段装着 FF、按键计数、按法。
 */
public class SmartButtonAdTest {

    /** 一份广播：flags 一段、装按键数据的一段（FF 计数 按法 第 8 字节 01 02 01）、名字一段，后面补零。 */
    private static byte[] advert(int countByte, int kindByte) {
        byte[] name = "ZEEKR_button".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[3 + 10 + 2 + name.length + 8];
        int i = 0;
        raw[i++] = 0x02;
        raw[i++] = 0x01;
        raw[i++] = 0x06;
        raw[i++] = 0x09;
        raw[i++] = 0x03;
        raw[i++] = (byte) 0xFF;
        raw[i++] = (byte) countByte;
        raw[i++] = (byte) kindByte;
        raw[i++] = 0x64;
        raw[i++] = 0x00;
        raw[i++] = 0x01;
        raw[i++] = 0x02;
        raw[i++] = 0x01;
        raw[i++] = (byte) (name.length + 1);
        raw[i++] = 0x09;
        System.arraycopy(name, 0, raw, i, name.length);
        return raw;
    }

    @Test
    public void singleAndDoubleComeFromTheKindByte() {
        SmartButtonAd.Press single = SmartButtonAd.parse(advert(0x01, 0x00));
        assertNotNull(single);
        assertEquals(SmartButtonAd.SINGLE, single.clicks);

        SmartButtonAd.Press twice = SmartButtonAd.parse(advert(0x01, 0x02));
        assertNotNull(twice);
        assertEquals(SmartButtonAd.DOUBLE, twice.clicks);

        // 没见过的按法：照样认出是一包按键广播（计数照算），按法是 0、不做事
        SmartButtonAd.Press unknown = SmartButtonAd.parse(advert(0x01, 0x01));
        assertNotNull(unknown);
        assertEquals(0, unknown.clicks);
    }

    /** 计数 2 位：低位在 0x01、高位在 0x10（只见过 00 / 01 / 10 / 11）。 */
    @Test
    public void counterIsTwoBitsAtOneAndSixteen() {
        assertEquals(0, SmartButtonAd.parse(advert(0x00, 0x00)).counter);
        assertEquals(1, SmartButtonAd.parse(advert(0x01, 0x00)).counter);
        assertEquals(2, SmartButtonAd.parse(advert(0x10, 0x00)).counter);
        assertEquals(3, SmartButtonAd.parse(advert(0x11, 0x00)).counter);
    }

    @Test
    public void otherAdvertsAreNotButtonPresses() {
        assertNull(SmartButtonAd.parse(null));
        assertNull(SmartButtonAd.parse(new byte[0]));
        // UUID 表的第一个字节不是 FF：真的服务号，不是按键数据
        assertNull(SmartButtonAd.parse(new byte[]{0x02, 0x01, 0x06, 0x05, 0x03, 0x0F, 0x18, 0x00, 0x00}));
        // 长度越界：到那里为止
        assertNull(SmartButtonAd.parse(new byte[]{0x02, 0x01, 0x06, 0x20, 0x03, (byte) 0xFF, 0x01}));
    }

    @Test
    public void onlyZeekrButtonNamesAreListed() {
        assertTrue(SmartButtonAd.isButtonName("ZEEKR_button"));
        assertTrue(SmartButtonAd.isButtonName("zeekr_button"));
        assertFalse(SmartButtonAd.isButtonName("Zeekr320"));
        assertFalse(SmartButtonAd.isButtonName("zeekr_fridge"));
        assertFalse(SmartButtonAd.isButtonName(null));
    }

    /** 连着读电量时按键从通知来：ASCII 的 1 / 2，别的不认。 */
    @Test
    public void notificationsCarryAsciiClickCounts() {
        assertEquals(SmartButtonAd.SINGLE, SmartButtonAd.clicksOfNotification("1".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(SmartButtonAd.DOUBLE, SmartButtonAd.clicksOfNotification("2".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, SmartButtonAd.clicksOfNotification("3".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, SmartButtonAd.clicksOfNotification(null));
    }

    @Test
    public void shortIdIsTheLastTwoBytes() {
        assertEquals("B5:E6", SmartButtonAd.shortId("02:75:91:69:B5:E6"));
        assertEquals("", SmartButtonAd.shortId(null));
    }
}
