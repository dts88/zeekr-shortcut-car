/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 警报那张照片下面那排按钮（纯字符串，{@code AlertButtonTest}）。
 *
 * <h3>为什么照片要带按钮</h3>
 *
 * <p>第一条消息只有一张压到 100 kB 的图：小到可以忽略，但看不清车牌。要看得清、要看那几秒视频，
 * 由车主在聊天里点一下再发 —— 车往上传的量因此只有他真的想看的那一份。</p>
 *
 * <p>按钮和「忘了开哨兵」那一套用同一个号：{@link GuardToken} 的六位记号，只对这一次警报有效，
 * 一周前那条消息上的按钮按下去什么都不做。</p>
 *
 * <h3>Why the picture carries buttons</h3>
 *
 * <p>The first message is one picture compressed under 100 kB: small enough to ignore, not sharp enough to
 * read a plate. Sharper, or the few seconds of video, is one tap away — so the car uploads only what the
 * owner actually wants to look at. The payload is <code>a:&lt;action&gt;:&lt;token&gt;</code> with the
 * same per-alert token shape the Sentry reminder uses.</p>
 */
public final class AlertButton {

    /** 要一张高画质的（900 kB 那一档）。A high-quality picture (the 900 kB ladder). */
    public static final String ACTION_HIGH = "hq";

    /** 要哪一路的视频：{@link #ACTION_CLIP} + 路号。Which camera's clip: {@link #ACTION_CLIP} plus the lane. */
    public static final String ACTION_CLIP = "c";

    private static final String PREFIX = "a";

    private AlertButton() {
    }

    /** 某一路视频的按钮动作。The action for one camera's clip. */
    public static String clipAction(int lane) {
        return ACTION_CLIP + lane;
    }

    /**
     * 这个动作要哪一路的视频。
     *
     * @return 0..3；不是视频动作就是 {@link TelegramCommand#ANY_LANE}
     */
    public static int laneOf(String action) {
        if (action == null || action.length() != 2 || !action.startsWith(ACTION_CLIP)) {
            return TelegramCommand.ANY_LANE;
        }
        char lane = action.charAt(1);
        return lane >= '0' && lane <= '3' ? lane - '0' : TelegramCommand.ANY_LANE;
    }

    /** 按钮上那串字。The bytes a button carries. */
    public static String data(String action, String token) {
        if (action == null || action.isEmpty() || token == null || token.isEmpty()) {
            return "";
        }
        return PREFIX + ":" + action + ":" + token;
    }

    /**
     * 这串字是不是这一次警报的，是的话要做什么。
     *
     * @return {@link #ACTION_HIGH} 或 {@code c0..c3}；号码不对、认不出来都是 {@code null}
     */
    public static String actionFor(String data, String token) {
        if (data == null || token == null || token.isEmpty()) {
            return null;
        }
        String[] parts = data.trim().split(":");
        if (parts.length != 3 || !PREFIX.equals(parts[0]) || !token.equals(parts[2])) {
            return null;
        }
        String action = parts[1];
        if (ACTION_HIGH.equals(action)) {
            return action;
        }
        return laneOf(action) == TelegramCommand.ANY_LANE ? null : action;
    }
}
