package com.kooo.evcam.button;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 灵动贴（广播名 ZEEKR_button）的按键怎么读。格式是 zeekr-shortcut-lab 在车上实测的（Lab findings §2.61、§2.64、§2.65，
 * 2026-10-29/30 两台按键都对过），这里只照着读。
 *
 * <ul>
 *   <li>广播是一串「长度、类型、数据」。按键的数据装在「16 位服务 UUID 表」的位置（类型 0x02 / 0x03），数据第一个字节总是 0xFF。</li>
 *   <li>它后面那个字节是按键计数：2 位，低位在 0x01、高位在 0x10，每个按键事件加一 —— 计数变了就是按了一下，
 *       同一阵广播里重复的包计数不变。</li>
 *   <li>再后面那个字节是按法：00 单击、02 双击，别的不认。</li>
 *   <li>连着读电量的那一两秒里按键不广播，按法从点击次数的通知来：ASCII 的「1」单击、「2」双击。</li>
 * </ul>
 *
 * <p>全是纯函数（{@code SmartButtonAdTest}）。</p>
 */
public final class SmartButtonAd {

    /** 单击。 */
    public static final int SINGLE = 1;
    /** 双击。 */
    public static final int DOUBLE = 2;

    /** 广播里的名字：搜索时只列名字里有它的（大小写不论）。 */
    static final String NAME = "zeekr_button";

    private SmartButtonAd() {
    }

    /** 一份广播里读出来的：按键计数（0–3）和按法（{@link #SINGLE} / {@link #DOUBLE}，不认识的是 0）。 */
    public static final class Press {
        public final int counter;
        public final int clicks;

        Press(int counter, int clicks) {
            this.counter = counter;
            this.clicks = clicks;
        }
    }

    /** 是不是灵动贴的名字。 */
    public static boolean isButtonName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains(NAME);
    }

    /**
     * 一份广播（{@code ScanRecord.getBytes()}：广播加扫描回应、后面补零）里的按键数据；不是灵动贴那种广播返回 null。
     * 长度越界的段到那里为止。
     */
    public static Press parse(byte[] raw) {
        if (raw == null) {
            return null;
        }
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + 1 + len > raw.length) {
                return null;
            }
            int type = raw[i + 1] & 0xFF;
            int data = i + 2;
            int dataLen = len - 1;
            if ((type == 0x02 || type == 0x03) && dataLen >= 3 && (raw[data] & 0xFF) == 0xFF) {
                int count = raw[data + 1] & 0xFF;
                int counter = (count & 0x01) | ((count >> 4) & 0x01) << 1;
                return new Press(counter, clicksOf(raw[data + 2] & 0xFF));
            }
            i += 1 + len;
        }
        return null;
    }

    /** 按法那个字节 → 几击：00 单击、02 双击，别的是 0。 */
    static int clicksOf(int kind) {
        return kind == 0x00 ? SINGLE : kind == 0x02 ? DOUBLE : 0;
    }

    /** 连着时点击次数通知的值（ASCII 数字）→ 几击：只认 1、2，别的是 0。 */
    public static int clicksOfNotification(byte[] value) {
        if (value == null) {
            return 0;
        }
        String text = new String(value, StandardCharsets.US_ASCII).trim();
        return "1".equals(text) ? SINGLE : "2".equals(text) ? DOUBLE : 0;
    }

    /** 地址的后两段（「B5:E6」）：设置里认是哪一台。 */
    public static String shortId(String address) {
        if (address == null) {
            return "";
        }
        return address.length() > 5 ? address.substring(address.length() - 5) : address;
    }
}
