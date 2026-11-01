package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 灵动贴听不听得到的判断（{@link SmartButtons#deaf}、{@link SmartButtons#mayRestart}、{@link SmartButtons#waitToSettle}、{@link SmartButtons#slept}、{@link ScanProbe#aboutBluetooth}）。 */
public class SmartButtonsListenTest {

    private static final long START = 1_000_000L;

    private static final long LIMIT = SmartButtons.DEAF_MS;

    @Test
    public void quietForLessThanTheLimitIsNotDeaf() {
        assertFalse(SmartButtons.deaf(true, START + LIMIT - 1, START, 0));
    }

    @Test
    public void nothingHeardSinceTheScanStartedIsDeaf() {
        assertTrue(SmartButtons.deaf(true, START + LIMIT, START, 0));
    }

    @Test
    public void aRecentAdvertKeepsTheScanAlive() {
        long heard = START + 10 * LIMIT;
        assertFalse(SmartButtons.deaf(true, heard + LIMIT - 1, START, heard));
        assertTrue(SmartButtons.deaf(true, heard + LIMIT, START, heard));
    }

    @Test
    public void aRestartGivesTheNewScanItsOwnTime() {
        long restarted = START + 10 * LIMIT;
        assertFalse(SmartButtons.deaf(true, restarted + 1, restarted, START));
    }

    /** 车机睡了一个多小时，时刻是含深睡的：醒来第一眼就算聋，除非已经收到了。 */
    @Test
    public void aLongSleepCountsAsDeafUntilSomethingIsHeard() {
        long woke = START + 6_000_000L;
        assertTrue(SmartButtons.deaf(true, woke, START, START));
        assertFalse(SmartButtons.deaf(true, woke + 1, START, woke));
    }

    /** 安卓熄屏时系统本来就停着不带过滤器的扫描：多久没收到都不算聋。 */
    @Test
    public void screenOffIsNeverDeaf() {
        assertFalse(SmartButtons.deaf(false, START + 100 * LIMIT, START, 0));
    }

    /** 2026-11-01 报告那种地方（一秒四十多包）也等够 10 秒：接手机之类的时候蓝牙芯片可能忙一阵，不去碰它。 */
    @Test
    public void silenceIsJudgedAfterTenSeconds() {
        assertEquals(10_000L, SmartButtons.DEAF_MS);
    }

    /** 车主 2026-11-01：不要反复强行启动。同一阵听不到只重开一次。 */
    @Test
    public void oneSilenceGetsOneRestart() {
        assertTrue(SmartButtons.mayRestart(START, START, 0, 0));
        assertFalse(SmartButtons.mayRestart(START, START, 1, 0));
        assertFalse(SmartButtons.mayRestart(START + 3_600_000L, START, 1, 0));
    }

    /** 蓝牙还没稳不碰它，稳了才算。 */
    @Test
    public void nothingIsRestartedWhileBluetoothSettles() {
        long settled = START + SmartButtons.SETTLE_MS;
        assertFalse(SmartButtons.mayRestart(START, settled, 0, 0));
        assertFalse(SmartButtons.mayRestart(settled - 1, settled, 0, 0));
        assertTrue(SmartButtons.mayRestart(settled, settled, 0, 0));
    }

    /** 上一次重开过了一阵才收到，是这里本来就稀：这几分钟不再开，一小时最多白开十二次。 */
    @Test
    public void aRestartForNothingPausesTheNext() {
        long quietUntil = START + SmartButtons.DEAF_QUIET_MS;
        assertFalse(SmartButtons.mayRestart(START + SmartButtons.DEAF_MS, 0, 0, quietUntil));
        assertFalse(SmartButtons.mayRestart(quietUntil - 1, 0, 0, quietUntil));
        assertTrue(SmartButtons.mayRestart(quietUntil, 0, 0, quietUntil));
        assertEquals(12, 3_600_000L / SmartButtons.DEAF_QUIET_MS);
    }

    /** 2026-11-01 报告：扫描真的没声时，重开 32 毫秒就收到，算得出来不是白开。 */
    @Test
    public void aRealStopIsToldApartFromAQuietPlace() {
        assertTrue(32 < SmartButtons.CURED_MS);
        assertTrue(SmartButtons.CURED_MS < SmartButtons.DEAF_MS);
    }

    /** 车机刚醒、蓝牙刚开好，什么都还没听到：先等。 */
    @Test
    public void aNewScanWaitsForBluetoothToSettle() {
        long settled = START + SmartButtons.SETTLE_MS;
        assertTrue(SmartButtons.waitToSettle(START, settled, START - 1000, false));
        assertTrue(SmartButtons.waitToSettle(settled - 1, settled, START - 1000, false));
        assertFalse(SmartButtons.waitToSettle(settled, settled, START - 1000, false));
    }

    /** 开机头一分钟也等：这时候还什么都没听到过。 */
    @Test
    public void theFirstMinuteAfterBootWaitsToo() {
        assertTrue(SmartButtons.waitToSettle(30_000L, SmartButtons.SETTLE_MS, 0, false));
        assertFalse(SmartButtons.waitToSettle(SmartButtons.SETTLE_MS, SmartButtons.SETTLE_MS, 0, false));
    }

    /** 人就在设置页上搜索：不等。 */
    @Test
    public void searchingDoesNotWait() {
        assertFalse(SmartButtons.waitToSettle(START, START + SmartButtons.SETTLE_MS, 0, true));
    }

    /** 这一阵已经听到过东西（上一轮还活着，读完电量接着听；搜索时听到了再点连接）：蓝牙是好的，不等。 */
    @Test
    public void somethingHeardSinceTheWakeMeansNoWait() {
        long settled = START + SmartButtons.SETTLE_MS;
        assertFalse(SmartButtons.waitToSettle(START + 5_000L, settled, START + 1, false));
    }

    /** 2026-11-01 报告：醒来 45 秒那轮扫描无声地停了，醒来 104 秒开的没停过。 */
    @Test
    public void theWaitFitsWhatTheCarShowed() {
        assertTrue(SmartButtons.SETTLE_MS > 45_000L);
        assertTrue(SmartButtons.SETTLE_MS < 104_000L);
    }

    /** 深睡的时间又多了才算睡过：两个钟不是同一刻读的，差几毫秒不算；重启以后这个数变小，也不算。 */
    @Test
    public void onlyARealSleepCounts() {
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L));
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L + 5));
        assertFalse(SmartButtons.slept(61_392_000L, 61_392_000L - 5));
        assertFalse(SmartButtons.slept(61_392_000L, 0));
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
