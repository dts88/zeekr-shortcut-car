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
        assertFalse(u.running());
        assertEquals(TelemetryUsers.Change.START_CORE, u.keepCore());
        assertTrue(u.running());
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
        assertTrue("核心还在：离散信号照读", u.running());
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
        assertTrue(u.running());
        assertEquals(TelemetryUsers.Change.STOP, u.release("vehicle-info"));
        assertFalse(u.running());
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
        assertTrue(u.running());
    }
}
