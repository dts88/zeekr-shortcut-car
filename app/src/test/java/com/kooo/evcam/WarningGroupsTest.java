/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 警告落盘时同一种的合起来（{@link WarningGroups}）。 */
public class WarningGroupsTest {

    private static final String TAG = "StallWatch";

    /** 录像时每 5.5 秒一条的写盘同步慢：一分钟只存一条，下一条后面补一句省掉了几条、中位、最大。 */
    @Test
    public void slowSyncEveryFewSecondsIsSavedOncePerMinute() {
        WarningGroups groups = new WarningGroups();
        assertEquals("第一条照存，没什么要补", "", groups.offer(TAG, "slow sync on camera 2: 785ms", 0));
        long[] took = {512, 576, 498, 1278, 610, 640, 701, 530, 588, 1089};
        for (int i = 0; i < took.length; i++) {
            assertNull("一分钟内同一种不存", groups.offer(TAG, "slow sync on camera 2: " + took[i] + "ms", 5_500L * (i + 1)));
        }
        assertEquals("    (10 similar messages in the minute before this one were not saved; median 588ms, max 1278ms)\n",
                groups.offer(TAG, "slow sync on camera 2: 700ms", WarningGroups.GAP_MS));
        assertNull(groups.offer(TAG, "slow sync on camera 2: 650ms", WarningGroups.GAP_MS + 1_000));
    }

    /** 一位数的相机号不当成数：相机 0 和相机 2 分开算；不同的 tag、不同的话也分开。 */
    @Test
    public void differentCamerasTagsAndSentencesAreDifferentKinds() {
        WarningGroups groups = new WarningGroups();
        assertEquals("", groups.offer(TAG, "slow sync on camera 2: 785ms", 0));
        assertEquals("", groups.offer(TAG, "slow sync on camera 0: 785ms", 1));
        assertEquals("", groups.offer("Other", "slow sync on camera 2: 785ms", 2));
        assertEquals("", groups.offer(TAG, "thread Writer-2 has not run a queued task for 1076ms", 3));
        assertNotEquals(WarningGroups.key(TAG, "slow sync on camera 2: 1ms"),
                WarningGroups.key(TAG, "slow sync on camera 0: 1ms"));
        assertEquals(WarningGroups.key(TAG, "slow sync on camera 2: 785ms"),
                WarningGroups.key(TAG, "slow sync on camera 2: 1278ms"));
    }

    /** 没有两位以上数字的那种和以前一样：完全一样的才合，补的那一句不带中位、最大。 */
    @Test
    public void aSentenceWithoutNumbersOnlyCountsWhatWasLeftOut() {
        WarningGroups groups = new WarningGroups();
        assertEquals("", groups.offer("App", "onTrimMemory level 5", 0));
        assertNull(groups.offer("App", "onTrimMemory level 5", 10_000));
        assertEquals("    (1 similar message in the minute before this one was not saved)\n",
                groups.offer("App", "onTrimMemory level 5", 70_000));
    }

    /** 数后面的单位跟着写；一种里只有一条被省掉时中位、最大就是它。 */
    @Test
    public void theUnitAfterTheNumberIsKept() {
        WarningGroups groups = new WarningGroups();
        assertEquals("", groups.offer(TAG, "thread Writer-2 has not run a queued task for 1076ms", 0));
        assertNull(groups.offer(TAG, "thread Writer-2 has not run a queued task for 2010ms", 30_000));
        assertEquals("    (1 similar message in the minute before this one was not saved; median 2010ms, max 2010ms)\n",
                groups.offer(TAG, "thread Writer-2 has not run a queued task for 1500ms", WarningGroups.GAP_MS));
    }
}
