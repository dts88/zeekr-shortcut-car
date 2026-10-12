package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 熄屏判断的几条边，拿 10-12 车上那几段原样走一遍（设计 v3 §8；黑匣子 diag_18 20:04–20:57，diag_17 01:40 夜里醒来）。
 * 熄屏 = 屏幕真黑了：安卓熄屏，或者车辆说黑（项目所有者 2026-10-12 D1）。
 */
public class ScreenTransitionsTest {

    private static final Integer ACC = Signal.IGNITION_ACC;
    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer AFTER_ACC = 0x00200102;
    private static final String DARK = "DARK";
    private static final String SLEEPS = "HEAD_UNIT_SLEEPS";
    private static final String WOKE = "WOKE_WHILE_DARK";
    private static final String LIT = "LIT";

    /** 一段输入，记下跨过的边。时间是 elapsedRealtime（毫秒），只有「读不到」的时限看它。 */
    private static final class Run {
        ScreenTransitions.State s;
        final List<String> edges = new ArrayList<>();

        Run(boolean androidOff) {
            s = ScreenTransitions.initial(androidOff);
        }

        /** 车辆接口连着，读到这两项。 */
        Run car(Boolean event, Integer ignition, long now) {
            return to(ScreenTransitions.verdict(s, VehicleScreen.verdict(event, ignition), true, now));
        }

        /** 车辆接口连着，两项都读不到（或者读不到的时限到了再看一次）。 */
        Run unreadable(long now) {
            return to(ScreenTransitions.verdict(s, null, true, now));
        }

        /** 车辆接口没连着（断了、在连、连不上）。 */
        Run linkDown(long now) {
            return to(ScreenTransitions.verdict(s, null, false, now));
        }

        Run androidOff() {
            return to(ScreenTransitions.androidOff(s));
        }

        /** 安卓亮了，醒来那一遍读到这两项（读不到、没读回来都是 null）。 */
        Run androidOn(Boolean event, Integer ignition) {
            return to(ScreenTransitions.androidOn(s, VehicleScreen.verdict(event, ignition), true));
        }

        Run androidOnLinkDown() {
            return to(ScreenTransitions.androidOn(s, null, false));
        }

        private Run to(ScreenTransitions.State next) {
            ScreenTransitions.Edge edge = ScreenTransitions.edge(s, next);
            if (edge != null) {
                edges.add(edge.name());
            }
            s = next;
            return this;
        }
    }

    /**
     * 10-12 哨兵模式没开离车（diag_18）：20:05:10.998 车门关上就黑（事件 0 → 1、点火 ON → ACC 同一毫秒）；
     * 20:05:15.221 车门又开，点火先回 ON（事件晚 0.1 秒才回 0）；20:05:46.709 又黑；20:11:05.976 点火 ACC → 0x00200102
     * （事件还是 1，不是边）；20:11:33 安卓熄屏 = 车机要睡；20:22:52 早上安卓亮、醒来那一遍读到亮。
     */
    @Test
    public void sentryOffLeaveAndMorningWake() {
        Run r = new Run(false)
                .car(false, ON, 1_000)
                .car(true, ACC, 10_998)
                .car(true, ON, 15_221)
                .car(false, ON, 15_325)
                .car(true, ACC, 46_709)
                .car(true, AFTER_ACC, 365_976)
                .androidOff()
                .androidOn(false, ON);
        assertEquals(Arrays.asList(DARK, LIT, DARK, SLEEPS, LIT), r.edges);
        assertFalse(r.s.dark());
    }

    /**
     * 早上醒来时记着的还是睡之前的「黑」（事件 1、点火 0x00200102）：醒来那一遍读回来之前安卓先不算亮
     * （监听者那一批先到，安卓还算熄屏，不是边），读回来是亮 —— 一条亮屏，不是「车机醒着、屏幕黑着」。
     */
    @Test
    public void aMorningWakeIsNotANightWake() {
        Run r = new Run(false).car(true, ACC, 0).androidOff();
        r.edges.clear();
        r.car(false, ON, 700_000).androidOn(false, ON);
        assertEquals(Arrays.asList(LIT), r.edges);
    }

    /** 10-12 哨兵模式开着离车 20 分钟：安卓一直没熄屏，只有车辆说黑、开车门说亮（点火先回 ON）。中间哨兵模式变了不是输入。 */
    @Test
    public void sentryOnParkTwentyMinutes() {
        Run r = new Run(false)
                .car(false, ON, 0)
                .car(true, ACC, 1_000)
                .car(true, ON, 1_000 + 1_226_140)
                .car(false, ON, 1_000 + 1_226_177);
        assertEquals(Arrays.asList(DARK, LIT), r.edges);
    }

    /**
     * 夜里车机自己醒（10-12 01:40）：安卓亮了，醒来那一遍车辆还说黑（事件 1、点火 0x00200102）—— 车机醒着、屏幕黑着；
     * 两分钟后安卓又熄屏 = 车机又要睡；早上安卓亮、读到亮才是亮屏。
     */
    @Test
    public void nightWakeStaysDark() {
        Run r = new Run(false).car(true, ACC, 0).androidOff();
        r.edges.clear();
        r.androidOn(true, AFTER_ACC);
        assertEquals(Arrays.asList(WOKE), r.edges);
        assertTrue("还算熄屏", r.s.dark());
        assertFalse(r.s.androidOff);
        r.androidOff().androidOn(false, ON);
        assertEquals(Arrays.asList(WOKE, SLEEPS, LIT), r.edges);
    }

    /** 安卓刚亮那一下只认醒来后读到的：读不到（或者等满了没读回来）就只看安卓，不拿睡之前的「黑」顶上（C3）。 */
    @Test
    public void anUnreadableWakeReadCountsAsAndroidOnly() {
        Run r = new Run(false).car(true, ACC, 0).androidOff();
        r.edges.clear();
        r.androidOn(null, null);
        assertEquals(Arrays.asList(LIT), r.edges);
        assertNull(r.s.vehicle);
    }

    /**
     * 车辆接口连着、两项一时读不到（占位值来回跳）：最多两次对账照上一次的判断算，不跨边；读回来了接着用；
     * 过了时限还读不到就丢掉、只看安卓（车机服务坏了也不会一直黑下去，C3）。
     */
    @Test
    public void unknownWhileConnectedKeepsTheLastVerdictForTwoReconciles() {
        long keep = ScreenTransitions.KEEP_UNKNOWN_MS;
        Run r = new Run(false).car(true, ACC, 0);
        r.edges.clear();
        r.unreadable(1_000);
        assertEquals(1_000, r.s.unknownSinceMs);
        r.unreadable(1_000 + keep - 1);
        assertTrue("时限之内照上一次算", r.s.dark());
        r.car(true, ACC, 1_000 + keep);
        assertEquals("读回来了：时限从头算", 0, r.s.unknownSinceMs);
        r.unreadable(20_000).unreadable(20_000 + keep);
        assertEquals("时限到了还读不到：只看安卓", Arrays.asList(LIT), r.edges);
        assertNull(r.s.vehicle);
        assertEquals(0, r.s.unknownSinceMs);
    }

    /** 亮着时读不到：没有可照着算的，直接只看安卓，也不起算时限。 */
    @Test
    public void unknownWithNothingToKeepIsAndroidOnly() {
        Run r = new Run(false).unreadable(5_000);
        assertNull(r.s.vehicle);
        assertEquals(0, r.s.unknownSinceMs);
        assertTrue(r.edges.isEmpty());
    }

    /** 车辆接口没连着（断了）：当场只看安卓 —— 车辆说的黑结束；安卓熄屏着的还是黑着。 */
    @Test
    public void aLostLinkClearsTheVehicleVerdict() {
        Run awake = new Run(false).car(true, ACC, 0).linkDown(1_000);
        assertEquals(Arrays.asList(DARK, LIT), awake.edges);
        Run asleep = new Run(false).car(true, ACC, 0).androidOff().linkDown(1_000);
        assertEquals(Arrays.asList(DARK, SLEEPS), asleep.edges);
        assertTrue(asleep.s.dark());
    }

    /** 车辆信号读不到（降级）：安卓熄屏就是熄屏和车机要睡一起来（一条熄屏边，androidOff 一起带着），安卓亮就是亮屏（和 2.11.1 一样）。 */
    @Test
    public void degradedModeIsAndroidOnly() {
        Run r = new Run(false).linkDown(0).androidOff();
        assertEquals(Arrays.asList(DARK), r.edges);
        assertTrue(r.s.androidOff);
        r.androidOnLinkDown();
        assertEquals(Arrays.asList(DARK, LIT), r.edges);
    }

    /** 黑的那一下两项分两次到（合并窗口没赶上）：先到的一项判不出黑（且），后到的那一下才黑 —— 还是一条熄屏边。亮的那一下点火先到就亮。 */
    @Test
    public void edgesSplitAcrossTwoPublishesGiveOneTransition() {
        Run eventFirst = new Run(false).car(false, ON, 0).car(true, ON, 10).car(true, ACC, 20);
        assertEquals(Arrays.asList(DARK), eventFirst.edges);
        Run ignitionFirst = new Run(false).car(false, ON, 0).car(false, ACC, 10).car(true, ACC, 20);
        assertEquals(Arrays.asList(DARK), ignitionFirst.edges);
        Run lit = new Run(false).car(true, ACC, 0).car(true, ON, 100).car(false, ON, 140);
        assertEquals(Arrays.asList(DARK, LIT), lit.edges);
    }

    /**
     * 进程起来时：安卓亮着、屏幕早黑了 —— 读到第一份判断就是一条熄屏边（ScreenState 那边记「起点不明」）；
     * 安卓熄屏着起来的 —— 已经黑着，不是边，读到车辆还黑着也不是；安卓亮、读到亮才是亮屏。
     */
    @Test
    public void processStart() {
        Run litStart = new Run(false).car(true, ACC, 1_100);
        assertEquals(Arrays.asList(DARK), litStart.edges);
        Run darkStart = new Run(true);
        assertTrue(darkStart.s.dark());
        darkStart.car(true, AFTER_ACC, 500).androidOn(false, ON);
        assertEquals(Arrays.asList(LIT), darkStart.edges);
    }

    /** 安卓熄屏着时车辆判断怎么变都不是边（屏幕本来就黑着）；车辆说黑时安卓熄屏是车机要睡，不是第二次熄屏。 */
    @Test
    public void onlyAndroidChangesMoveWithinTheDark() {
        Run r = new Run(true).car(false, ON, 0).car(true, ACC, 10).car(false, ON, 20);
        assertTrue(r.edges.isEmpty());
        Run v = new Run(false).car(true, ACC, 0).androidOff().androidOff();
        assertEquals(Arrays.asList(DARK, SLEEPS), v.edges);
    }
}
