/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 安静时段：这个时间段里不发警报（停车警报）。纯判断（{@code QuietHoursTest}）。
 *
 * <p>为什么还要这个：车停在自己家车库、小区里，夜里有人走过、猫踏过都会响。
 * 那些不是你要知道的事，而且反复响就会让人把整个功能关掉 —— 比响一次更坏。</p>
 *
 * <p>跨午夜算得对：{@code 22:00 → 07:00} 是一段，不是两段。开头与结尾相同时当作“不安静”，
 * 不能因为几次点错就把警报永远关掉。</p>
 *
 * <p><b>用的是车机墙上的钟</b>（D-10，就这样，不改）：这里只看一天里的第几分钟，
 * 那个分钟数由调用方从默认时区取。所以时区一旦因为夏令时改了一小时，窗口在现实中就跟着
 * 多一小时或少一小时 —— 回拨那一夜 {@code 22:00 → 07:00} 是九小时，春进那一夜是五小时。
 * 车平时跑的地方没有夏令时，所以不动它；{@code QuietHoursTest} 用固定偏移把这件事钉住。</p>
 *
 * Quiet hours: no alarm (parking alarm) fires during this time. Pure judgment ({@code QuietHoursTest}).
 *
 * <p>Why this is needed: when the car is parked in your own garage or residential compound, someone
 * walking by or a cat stepping on it at night would fire. Those are not things you need to know, and
 * firing repeatedly makes people turn the whole feature off; worse than firing once.</p>
 *
 * <p>Crossing midnight is computed correctly: {@code 22:00 → 07:00} is one span, not two. When start
 * and end are equal it is treated as "not quiet", so a few wrong taps must not turn the alarm off
 * forever.</p>
 *
 * <p><b>Uses the head unit's wall clock</b> (D-10, settled, not changing): only the minute of the day
 * matters, and the caller takes that minute from the default time zone. So when the offset moves by an
 * hour for daylight saving, the window grows or shrinks by an hour of real time — {@code 22:00 → 07:00}
 * is nine hours on the night the clocks go back and five on the night they go forward. The car does not
 * drive where that happens, so nothing is changed for it; {@code QuietHoursTest} pins this with fixed
 * offsets.</p>
 */
public final class QuietHours {

    /**
     * 默认：22:00 到 07:00（分钟数）。
     *
     * Default: 22:00 to 07:00 (in minutes).
     */
    public static final int DEFAULT_START_MIN = 22 * 60;
    public static final int DEFAULT_END_MIN = 7 * 60;

    private QuietHours() {
    }

    /**
     * 一天里的第几分钟，越界夹回去。
     *
     * The minute of day; clamp out-of-range values back.
     */
    public static int clampMinute(int minute) {
        if (minute < 0) {
            return 0;
        }
        return Math.min(minute, 24 * 60 - 1);
    }

    /**
     * 现在在不在安静时段里。
     *
     * @param enabled 设置里开了没有
     * @param startMin 开始（从 0:00 算的分钟）
     * @param endMin   结束
     * @param nowMin  现在的分钟数
     *
     * Whether it is currently in quiet hours.
     *
     * @param enabled  whether it is on in settings
     * @param startMin start (minutes from 0:00)
     * @param endMin   end
     * @param nowMin   current minute
     */
    public static boolean active(boolean enabled, int startMin, int endMin, int nowMin) {
        if (!enabled) {
            return false;
        }
        int start = clampMinute(startMin);
        int end = clampMinute(endMin);
        int now = clampMinute(nowMin);
        if (start == end) {
            // 一段零长的窗口不是“全天安静”，而是没开
            // A zero-length window is not "quiet all day", it is simply off
            return false;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        // 跨午夜：22:00–07:00
        // Crossing midnight: 22:00–07:00
        return now >= start || now < end;
    }

    /**
     * {@code "22:00"} → 1320；格式不对返回 -1。
     *
     * {@code "22:00"} → 1320; bad format returns -1.
     */
    public static int parse(String text) {
        if (text == null) {
            return -1;
        }
        String value = text.trim();
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, colon).trim());
            int minute = Integer.parseInt(value.substring(colon + 1).trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * {@code 1320} → {@code "22:00"}。
     *
     * Minutes since midnight to HH:mm.
     */
    public static String format(int minute) {
        int value = clampMinute(minute);
        return String.format(java.util.Locale.US, "%02d:%02d", value / 60, value % 60);
    }
}
