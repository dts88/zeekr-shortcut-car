/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/**
 * 灵动贴什么时候开扫描、电量怎么取（{@link SmartButtons#waitToSettle}、{@link SmartButtons#startWait}、
 * {@link SmartButtons.BatteryWindow}、{@link SmartButtons.PhoneLink}、{@link ScanProbe#aboutBluetooth}）。
 */
public class SmartButtonsListenTest {

    private static final long START = 1_000_000L;

    /** 车主 2026-11-01：车机醒了等 10 秒，再向系统要蓝牙服务、开始扫描。 */
    @Test
    public void aWakeWaitsTenSeconds() {
        assertEquals(10_000L, SmartButtons.SETTLE_MS);
        long settled = START + SmartButtons.SETTLE_MS;
        assertTrue(SmartButtons.waitToSettle(START, settled, false));
        assertTrue(SmartButtons.waitToSettle(settled - 1, settled, false));
        assertFalse(SmartButtons.waitToSettle(settled, settled, false));
    }

    /** 人就在设置页上绑定：不等。 */
    @Test
    public void bindingDoesNotWait() {
        assertFalse(SmartButtons.waitToSettle(START, START + SmartButtons.SETTLE_MS, true));
    }

    /** 没睡过就不用等：进程刚起来。 */
    @Test
    public void withoutAWakeThereIsNoWait() {
        assertFalse(SmartButtons.waitToSettle(START, 0, false));
    }

    /** 安卓 12 一个 uid 30 秒里开扫描超过 5 次会被悄悄拒，容器里别的应用也算：我们 30 秒最多开 3 次。 */
    @Test
    public void atMostThreeStartsInThirtySeconds() {
        assertEquals(3, SmartButtons.MAX_STARTS);
        assertEquals(30_000L, SmartButtons.STARTS_WINDOW_MS);
        assertEquals(0, SmartButtons.startWait(Collections.emptyList(), START));
        assertEquals(0, SmartButtons.startWait(Arrays.asList(START - 2_000, START - 1_000), START));
        assertEquals(28_000L, SmartButtons.startWait(Arrays.asList(START - 2_000, START - 1_500, START - 1_000), START));
    }

    /** 早过了这一阵的那几次不算：等到最早那一次过 30 秒就能开。 */
    @Test
    public void oldStartsDoNotCount() {
        assertEquals(0, SmartButtons.startWait(Arrays.asList(START - 31_000, START - 2_000, START - 1_000), START));
        assertEquals(1, SmartButtons.startWait(Arrays.asList(START - 29_999, START - 2_000, START - 1_000), START));
    }

    /** 连着时按键那一下电量会掉到 0 / 25（Lab 3.5.3 车上）：取 10 秒里最高的；过了 10 秒的不算。 */
    @Test
    public void batteryIsTheHighestOfTheLastTenSeconds() {
        SmartButtons.BatteryWindow window = new SmartButtons.BatteryWindow();
        assertEquals(50, window.add(START, 50));
        assertEquals(50, window.add(START + 1_000, 0));
        assertEquals(50, window.add(START + 2_000, 25));
        assertEquals(50, window.add(START + 10_000, 25));
        assertEquals(25, window.add(START + 10_001, 25));
    }

    private static final String PHONE = "00:11:22:33:44:55";
    private static final String OTHER = "AA:BB:CC:DD:EE:FF";
    private static final int BOTH = SmartButtons.LINK_CALLS | SmartButtons.LINK_AUDIO;

    /** 手机跟车机的蓝牙：上次连着的电话、音乐或设备这次没连着才算断开；本来就没连的、刚连上的、读不到的不算。 */
    @Test
    public void phoneLinkDropsAreSeen() {
        assertTrue(dropped(link(BOTH, PHONE), link(0, PHONE)));
        assertTrue(dropped(link(BOTH, PHONE), link(SmartButtons.LINK_CALLS, PHONE)));
        assertTrue(dropped(link(0, PHONE), link(0)));
        assertTrue(dropped(link(BOTH, PHONE, OTHER), link(BOTH, OTHER)));
        assertFalse(dropped(link(BOTH, PHONE), link(BOTH, PHONE)));
        assertFalse(dropped(link(0), link(BOTH, PHONE)));
        assertFalse(dropped(link(SmartButtons.LINK_CALLS, PHONE), link(BOTH, PHONE, OTHER)));
        assertFalse(dropped(link(-1, PHONE), link(0, PHONE)));
        assertFalse(dropped(devicesUnread(0), link(0)));
        assertFalse(dropped(link(BOTH, PHONE), devicesUnread(-1)));
        assertFalse(dropped(null, link(0)));
    }

    @Test
    public void phoneLinkReadsAsWords() {
        assertEquals("calls+audio, connected 44:55", link(BOTH, PHONE).words());
        assertEquals("calls, connected 44:55 EE:FF", link(SmartButtons.LINK_CALLS, OTHER, PHONE).words());
        assertEquals("audio, connected none", link(SmartButtons.LINK_AUDIO).words());
        assertEquals("none, connected none", link(0).words());
        assertEquals("?, connected ?", devicesUnread(-1).words());
    }

    /** 黑匣子只在变了的时候记：一样的连接算相等，设备的先后不算。 */
    @Test
    public void samePhoneLinkIsEqual() {
        assertEquals(link(BOTH, PHONE, OTHER), link(BOTH, OTHER, PHONE));
        assertEquals(link(BOTH, PHONE, OTHER).hashCode(), link(BOTH, OTHER, PHONE).hashCode());
        assertNotEquals(link(BOTH, PHONE), link(0, PHONE));
        assertNotEquals(link(0), devicesUnread(0));
        assertNotEquals(link(0), null);
    }

    private static boolean dropped(SmartButtons.PhoneLink before, SmartButtons.PhoneLink now) {
        return SmartButtons.PhoneLink.dropped(before, now);
    }

    private static SmartButtons.PhoneLink link(int profiles, String... devices) {
        return new SmartButtons.PhoneLink(profiles, new HashSet<>(Arrays.asList(devices)));
    }

    private static SmartButtons.PhoneLink devicesUnread(int profiles) {
        return new SmartButtons.PhoneLink(profiles, null);
    }

    @Test
    public void keepsLinesAboutBluetoothOnly() {
        assertTrue(ScanProbe.aboutBluetooth(
                "10-31 13:58:06.000 E/BluetoothLeScanner(24373): application registeration exception"));
        assertTrue(ScanProbe.aboutBluetooth(
                "10-31 13:58:06.000 D/falco_32(24373): IBluetoothManager.getBluetoothGatt -> null"));
        assertFalse(ScanProbe.aboutBluetooth(
                "10-31 13:58:06.000 W/StallWatch(24373): slow sync on camera 2: 497ms"));
        assertFalse(ScanProbe.aboutBluetooth(
                "11-01 10:25:59.226 D/BluetoothLeScanner(24300): onScanResult() - ScanResult{device=64:19:CE:6A:AC:57}"));
    }
}
