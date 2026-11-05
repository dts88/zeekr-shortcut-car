/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 灵动贴（广播名 ZEEKR_button）的广播、通知和查询怎么读、怎么写。格式是 zeekr-shortcut-lab 在车上实测、又对过中国版「极+」的
 * （Lab findings §2.61、§2.65、§2.92、§2.93，2026-11-04 车上又对了一遍），这里只照着读。
 *
 * <h3>广播</h3>
 * <p>下标是 {@code ScanRecord.getBytes()} 的绝对位置，跟「极+」一样不拆段。车上的灵动贴是
 * {@code 02 01 06 | 08 02 FF 计数 按法 电量 01 02 01 | 名字 | 发射功率}。</p>
 * <ul>
 *   <li>[4] 是 02 / 03、[5] 是 FF：装按键数据的那一段在车上见过的位置。不在这里的不认：按法会写车（驾驶模式），读错了不如不读。</li>
 *   <li>[6] 计数：跟上次记下的不一样就是按了一下；0xFE 不算（「极+」）。车上按 00 → 01 → 10 → 11 → 00 走。</li>
 *   <li>[7] 按法：00 单击、02 双击、01 长按（约 3 秒、背光闪；只用来连接，不绑动作，车主 2026-11-04）。</li>
 *   <li>[8] 电量：是 25 的倍数、又不是 0 才算带了（平时是 0，长按那一下带 0x32 = 50，跟连上读的一样）。</li>
 *   <li>[9]–[11] 固件版本（两台都是 1.2.1）。</li>
 * </ul>
 *
 * <h3>连着时</h3>
 * <p>服务 0x1820 的 0x2A60 推通知：按法是 ASCII 的 '1' 单击、'2' 双击、'3' 长按、'4' 长按松开（车上只见过 31、32）；
 * 也回查询的结果：{@code zk_v_1_2_1} 是固件版本，{@code psn=…} 或 {@code CSB10Z1A…} 开头的是序列号。
 * 查询写到 0x2A70：{@code A5 A5 类型 总长 参数 校验}，校验 = 前面各字节相加的低 8 位。只查版本、查序列号，
 * 不写背光、睡眠这些（车主 2026-11-04）。</p>
 *
 * <p>全是纯函数（{@code SmartButtonAdTest}）。</p>
 */
public final class SmartButtonAd {

    /** 单击。也是单击选的动作存在哪一格（{@code AppConfig#getSmartButtonAction}）。 */
    public static final int SINGLE = 1;
    /** 双击。 */
    public static final int DOUBLE = 2;
    /** 长按（约 3 秒、背光闪）：只用来连接，不绑动作。 */
    public static final int LONG = 3;
    /** 长按松开：只在连着时从通知来，不做事。 */
    public static final int RELEASE = 4;

    /** 广播里的名字：扫描按它过滤（整个比、分大小写）。 */
    public static final String NAME = "ZEEKR_button";

    /** 不算的计数（「极+」）。 */
    static final int COUNTER_IGNORED = 0xFE;
    /** 同一下从另一路又来一遍，隔这么近算同一下；长按另给 5 秒（Lab 3.5.0 起的做法）。 */
    static final long SAME_PRESS_MS = 1_500L;
    static final long SAME_LONG_MS = 5_000L;

    /** 序列号回话不带 {@code psn=} 时的开头（灵动贴的型号）。 */
    private static final String SERIAL_PREFIX = "CSB10Z1A";
    private static final String VERSION_PREFIX = "zk_v_";
    private static final String PSN_PREFIX = "psn=";

    private SmartButtonAd() {
    }

    /** 一包广播里读出来的。 */
    public static final class Advert {
        /** 计数（整个字节）。 */
        public final int counter;
        /** {@link #SINGLE} / {@link #DOUBLE} / {@link #LONG}；不认识的是 0。 */
        public final int gesture;
        /** 带的电量（%）；没带是 -1。 */
        public final int battery;
        /** 固件版本，「1.2.1」。 */
        public final String version;

        Advert(int counter, int gesture, int battery, String version) {
            this.counter = counter;
            this.gesture = gesture;
            this.battery = battery;
            this.version = version;
        }
    }

    /** 一份广播；不到 12 字节、或者装按键数据的那段不在见过的位置，返回 null。 */
    public static Advert parse(byte[] raw) {
        if (raw == null || raw.length < 12) {
            return null;
        }
        int type = raw[4] & 0xFF;
        if ((type != 0x02 && type != 0x03) || (raw[5] & 0xFF) != 0xFF) {
            return null;
        }
        int battery = raw[8] & 0xFF;
        String version = String.format(Locale.US, "%d.%d.%d", raw[9] & 0xFF, raw[10] & 0xFF, raw[11] & 0xFF);
        return new Advert(raw[6] & 0xFF, gestureOfAdvert(raw[7] & 0xFF),
                battery > 0 && battery <= 100 && battery % 25 == 0 ? battery : -1, version);
    }

    /** 广播的按法字节 → 按法：00 单击、02 双击、01 长按，别的是 0。 */
    static int gestureOfAdvert(int value) {
        switch (value) {
            case 0x00:
                return SINGLE;
            case 0x02:
                return DOUBLE;
            case 0x01:
                return LONG;
            default:
                return 0;
        }
    }

    /**
     * 这一包算不算新的一下：计数跟上次记下的不一样，又不是 0xFE。{@code last} 是 -1（没记下、或者连上过以后作废了）时哪个都算：
     * 按名字过滤以后只有按下时才有广播，没记下的那一包就是新按的。
     */
    static boolean isNew(int counter, int last) {
        return counter != COUNTER_IGNORED && counter != last;
    }

    /**
     * 同一下按键从另一路又来了一遍（广播、通知各报一次，连上、断开的那一刻可能有）：同一种按法、隔得这么近，只算前面那一次。
     */
    static boolean samePress(int lastGesture, boolean lastViaNotification, long lastAt,
                             int gesture, boolean viaNotification, long now) {
        return lastGesture == gesture && lastViaNotification != viaNotification
                && now - lastAt < (gesture == LONG ? SAME_LONG_MS : SAME_PRESS_MS);
    }

    /** 0x2A60 来的一条：版本、序列号、按法，三样只有一样。 */
    public static final class Reply {
        /** 固件版本「1.2.1」；不是版本是 null。 */
        public final String version;
        /** 序列号；不是序列号是 null。 */
        public final String serial;
        /** 按法；不是按法、认不出是 0。 */
        public final int gesture;

        Reply(String version, String serial, int gesture) {
            this.version = version;
            this.serial = serial;
            this.gesture = gesture;
        }
    }

    /** 拆一条通知：先当字符串看是不是版本、序列号（「极+」的读法），不是的话第 0 个字节是按法。 */
    static Reply parseNotification(byte[] value) {
        if (value == null || value.length == 0) {
            return new Reply(null, null, 0);
        }
        String text = new String(value, StandardCharsets.US_ASCII).trim();
        if (text.startsWith(VERSION_PREFIX)) {
            return new Reply(text.substring(VERSION_PREFIX.length()).replace('_', '.'), null, 0);
        }
        if (text.startsWith(PSN_PREFIX)) {
            return new Reply(null, text.substring(PSN_PREFIX.length()), 0);
        }
        if (text.startsWith(SERIAL_PREFIX)) {
            return new Reply(null, text, 0);
        }
        switch (value[0]) {
            case '1':
                return new Reply(null, null, SINGLE);
            case '2':
                return new Reply(null, null, DOUBLE);
            case '3':
                return new Reply(null, null, LONG);
            case '4':
                return new Reply(null, null, RELEASE);
            default:
                return new Reply(null, null, 0);
        }
    }

    /** 查固件版本：{@code A5 A5 03 06 01 54}。 */
    static byte[] askVersion() {
        return query(0x01);
    }

    /** 查序列号：{@code A5 A5 03 06 03 56}。 */
    static byte[] askSerial() {
        return query(0x03);
    }

    /** 查询（类型 03）：{@code A5 A5 03 06 参数 校验}。 */
    private static byte[] query(int what) {
        byte[] out = {(byte) 0xA5, (byte) 0xA5, 0x03, 0x06, (byte) what, 0};
        int sum = 0;
        for (int i = 0; i < out.length - 1; i++) {
            sum += out[i] & 0xFF;
        }
        out[out.length - 1] = (byte) sum;
        return out;
    }

    /** 一串字节写成十六进制（黑匣子用）。 */
    static String hex(byte[] value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : value) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 地址的后两段（「B5:E6」）：设置里认是哪一台。 */
    public static String shortId(String address) {
        if (address == null) {
            return "";
        }
        return address.length() > 5 ? address.substring(address.length() - 5) : address;
    }
}
