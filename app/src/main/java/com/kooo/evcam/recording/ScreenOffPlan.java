/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recording;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.ChannelRules;

/**
 * 录制键上那行小字：现在熄屏的话这段录像会怎样。
 *
 * <p>2026-11-02 起熄屏怎么录只由相机管家判（项目所有者「熄屏判断收归通道调度」）：录像停不停、什么时候停、黑着能不能开、
 * 车机要睡，都在 {@link ChannelRules}（{@link ChannelRules#recordInDark}、{@link ChannelRules#startsInDark}、
 * {@link ChannelRules#headUnitSleeps}），小字照 {@link ChannelRules#darkOutlook} 说 —— 和实际会发生的同一套规矩。
 * 以前这里是录像那一侧自己的那张表（熄屏持续录制关着时屏幕黑 10 秒就停；车机要睡另有一个定义，写进登记表给调度读）。
 * 这里只剩句子。</p>
 */
public enum ScreenOffPlan {

    /** 熄屏后继续录制环视：熄屏持续录制开着、计划里有环视，在开车、或者哨兵模式开或布防。 */
    CONTINUE(R.string.record_screen_off_continue),
    /** 熄屏暂停，亮屏后恢复：黑满 30 秒停，「熄屏持续录制」「启动自动录制」开着一个，亮屏接着录。 */
    PAUSE(R.string.record_screen_off_pause),
    /** 熄屏后停止录制：黑满 30 秒停，亮屏也不接。 */
    STOP(R.string.record_screen_off_stop);

    /** 录制键上那行小字。 */
    public final int text;

    ScreenOffPlan(int text) {
        this.text = text;
    }

    /** 相机管家说的那一种（{@link ChannelRules#darkOutlook}）写成哪一句。 */
    public static ScreenOffPlan of(ChannelRules.DarkOutlook outlook) {
        switch (outlook) {
            case CONTINUE:
                return CONTINUE;
            case PAUSE:
                return PAUSE;
            default:
                return STOP;
        }
    }
}
