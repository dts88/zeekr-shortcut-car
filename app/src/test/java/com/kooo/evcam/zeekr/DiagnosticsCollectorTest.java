package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import org.junit.Test;

/**
 * 诊断报告「3.1 熄屏」「6.2 车辆信号」和通道调度那一节登记表那一行共用的几处写法（熄屏 v3，设计 v3 §6）。
 *
 * <p>报告是下一次上车后对着黑匣子看的：谁判的熄屏、从什么时候起、两项车辆信号是什么，写错了就会看错。
 * 读不到（null）的每一项都要有字，不能在报告里抛出来。</p>
 */
public class DiagnosticsCollectorTest {

    @Test
    public void theDarkPeriodSaysWhoJudgedItAndSinceWhen() {
        assertEquals("车辆信号，12 分 3 秒前起", DiagnosticsCollector.darkText(ScreenState.Source.VEHICLE, 723_400L));
        assertEquals("安卓熄屏，31 秒前起", DiagnosticsCollector.darkText(ScreenState.Source.ANDROID, 31_000L));
        assertEquals("读到时已经黑着：记下的是读到的那一刻",
                "车辆信号，5 秒前读到时已经黑着，起点不明",
                DiagnosticsCollector.darkText(ScreenState.Source.VEHICLE_READ, 5_400L));
        assertEquals("进程起来时就黑着：不知道从什么时候起",
                "安卓熄屏，进程起来时就黑着，起点不明", DiagnosticsCollector.darkText(ScreenState.Source.ANDROID, -1L));
        assertEquals("哨兵模式开着停车那一夜", "车辆信号，2 小时 5 分前起",
                DiagnosticsCollector.darkText(ScreenState.Source.VEHICLE, 7_500_000L));
    }

    @Test
    public void ignitionShowsItsNameAndRawCodeAndUnnamedCodesOnlyRaw() {
        assertEquals("ACC（0x00200104）", DiagnosticsCollector.ignitionText(Signal.IGNITION_ACC));
        assertEquals("ON（0x00200105）", DiagnosticsCollector.ignitionText(Signal.IGNITION_ON));
        assertEquals("车机要睡前那个没名字的码", "0x00200102", DiagnosticsCollector.ignitionText(0x00200102));
        assertEquals("读不到", DiagnosticsCollector.ignitionText(null));
    }

    @Test
    public void screenPowerEventShowsTheRawValueAndWhatItMeans() {
        assertEquals("1（黑）", DiagnosticsCollector.eventText(Boolean.TRUE));
        assertEquals("0（亮）", DiagnosticsCollector.eventText(Boolean.FALSE));
        assertEquals("读不到", DiagnosticsCollector.eventText(null));
    }

    @Test
    public void verdictSentryAndDurations() {
        assertEquals("黑", DiagnosticsCollector.verdictText(Boolean.TRUE));
        assertEquals("亮", DiagnosticsCollector.verdictText(Boolean.FALSE));
        assertEquals("说不上来（只看安卓）", DiagnosticsCollector.verdictText(null));
        assertEquals("关（0）", DiagnosticsCollector.sentryText(0));
        assertEquals("开（1）", DiagnosticsCollector.sentryText(1));
        assertEquals("布防（2）", DiagnosticsCollector.sentryText(2));
        assertEquals("读不到", DiagnosticsCollector.sentryText(null));
        assertEquals("0 秒", DiagnosticsCollector.duration(-5L));
        assertEquals("59 秒", DiagnosticsCollector.duration(59_999L));
        assertEquals("1 分 0 秒", DiagnosticsCollector.duration(60_000L));
    }

    @Test
    public void everyCarLinkResultHasItsOwnText() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Telemetry.CarLink link : Telemetry.CarLink.values()) {
            String text = DiagnosticsCollector.linkText(link);
            assertFalse(link + " 没有字", text.isEmpty());
            assertTrue(link + " 和别的结果写得一样", seen.add(text));
        }
        assertFalse("核心还没登记", DiagnosticsCollector.linkText(null).isEmpty());
        assertFalse(seen.contains(DiagnosticsCollector.linkText(null)));
    }
}
