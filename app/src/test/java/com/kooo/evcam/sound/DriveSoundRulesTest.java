package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

/**
 * 模拟声浪什么时候出声（项目所有者 2026-10-21：只在车内、只做运动模式；运动模式下一直转着，停着是怠速）。
 *
 * <p>算错的表现：舒适 / 标准模式也响；关了开关、切出运动模式还在响；车速油门读不到时卡在一个声音上；
 * 电话里还在轰；或者没上电就开着喇叭。</p>
 */
public class DriveSoundRulesTest {

    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer SPORT = Signal.DRIVE_MODE_SPORT;

    private static DriveSoundRules.Why decide(boolean enabled, boolean link, Integer ignition, String gear,
                                              Integer mode, float speed, float throttle, long age, boolean call) {
        return DriveSoundRules.decide(enabled, link, ignition, gear, mode, speed, throttle, age, call);
    }

    @Test
    public void soundsInSportWhateverTheGear() {
        for (String gear : new String[]{"P", "R", "N", "D"}) {
            assertEquals(gear, DriveSoundRules.Why.RUNNING, decide(true, true, ON, gear, SPORT, 0f, 0f, 20L, false));
        }
        assertEquals(DriveSoundRules.Why.RUNNING,
                decide(true, true, Signal.IGNITION_DRIVING, "D", SPORT, 80f, 30f, 49L, false));
    }

    @Test
    public void onlySportModeSounds() {
        assertEquals(DriveSoundRules.Why.NOT_SPORT,
                decide(true, true, ON, "D", Signal.DRIVE_MODE_COMFORT, 50f, 30f, 20L, false));
        assertEquals(DriveSoundRules.Why.NOT_SPORT,
                decide(true, true, ON, "D", Signal.DRIVE_MODE_STANDARD, 50f, 30f, 20L, false));
        assertEquals("驾驶模式读不到也不出声", DriveSoundRules.Why.NOT_SPORT,
                decide(true, true, ON, "D", null, 50f, 30f, 20L, false));
    }

    @Test
    public void offNoLinkAndPowerOffComeFirst() {
        assertEquals(DriveSoundRules.Why.OFF, decide(false, true, ON, "D", SPORT, 50f, 30f, 20L, false));
        assertEquals(DriveSoundRules.Why.NO_LINK, decide(true, false, ON, "D", SPORT, 50f, 30f, 20L, false));
        assertEquals(DriveSoundRules.Why.POWER_OFF,
                decide(true, true, Signal.IGNITION_ACC, "P", SPORT, 0f, 0f, 20L, false));
    }

    @Test
    public void staleOrMissingReadingsGoQuiet() {
        assertEquals(DriveSoundRules.Why.STALE, decide(true, true, ON, "D", SPORT, Float.NaN, 30f, 20L, false));
        assertEquals(DriveSoundRules.Why.STALE, decide(true, true, ON, "D", SPORT, 50f, Float.NaN, 20L, false));
        assertEquals(DriveSoundRules.Why.STALE,
                decide(true, true, ON, "D", SPORT, 50f, 30f, DriveSoundRules.STALE_MS + 1, false));
        assertEquals("一次都没读到", DriveSoundRules.Why.STALE, decide(true, true, ON, "D", SPORT, 50f, 30f, -1L, false));
        assertEquals(DriveSoundRules.Why.RUNNING,
                decide(true, true, ON, "D", SPORT, 50f, 30f, DriveSoundRules.STALE_MS, false));
    }

    @Test
    public void callsMuteIt() {
        assertEquals(DriveSoundRules.Why.CALL, decide(true, true, ON, "D", SPORT, 50f, 30f, 20L, true));
    }

    @Test
    public void whatEachVerdictMeans() {
        assertFalse(DriveSoundRules.Why.OFF.armed);
        assertFalse("不是运动模式不快读，不让车机服务白读", DriveSoundRules.Why.NOT_SPORT.armed);
        assertFalse(DriveSoundRules.Why.NOT_SPORT.open);
        assertTrue("运动模式下读数过期也照样快读，等它回来", DriveSoundRules.Why.STALE.armed);
        assertTrue("读数过期、通话：发动机还转着，只是静音", DriveSoundRules.Why.STALE.open
                && DriveSoundRules.Why.CALL.open);
        assertFalse(DriveSoundRules.Why.STALE.audible || DriveSoundRules.Why.CALL.audible);
        assertTrue(DriveSoundRules.Why.RUNNING.audible);
        assertEquals("切出运动模式 0.6 秒淡出", 0.6f, DriveSoundRules.Why.NOT_SPORT.fadeSec, 0f);
        for (DriveSoundRules.Why why : DriveSoundRules.Why.values()) {
            assertTrue(why.fadeSec > 0f);
            assertTrue("响就一定转着、转着就一定在快读", (!why.audible || why.open) && (!why.open || why.armed));
        }
    }

    @Test
    public void ignitionUnknownFallsBackToTheGear() {
        assertTrue(DriveSoundRules.poweredOn(null, "D"));
        assertTrue(DriveSoundRules.poweredOn(null, "R"));
        assertFalse(DriveSoundRules.poweredOn(null, "P"));
        assertFalse(DriveSoundRules.poweredOn(null, null));
        assertTrue(DriveSoundRules.poweredOn(Signal.IGNITION_DRIVING, "P"));
        assertFalse(DriveSoundRules.poweredOn(Signal.IGNITION_ACC, "D"));
    }

    @Test
    public void signalCodesMapToTheEngine() {
        assertEquals(VirtualEngine.Gear.R, DriveSoundRules.gearOf("R"));
        assertEquals(VirtualEngine.Gear.UNKNOWN, DriveSoundRules.gearOf(null));
        assertEquals(VirtualEngine.Flow.REGEN, DriveSoundRules.flowOf(Signal.POWER_FLOW_REGEN));
        assertEquals(VirtualEngine.Flow.DRIVE, DriveSoundRules.flowOf(Signal.POWER_FLOW_DRIVE));
        assertEquals(VirtualEngine.Flow.NOT_READY, DriveSoundRules.flowOf(0));
        assertEquals(VirtualEngine.Flow.UNKNOWN, DriveSoundRules.flowOf(null));
        assertEquals(VirtualEngine.Flow.UNKNOWN, DriveSoundRules.flowOf(12345));
    }

    @Test
    public void thePushedThrottleWinsWhilePushesKeepComing() {
        assertFalse("没推过", DriveSoundRules.usePush(0L, 0L, Float.NaN, 10f, 1000L));
        assertFalse("推来的读不到", DriveSoundRules.usePush(3L, 900L, Float.NaN, 10f, 1000L));
        // 快读晚约 100 ms：刚推来 40，快读还是旧的 10 —— 照样用推来的（以前按读到的时刻比，旧值会把它拉回去）
        assertTrue("快读读到得晚但值旧", DriveSoundRules.usePush(3L, 950L, 40f, 10f, 1000L));
        assertTrue("一样就无所谓", DriveSoundRules.usePush(3L, 0L, 40f, 40f, 5000L));
        assertTrue("快读读不到", DriveSoundRules.usePush(3L, 0L, 40f, Float.NaN, 5000L));
        assertTrue("推送刚停一会儿", DriveSoundRules.usePush(3L, 1000L, 40f, 10f, 1000L + DriveSoundRules.PUSH_QUIET_MS));
        assertFalse("推送停了一秒多、快读不一样：改用快读",
                DriveSoundRules.usePush(3L, 1000L, 40f, 10f, 1001L + DriveSoundRules.PUSH_QUIET_MS));
    }

    @Test
    public void volumeIsReadAsDecibels() {
        assertEquals(-6f, DriveSoundRules.volumeDb("-6"), 0f);
        assertEquals(-3f, DriveSoundRules.volumeDb("-3"), 0f);
        assertEquals(0f, DriveSoundRules.volumeDb("0"), 0f);
        assertEquals("不会比 0 dB 更响", 0f, DriveSoundRules.volumeDb("6"), 0f);
        assertEquals("认不出的按默认 0 dB", 0f, DriveSoundRules.volumeDb("loud"), 0f);
        assertEquals(0f, DriveSoundRules.volumeDb(null), 0f);
    }
}
