/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.guard;

/**
 * 按钮上带的那一小段号码 —— 纯字符串，{@code GuardTokenTest}。
 *
 * <h3>为什么按钮要带号码</h3>
 *
 * <p>Telegram 的按钮永远不会过期：一周前那条消息上的「打开哨兵」还按得下去。号码是这一次提醒的记号，
 * 只对这一次提醒有效 —— 换了号码，旧按钮按下去什么都不做，也不会把「上个星期的停车场」当成现在。</p>
 *
 * <h3>形状</h3>
 *
 * <p>{@code g:<action>:<token>}，{@code callback_data} 上限 64 字节，这里最长十几个字节。
 * 里面不放时间、不放坐标、不放会话号：按钮在聊天记录里躺着，不该带着能读的信息。</p>
 *
 * <h3>Why a button carries a number</h3>
 *
 * <p>Telegram buttons never expire, so a week-old "turn sentry on" is still pressable. The token names
 * the one reminder it belongs to; a press with any other token is not ours and does nothing. Nothing
 * but the token goes in the payload — no time, no coordinates, no chat id.</p>
 */
public final class GuardToken {

    /** 「现在就开」。Turn it on now. */
    public static final String ACTION_ON = "on";
    /** 「别开」。Leave it off. */
    public static final String ACTION_NO = "no";
    /** 「关掉」。Switch it off again (the receipt's undo). */
    public static final String ACTION_OFF = "off";
    /** 「说说现在」。One line of state. */
    public static final String ACTION_STATE = "st";

    /** 前缀：认得出的按钮，和别的按钮区分开。 */
    private static final String PREFIX = "g";

    /** 号码长度：36 进制六位，约 31 位。六位够两次提醒不撞，也不占字节。 */
    private static final int LENGTH = 6;

    private GuardToken() {
    }

    /**
     * 给这一次提醒发一个号。
     *
     * @param seed 这一次提醒的时刻（毫秒）之类独一无二的东西
     */
    public static String mint(long seed) {
        long value = seed;
        // 0 也发得出号：拿 0 当「没有号」的地方在这里不成立
        String base = Long.toString(Math.abs(value), 36);
        if (value < 0) {
            base = base + "z";
        }
        while (base.length() < LENGTH) {
            base = "0" + base;
        }
        return base.length() <= LENGTH ? base : base.substring(base.length() - LENGTH);
    }

    /** 按钮上的那串字。The bytes a button carries. */
    public static String data(String action, String token) {
        if (action == null || action.isEmpty() || token == null || token.isEmpty()) {
            return "";
        }
        return PREFIX + ":" + action + ":" + token;
    }

    /**
     * 这串字是不是这一次提醒的，是的话要做什么。
     *
     * @return {@link #ACTION_ON} / {@link #ACTION_NO} / {@link #ACTION_OFF} / {@link #ACTION_STATE}；
     *         {@code null} 表示不是我们的、号码不对、或者认不出来
     */
    public static String actionFor(String data, String token) {
        if (data == null || token == null || token.isEmpty()) {
            return null;
        }
        String body = data.trim();
        String[] parts = body.split(":");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return null;
        }
        if (!token.equals(parts[2])) {
            return null;
        }
        String action = parts[1];
        if (ACTION_ON.equals(action) || ACTION_NO.equals(action)
                || ACTION_OFF.equals(action) || ACTION_STATE.equals(action)) {
            return action;
        }
        return null;
    }
}
