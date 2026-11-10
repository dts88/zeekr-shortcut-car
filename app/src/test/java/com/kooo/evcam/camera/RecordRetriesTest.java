/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link RecordRetries}：录像输出被会话带不上之后什么时候再试（相机管家 3c 第二步 2b；时机照旧：隔 12 秒，连着 3 次就等
 * 相机走了回来、登记表变了或者亮屏）。时间和次数照批过的写死，改了规则这里跟着改。
 */
public class RecordRetriesTest {

    private long now = 1000L;
    private final List<long[]> pending = new ArrayList<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> due = new ArrayList<>();
    private final RecordRetries retries = new RecordRetries(due::add,
            (delayMs, task) -> {
                pending.add(new long[]{now + delayMs});
                tasks.add(task);
            },
            () -> now);

    /** 拨到这一刻：到点的都跑一遍。 */
    private void advanceTo(long time) {
        now = time;
        for (int i = 0; i < tasks.size(); i++) {
            if (pending.get(i)[0] <= time && tasks.get(i) != null) {
                Runnable task = tasks.get(i);
                tasks.set(i, null);
                task.run();
            }
        }
    }

    @Test
    public void theRuleRetriesTwiceAfterTwelveSecondsThenWaits() {
        assertEquals(12000L, ChannelRules.recordRetryAfter(1));
        assertEquals(12000L, ChannelRules.recordRetryAfter(2));
        assertEquals(0L, ChannelRules.recordRetryAfter(3));
        assertEquals(0L, ChannelRules.recordRetryAfter(4));
    }

    @Test
    public void aRefusedLaneWaitsTwelveSecondsThenIsDue() {
        assertEquals(1, retries.refuse("surround"));
        assertTrue(retries.refused("surround"));
        assertFalse(retries.refusedUntilGone("surround"));
        assertFalse(retries.refused("cabin2"));

        advanceTo(1000L + 11999L);
        assertTrue(retries.refused("surround"));
        assertEquals(Collections.emptyList(), due);

        advanceTo(1000L + 12000L);
        assertFalse(retries.refused("surround"));
        assertEquals(Collections.singletonList("surround"), due);
    }

    @Test
    public void theThirdRefusalWaitsForTheCameraAndSetsNoTimer() {
        retries.refuse("surround");
        advanceTo(13000L);
        retries.refuse("surround");
        advanceTo(25000L);
        assertEquals(Arrays.asList("surround", "surround"), due);

        assertEquals(3, retries.refuse("surround"));
        assertTrue(retries.refusedUntilGone("surround"));
        assertTrue(retries.refused("surround"));
        assertEquals(2, tasks.size());
        advanceTo(1_000_000L);
        assertTrue(retries.refused("surround"));
        assertEquals(2, due.size());
    }

    @Test
    public void joiningClearsTheCount() {
        retries.refuse("surround");
        retries.refuse("surround");
        retries.joined("surround");
        assertFalse(retries.refused("surround"));
        assertEquals(1, retries.refuse("surround"));
    }

    @Test
    public void theCameraGoingAwayClearsAnyRecord() {
        assertFalse(retries.cameraGone("surround"));
        retries.refuse("surround");
        assertTrue(retries.cameraGone("surround"));
        assertFalse(retries.refused("surround"));
        assertFalse(retries.cameraGone("surround"));
    }

    @Test
    public void liftingFreesOnlyTheLanesThatWait() {
        retries.refuse("surround");
        retries.refuse("surround");
        retries.refuse("surround");
        retries.refuse("cabin2");

        assertEquals(Collections.emptyList(), retries.lift(Collections.singleton("cabin2")));
        assertTrue(retries.refused("cabin2"));
        assertEquals(Collections.emptyList(), retries.lift(Collections.singleton("cabin1")));
        assertTrue(retries.refusedUntilGone("surround"));

        assertEquals(Collections.singletonList("surround"), retries.lift(null));
        assertFalse(retries.refused("surround"));
        assertTrue(retries.refused("cabin2"));
        assertEquals(1, retries.refuse("surround"));
    }

    @Test
    public void clearingDropsTheRecordsAndCancelsPendingRetries() {
        retries.refuse("surround");
        retries.clear();
        assertFalse(retries.refused("surround"));
        advanceTo(1_000_000L);
        assertEquals(Collections.emptyList(), due);
        assertEquals(1, retries.refuse("surround"));
    }
}
