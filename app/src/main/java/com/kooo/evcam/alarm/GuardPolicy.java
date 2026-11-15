/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 忘了开哨兵：车停在外面、车主走了、哨兵没开 —— 该不该提醒、该不该自己开 — 纯判断（{@code GuardPolicyTest}）。
 *
 * <h3>为什么是「车主离开的那一刻」</h3>
 *
 * <p>哨兵没开时车机在车主离车四到六分钟后下电，进程再活三到五秒（{@code docs/zeekr-platform-notes.md} §「车主离车之后」）。
 * 那就是按钮的全部寿命：晚一点发出去的消息没人按得到。所以判断挂在离开的那一刻，而不是「停了十分钟还没开」——
 * 十分钟后连发消息的进程都不在了。</p>
 *
 * <h3>为什么三个时间都比实测的窗口短</h3>
 *
 * <p>实测四到六分钟，这里按三分钟收手：宁可早一点说「来不及了」，也不要按钮看着能按、按下去没反应。
 * 问出去 60 秒没人否决就自己开 —— 等得越久越容易把这一趟车丢掉。</p>
 *
 * <h3>This is the whole decision</h3>
 *
 * <p>The head unit is gone four to six minutes after the owner leaves, so the reminder is tied to the
 * leaving moment and every deadline is shorter than the measured window: asking is worth nothing if
 * the car is asleep by the time the answer arrives.</p>
 */
public final class GuardPolicy {

    /** 提醒要做什么。What the reminder does. */
    public enum Mode {
        /** 关：什么都不做。Off: nothing happens. */
        OFF,
        /** 只提醒：按钮说开才开。Ask: the button is the only way to switch it on. */
        ASK,
        /** 提醒，60 秒没人否决就自己开。Ask, then switch it on unless the owner says no. */
        ASK_THEN_ON,
        /** 不问，直接开，发一条收据。Switch it on and report; no question. */
        ON;

        /** 存的是 0..3；认不出来的按关算 —— 存坏的设置不该让车自己动。Stored 0..3; anything else is OFF. */
        public static Mode of(int value) {
            switch (value) {
                case 1:
                    return ASK;
                case 2:
                    return ASK_THEN_ON;
                case 3:
                    return ON;
                default:
                    return OFF;
            }
        }

        public int stored() {
            return ordinal();
        }
    }

    /** 这一次该做什么。What this leaving moment calls for. */
    public enum Verdict {
        /** 不动。Do nothing. */
        IGNORE,
        /** 发一条提醒，等按钮。Send the ask and wait for a button. */
        ASK,
        /** 直接开。Switch it on now. */
        ACT
    }

    /** 离开之后多久发提醒：让关门那一下过去，不要拍在车主脸上。 */
    public static final long ASK_AT_MS = 5_000L;

    /** 问出去多久没人否决就自己开。短，是因为车机等不起。 */
    public static final long AUTO_ON_MS = 60_000L;

    /** 离开之后按钮还算数的时长。实测四到六分钟，这里按三分钟 —— 保守的一侧。 */
    public static final long ACT_WINDOW_MS = 180_000L;

    /** 默认关：没打开这一项的车上，行为和以前一模一样。 */
    public static final Mode DEFAULT_MODE = Mode.OFF;

    private GuardPolicy() {
    }

    /**
     * 这一刻该做什么。
     *
     * @param mode       设置里的那一档
     * @param sentry     哨兵模式的读数；{@code null} 表示读不到 —— 读不到就不许写
     * @param parked     车停着（和录像那边同一条规则）
     * @param afterDrive 上一次停车之后真的开过车（不然「停在车库没动过」也会提醒）
     * @param where      离家远不远（{@link GuardHome}）；{@code UNKNOWN} 照样可以问
     * @param anywhere   车主选了「在哪都开」：在家也算数（{@link #maySwitchOnItself}）
     * @param leftAtMs   车主离开那一刻（{@code elapsedRealtime}）；0 表示不知道
     * @param askedAtMs  这一趟停车已经问过的时刻；0 表示没问过
     * @param nowMs      现在（同一把时钟）
     */
    public static Verdict decide(Mode mode, Integer sentry, boolean parked, boolean afterDrive,
                                 GuardHome.Where where, boolean anywhere,
                                 long leftAtMs, long askedAtMs, long nowMs) {
        if (mode == null || mode == Mode.OFF) {
            return Verdict.IGNORE;
        }
        // 读不到哨兵就不动手：这一项是「替车主打开它」，猜一个值去写是最坏的错
        if (sentry == null) {
            return Verdict.IGNORE;
        }
        // 只补「关着」这一种：1 = 开着还没布防，2 = 已布防，两种都在守；别的值（255 之类）不认识，不碰
        if (sentry != 0) {
            return Verdict.IGNORE;
        }
        if (!parked || !afterDrive) {
            return Verdict.IGNORE;
        }
        // 在家不开：车库里天天提醒一次，这一项活不过一周 —— 除非车主自己选了「在哪都开」
        // Home is left alone: a reminder every evening in the driveway would not survive a week —
        // unless the owner asked for "everywhere" themselves.
        if (where == GuardHome.Where.HOME && !anywhere) {
            return Verdict.IGNORE;
        }
        if (leftAtMs <= 0) {
            return Verdict.IGNORE;
        }
        // 这一趟已经问过：问一次就够，按钮和超时都挂在同一个问题上
        if (askedAtMs > 0 && askedAtMs >= leftAtMs) {
            return Verdict.IGNORE;
        }
        if (!stillActionable(leftAtMs, nowMs)) {
            return Verdict.IGNORE;
        }
        return mode == Mode.ON ? Verdict.ACT : Verdict.ASK;
    }

    /** 现在按按钮还来不来得及（按离开的时刻算）。Whether a button pressed now can still be honoured. */
    public static boolean stillActionable(long leftAtMs, long nowMs) {
        if (leftAtMs <= 0) {
            return false;
        }
        long age = nowMs - leftAtMs;
        return age >= 0 && age <= ACT_WINDOW_MS;
    }

    /** 问出去满 60 秒了没有。Whether the auto-on delay has passed. */
    public static boolean autoOnDue(long askedAtMs, long nowMs) {
        return askedAtMs > 0 && nowMs - askedAtMs >= AUTO_ON_MS;
    }

    /**
     * 没问过就可以自己开吗：知道在外面，或者干脆没设过家，或者车主选了「在哪都开」。
     *
     * <p>设了家、定位却说「不知道」时不自己开 —— 那可能就在车库里，而车主没在看手机。
     * 「在哪都开」是车主自己把这一条放开的：在家也开，代价是每次停进车库哨兵也跟着开，
     * 就寝中的车机被它顶着不睡。默认关。</p>
     *
     * <p>Whether switching on unasked is allowed: away, no home configured at all, or the owner asked
     * for everywhere. A configured home plus an unknown fix does not qualify — that may well be the
     * garage. "Everywhere" is the owner lifting that rule: sentry also comes on in their own driveway,
     * and the cost is that the head unit stays awake there too. Off by default.</p>
     */
    public static boolean maySwitchOnItself(GuardHome.Where where, boolean homeConfigured,
                                            boolean anywhere) {
        if (anywhere) {
            return true;
        }
        if (where == GuardHome.Where.HOME) {
            return false;
        }
        return where == GuardHome.Where.AWAY || !homeConfigured;
    }
}
