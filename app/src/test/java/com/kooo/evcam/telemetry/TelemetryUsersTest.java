/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 谁在用车辆信号、来源开到哪一层（2026-10-12）：核心（熄屏判断）一直在，只读离散信号；完整用户另外要浮点和定位。
 *
 * <p>算错的表现：核心一走车辆信号就停 —— 熄屏跟着录像停而读不到，录像来回停开；
 * 或者核心在时 GPS 一直开着、旧的车速和位置画进下一次录像。</p>
 */
public class TelemetryUsersTest {

    @Test
    public void theCoreAloneReadsDiscreteSignalsOnly() {
        TelemetryUsers u = new TelemetryUsers();
        assertFalse(u.core() || u.full());
        assertEquals(TelemetryUsers.Change.START_CORE, u.keepCore());
        assertTrue(u.core());
        assertFalse("核心不读浮点、不开定位", u.full());
        assertEquals("核心只登记一次", TelemetryUsers.Change.NONE, u.keepCore());
    }

    /** 核心在跑：第一个完整用户只是加上浮点和定位（不重连、快照不清），最后一个走了只是撤掉它们。 */
    @Test
    public void fullUsersAddAndDropFloatsOnTopOfTheCore() {
        TelemetryUsers u = new TelemetryUsers();
        u.keepCore();
        assertEquals(TelemetryUsers.Change.FLOATS_ON, u.acquire("recording"));
        assertTrue(u.full());
        assertEquals(TelemetryUsers.Change.NONE, u.acquire("vehicle-info"));
        assertEquals("重复登记无害", TelemetryUsers.Change.NONE, u.acquire("recording"));
        assertEquals(Arrays.asList("recording", "vehicle-info"), u.names());
        assertEquals(TelemetryUsers.Change.NONE, u.release("recording"));
        assertTrue("还有人要浮点", u.full());
        assertEquals(TelemetryUsers.Change.FLOATS_OFF, u.release("vehicle-info"));
        assertFalse(u.full());
        assertTrue("核心还在：离散信号照读", u.core());
        assertEquals(Collections.emptyList(), u.names());
    }

    @Test
    public void releasingSomeoneWhoNeverAcquiredIsHarmless() {
        TelemetryUsers u = new TelemetryUsers();
        u.keepCore();
        assertEquals(TelemetryUsers.Change.NONE, u.release("nobody"));
        u.acquire("recording");
        assertEquals(TelemetryUsers.Change.NONE, u.release("nobody"));
        assertTrue(u.full());
    }

    /** 没有核心时（进程起来之前、测试）规则一样：第一个人把一切拉起来，最后一个人走了全停。 */
    @Test
    public void withoutTheCoreTheLastUserStopsEverything() {
        TelemetryUsers u = new TelemetryUsers();
        assertEquals(TelemetryUsers.Change.START_FULL, u.acquire("vehicle-info"));
        assertTrue(u.full());
        assertEquals(TelemetryUsers.Change.STOP, u.release("vehicle-info"));
        assertFalse(u.full());
        assertFalse(u.core());
    }

    /** 核心晚到（完整用户已经把来源连浮点一起拉起来了）：什么都不用动；之后最后一个完整用户走，只撤浮点。 */
    @Test
    public void aLateCoreJoinsARunningSource() {
        TelemetryUsers u = new TelemetryUsers();
        u.acquire("recording");
        assertEquals(TelemetryUsers.Change.NONE, u.keepCore());
        assertTrue(u.core());
        assertEquals(TelemetryUsers.Change.FLOATS_OFF, u.release("recording"));
        assertFalse(u.full());
        assertTrue("核心还在：来源照跑", u.core());
    }

    // ------------------------------------------------------------------ 停车（2026-11-21：人不在车上不取资源）

    /** 停车：登记着完整用户（在录的信息条）也停掉浮点和定位，他们还登记着；人回来照登记的开回来。 */
    @Test
    public void parkingStopsFloatsAndComingBackResumesThem() {
        TelemetryUsers u = new TelemetryUsers();
        u.keepCore();
        u.acquire("recording");
        assertEquals(TelemetryUsers.Change.FLOATS_OFF, u.park());
        assertTrue(u.parked());
        assertFalse("停车着：不读浮点、不开定位", u.full());
        assertEquals("还登记着", Arrays.asList("recording"), u.names());
        assertEquals("重复叫无害", TelemetryUsers.Change.NONE, u.park());
        assertEquals(TelemetryUsers.Change.FLOATS_ON, u.unpark());
        assertFalse(u.parked());
        assertTrue(u.full());
        assertEquals(TelemetryUsers.Change.NONE, u.unpark());
    }

    /** 没有完整用户时停车、回来：浮点本来就没开，什么都不用动。 */
    @Test
    public void parkingWithNoFullUsersChangesNothing() {
        TelemetryUsers u = new TelemetryUsers();
        u.keepCore();
        assertEquals(TelemetryUsers.Change.NONE, u.park());
        assertEquals(TelemetryUsers.Change.NONE, u.unpark());
        assertFalse(u.full());
    }

    /** 停车着来的、走的完整用户只记账（停车时开录、停录）：浮点一直停着；人回来时还登记着的才开。 */
    @Test
    public void usersComingAndGoingWhileParkedOnlyAreNoted() {
        TelemetryUsers u = new TelemetryUsers();
        u.keepCore();
        u.park();
        assertEquals("停车着登记：先记着", TelemetryUsers.Change.NONE, u.acquire("recording"));
        assertFalse(u.full());
        assertEquals(Arrays.asList("recording"), u.names());
        assertEquals("停车着注销：浮点本来就停着", TelemetryUsers.Change.NONE, u.release("recording"));
        assertEquals("都走了：回来不用开", TelemetryUsers.Change.NONE, u.unpark());

        u.park();
        u.acquire("recording");
        u.acquire("auto-lock");
        u.release("recording");
        assertEquals("还有一个登记着：回来开", TelemetryUsers.Change.FLOATS_ON, u.unpark());
        assertTrue(u.full());
    }

    /** 进程起来时就停着（没有人的证据）：核心照起，之后来的完整用户等人回来。 */
    @Test
    public void aProcessThatStartsParkedKeepsFloatsOffUntilSomeoneIsBack() {
        TelemetryUsers u = new TelemetryUsers();
        assertEquals(TelemetryUsers.Change.START_CORE, u.keepCore());
        assertEquals(TelemetryUsers.Change.NONE, u.park());
        assertEquals(TelemetryUsers.Change.NONE, u.acquire("vehicle-info"));
        assertFalse(u.full());
        assertEquals(TelemetryUsers.Change.FLOATS_ON, u.unpark());
    }

    /** 停车只在核心登记着时算数（进程里核心一起来就登记）：没有核心时照旧，第一个人把一切拉起来、最后一个人走了全停。 */
    @Test
    public void withoutTheCoreParkingChangesNothing() {
        TelemetryUsers u = new TelemetryUsers();
        assertEquals(TelemetryUsers.Change.NONE, u.park());
        assertEquals(TelemetryUsers.Change.START_FULL, u.acquire("vehicle-info"));
        assertTrue(u.full());
        assertEquals(TelemetryUsers.Change.STOP, u.release("vehicle-info"));
        assertEquals(TelemetryUsers.Change.NONE, u.unpark());
    }
}
