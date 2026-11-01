package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 灵动贴听不听得到的判断（{@link SmartButtons#deaf}、{@link SmartButtons#deafLimit}、{@link SmartButtons#mayRestart}、{@link SmartButtons#slept}、{@link ScanProbe#aboutBluetooth}）。 */
public class SmartButtonsListenTest {

    private static final long START = 1_000_000L;

    private static final long LIMIT = SmartButtons.DEAF_MAX_MS;

    @Test
    public void quietForLessThanTheLimitIsNotDeaf() {
        assertFalse(SmartButtons.deaf(true, START + LIMIT - 1, START, 0, LIMIT));
    }

    @Test
    public void nothingHeardSinceTheScanStartedIsDeaf() {
        assertTrue(SmartButtons.deaf(true, START + LIMIT, START, 0, LIMIT));
    }

    @Test
    public void aRecentAdvertKeepsTheScanAlive() {
        long heard = START + 10 * LIMIT;
        assertFalse(SmartButtons.deaf(true, heard + LIMIT - 1, START, heard, LIMIT));
        assertTrue(SmartButtons.deaf(true, heard + LIMIT, START, heard, LIMIT));
    }

    @Test
    public void aRestartGivesTheNewScanItsOwnTime() {
        long restarted = START + 10 * LIMIT;
        assertFalse(SmartButtons.deaf(true, restarted + 1, restarted, START, LIMIT));
    }

    /** 车机睡了一个多小时，时刻是含深睡的：醒来第一眼就算聋，除非已经收到了。 */
    @Test
    public void aLongSleepCountsAsDeafUntilSomethingIsHeard() {
        long woke = START + 6_000_000L;
        assertTrue(SmartButtons.deaf(true, woke, START, START, LIMIT));
        assertFalse(SmartButtons.deaf(true, woke + 1, START, woke, LIMIT));
    }

    /** 安卓熄屏时系统本来就停着不带过滤器的扫描：多久没收到都不算聋。 */
    @Test
    public void screenOffIsNeverDeaf() {
        assertFalse(SmartButtons.deaf(false, START + 100 * LIMIT, START, 0, LIMIT));
    }

    /** 2026-11-01 报告那种地方（一秒四十多包）：也等够 10 秒，接手机之类的时候蓝牙芯片可能忙一阵，不去碰它。 */
    @Test
    public void whereAdvertsAreDenseItStillWaitsTenSeconds() {
        assertEquals(SmartButtons.DEAF_MIN_MS, SmartButtons.deafLimit(20));
        assertEquals(10_000L, SmartButtons.DEAF_MIN_MS);
    }

    @Test
    public void whereAdvertsAreSparseItWaitsLonger() {
        assertEquals(30_000L, SmartButtons.deafLimit(1000));
        assertEquals(SmartButtons.DEAF_MAX_MS, SmartButtons.deafLimit(5000));
        assertEquals(60_000L, SmartButtons.DEAF_MAX_MS);
    }

    /** 周围设备变少，等的时间跟着变长；一次长长的停顿不会把它拉到回不来。 */
    @Test
    public void theAverageGapFollowsWhatIsHeard() {
        double gap = SmartButtons.DEAF_MAX_MS;
        for (int i = 0; i < 200; i++) {
            gap = SmartButtons.averageGap(gap, 20);
        }
        assertEquals(20.0, gap, 1.0);

        gap = SmartButtons.averageGap(gap, 3_600_000L);
        assertTrue(gap < SmartButtons.DEAF_MAX_MS / 9.0);
        for (int i = 0; i < 120; i++) {
            gap = SmartButtons.averageGap(gap, 20);
        }
        assertEquals(20.0, gap, 2.0);

        for (int i = 0; i < 30; i++) {
            gap = SmartButtons.averageGap(gap, 5000);
        }
        assertEquals(SmartButtons.DEAF_MAX_MS, SmartButtons.deafLimit(gap));
    }

    /** 车主 2026-11-01：不要反复强行启动。同一阵听不到只重开一次。 */
    @Test
    public void oneSilenceGetsOneRestart() {
        assertTrue(SmartButtons.mayRestart(START, START, 0));
        assertFalse(SmartButtons.mayRestart(START, START, 1));
        assertFalse(SmartButtons.mayRestart(START + 3_600_000L, START, 1));
    }

    /** 蓝牙还没稳不碰它，稳了才算。 */
    @Test
    public void nothingIsRestartedWhileBluetoothSettles() {
        long settled = START + SmartButtons.SETTLE_MS;
        assertFalse(SmartButtons.mayRestart(START, settled, 0));
        assertFalse(SmartButtons.mayRestart(settled - 1, settled, 0));
        assertTrue(SmartButtons.mayRestart(settled, settled, 0));
    }

    /** 2026-11-01 报告：醒来 45 秒那轮扫描无声地停了，醒来 104 秒开的没停过。 */
    @Test
    public void theWaitFitsWhatTheCarShowed() {
        assertTrue(SmartButtons.SETTLE_MS > 45_000L);
        assertTrue(SmartButtons.SETTLE_MS < 104_000L);
    }

    /** 深睡的时间又多了才算睡过：两个钟不是同一刻读的，差几毫秒不算。 */
    @Test
    public void onlyARealSleepCounts() {
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L));
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L + 5));
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L - 5));
        assertTrue(SmartButtons.slept(61_392_000L, 61_392_000L + SmartButtons.SLEPT_MS));
        assertTrue(SmartButtons.slept(31_497_000L, 61_392_000L));
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
