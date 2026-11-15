/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.R;

/**
 * 车能发给机器人的每一条消息 —— 一项一条，带一句「它是什么时候来的」（{@code AlertEventTest}）。
 *
 * <h3>为什么是一张表</h3>
 *
 * <p>「发一张测试照片」只证明图片那条路通。车主真正要确认的是<b>每一种</b>消息长什么样：
 * 双闪那一条、忘了开哨兵那一条问、开了之后那张回执、片段按需来的那一条。所以这一项做成一张表，
 * 测试那一屏照着表逐条发出去，发的是真消息、真按钮，只是触发器由人点，不是车自己。</p>
 *
 * <h3>表的顺序就是屏上的顺序</h3>
 *
 * <p>先是警报（车被动了），再是按需要的东西（HD、片段），最后是「忘了开哨兵」和纯文字那几条。
 * 每一行的描述写清楚「真的什么时候会发」和「这一条是模拟的」。</p>
 *
 * <h3>One row per message the car can send</h3>
 *
 * <p>"Send a test picture" only proves the picture path. What the owner needs to check is what every
 * message looks like: the hazard alert, the reminder's question, the receipt after it switched sentry on,
 * the clip on demand. So the events are a table, and the test screen walks it, sending real messages with
 * real buttons — only the trigger is a finger instead of the car.</p>
 */
public enum AlertEvent {

    /* ---- 警报：车被动了 / alerts, something touched the car ---- */
    HAZARD(R.string.alert_headline_hazard, R.string.alert_event_hazard),
    FLASH(R.string.alert_headline_flash, R.string.alert_event_flash),
    DOORS(R.string.alert_headline_door, R.string.alert_event_doors),
    LAMPS(R.string.alert_headline_lights, R.string.alert_event_lamps),
    SCREEN(R.string.alert_headline_screen, R.string.alert_event_screen),
    MOTION(R.string.alert_headline_motion, R.string.alert_event_motion),

    /* ---- 照片与片段：按需才发 / picture and clips, on demand ---- */
    PICTURE(R.string.alert_event_picture_title, R.string.alert_event_picture),
    HD_PICTURE(R.string.alert_btn_hd, R.string.alert_event_hd),
    CLIP_FRONT(R.string.alert_event_clip_front_title, R.string.alert_event_clip_front),
    CLIP_REAR(R.string.alert_event_clip_rear_title, R.string.alert_event_clip_rear),
    CLIP_LEFT(R.string.alert_event_clip_left_title, R.string.alert_event_clip_left),
    CLIP_RIGHT(R.string.alert_event_clip_right_title, R.string.alert_event_clip_right),

    /* ---- 忘了开哨兵 / the Sentry reminder ---- */
    GUARD_ASK(R.string.alert_event_guard_ask_title, R.string.alert_event_guard_ask),
    GUARD_ON(R.string.alert_event_guard_on_title, R.string.alert_event_guard_on),
    GUARD_OFF(R.string.alert_event_guard_off_title, R.string.alert_event_guard_off),
    GUARD_LATE(R.string.alert_event_guard_late_title, R.string.alert_event_guard_late),
    GUARD_ARMED(R.string.alert_event_guard_armed_title, R.string.alert_event_guard_armed),

    /* ---- 纯文字 / text only ---- */
    STATUS(R.string.alert_event_status_title, R.string.alert_event_status),
    HELP(R.string.alert_cmd_help, R.string.alert_event_help);

    /** 这一行左边那句（就是消息的标题）。The line's title — the message's own headline. */
    public final int titleRes;

    /** 这一行下面那句：什么时候真的会发、这一条模拟了什么。When it really goes out, and what is simulated. */
    public final int descriptionRes;

    AlertEvent(int titleRes, int descriptionRes) {
        this.titleRes = titleRes;
        this.descriptionRes = descriptionRes;
    }

    /** 路号：只有四路片段那四行有。The lane, for the four clip rows; -1 otherwise. */
    public int lane() {
        switch (this) {
            case CLIP_FRONT:
                return 0;
            case CLIP_REAR:
                return 1;
            case CLIP_LEFT:
                return 2;
            case CLIP_RIGHT:
                return 3;
            default:
                return -1;
        }
    }

    /** 这一行是不是一张要拍照片的。Whether this row has to take a picture first. */
    public boolean isPicture() {
        switch (this) {
            case HAZARD:
            case FLASH:
            case DOORS:
            case LAMPS:
            case SCREEN:
            case MOTION:
            case PICTURE:
            case HD_PICTURE:
                return true;
            default:
                return false;
        }
    }
}
