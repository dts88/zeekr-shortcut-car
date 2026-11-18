/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardEdge;
import com.kooo.evcam.alarm.guard.GuardHome;
import com.kooo.evcam.alarm.guard.GuardPolicy;
import com.kooo.evcam.alarm.policy.SentryFacts;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.TestSnapshots;

/**
 * 车上那一趟：警报开着、Telegram 填好了、哨兵关着、开车、停车、屏幕黑、车主走开 —— 车会不会发。
 *
 * <p>走的都是真的那几层：{@link TestSnapshots} 把车上的原始值过 {@link Signal#decode}，屏幕那一条遍用
 * {@link SentryFacts#screenLit}，离开那一刻用 {@link GuardEdge}，该不该发用 {@link GuardPolicy}。
 * 只有网络不在里面 —— 断言的是「会发」这个判断，不是一条 HTTP。</p>
 *
 * <p>两个场景是车主报上来的那两件事：在车里按锁车什么都不该发生（主驾还坐着人）；下车走开该发问。</p>
 *
 * The car sequence: alerts on, Telegram configured, sentry 0, a drive, park, screen black, owner gone —
 * would the car send? These run the real layers: {@link TestSnapshots} pushes raw on-car values through
 * {@link Signal#decode}, the screen fact through {@link SentryFacts#screenLit}, the leaving moment through
 * {@link GuardEdge}, and the answer through {@link GuardPolicy}. Only the network is absent: what is
 * asserted is the decision, not an HTTP call.
 */
public class ForgottenSentrySequenceTest {

    /** 停着没有，用的是生产那条规则（{@link ChannelRules#driving}，和 {@code SentryAlerts.parkedNow} 同一条）。 */
    private static boolean parked(String gear, float speedKmh) {
        return !ChannelRules.driving(gear, speedKmh);
    }

    /** 车上的一帧：屏幕电源事件的原值（0 亮 / 1 黑）+ 主驾座椅的原值（1 空 / 2 有人）。 */
    private static Readings frame(int screenPowerEvent, int seatDriver) {
        return TestSnapshots.of(Signal.SCREEN_POWER_EVENT, screenPowerEvent,
                Signal.SEAT_DRIVER, seatDriver);
    }

    /** 这一帧里车主走了没有（{@link SentryAlerts#watchForForgottenSentry} 用的就是这两条事实）。 */
    private static GuardEdge.Leaving leaving(boolean parked, Readings before, Readings now) {
        return GuardEdge.leaving(parked, before.bool(Signal.SEAT_DRIVER),
                now.bool(Signal.SEAT_DRIVER), SentryFacts.screenLit(before),
                SentryFacts.screenLit(now));
    }

    /** 车会不会真的发：先认成离开，再问 {@link GuardPolicy}。Would the car send: departure, then the policy. */
    private static boolean sends(GuardEdge.Leaving leaving, GuardPolicy.Mode mode, Integer sentry,
                                 boolean parked, boolean afterDrive, GuardHome.Where where,
                                 long leftAtMs, long askedAtMs, long nowMs) {
        if (leaving != GuardEdge.Leaving.DEPARTURE) {
            return false;
        }
        return GuardPolicy.decide(mode, sentry, parked, afterDrive, where, false,
                leftAtMs, askedAtMs, nowMs) != GuardPolicy.Verdict.IGNORE;
    }

    @Test
    public void lockedFromInsideWithSomeoneInTheSeatSendsNothing() {
        // 车主就在方向盘后面，从里面按了锁车：屏幕黑了，座椅还坐着人。哨兵关着，但这不是离开。
        // The owner is at the wheel and locks from inside: the screen goes black, the seat is still
        // occupied. Sentry is off, but this is not a departure.
        boolean parked = parked("P", 0f);
        Readings sitting = frame(0, 2);
        Readings locked = frame(1, 2);
        assertEquals(GuardEdge.Leaving.STILL_SEATED, leaving(parked, sitting, locked));
        assertFalse("在车里按锁车，不该问", sends(leaving(parked, sitting, locked),
                GuardPolicy.Mode.ASK, 0, parked, true, GuardHome.Where.UNKNOWN, 1_000L, 0L, 1_000L));
        assertFalse(sends(leaving(parked, sitting, locked), GuardPolicy.Mode.ON, 0, parked, true,
                GuardHome.Where.UNKNOWN, 1_000L, 0L, 1_000L));
    }

    @Test
    public void theOwnerWalkingAwayAsksInEveryModeThatPromisesTo() {
        // 真的那一趟：坐着、开着车、停下来、起身（座椅空）、锁车走人（屏幕黑）。哨兵始终是 0。
        // The real trip: sitting, driving, stopping, getting up (seat empty), locking and walking away
        // (screen black). Sentry reads 0 throughout.
        Readings driving = frame(0, 2);
        Readings stopped = frame(0, 2);
        boolean onTheRoad = parked("D", 30f);
        assertFalse("D 挡就是在开车，不是停着", onTheRoad);

        // 「这一趟真的开过车」：开车时 parkedNow 是假，于是 guardDroveSincePark 被置真。
        // "A real drive happened": while driving parkedNow is false, so guardDroveSincePark is set.
        boolean afterDrive = !onTheRoad;

        // 停车（P 挡）：车停着、哨兵 0、主驾还坐着 —— 还没走。
        // Parked (gear P): still, sentry 0, still seated — not gone yet.
        boolean parked = parked("P", 0f);
        assertTrue("P 挡是停着", parked);
        assertEquals(GuardEdge.Leaving.NO_EDGE, leaving(parked, stopped, frame(0, 2)));

        // 起身：座椅有人 → 没人，屏幕还亮着。这一下就是离开。
        // Getting up: seat occupied → empty, screen still lit. That is the departure.
        Readings emptied = frame(0, 1);
        GuardEdge.Leaving leaving = leaving(parked, stopped, emptied);
        assertEquals(GuardEdge.Leaving.DEPARTURE, leaving);
        long leftAt = 10_000L;
        for (GuardPolicy.Mode mode : new GuardPolicy.Mode[] {
                GuardPolicy.Mode.ASK, GuardPolicy.Mode.ASK_THEN_ON, GuardPolicy.Mode.ON}) {
            assertTrue(mode + "：「下车那一刻哨兵是 0」，该发", sends(leaving, mode, 0, parked,
                    afterDrive, GuardHome.Where.UNKNOWN, leftAt, 0L, leftAt));
        }
        assertEquals("先问一句", GuardPolicy.Verdict.ASK, GuardPolicy.decide(GuardPolicy.Mode.ASK,
                0, parked, afterDrive, GuardHome.Where.UNKNOWN, false, leftAt, 0L, leftAt));
        assertEquals("不问就开", GuardPolicy.Verdict.ACT, GuardPolicy.decide(GuardPolicy.Mode.ON,
                0, parked, afterDrive, GuardHome.Where.UNKNOWN, false, leftAt, 0L, leftAt));

        // 再后来屏幕才黑（锁车）：还是离开，但这一趟已经问过，不再问第二次。
        // The screen blacks later (on locking): still a departure, but this park was already asked.
        Readings dark = frame(1, 1);
        assertEquals(GuardEdge.Leaving.DEPARTURE, leaving(parked, emptied, dark));
        assertFalse("一次停车只问一次", sends(leaving(parked, emptied, dark), GuardPolicy.Mode.ASK,
                0, parked, afterDrive, GuardHome.Where.UNKNOWN, leftAt, leftAt, leftAt + 3_000L));
        assertEquals(GuardPolicy.Block.ALREADY_ASKED, GuardPolicy.block(GuardPolicy.Mode.ASK, 0,
                parked, afterDrive, GuardHome.Where.UNKNOWN, false, leftAt, leftAt, leftAt + 3_000L));
    }

    @Test
    public void aBlackScreenWithAnUnreadableSeatSendsNothing() {
        // 屏幕黑了，座椅读数却是 null（总线睡了、或者这一帧没有它）：分不清车里有没有人，不动。
        // The screen is black and the seat reading is null (bus asleep, or absent from this frame):
        // cannot tell, so nothing is sent.
        Readings before = TestSnapshots.of(Signal.SCREEN_POWER_EVENT, 0);
        Readings now = TestSnapshots.of(Signal.SCREEN_POWER_EVENT, 1);
        assertEquals(GuardEdge.Leaving.SEAT_UNKNOWN, leaving(true, before, now));
        assertFalse(sends(GuardEdge.Leaving.SEAT_UNKNOWN, GuardPolicy.Mode.ASK, 0, true, true,
                GuardHome.Where.UNKNOWN, 1_000L, 0L, 1_000L));
    }

    @Test
    public void atHomeWithASentryOffReadsAsTheExistingHomeGate() {
        // 在家、没选「在哪都开」：本来就是「在家不动」，但那要能从黑匣子看出来（GuardPolicy.Block.HOME）。
        // At home without "everywhere" the rule is unchanged, and the black box now says so.
        assertEquals(GuardPolicy.Block.HOME, GuardPolicy.block(GuardPolicy.Mode.ASK, 0, true, true,
                GuardHome.Where.HOME, false, 1_000L, 0L, 1_000L));
        assertEquals(GuardPolicy.Block.SENTRY_UNREADABLE, GuardPolicy.block(GuardPolicy.Mode.ASK,
                null, true, true, GuardHome.Where.UNKNOWN, false, 1_000L, 0L, 1_000L));
        assertEquals(GuardPolicy.Block.NO_DRIVE, GuardPolicy.block(GuardPolicy.Mode.ASK, 0, true,
                false, GuardHome.Where.UNKNOWN, false, 1_000L, 0L, 1_000L));
    }
}
