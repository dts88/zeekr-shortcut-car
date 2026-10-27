package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 熄屏判断的几条边，拿 10-12 车上那几段原样走一遍（设计 v3 §8；黑匣子 diag_18 20:04–20:57，diag_17 01:40 夜里醒来）。
 * 熄屏 = 屏幕真黑了：安卓熄屏，或者车辆说黑（项目所有者 2026-10-12 D1）。黑着、或者还没有车辆的判断时亮要有证据：
 * 车没通电还是黑（项目所有者 2026-10-27；10-24 远程开空调那一段）。
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

        /** 车辆接口连着，读到这两项（和 ScreenState 一样，亮要不要证据照此刻的状态）。 */
        Run car(Boolean event, Integer ignition, long now) {
            return to(ScreenTransitions.verdict(s,
                    VehicleScreen.verdict(event, ignition, ScreenTransitions.litNeedsEvidence(s)), true, now));
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
            return to(ScreenTransitions.androidOn(s,
                    VehicleScreen.verdict(event, ignition, ScreenTransitions.litNeedsEvidence(s)), true));
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

    /**
     * 10-24 15:43:58 远程开空调（或远程看车）：黑着，屏幕电源事件 1 → 0 亮了整 60 秒，点火一直 ACC，然后事件回到 1 ——
     * 车没通电，从头到尾都是黑，一条边都没有（以前是一条亮屏、一条熄屏：主界面、超级镜子重新登记，座舱又被要）。
     */
    @Test
    public void aLitScreenWithTheCarNotPoweredStaysDark() {
        Run r = new Run(false).car(false, ON, 0).car(true, ACC, 1_000);
        r.edges.clear();
        r.car(false, ACC, 100_000);
        assertTrue("事件说亮、车没通电：还是黑", r.s.dark());
        assertEquals(Boolean.TRUE, r.s.vehicle);
        r.car(false, ACC, 130_000).car(true, ACC, 160_000);
        assertTrue(r.edges.isEmpty());
        assertTrue(r.s.dark());
    }

    /** 黑着时结束黑的是车通电了：点火 ACC → ON 是亮屏 —— 事件早就说亮了（远程开空调那一段里车主回来）也一样。 */
    @Test
    public void theCarPoweringOnIsTheRealReturn() {
        Run r = new Run(false).car(true, ACC, 0).car(false, ACC, 10_000);
        r.edges.clear();
        r.car(false, ON, 20_000);
        assertEquals(Arrays.asList(LIT), r.edges);
        assertEquals(Boolean.FALSE, r.s.vehicle);
    }

    /**
     * 进程在 ACC、屏幕亮着时起来（还没有车辆的判断）：读到的第一份就是黑 —— 一条熄屏边，起之前没有车辆判断
     * （ScreenState 那边记「读到时已经黑着，起点不明」）。在 ON 时起来照旧是亮。
     */
    @Test
    public void aProcessStartAtAccIsDark() {
        Run acc = new Run(false);
        assertNull("起之前没有车辆判断：起点不明", acc.s.vehicle);
        acc.car(false, ACC, 1_100);
        assertEquals(Arrays.asList(DARK), acc.edges);
        assertEquals(Boolean.TRUE, acc.s.vehicle);
        Run on = new Run(false).car(false, ON, 1_100);
        assertTrue(on.edges.isEmpty());
        assertFalse(on.s.dark());
    }

    /**
     * 车机醒来、安卓亮了，屏幕亮着但车没通电（点火 ACC）：车机醒着、屏幕黑着（E2'），不是亮屏；之后车通电了才亮屏。
     * 醒来读到 ON 是亮屏（早上回到车上）。
     */
    @Test
    public void aWakeWithTheCarNotPoweredStaysDark() {
        Run acc = new Run(false).car(true, ACC, 0).androidOff();
        acc.edges.clear();
        acc.androidOn(false, ACC);
        assertEquals(Arrays.asList(WOKE), acc.edges);
        assertTrue(acc.s.dark());
        acc.car(false, ON, 700_000);
        assertEquals(Arrays.asList(WOKE, LIT), acc.edges);
        Run on = new Run(false).car(true, ACC, 0).androidOff();
        on.edges.clear();
        on.androidOn(false, ON);
        assertEquals(Arrays.asList(LIT), on.edges);
    }

    /**
     * 黑着，事件说亮、点火读不到（车通没通电不知道）：说不上来 —— 照上一次的「黑」算两次对账，过了只看安卓（安卓亮着：亮屏）。
     * 之后点火读回来还是 ACC：没有车辆判断时亮要有证据，又是一条熄屏（起点不明）。
     */
    @Test
    public void aLitScreenWithUnreadableCarPowerIsHeldThenAndroidOnly() {
        long keep = ScreenTransitions.KEEP_UNKNOWN_MS;
        Run r = new Run(false).car(true, ACC, 0);
        r.edges.clear();
        r.car(false, null, 1_000);
        assertEquals(1_000, r.s.unknownSinceMs);
        assertTrue("时限之内照上一次算", r.s.dark());
        r.car(false, null, 1_000 + keep - 1);
        assertTrue(r.edges.isEmpty());
        r.car(false, null, 1_000 + keep);
        assertEquals("时限到了：只看安卓", Arrays.asList(LIT), r.edges);
        assertNull(r.s.vehicle);
        r.car(false, ACC, 20_000);
        assertEquals(Arrays.asList(LIT, DARK), r.edges);
    }

    /**
     * 人在车里、一直没黑过（车辆判过亮）：点火掉到 ACC、事件还是 0，照两项「且」还是亮着；事件变 1 才熄屏（和以前一样）。
     * 车辆接口断了再连上的那一下没有车辆判断，亮要有证据：ACC 就是黑。
     */
    @Test
    public void aVehicleLitStateAtAccStaysLit() {
        Run r = new Run(false).car(false, ON, 0).car(false, ACC, 1_000);
        assertTrue(r.edges.isEmpty());
        assertEquals(Boolean.FALSE, r.s.vehicle);
        r.car(true, ACC, 2_000);
        assertEquals(Arrays.asList(DARK), r.edges);
        Run relink = new Run(false).car(false, ON, 0).car(false, ACC, 1_000).linkDown(2_000).car(false, ACC, 3_000);
        assertEquals(Arrays.asList(DARK), relink.edges);
    }

    /**
     * 等车辆接口的那一段什么时候有了着落（进程刚起来；安卓刚亮、连不上的先再连了一次，审查 rev3 F1）：读到判断就算；
     * 还在连、核心还没登记不算；连不上、这台车机没有就只看安卓；连上了的那一下读数还是空的（连上的通知比读数早约 40 ms），
     * 等发布出有值的那一份 —— 不然醒来那一下又只看安卓判成亮屏，连上以后再黑回去。
     */
    @Test
    public void waitingForTheLinkEndsOnTheFirstVerdictOrAResult() {
        assertTrue(ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, true, 2));
        assertTrue(ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, false, 1));
        assertFalse("在连", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTING, null, 0));
        assertFalse("核心还没登记", ScreenTransitions.linkSettled(null, null, 0));
        assertTrue("连不上：只看安卓", ScreenTransitions.linkSettled(Telemetry.CarLink.FAILED, null, 0));
        assertTrue("这台车机没有：只看安卓", ScreenTransitions.linkSettled(Telemetry.CarLink.UNAVAILABLE, null, 0));
        assertFalse("连上的通知先到、读数还是空的", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, null, 0));
        assertTrue("有值的读数到了，两项都读不到：只看安卓", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, null, 3));
    }
}
