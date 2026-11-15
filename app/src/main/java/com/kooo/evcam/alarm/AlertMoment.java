/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 警报里那一个时刻：怎么写成字、怎么从车主回的字里读回来。纯字符串和日历，不碰 Android、不碰位图。
 *
 * <h3>为什么时刻要单独拿出来</h3>
 *
 * <p>一条警报是钉在一个时刻上的（{@code 18:16} 那张图、那段录像）。车主回的字必须回答那一个时刻，
 * 或者直说那个时刻拿不到 —— 不能悄悄换成一个新的。所以这里只做两件事：把一个墙钟毫秒写成能贴进
 * 消息里的文本，以及把文本读回今天的那个墙钟毫秒。</p>
 *
 * <h3>为什么往后一分钟的都算读不懂</h3>
 *
 * <p>录像里不可能有未来的画面。读到比「现在 + 1 分钟」还晚的时间，只可能是读错了或者车主写的不是
 * 今天 —— 两种情况都不该猜，返回 {@code null} 交给上层说不出来。</p>
 *
 * A single moment in an alert: how to write it down, and how to read it back from what the
 * owner typed. Pure strings and calendar; no Android, no bitmaps.
 *
 * <h3>Why the moment is its own thing</h3>
 *
 * <p>An alert is pinned to one moment (the picture and the clip at {@code 18:16}). A reply must
 * answer about that moment, or say plainly that it is not available -- it must never quietly
 * record a new one. So this does two jobs only: render a wall-clock millisecond as text that
 * can go into a message, and read text back to that time-of-day's millisecond today.</p>
 *
 * <h3>Why anything later than a minute ahead does not parse</h3>
 *
 * <p>There can be no future frames in a recording. A time later than "now + 1 minute" means the
 * text was misread or is not about today; neither should be guessed at, so it returns {@code null}
 * and the caller says it is not available.</p>
 */
public final class AlertMoment {

    /**
     * 回的字里可能带的头一个字，读之前先丢掉。
     *
     * The leading word a reply may carry, dropped before reading the time.
     */
    private static final Pattern LEADING_WORD = Pattern.compile("^(pic|status)\\b\\s*");

    /**
     * 时间本身：{@code 18:22}、{@code 18:22:30}、{@code 6:22pm}、{@code 6:22 pm}、{@code 6pm}。
     *
     * The time itself: {@code 18:22}, {@code 18:22:30}, {@code 6:22pm}, {@code 6:22 pm},
     * {@code 6pm}.
     */
    private static final Pattern CLOCK_FORM = Pattern.compile(
            "^(\\d{1,2})(?::(\\d{2}))?(?::(\\d{2}))?\\s*(am|pm)?$");

    /** 读不懂时最多认到「现在往后一分钟」。 */
    private static final long FUTURE_SLACK_MS = 60_000L;

    private AlertMoment() {
    }

    /**
     * 带时区的 ISO-8601，例如 {@code 2026-11-15T18:16:04+11:00}。使用的总是设备默认时区。
     *
     * <p>不抛异常：格式器出任何问题都退回 UTC 再写一次，还不行就给空串 —— 一行日志的时间戳不值得
     * 让发警报的那条路崩掉。</p>
     *
     * ISO-8601 with the offset, e.g. {@code 2026-11-15T18:16:04+11:00}, always in the device's
     * default zone.
     *
     * <p>Never throws: any formatting failure retries in UTC and then yields an empty string -- a
     * log timestamp is not worth crashing the alert path for.</p>
     */
    public static String iso(long wallMs) {
        // 0 是「没有时刻」，不是 1970 年：宁可空着，也不在图上印一个假日期。
        // Zero means "no moment", not 1970: an empty string beats a false date printed on the picture.
        if (wallMs <= 0) {
            return "";
        }
        try {
            return format("yyyy-MM-dd'T'HH:mm:ssXXX", wallMs, null);
        } catch (RuntimeException e) {
            try {
                return format("yyyy-MM-dd'T'HH:mm:ssXXX", wallMs, TimeZone.getTimeZone("UTC"));
            } catch (RuntimeException ignored) {
                return "";
            }
        }
    }

    /**
     * 只看一天里的哪一刻，{@code 18:16}。照样是设备默认时区。
     *
     * Only the time of day, {@code 18:16}, also in the device's default zone.
     */
    public static String clock(long wallMs) {
        try {
            return format("HH:mm", wallMs, null);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 把一段文字读回「今天那一刻」的墙钟毫秒；读不懂就给 {@code null}。
     *
     * <p>认得 {@code 18:22}、{@code 18:22:30}、{@code 6:22pm}、{@code 6:22 pm}、{@code 6pm}，
     * 前面带 {@code pic } 或 {@code status } 也认。比 {@code nowWallMs} 往后一分钟还晚的算读不懂
     * —— 录像里没有未来的画面。</p>
     *
     * Read text back to today's wall-clock millisecond; {@code null} when it does not parse.
     *
     * <p>Accepts {@code 18:22}, {@code 18:22:30}, {@code 6:22pm}, {@code 6:22 pm} and {@code 6pm},
     * with an optional leading {@code pic } or {@code status }. Anything later than
     * {@code nowWallMs} plus a minute does not parse -- a recording holds no future frames.</p>
     */
    public static Long parseClock(String text, long nowWallMs) {
        if (text == null) {
            return null;
        }
        String body = text.trim().toLowerCase(Locale.US);
        Matcher lead = LEADING_WORD.matcher(body);
        if (lead.find()) {
            body = body.substring(lead.end()).trim();
        }
        if (body.isEmpty()) {
            return null;
        }
        Matcher form = CLOCK_FORM.matcher(body);
        if (!form.matches()) {
            return null;
        }
        Integer hour = number(form.group(1));
        Integer minute = form.group(2) == null ? Integer.valueOf(0) : number(form.group(2));
        Integer second = form.group(3) == null ? Integer.valueOf(0) : number(form.group(3));
        String meridiem = form.group(4);
        if (hour == null || minute == null || second == null) {
            return null;
        }
        if (meridiem == null) {
            // 没有 am/pm 就是 24 小时制：必须带分钟，不然「6」是早上还是下午说不清。
            // Without am/pm it is 24-hour clock: minutes are required, or "6" could be either half.
            if (form.group(2) == null || hour > 23) {
                return null;
            }
        } else {
            if (hour < 1 || hour > 12) {
                return null;
            }
            boolean pm = "pm".equals(meridiem);
            if (hour == 12) {
                hour = pm ? 12 : 0;
            } else if (pm) {
                hour = hour + 12;
            }
        }
        if (minute > 59 || second > 59) {
            return null;
        }
        long wallMs = timeOfDay(nowWallMs, hour, minute, second);
        if (wallMs > nowWallMs + FUTURE_SLACK_MS) {
            return null;
        }
        return wallMs;
    }

    /**
     * 给人看的那一行：今天只写 {@code 18:16}，隔天写成 {@code 2026-11-15 18:16}。
     *
     * The line a human reads: just {@code 18:16} for today, {@code 2026-11-15 18:16} otherwise.
     */
    public static String describe(long wallMs, long nowWallMs) {
        try {
            Calendar at = calendar(wallMs);
            Calendar now = calendar(nowWallMs);
            boolean today = at.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && at.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
            return format(today ? "HH:mm" : "yyyy-MM-dd HH:mm", wallMs, null);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 那个时刻是不是还在发警报用的时间窗里：{@code 0 <= now - event <= ringMs}。
     *
     * <p>年龄是负的（时刻在未来）或者窗口非正，都算不在 —— 一个没有长度的窗口装不下任何东西。</p>
     *
     * Whether the moment still sits inside the window a ring is valid for:
     * {@code 0 <= now - event <= ringMs}.
     *
     * <p>A negative age (the moment is in the future) or a non-positive window is outside -- a
     * window with no length holds nothing.</p>
     */
    public static boolean withinRing(long eventWallMs, long nowWallMs, long ringMs) {
        if (ringMs <= 0) {
            return false;
        }
        long age = nowWallMs - eventWallMs;
        return age >= 0 && age <= ringMs;
    }

    /**
     * 今天那一刻的墙钟毫秒，用 {@code nowWallMs} 落在哪一天。
     *
     * Today's wall-clock millisecond for that time of day, where "today" is the day
     * {@code nowWallMs} falls on.
     */
    private static long timeOfDay(long nowWallMs, int hour, int minute, int second) {
        Calendar now = calendar(nowWallMs);
        Calendar at = Calendar.getInstance();
        at.clear();
        at.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH),
                hour, minute, second);
        return at.getTimeInMillis();
    }

    private static Calendar calendar(long wallMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(wallMs);
        return calendar;
    }

    private static String format(String pattern, long wallMs, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
        if (zone != null) {
            formatter.setTimeZone(zone);
        }
        return formatter.format(new Date(wallMs));
    }

    private static Integer number(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
