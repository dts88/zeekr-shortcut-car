/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.AlertAnchor;

/**
 * 最近几条警报的时刻（{@link AlertAnchor}）：留几条、丢哪条、按时间怎么找回来。
 *
 * The last few alert moments ({@link AlertAnchor}): how many are kept, which one is dropped,
 * and how a moment is found back by time.
 */
public class AlertAnchorTest {

    private static byte[] hd(int marker) {
        return new byte[]{(byte) marker};
    }

    @Test
    public void keepsFourAndDropsTheOldest() {
        AlertAnchor anchor = new AlertAnchor();
        // 相隔十秒，远过两秒的容差，所以找回来的一定是它自己那一条。
        // Ten seconds apart, well past the two-second slack, so a lookup can only find its own entry.
        for (int i = 0; i < 6; i++) {
            anchor.add(10_000L * i, hd(i), "caption " + i);
        }
        assertEquals("最旧的先走 / the oldest go first",
                50_000L, anchor.newest().wallMs);
        assertEquals("caption 5", anchor.newest().caption);
        assertNull("0 和 10 s 已经被挤掉 / 0 and 10 s were pushed out",
                anchor.at(0L));
        assertNull(anchor.at(10_000L));
        assertNotNull("20 s 还在 / 20 s is still here", anchor.at(20_000L));
        assertNotNull(anchor.at(50_000L));
    }

    @Test
    public void emptyAnchorHasNoNewest() {
        assertNull(new AlertAnchor().newest());
    }

    @Test
    public void findsAnExactMomentAndOneWithinTwoSeconds() {
        AlertAnchor anchor = new AlertAnchor();
        anchor.add(10_000L, hd(1), "first");
        anchor.add(20_000L, hd(2), "second");

        assertEquals("first", anchor.at(10_000L).caption);
        assertEquals("差两秒也算同一条 / two seconds off is the same moment",
                "first", anchor.at(12_000L).caption);
        assertEquals("往前两秒也算 / two seconds before also matches",
                "first", anchor.at(8_000L).caption);
        assertEquals("最近的优先 / the nearest wins",
                "second", anchor.at(19_000L).caption);
    }

    @Test
    public void aMomentOutsideTheSlackIsAMiss() {
        AlertAnchor anchor = new AlertAnchor();
        anchor.add(10_000L, hd(1), "first");
        assertNull("超过两秒就不认 / more than two seconds is a miss", anchor.at(12_001L));
        assertNull(anchor.at(7_999L));
        assertNull("完全没发过 / never sent", anchor.at(500_000L));
    }

    @Test
    public void clearDropsEverything() {
        AlertAnchor anchor = new AlertAnchor();
        anchor.add(10_000L, hd(1), "first");
        anchor.add(20_000L, hd(2), "second");
        anchor.clear();
        assertNull(anchor.newest());
        assertNull(anchor.at(10_000L));
        assertNull(anchor.at(20_000L));
    }

    @Test
    public void thePictureAndTheMomentComeBackAsTheyWentIn() {
        AlertAnchor anchor = new AlertAnchor();
        byte[] jpeg = hd(7);
        anchor.add(10_000L, jpeg, "18:16");
        AlertAnchor.Entry entry = anchor.at(10_000L);
        assertNotNull(entry);
        assertEquals(10_000L, entry.wallMs);
        assertEquals("18:16", entry.caption);
        assertSame("图照原样拿回来 / the picture comes back as handed over",
                jpeg, entry.hd);
    }
}
