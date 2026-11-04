package com.kooo.evcam.button;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 灵动贴的读法（{@link SmartButtonAd}）：广播照 zeekr-shortcut-lab 车上抓的那几份（findings §2.61、§2.93）读；
 * 通知、查询照 findings §2.92 和 2026-11-04 车上的回话。
 */
public class SmartButtonAdTest {

    /** 一份广播：flags 一段、装按键数据的一段（FF 计数 按法 电量 01 02 01）、名字一段，后面补零。 */
    private static byte[] advert(int type, int counter, int gesture, int battery) {
        byte[] name = "ZEEKR_button".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[3 + 9 + 2 + name.length + 8];
        int i = 0;
        raw[i++] = 0x02;
        raw[i++] = 0x01;
        raw[i++] = 0x06;
        raw[i++] = 0x08;
        raw[i++] = (byte) type;
        raw[i++] = (byte) 0xFF;
        raw[i++] = (byte) counter;
        raw[i++] = (byte) gesture;
        raw[i++] = (byte) battery;
        raw[i++] = 0x01;
        raw[i++] = 0x02;
        raw[i++] = 0x01;
        raw[i++] = (byte) (name.length + 1);
        raw[i++] = 0x09;
        System.arraycopy(name, 0, raw, i, name.length);
        return raw;
    }

    @Test
    public void gesturesComeFromTheSeventhByte() {
        assertEquals(SmartButtonAd.SINGLE, SmartButtonAd.parse(advert(0x02, 0x01, 0x00, 0)).gesture);
        assertEquals(SmartButtonAd.DOUBLE, SmartButtonAd.parse(advert(0x02, 0x01, 0x02, 0)).gesture);
        assertEquals(SmartButtonAd.LONG, SmartButtonAd.parse(advert(0x02, 0x01, 0x01, 0x32)).gesture);
        // 没见过的按法：照样认出是一包按键广播（计数照算），按法是 0、不做事
        SmartButtonAd.Advert unknown = SmartButtonAd.parse(advert(0x02, 0x01, 0x07, 0));
        assertNotNull(unknown);
        assertEquals(0, unknown.gesture);
    }

    /** 计数是整个字节（车上 00 → 01 → 10 → 11 → 00）；版本是后三个字节。 */
    @Test
    public void counterAndVersionAreReadAsIs() {
        SmartButtonAd.Advert ad = SmartButtonAd.parse(advert(0x03, 0x10, 0x00, 0));
        assertEquals(0x10, ad.counter);
        assertEquals("1.2.1", ad.version);
    }

    /** 电量：25 的倍数、又不是 0 才算带了（平时是 0，长按那一下带 0x32 = 50）。 */
    @Test
    public void batteryOnlyWhenItIsAMultipleOfTwentyFive() {
        assertEquals(50, SmartButtonAd.parse(advert(0x02, 0x01, 0x01, 0x32)).battery);
        assertEquals(100, SmartButtonAd.parse(advert(0x02, 0x01, 0x00, 0x64)).battery);
        assertEquals(-1, SmartButtonAd.parse(advert(0x02, 0x01, 0x00, 0)).battery);
        assertEquals(-1, SmartButtonAd.parse(advert(0x02, 0x01, 0x00, 0x31)).battery);
        assertEquals(-1, SmartButtonAd.parse(advert(0x02, 0x01, 0x00, 0xC8)).battery);
    }

    /** 装按键数据的那段不在见过的位置、不够 12 字节：不认（按法会写车，读错了不如不读）。 */
    @Test
    public void otherLayoutsAreNotRead() {
        assertNull(SmartButtonAd.parse(null));
        assertNull(SmartButtonAd.parse(new byte[11]));
        assertNull(SmartButtonAd.parse(advert(0x09, 0x01, 0x00, 0)));
        byte[] notFf = advert(0x02, 0x01, 0x00, 0);
        notFf[5] = 0x0F;
        assertNull(SmartButtonAd.parse(notFf));
    }

    /** 计数跟上次记下的不一样才算新的一下；0xFE 不算；没记下（-1）的哪个都算。 */
    @Test
    public void aNewCounterIsANewPress() {
        assertTrue(SmartButtonAd.isNew(0x01, 0x00));
        assertFalse(SmartButtonAd.isNew(0x01, 0x01));
        assertFalse(SmartButtonAd.isNew(0xFE, 0x01));
        assertTrue(SmartButtonAd.isNew(0x00, -1));
        assertFalse(SmartButtonAd.isNew(0xFE, -1));
    }

    /** 同一下从另一路又来一遍：同一种按法、1.5 秒内（长按 5 秒）只算前面那一次；同一路来的、换了按法的、隔得远的都算。 */
    @Test
    public void theSamePressFromTheOtherPathCountsOnce() {
        long at = 1_000_000L;
        assertTrue(SmartButtonAd.samePress(SmartButtonAd.SINGLE, false, at, SmartButtonAd.SINGLE, true, at + 1_499));
        assertFalse(SmartButtonAd.samePress(SmartButtonAd.SINGLE, false, at, SmartButtonAd.SINGLE, true, at + 1_500));
        assertFalse(SmartButtonAd.samePress(SmartButtonAd.SINGLE, true, at, SmartButtonAd.SINGLE, true, at + 100));
        assertFalse(SmartButtonAd.samePress(SmartButtonAd.SINGLE, false, at, SmartButtonAd.DOUBLE, true, at + 100));
        assertTrue(SmartButtonAd.samePress(SmartButtonAd.LONG, false, at, SmartButtonAd.LONG, true, at + 4_999));
        assertFalse(SmartButtonAd.samePress(SmartButtonAd.LONG, false, at, SmartButtonAd.LONG, true, at + 5_000));
    }

    /** 连着时的通知：按法是 ASCII 的 1–4（车上单击 31、双击 32），也回版本、序列号。 */
    @Test
    public void notificationsCarryGesturesVersionAndSerial() {
        assertEquals(SmartButtonAd.SINGLE, SmartButtonAd.parseNotification(new byte[]{0x31}).gesture);
        assertEquals(SmartButtonAd.DOUBLE, SmartButtonAd.parseNotification(new byte[]{0x32}).gesture);
        assertEquals(SmartButtonAd.LONG, SmartButtonAd.parseNotification(new byte[]{0x33}).gesture);
        assertEquals(SmartButtonAd.RELEASE, SmartButtonAd.parseNotification(new byte[]{0x34}).gesture);
        assertEquals(0, SmartButtonAd.parseNotification(new byte[]{0x39}).gesture);
        assertEquals(0, SmartButtonAd.parseNotification(null).gesture);

        SmartButtonAd.Reply version = SmartButtonAd.parseNotification("zk_v_1_2_1".getBytes(StandardCharsets.US_ASCII));
        assertEquals("1.2.1", version.version);
        assertNull(version.serial);
        assertEquals(0, version.gesture);

        assertEquals("CSB10Z1A0000000001",
                SmartButtonAd.parseNotification("CSB10Z1A0000000001".getBytes(StandardCharsets.US_ASCII)).serial);
        assertEquals("ABC123", SmartButtonAd.parseNotification("psn=ABC123".getBytes(StandardCharsets.US_ASCII)).serial);
    }

    /** 查询：A5 A5 03 06 参数 校验，校验是前面几个字节相加的低 8 位。 */
    @Test
    public void queriesMatchTheOfficialFrames() {
        assertArrayEquals(new byte[]{(byte) 0xA5, (byte) 0xA5, 0x03, 0x06, 0x01, 0x54}, SmartButtonAd.askVersion());
        assertArrayEquals(new byte[]{(byte) 0xA5, (byte) 0xA5, 0x03, 0x06, 0x03, 0x56}, SmartButtonAd.askSerial());
        assertEquals("A5 A5 03 06 01 54", SmartButtonAd.hex(SmartButtonAd.askVersion()));
    }

    @Test
    public void shortIdIsTheLastTwoBytes() {
        assertEquals("B5:E6", SmartButtonAd.shortId("02:75:91:69:B5:E6"));
        assertEquals("", SmartButtonAd.shortId(null));
    }
}
