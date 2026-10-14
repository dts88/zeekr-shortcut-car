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

    /**
     * 「2.3.3 相机步骤统计」（2.11.4 R2，项目所有者 2026-10-14）：按相机、动作数；按哨兵等分开数；备选方案「关座舱时先短暂关环视」
     * 要比的那一组（R3）；最近几步一步一行。没记下的时候也有一句。
     */
    @Test
    public void stepStatisticsCountByLaneAndMoveSplitByConditionsAndListTheLatestSteps() {
        assertEquals("还没有记下的步骤（2.11.4 起每结束一步记一条）\n",
                DiagnosticsCollector.stepStatsText(new java.util.ArrayList<>()));
        java.util.List<com.kooo.evcam.camera.StepStats.Entry> entries = java.util.Arrays.asList(
                com.kooo.evcam.camera.StepStats.parse(
                        "v1 t=1000 k=left c=1 m=CLOSE r=CLOSED ms=220 sen=1 fg=0 sur=STREAMING sg=0 sc=0"),
                com.kooo.evcam.camera.StepStats.parse("v1 t=2000 k=left c=1 m=OPEN r=NO_FRAMES ms=15000 cfg=80"
                        + " cnt=HAL_HOLDING st=0 res=0 fail=1 lost=0 sen=1 fg=0 lc=4500 lcd=220 sur=STREAMING"),
                com.kooo.evcam.camera.StepStats.parse(
                        "v1 t=3000 k=left c=1 m=REOPEN r=FRAMED ms=8700 oc=8535 sen=1 fg=1 sur=STREAMING"),
                com.kooo.evcam.camera.StepStats.parse("v1 t=4000 k=back c=0 m=OPEN r=FRAMED ms=100 sen=0 fg=1"));
        String text = DiagnosticsCollector.stepStatsText(entries);
        assertTrue(text, text.startsWith("最近 4 步（最多 200 步，跨进程留着）："));
        assertTrue(text, text.contains("  后座舱 开：1 次：出画面 0、没画面 1、出错 0\n"));
        assertTrue(text, text.contains("  后座舱 关：1 次：关 1 次，用时中位 0.2 秒、最长 0.2 秒，超过 1 秒 0 次\n"));
        assertTrue("后座舱排在前座舱前面", text.indexOf("  后座舱 开：") < text.indexOf("  前座舱 开："));
        assertTrue(text, text.contains("  后座舱 开 · 哨兵 开：1 次：出画面 0、没画面 1、出错 0\n"));
        assertTrue(text, text.contains("  前座舱 开 · 主界面在前：1 次：出画面 1、没画面 0、出错 0\n"));
        assertTrue(text, text.contains("  后座舱 开 · 离上次关 3–6 秒：1 次"));
        assertTrue(text, text.contains("  后座舱 救援重开 · 不是车机刚放开：1 次"));
        assertTrue(text, text.contains("  环视在出画面：1 次：关 1 次，用时中位 0.2 秒、最长 0.2 秒，超过 1 秒 0 次；"
                + "之后那一次开 1 次，没画面 1\n"));
        assertTrue(text, text.contains("后座舱 开 → 15 秒没画面（一个都没回（相机 HAL 收了请求不回）：开始 0、结果 0、失败 1、丢缓冲 0）"
                + "；哨兵 1、主界面后台、离上次关 4.5 秒（那次关了 0.2 秒）、环视在出画面"));
        assertTrue(text, text.contains("后座舱 救援重开 → 出画面 8.7 秒，旧设备关了 8.5 秒"));
        assertTrue("最近的在前", text.indexOf("前座舱 开 → 出画面") < text.indexOf("后座舱 关 → 关了"));
    }

    /**
     * 2.11.5（项目所有者 2026-10-15 批准，第 D0、D1、D3 项）：关座舱时环视那一条单独数（不算步数），按环视是不是第一台、主界面在不在前分开；
     * 丢了之后那一次关单算一行；存下的「我们的输出接不住」里开始 0 的照今天的规矩写成 HAL 只回了错误；环视那一台是第几台写在那一步后面。
     */
    @Test
    public void stepStatisticsSplitTheSurroundSideByLineageAndMainScreen() {
        java.util.List<com.kooo.evcam.camera.StepStats.Entry> entries = java.util.Arrays.asList(
                com.kooo.evcam.camera.StepStats.parse("v1 t=1000 k=front c=2 m=OPEN r=NO_FRAMES ms=15000"
                        + " cnt=STARVING st=0 res=0 fail=2 lost=1 sl=5 slr=CAME_BACK"),
                com.kooo.evcam.camera.StepStats.parse("v1 t=2000 k=front c=2 m=CLOSE r=CLOSED ms=5798 ck=LOSS err=DEVICE_ERROR:4"),
                com.kooo.evcam.camera.StepStats.parse("v1 t=3000 k=front c=2 m=COUPLE r=STALLED ms=8121 fg=0 sl=5"
                        + " slr=CAME_BACK cc=back,left cw=DARK_RELEASE lr=-35 a1=0 our=RESCUE ourms=8100 ign=ACC"),
                com.kooo.evcam.camera.StepStats.parse("v1 t=4000 k=front c=2 m=COUPLE r=STEADY ms=100 fg=0 sl=1"
                        + " slr=TAKEN cc=back,left cw=DARK_RELEASE lr=29900 a1=1"));
        String text = DiagnosticsCollector.stepStatsText(entries);
        assertTrue(text, text.startsWith("最近 2 步（最多 200 步，跨进程留着）："));
        assertTrue(text, text.contains("  环视 关（丢了之后）：1 次：关 1 次，用时中位 5.8 秒、最长 5.8 秒，超过 1 秒 1 次\n"));
        assertTrue(text, text.contains("  环视 开 · 环视不是第一台：1 次：出画面 0、没画面 1、出错 0\n"));
        assertTrue(text, text.contains("    一共：2 次：断了 1 次，最长 8.1 秒\n"));
        assertTrue(text, text.contains("    环视不是第一台 · 主界面在后台：1 次：断了 1 次，最长 8.1 秒\n"));
        assertTrue(text, text.contains("    环视是车机重新初始化后的第一台 · 主界面在后台：1 次：断了 0 次\n"));
        assertTrue(text, text.contains("环视 开 → 15 秒没画面（相机 HAL 没出过一帧，只回了错误（失败 2、丢缓冲 1）：开始 0、结果 0、失败 2、丢缓冲 1）"));
        assertTrue(text, text.contains("、环视第 5 台（重置：它在相机服务里没了又回来）"));
        assertTrue(text, text.contains("关座舱时环视最近 10 次（新的在前）：\n"));
        assertTrue(text, text.contains("关 前座舱、后座舱（DARK_RELEASE）：环视断了 8.1 秒（只量到关开始后第 8.1 秒：那时我们在救它）"
                + "；最后一帧在关完前 35 ms；关完 1 秒后没出过画面；环视第 5 台（重置：它在相机服务里没了又回来）；主界面后台；点火 ACC"));
        assertTrue("新的在前", text.indexOf("环视没断（最长断帧 0.1 秒）") < text.indexOf("环视断了 8.1 秒（"));
        assertFalse("关座舱时环视那一条不进最近 30 步", text.substring(text.indexOf("最近 30 步"),
                text.indexOf("关座舱时环视最近 10 次")).contains("关座舱时"));
    }

    @Test
    public void everyStepBucketHasItsOwnText() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String bucket : new String[]{"sentry:0", "sentry:1", "sentry:2", "sentry:?", "main:yes", "main:no",
                "main:?", "close:none", "close:<3", "close:3-6", "close:6-30", "close:>30", "release:yes",
                "release:no", "lineage:first", "lineage:later", "lineage:?"}) {
            String text = DiagnosticsCollector.bucketText(bucket);
            assertFalse(bucket + " 没有翻成中文", text.equals(bucket));
            assertTrue(bucket + " 和别的写得一样", seen.add(text));
        }
    }
}
