/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
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
 */
public final class QuietHours {

    /** 默认：22:00 到 07:00（分钟数）。 */
    public static final int DEFAULT_START_MIN = 22 * 60;
    public static final int DEFAULT_END_MIN = 7 * 60;

    private QuietHours() {
    }

    /** 一天里的第几分钟，越界夹回去。 */
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
            return false;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        // 跨午夜：22:00–07:00
        return now >= start || now < end;
    }

    /** {@code "22:00"} → 1320；格式不对返回 -1。 */
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

    /** {@code 1320} → {@code "22:00"}。 */
    public static String format(int minute) {
        int value = clampMinute(minute);
        return String.format(java.util.Locale.US, "%02d:%02d", value / 60, value % 60);
    }
}
