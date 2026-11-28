/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 「分享到 zeekr.autos」（{@link SiteShare}）：请求体只有这串码；网站的几种回话怎么读；分享过的记在本机。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SiteShareTest {

    @Test
    public void sendsOnlyTheCode() {
        assertEquals("{\"code\":\"1A1G0C580963\"}", SiteShare.body("1A1G0C580963"));
        assertEquals("只发到 zeekr.autos 的分享接口", "https://zeekr.autos/api/share", SiteShare.URL);
        assertEquals("固定的应用名，不带型号和版本", "ZeekrShortcut", SiteShare.USER_AGENT);
        for (String bad : new String[]{null, "", "1a1g", "1A1G 0C58", "1A\"}", "1A1G0C580963\n"}) {
            try {
                SiteShare.body(bad);
                fail("不是码的不发：" + bad);
            } catch (IllegalArgumentException expected) {
                // 对
            }
        }
    }

    @Test
    public void readsTheAnswers() {
        SiteShare.Result ok = SiteShare.read(200, "{\"code\":\"1A1G0C580963\",\"count\":7}");
        assertEquals(SiteShare.Outcome.SHARED, ok.outcome);
        assertEquals(7, ok.count);
        assertEquals(SiteShare.Outcome.TOO_OFTEN, SiteShare.read(429, "").outcome);
        assertEquals(SiteShare.Outcome.REFUSED, SiteShare.read(400, "{\"error\":\"checksum\"}").outcome);
        assertEquals(SiteShare.Outcome.FAILED, SiteShare.read(403, "{\"error\":\"origin\"}").outcome);
        assertEquals(SiteShare.Outcome.FAILED, SiteShare.read(500, "").outcome);
        assertEquals("看不懂的 200 也不算成", SiteShare.Outcome.FAILED, SiteShare.read(200, "<html>").outcome);
        assertEquals(SiteShare.Outcome.FAILED, SiteShare.read(200, "{\"code\":\"X\"}").outcome);
    }

    @Test
    public void remembersWhatThisCarShared() {
        assertFalse(SiteShare.sharedBefore(RuntimeEnvironment.getApplication(), "1A1G0C580963"));
        SiteShare.remember(RuntimeEnvironment.getApplication(), "1A1G0C580963");
        assertTrue(SiteShare.sharedBefore(RuntimeEnvironment.getApplication(), "1A1G0C580963"));
        assertFalse(SiteShare.sharedBefore(RuntimeEnvironment.getApplication(), "1EC12567"));
    }
}
