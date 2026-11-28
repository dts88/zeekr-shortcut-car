/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 只在 Wi-Fi 下下载（{@link WifiGate}）：看出网地址的前两段；记过的照记的算，没见过的要问一次。
 * 地址都用文档专用的段（192.0.2.x、198.51.100.x、203.0.113.x、2001:db8::），不是谁的真网络。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WifiGateTest {

    @Test
    public void keepsOnlyTheFirstTwoParts() {
        assertEquals("203.0", WifiGate.prefixOf("fl=1\nh=zeekr.autos\nip=203.0.113.7\nts=1\n"));
        assertEquals("2001:db8", WifiGate.prefixOf("ip=2001:db8:2077:1e60::12\n"));
        assertEquals("2001:db8", WifiGate.prefixOf("ip=2001:db8::1\n"));
        assertNull(WifiGate.prefixOf("ip=300.1.1.1\n"));
        assertNull(WifiGate.prefixOf("ip=hello\n"));
        assertNull(WifiGate.prefixOf("h=zeekr.autos\n"));
        assertNull(WifiGate.prefixOf(null));
    }

    @Test
    public void decides() {
        Set<String> mobile = new HashSet<>(Arrays.asList("198.51"));
        Set<String> none = Collections.emptySet();
        assertEquals(WifiGate.Kind.MOBILE, WifiGate.decide("198.51", mobile, none));
        assertEquals("没见过的不当 Wi-Fi，要问", WifiGate.Kind.UNCONFIRMED, WifiGate.decide("203.0", mobile, none));
        assertEquals(WifiGate.Kind.UNKNOWN, WifiGate.decide(null, mobile, none));
        assertEquals("记过是 Wi-Fi 的就是 Wi-Fi", WifiGate.Kind.WIFI,
                WifiGate.decide("198.51", mobile, new HashSet<>(Arrays.asList("198.51"))));
    }

    @Test
    public void answersAreRemembered() {
        WifiGate gate = new WifiGate(RuntimeEnvironment.getApplication());
        assertEquals(WifiGate.Kind.UNCONFIRMED, gate.judge("192.0").kind);
        gate.teach("192.0", false);
        assertEquals(WifiGate.Kind.MOBILE, gate.judge("192.0").kind);
        gate.teach("192.0", true);
        assertEquals(WifiGate.Kind.WIFI, gate.judge("192.0").kind);
        assertEquals("记在设置里", WifiGate.Kind.WIFI, new WifiGate(RuntimeEnvironment.getApplication()).judge("192.0").kind);
        assertEquals("别的网络照样要问", WifiGate.Kind.UNCONFIRMED, gate.judge("198.51").kind);
        assertEquals(WifiGate.Kind.UNKNOWN, gate.judge(null).kind);
        gate.teach(null, true);
    }
}
