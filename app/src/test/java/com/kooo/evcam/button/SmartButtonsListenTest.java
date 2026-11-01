package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 灵动贴听不听得到的判断（{@link SmartButtons#deaf}、{@link SmartButtons#deafLimit}、{@link ScanStarts}、{@link ScanProbe#aboutBluetooth}）。 */
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

    /** 2026-11-01 报告那种地方（一秒四十多包）：一秒没有一包就算聋，不等了。 */
    @Test
    public void whereAdvertsAreDenseOneSecondIsEnough() {
        assertEquals(SmartButtons.DEAF_MIN_MS, SmartButtons.deafLimit(20, 0));
        assertEquals(1000L, SmartButtons.DEAF_MIN_MS);
    }

    /** 灵动贴一下广播最短 1.5 秒（Lab findings §2.61）：看的间隔、等新的一路都算上，接回来还赶得上同一下。 */
    @Test
    public void aPressDuringASilenceIsStillCaught() {
        assertTrue(SmartButtons.DEAF_MIN_MS + SmartButtons.WATCH_MS + SmartButtons.FRESH_SCAN_WAIT_MS < 1_600L);
    }

    @Test
    public void whereAdvertsAreSparseItWaitsLonger() {
        assertEquals(3000L, SmartButtons.deafLimit(100, 0));
        assertEquals(SmartButtons.DEAF_MAX_MS, SmartButtons.deafLimit(5000, 0));
    }

    /** 两步都试过还是没有：多半周围真的没设备，等的时间翻倍，翻到头就不再翻。 */
    @Test
    public void restartsWaitLongerUpToTheLimit() {
        assertEquals(SmartButtons.DEAF_RETRY_MS, SmartButtons.deafLimit(20, 1));
        assertEquals(SmartButtons.DEAF_RETRY_MS * 2, SmartButtons.deafLimit(20, 2));
        long most = SmartButtons.DEAF_RETRY_MS << SmartButtons.DEAF_MAX_DOUBLINGS;
        assertEquals(most, SmartButtons.deafLimit(20, 1 + SmartButtons.DEAF_MAX_DOUBLINGS));
        assertEquals(most, SmartButtons.deafLimit(20, 100));
    }

    /** 周围设备变少，等的时间跟着变长；一次长长的停顿不会把它拉到回不来。 */
    @Test
    public void theAverageGapFollowsWhatIsHeard() {
        double gap = SmartButtons.DEAF_MAX_MS;
        for (int i = 0; i < 100; i++) {
            gap = SmartButtons.averageGap(gap, 20);
        }
        assertEquals(20.0, gap, 1.0);

        gap = SmartButtons.averageGap(gap, 3_600_000L);
        assertTrue(gap < 900);
        for (int i = 0; i < 60; i++) {
            gap = SmartButtons.averageGap(gap, 20);
        }
        assertEquals(20.0, gap, 2.0);

        for (int i = 0; i < 10; i++) {
            gap = SmartButtons.averageGap(gap, 5000);
        }
        assertEquals(SmartButtons.DEAF_MAX_MS, SmartButtons.deafLimit(gap, 0));
    }

    /** 开扫描不超过安卓的限制（30 秒里 5 次）：留一次余量，到限了等最早的那次过了窗口。 */
    @Test
    public void scanStartsStayWithinAndroidsLimit() {
        assertTrue(ScanStarts.MOST < 5);
        assertEquals(30_000L, ScanStarts.WINDOW_MS);
        ScanStarts starts = new ScanStarts();
        long t = START;
        for (int i = 0; i < ScanStarts.MOST; i++) {
            assertEquals(0, starts.waitMs(t));
            starts.record(t);
            t += 1000;
        }
        assertEquals(30_000L - ScanStarts.MOST * 1000L, starts.waitMs(t));
        assertEquals(0, starts.waitMs(START + ScanStarts.WINDOW_MS));
        starts.record(START + ScanStarts.WINDOW_MS);
        assertEquals(1000L, starts.waitMs(START + ScanStarts.WINDOW_MS));
    }

    /** 没开成的不算：醒来时扫描失败几次，不该占掉接回来的名额。 */
    @Test
    public void aFailedStartDoesNotCount() {
        ScanStarts starts = new ScanStarts();
        for (int i = 0; i < ScanStarts.MOST; i++) {
            starts.record(START);
        }
        assertTrue(starts.waitMs(START) > 0);
        starts.forgetLast();
        assertEquals(0, starts.waitMs(START));
        starts.record(START);
        assertTrue(starts.waitMs(START) > 0);
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
