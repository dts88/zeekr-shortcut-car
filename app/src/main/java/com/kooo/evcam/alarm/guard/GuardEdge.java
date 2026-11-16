/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.guard;

/**
 * 「车主走了」这一刻怎么认 —— 纯判断（{@code GuardEdgeTest}）。
 *
 * <h3>两条边，主驾座椅是裁决者</h3>
 *
 * <p>屏幕灭（{@code SCREEN_POWER_EVENT} 亮 → 黑）和主驾座椅有人 → 没人，两条都算离开的那一刻，
 * 但<b>车里还坐着人的时候一律不算</b>：在车里按锁车，屏幕同样会黑，那时问一句「哨兵没开」是错的 ——
 * 车主就在方向盘后面。所以：</p>
 *
 * <ul>
 *   <li>主驾座椅有人 → 没人：离开。这一条在车主起身、关门的时候就来，最早也最可信。</li>
 *   <li>屏幕亮 → 黑：只有在主驾座椅<b>确定没人</b>的时候才算离开（车主早几秒已经起身，屏幕跟着锁车黑掉）。</li>
 *   <li>屏幕黑了、座椅读数却是「不知道」：不动。座椅读数空着的时候分不出「车里有人」和「人走了」，
 *       而这两件事该做的事正好相反。</li>
 *   <li>两条边都没有：什么都不做。</li>
 * </ul>
 *
 * <h3>Seat first, screen second, and nobody sitting in the driver's seat</h3>
 *
 * <p>Both the screen going black and the driver seat going occupied → empty are leaving moments, but
 * <b>never while someone is still sitting in the driver's seat</b>: locking the car from inside also
 * blacks the screen, and asking "Sentry Mode is off" then is wrong — the owner is at the wheel. The seat
 * emptying is the early, reliable edge; the screen going black only counts when the seat is already known
 * to be empty. A black screen with an unreadable seat decides nothing: an empty reading cannot separate
 * "someone is in the car" from "the owner walked away", and those two want opposite things.</p>
 */
public final class GuardEdge {

    /** 这一刻认成了什么。What this moment was taken for. */
    public enum Leaving {
        /** 车主走了。The owner left: this is the departure. */
        DEPARTURE,
        /** 车在走，或者停着但还没停稳：不是在离开。Driving, or not settled yet: not a departure. */
        NOT_PARKED,
        /** 两条边都没出现。Neither edge happened. */
        NO_EDGE,
        /** 屏幕黑了，可主驾座椅上还有人：不是离开，车里坐着人呢。Screen went black with the driver seat still occupied. */
        STILL_SEATED,
        /** 有边，但主驾座椅读不到：分不清，就不动。An edge, but the driver seat is unreadable: decide nothing. */
        SEAT_UNKNOWN
    }

    private GuardEdge() {
    }

    /**
     * 这一刻是不是「车主走了」。
     *
     * @param parked          车停着（和录像那边同一条规则）
     * @param seatBefore      上一次主驾座椅有人没有；{@code null} 读不到
     * @param seatNow         这一次
     * @param screenLitBefore 上一次屏幕亮不亮；{@code null} 读不到
     * @param screenLitNow    这一次
     */
    public static Leaving leaving(boolean parked, Boolean seatBefore, Boolean seatNow,
                                  Boolean screenLitBefore, Boolean screenLitNow) {
        if (!parked) {
            return Leaving.NOT_PARKED;
        }
        boolean seatEmptied = Boolean.TRUE.equals(seatBefore) && Boolean.FALSE.equals(seatNow);
        boolean screenWentBlack = Boolean.TRUE.equals(screenLitBefore)
                && Boolean.FALSE.equals(screenLitNow);
        if (!seatEmptied && !screenWentBlack) {
            return Leaving.NO_EDGE;
        }
        if (Boolean.TRUE.equals(seatNow)) {
            return Leaving.STILL_SEATED;
        }
        if (seatEmptied) {
            return Leaving.DEPARTURE;
        }
        return Boolean.FALSE.equals(seatNow) ? Leaving.DEPARTURE : Leaving.SEAT_UNKNOWN;
    }
}
