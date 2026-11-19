/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import static org.junit.Assert.assertEquals;

import com.kooo.evcam.drive.HillDescentRules.Decision;

import org.junit.Test;

/** 每次上车、陡坡缓降能用了就把它打开的规矩（{@link HillDescentRules}）。 */
public class HillDescentRulesTest {

    @Test
    public void writesOnceItHasBeenAvailableTwiceInARow() {
        assertEquals(Decision.WAIT, HillDescentRules.decide(true, 0, 0, 0));
        assertEquals(Decision.WAIT, HillDescentRules.decide(true, OffOn.CONFIRM - 1, 0, 0));
        assertEquals(Decision.WRITE, HillDescentRules.decide(true, OffOn.CONFIRM, 0, 0));
        assertEquals(Decision.WRITE, HillDescentRules.decide(true, 60, 0, 0));
    }

    @Test
    public void doesNothingOnceThisDriveIsUsedUp() {
        assertEquals(Decision.WAIT, HillDescentRules.decide(false, 60, 0, 0));
        assertEquals(Decision.WAIT, HillDescentRules.decide(false, 60, HillDescentRules.MAX_WRITES, 0));
    }

    @Test
    public void retriesAFailedWriteAfterAGapUpToTheLimit() {
        assertEquals(Decision.WAIT, HillDescentRules.decide(true, 5, 1, HillDescentRules.RETRY_MS - 1));
        assertEquals(Decision.WRITE, HillDescentRules.decide(true, 5, 1, HillDescentRules.RETRY_MS));
        assertEquals(Decision.WRITE,
                HillDescentRules.decide(true, 5, HillDescentRules.MAX_WRITES - 1, HillDescentRules.RETRY_MS));
        assertEquals(Decision.FAILED,
                HillDescentRules.decide(true, 5, HillDescentRules.MAX_WRITES, HillDescentRules.RETRY_MS));
        // 这一会儿又用不了：不写，等它能用
        assertEquals(Decision.WAIT, HillDescentRules.decide(true, 0, HillDescentRules.MAX_WRITES, HillDescentRules.RETRY_MS));
    }
}
