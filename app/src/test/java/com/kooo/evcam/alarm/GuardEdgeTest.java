/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardEdge;
import com.kooo.evcam.alarm.guard.GuardEdge.Leaving;

/**
 * 「车主走了」那一条边（{@link GuardEdge}）：主驾座椅有人 → 没人算离开；屏幕亮 → 黑只在座椅已经空着时算；
 * 车里还坐着人时什么都不算 —— 在车里按锁车，屏幕一样会黑。
 *
 * The leaving edge ({@link GuardEdge}): the driver seat emptying is a departure; the screen going black
 * only counts once the seat is empty; and nothing counts while someone is still sitting in the driver
 * seat, because locking from inside blacks the screen the same way.
 */
public class GuardEdgeTest {

    private static final Boolean LIT = Boolean.TRUE;
    private static final Boolean DARK = Boolean.FALSE;
    private static final Boolean SITTING = Boolean.TRUE;
    private static final Boolean EMPTY = Boolean.FALSE;

    @Test
    public void theDriverSeatEmptyingIsADeparture() {
        assertEquals(Leaving.DEPARTURE,
                GuardEdge.leaving(true, SITTING, EMPTY, LIT, LIT));
        assertEquals("屏幕已经黑了，座椅才空：一样是走了",
                Leaving.DEPARTURE, GuardEdge.leaving(true, SITTING, EMPTY, DARK, DARK));
    }

    @Test
    public void theScreenGoingBlackCountsOnceTheSeatIsAlreadyEmpty() {
        assertEquals("车主先起身、屏幕跟着锁车黑掉", Leaving.DEPARTURE,
                GuardEdge.leaving(true, EMPTY, EMPTY, LIT, DARK));
    }

    @Test
    public void nobodyLeavesWhileSomeoneStillSitsInTheDriverSeat() {
        assertEquals("在车里按锁车：屏幕同样会黑", Leaving.STILL_SEATED,
                GuardEdge.leaving(true, SITTING, SITTING, LIT, DARK));
        assertEquals(Leaving.STILL_SEATED, GuardEdge.leaving(true, EMPTY, SITTING, LIT, DARK));
    }

    @Test
    public void aBlackScreenWithAnUnreadableSeatDecidesNothing() {
        assertEquals(Leaving.SEAT_UNKNOWN, GuardEdge.leaving(true, null, null, LIT, DARK));
        assertEquals(Leaving.SEAT_UNKNOWN, GuardEdge.leaving(true, SITTING, null, LIT, DARK));
    }

    @Test
    public void noEdgeAndStillDrivingAreNotDepartures() {
        assertEquals("两条边都没动", Leaving.NO_EDGE,
                GuardEdge.leaving(true, EMPTY, EMPTY, DARK, DARK));
        assertEquals("开着车：屏幕和座椅怎么变都不是下车", Leaving.NOT_PARKED,
                GuardEdge.leaving(false, SITTING, EMPTY, LIT, DARK));
        assertEquals("屏幕通电那条边是黑 → 亮，不是离开", Leaving.NO_EDGE,
                GuardEdge.leaving(true, EMPTY, EMPTY, DARK, LIT));
    }
}
