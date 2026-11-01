package com.kooo.evcam.button;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 灵动贴听不听得到的判断（{@link SmartButtons#deaf}、{@link ScanProbe#aboutBluetooth}）。 */
public class SmartButtonsListenTest {

    private static final long START = 1_000_000L;

    @Test
    public void quietForLessThanTheLimitIsNotDeaf() {
        assertFalse(SmartButtons.deaf(true, START + SmartButtons.DEAF_MS - 1, START, 0, 0));
    }

    @Test
    public void nothingHeardSinceTheScanStartedIsDeaf() {
        assertTrue(SmartButtons.deaf(true, START + SmartButtons.DEAF_MS, START, 0, 0));
    }

    @Test
    public void aRecentAdvertKeepsTheScanAlive() {
        long heard = START + 10 * SmartButtons.DEAF_MS;
        assertFalse(SmartButtons.deaf(true, heard + SmartButtons.DEAF_MS - 1, START, heard, 0));
        assertTrue(SmartButtons.deaf(true, heard + SmartButtons.DEAF_MS, START, heard, 0));
    }

    @Test
    public void aRestartGivesTheNewScanItsOwnTime() {
        long restarted = START + 10 * SmartButtons.DEAF_MS;
        assertFalse(SmartButtons.deaf(true, restarted + 1, restarted, START, 1));
    }

    /** 车机睡了一个多小时，时刻是含深睡的：醒来第一眼就算聋，除非已经收到了。 */
    @Test
    public void aLongSleepCountsAsDeafUntilSomethingIsHeard() {
        long woke = START + 6_000_000L;
        assertTrue(SmartButtons.deaf(true, woke, START, START, 0));
        assertFalse(SmartButtons.deaf(true, woke + 1, START, woke, 0));
    }

    /** 安卓熄屏时系统本来就停着不带过滤器的扫描：多久没收到都不算聋。 */
    @Test
    public void screenOffIsNeverDeaf() {
        assertFalse(SmartButtons.deaf(false, START + 100 * SmartButtons.DEAF_MS, START, 0, 0));
    }

    /** 一轮扫描聋了就重开，一直这样也不能超过安卓的限制（30 秒里开 5 次）。 */
    @Test
    public void restartsStayWithinAndroidsScanLimit() {
        assertTrue(4 * SmartButtons.DEAF_MS >= 30_000L);
        assertTrue(SmartButtons.WATCH_MS <= SmartButtons.DEAF_MS / 2);
    }

    /** 重开过还是没有：等的时间翻倍，翻到头就不再翻。 */
    @Test
    public void restartsWaitLongerUpToTheLimit() {
        long once = SmartButtons.DEAF_MS * 2;
        assertFalse(SmartButtons.deaf(true, START + once - 1, START, 0, 1));
        assertTrue(SmartButtons.deaf(true, START + once, START, 0, 1));

        long most = SmartButtons.DEAF_MS * 16;
        assertFalse(SmartButtons.deaf(true, START + most - 1, START, 0, SmartButtons.DEAF_MAX_DOUBLINGS));
        assertTrue(SmartButtons.deaf(true, START + most, START, 0, SmartButtons.DEAF_MAX_DOUBLINGS));
        assertTrue(SmartButtons.deaf(true, START + most, START, 0, 100));
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
