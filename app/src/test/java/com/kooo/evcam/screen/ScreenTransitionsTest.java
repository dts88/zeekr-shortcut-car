/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

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
import java.util.Collections;
import java.util.List;

/**
 * 熄屏判断的几条边。亮 = 人在车上，要有证据（项目所有者 2026-11-21）：黑着时车通电了、而且刚拉开过车门或刚碰过应用才亮；
 * 车辆读不到只认碰过应用；车自己通电、车自己醒来、读不到、连不上都还是黑；进程起来一律从黑起。亮着时有黑的证据才黑
 * （10-12 车上那几段：黑匣子 diag_18 20:04–20:57，diag_17 01:40 夜里醒来）。
 */
public class ScreenTransitionsTest {

    private static final Integer ACC = Signal.IGNITION_ACC;
    private static final Integer ON = Signal.IGNITION_ON;
    private static final Integer AFTER_ACC = 0x00200102;
    private static final String DARK = "DARK";
    private static final String SLEEPS = "HEAD_UNIT_SLEEPS";
    private static final String WOKE = "WOKE_WHILE_DARK";
    private static final String LIT = "LIT";
    private static final Telemetry.CarLink CONNECTED = Telemetry.CarLink.CONNECTED;
    private static final long FRESH = ScreenTransitions.EVIDENCE_FRESH_MS;
    private static final long LOST = ScreenTransitions.LOST_MAX_MS;

    /** 一段输入，记下跨过的边。时间是 elapsedRealtime（毫秒）。证据照 ScreenState 的记法（同一个 PersonEvidence）：先记这一批、再判。 */
    private static final class Run {
        ScreenTransitions.State s;
        final List<String> edges = new ArrayList<>();
        final PersonEvidence evidence = new PersonEvidence();
        /** 上一批读数里有车门开着（熄屏那一下照它记；车辆接口没连着是 false）。 */
        boolean doorOpen;

        Run(boolean androidOff) {
            s = ScreenTransitions.initial(androidOff);
        }

        /** 进程起来、人已经在车上亮着的那种起点（测亮着以后的事）：拉开车门、车通电。 */
        static Run lit() {
            Run r = new Run(false).door(false, ON, 1);
            r.edges.clear();
            return r;
        }

        /** 车辆接口连着，读到这两项，车门都关着。 */
        Run car(Boolean event, Integer ignition, long now) {
            return doors(false, now).judge(CONNECTED, event, ignition, now);
        }

        /** 车辆接口连着，读到这两项，这一批里有一扇车门开着。 */
        Run door(Boolean event, Integer ignition, long now) {
            return doors(true, now).judge(CONNECTED, event, ignition, now);
        }

        /** 人碰了应用，照此刻的读数再判一次（车门照上一批）。 */
        Run touch(Telemetry.CarLink link, Boolean event, Integer ignition, long now) {
            evidence.touched(now);
            return judge(link, event, ignition, now);
        }

        /** 车辆接口连着，两项都读不到（或者时限到了再看一次），车门也读不到。 */
        Run unreadable(long now) {
            return doors(false, now).judge(CONNECTED, null, null, now);
        }

        /** 车辆接口连着的一批读数里车门开没开着。 */
        Run doors(boolean open, long now) {
            doorOpen = open;
            evidence.doors(open, now);
            return this;
        }

        /** 车辆接口没连着（断了、在连、连不上）。 */
        Run linkDown(long now) {
            return judge(Telemetry.CarLink.FAILED, null, null, now);
        }

        /** 这台车机没有车辆接口。 */
        Run noInterface(long now) {
            return judge(Telemetry.CarLink.UNAVAILABLE, null, null, now);
        }

        Run androidOff() {
            return to(ScreenTransitions.androidOff(s));
        }

        /** 安卓亮了，醒来那一遍读到这两项（读不到、没读回来都是 null），车门关着。 */
        Run androidOn(Boolean event, Integer ignition, long now) {
            return to(ScreenTransitions.verdict(ScreenTransitions.androidOn(s), CONNECTED, event, ignition,
                    evidence.door(now), evidence.touch(now), evidence.touchAtMs(), now));
        }

        Run androidOnNoInterface(long now) {
            return to(ScreenTransitions.verdict(ScreenTransitions.androidOn(s), Telemetry.CarLink.UNAVAILABLE, null, null,
                    false, false, 0, now));
        }

        private Run judge(Telemetry.CarLink link, Boolean event, Integer ignition, long now) {
            if (link != CONNECTED) {
                doorOpen = false;
            }
            return to(ScreenTransitions.verdict(s, link, event, ignition, evidence.door(now), evidence.touch(now),
                    evidence.touchAtMs(), now));
        }

        private Run to(ScreenTransitions.State next) {
            ScreenTransitions.Edge edge = ScreenTransitions.edge(s, next);
            if (edge != null) {
                edges.add(edge.name());
            }
            if (edge == ScreenTransitions.Edge.DARK) {
                evidence.wentDark(doorOpen);
            }
            s = next;
            return this;
        }
    }

    /**
     * 10-12 哨兵模式没开离车（diag_18）：车门关上就黑（事件 0 → 1、点火 ON → ACC 同一毫秒）；4 秒后车门又开，点火先回 ON
     * （事件晚 0.1 秒才回 0）；又黑；点火 ACC → 0x00200102（不是边）；安卓熄屏 = 车机要睡；早上人快到了车机先醒（没有证据：
     * 车机醒着、屏幕黑着），拉开车门、车通电才是亮屏。
     */
    @Test
    public void sentryOffLeaveAndMorningReturn() {
        Run r = new Run(false)
                .door(false, ON, 1_000)
                .car(true, ACC, 10_998)
                .door(true, ON, 15_221)
                .car(false, ON, 15_325)
                .car(true, ACC, 46_709)
                .car(true, AFTER_ACC, 365_976)
                .androidOff()
                .androidOn(true, AFTER_ACC, 40_000_000)
                .door(true, ON, 40_014_000)
                .car(false, ON, 40_014_100);
        assertEquals(Arrays.asList(LIT, DARK, LIT, DARK, SLEEPS, WOKE, LIT), r.edges);
        assertFalse(r.s.dark());
    }

    /** 10-12 哨兵模式开着离车 20 分钟：安卓一直没熄屏，只有车辆说黑、拉开车门（点火先回 ON）才亮。 */
    @Test
    public void sentryOnParkTwentyMinutes() {
        Run r = Run.lit()
                .car(true, ACC, 1_000)
                .door(true, ON, 1_000 + 1_226_140)
                .car(false, ON, 1_000 + 1_226_177);
        assertEquals(Arrays.asList(DARK, LIT), r.edges);
    }

    /** 车自己通电（没人拉车门、没人碰应用）：还是黑。之后有人拉开车门才亮。 */
    @Test
    public void theCarPoweringOnByItselfStaysDark() {
        Run r = Run.lit().car(true, ACC, 1_000);
        r.edges.clear();
        r.car(true, ON, 500_000).car(false, ON, 500_100).car(false, Signal.IGNITION_DRIVING, 560_000);
        assertTrue("车通电了、屏幕也亮了，没有人的证据：黑", r.s.dark());
        assertTrue(r.edges.isEmpty());
        r.door(false, ON, 600_000);
        assertEquals(Arrays.asList(LIT), r.edges);
    }

    /** 拉开了车门、车没通电：还是黑；一分钟之内车通电了才亮，过了一分钟不算。 */
    @Test
    public void anOpenDoorNeedsThePowerSoonAfter() {
        Run soon = Run.lit().car(true, ACC, 1_000);
        soon.edges.clear();
        soon.door(true, ACC, 100_000);
        assertTrue("车门开了、车没通电：黑", soon.s.dark());
        soon.car(true, ON, 100_000 + FRESH);
        assertEquals(Arrays.asList(LIT), soon.edges);

        Run late = Run.lit().car(true, ACC, 1_000);
        late.edges.clear();
        late.door(true, ACC, 100_000).car(true, ON, 100_000 + FRESH + 1);
        assertTrue("车门是一分多钟以前开的：不算", late.s.dark());
        assertTrue(late.edges.isEmpty());
    }

    /** 下车时开过的车门不算回来的证据：熄屏那一下证据清掉，再亮要再拉一次车门。 */
    @Test
    public void theDoorOpenedToLeaveDoesNotCountForTheReturn() {
        Run r = Run.lit().door(false, ON, 5_000).car(true, ACC, 8_000);
        assertEquals(Arrays.asList(DARK), r.edges);
        r.car(true, ON, 20_000);
        assertTrue("下车 12 秒后车自己通电：黑", r.s.dark());
        assertEquals(Arrays.asList(DARK), r.edges);
    }

    /**
     * 熄屏那一下车门还读到开着（车门信号比屏幕、点火晚到，或者车门就那么开着）：这一扇不算，车自己通电还是黑；
     * 读到关上、再读到开着才算。
     */
    @Test
    public void aDoorStillOpenWhenItWentDarkDoesNotCount() {
        Run r = Run.lit().door(true, ACC, 8_000);
        assertEquals(Arrays.asList(DARK), r.edges);
        r.door(true, ON, 20_000);
        assertTrue("熄屏那一下就开着的车门：不算", r.s.dark());
        r.car(true, ACC, 30_000).door(true, ON, 40_000);
        assertEquals("关上再拉开：算", Arrays.asList(DARK, LIT), r.edges);
    }

    /** 点火读不到、屏幕电源事件说熄着：碰了应用也不算（照它亮了下一眼就又黑）；事件说亮着的，碰了就亮，亮了不来回。 */
    @Test
    public void aTouchDoesNotCountWhileTheCarSaysTheScreenIsOff() {
        Run off = Run.lit().car(true, ACC, 0);
        off.edges.clear();
        off.touch(CONNECTED, true, null, 100_000);
        assertTrue(off.s.dark());
        off.touch(CONNECTED, true, 0x00200106, 101_000);
        assertTrue("没见过的码当读不到，事件说熄着：不算", off.edges.isEmpty());

        Run on = Run.lit().car(true, ACC, 0);
        on.edges.clear();
        on.touch(CONNECTED, false, null, 100_000);
        assertEquals(Arrays.asList(LIT), on.edges);
        on.car(false, null, 101_000);
        assertEquals("亮了以后不来回", Arrays.asList(LIT), on.edges);
    }

    /** 夜里车机自己醒：安卓亮了，没有人的证据 —— 车机醒着、屏幕黑着；两分钟后安卓又熄屏 = 车机又要睡。 */
    @Test
    public void nightWakeStaysDark() {
        Run r = Run.lit().car(true, ACC, 0).androidOff();
        r.edges.clear();
        r.androidOn(true, AFTER_ACC, 7_500_000);
        assertEquals(Arrays.asList(WOKE), r.edges);
        assertTrue("还算熄屏", r.s.dark());
        assertFalse(r.s.androidOff);
        r.androidOff();
        assertEquals(Arrays.asList(WOKE, SLEEPS), r.edges);
    }

    /** 安卓刚亮、醒来那一遍读不到（或者等满了没读回来）：还是黑，不再只看安卓判成亮屏。 */
    @Test
    public void anUnreadableWakeReadStaysDark() {
        Run r = Run.lit().car(true, ACC, 0).androidOff();
        r.edges.clear();
        r.androidOn(null, null, 7_500_000);
        assertEquals(Arrays.asList(WOKE), r.edges);
        assertTrue(r.s.dark());
        assertEquals(Boolean.TRUE, r.s.vehicle);
    }

    /** 醒来那一遍读到车通电了、车门没开过：还是黑（车自己醒来、自己通电）。醒来时车门正开着、车通电了：亮屏。 */
    @Test
    public void aWakeNeedsTheSameEvidence() {
        Run alone = Run.lit().car(true, ACC, 0).androidOff();
        alone.edges.clear();
        alone.androidOn(false, ON, 7_500_000);
        assertEquals(Arrays.asList(WOKE), alone.edges);

        Run person = Run.lit().car(true, ACC, 0).androidOff();
        person.edges.clear();
        person.doors(true, 7_499_000);
        person.androidOn(false, ON, 7_500_000);
        assertEquals(Arrays.asList(LIT), person.edges);
    }

    /** 黑着时车辆接口断了、两项读不到：一直黑，没有边（以前过了 6 秒只看安卓，哨兵模式开着时就判成亮屏）。 */
    @Test
    public void aLostLinkWhileDarkStaysDark() {
        Run awake = Run.lit().car(true, ACC, 0);
        awake.edges.clear();
        awake.linkDown(1_000).unreadable(10_000).linkDown(10_000_000);
        assertTrue(awake.s.dark());
        assertTrue(awake.edges.isEmpty());
        assertEquals(0, awake.s.unknownSinceMs);
    }

    /**
     * 亮着时（人在车上）车辆读不到：照亮着算，读回来了时限作废；一直读不到，满了时限按人不在算。
     * 行驶中车机服务抖一下不能把录像停了；哨兵模式开着时也不能一直拿着相机。
     */
    @Test
    public void unreadableWhileLitIsKeptThenCountsAsGone() {
        Run r = Run.lit();
        r.unreadable(1_000);
        assertEquals(1_000, r.s.unknownSinceMs);
        r.linkDown(1_000 + LOST - 1);
        assertFalse("时限之内照亮着算", r.s.dark());
        r.car(false, ON, 1_000 + LOST);
        assertEquals("读回来了：时限作废", 0, r.s.unknownSinceMs);
        r.linkDown(2_000_000).linkDown(2_000_000 + LOST);
        assertEquals("满了时限：按人不在算", Arrays.asList(DARK), r.edges);
        assertEquals(Boolean.TRUE, r.s.vehicle);
    }

    /** 读不到的那一段里人碰过应用：时限从那一下重新算。 */
    @Test
    public void aTouchRestartsTheUnreadableLimit() {
        Run r = Run.lit();
        r.linkDown(1_000);
        r.touch(Telemetry.CarLink.FAILED, null, null, 1_000 + LOST - 10);
        r.linkDown(1_000 + LOST + 5);
        assertFalse(r.s.dark());
        r.linkDown(1_000 + LOST - 10 + LOST);
        assertEquals(Arrays.asList(DARK), r.edges);
    }

    /**
     * 人碰了应用（项目所有者 2026-11-21）：车通电了就亮（没赶上车门那一下）；车辆读不到也亮，从那一刻起算读不到的时限；
     * 车没通电还是黑（2026-10-27：人在车里、车没通电时点亮屏幕照黑着算）。
     */
    @Test
    public void aTouchIsEvidenceUnlessTheCarIsUnpowered() {
        Run powered = Run.lit().car(true, ACC, 0);
        powered.edges.clear();
        powered.car(false, ON, 100_000).touch(CONNECTED, false, ON, 130_000);
        assertEquals(Arrays.asList(LIT), powered.edges);
        assertEquals(0, powered.s.unknownSinceMs);

        Run broken = new Run(false).linkDown(1_000);
        assertTrue(broken.s.dark());
        broken.touch(Telemetry.CarLink.FAILED, null, null, 5_000);
        assertEquals(Arrays.asList(LIT), broken.edges);
        assertEquals("车辆读不到：从亮的那一刻起算时限", 5_000, broken.s.unknownSinceMs);
        broken.linkDown(5_000 + LOST);
        assertEquals(Arrays.asList(LIT, DARK), broken.edges);

        Run unpowered = Run.lit().car(true, ACC, 0);
        unpowered.edges.clear();
        unpowered.touch(CONNECTED, false, ACC, 100_000);
        assertTrue(unpowered.s.dark());
        assertTrue(unpowered.edges.isEmpty());
    }

    /** 碰应用也只算一分钟：一分多钟以后车才通电，不算。 */
    @Test
    public void aTouchGoesStaleToo() {
        Run r = Run.lit().car(true, ACC, 0);
        r.edges.clear();
        r.touch(CONNECTED, true, ACC, 100_000).car(true, ON, 100_000 + FRESH + 1);
        assertTrue(r.s.dark());
    }

    /** 黑的那一下两项分两次到：先到的一项判不出黑（且），后到的那一下才黑 —— 还是一条熄屏边。亮的那一下点火先到就亮。 */
    @Test
    public void edgesSplitAcrossTwoPublishesGiveOneTransition() {
        Run eventFirst = Run.lit().car(true, ON, 10).car(true, ACC, 20);
        assertEquals(Arrays.asList(DARK), eventFirst.edges);
        Run ignitionFirst = Run.lit().car(false, ACC, 10).car(true, ACC, 20);
        assertEquals(Arrays.asList(DARK), ignitionFirst.edges);
        Run lit = Run.lit().car(true, ACC, 0).door(true, ON, 100).car(false, ON, 140);
        assertEquals(Arrays.asList(DARK, LIT), lit.edges);
    }

    /**
     * 进程起来时一律从黑起，不是边：安卓亮着、车通电着（人多半在车上）也要等证据 —— 分不出是人在开车时进程重启，还是车自己醒来
     * 时被拉起来。安卓熄屏着起来的同样。
     */
    @Test
    public void aProcessAlwaysStartsDark() {
        Run on = new Run(false);
        assertTrue(on.s.dark());
        assertEquals(Boolean.TRUE, on.s.vehicle);
        on.car(false, ON, 1_100).car(false, Signal.IGNITION_DRIVING, 5_000).car(false, ACC, 9_000);
        assertTrue(on.edges.isEmpty());
        assertTrue(on.s.dark());
        on.touch(CONNECTED, false, ON, 12_000);
        assertEquals(Arrays.asList(LIT), on.edges);

        Run asleep = new Run(true);
        assertTrue(asleep.s.dark());
        asleep.car(true, AFTER_ACC, 500).androidOn(true, AFTER_ACC, 600);
        assertEquals(Arrays.asList(WOKE), asleep.edges);
    }

    /** 安卓熄屏着时车辆读数怎么变都不是边；黑着时安卓熄屏是车机要睡，不是第二次熄屏。 */
    @Test
    public void onlyAndroidChangesMoveWithinTheDark() {
        Run r = new Run(true).door(false, ON, 0).car(true, ACC, 10).door(false, ON, 20);
        assertTrue(r.edges.isEmpty());
        Run v = Run.lit().car(true, ACC, 0).androidOff().androidOff();
        assertEquals(Arrays.asList(DARK, SLEEPS), v.edges);
    }

    /** 亮着时安卓熄屏（车辆没来得及说黑）：熄屏和车机要睡一起来；醒来不算人还在，要重新有证据。 */
    @Test
    public void androidOffEndsTheLitState() {
        Run r = Run.lit().androidOff();
        assertEquals(Arrays.asList(DARK), r.edges);
        assertTrue(r.s.androidOff);
        r.androidOn(false, ON, 9_000_000);
        assertEquals("睡之前亮着、醒来车通电着，没人拉车门：黑", Arrays.asList(DARK, WOKE), r.edges);
        assertTrue(r.s.dark());
    }

    /**
     * 10-24 15:43:58 远程开空调（或远程看车）：黑着，屏幕电源事件 1 → 0 亮了整 60 秒，点火一直 ACC，然后事件回到 1 ——
     * 车没通电，从头到尾都是黑，一条边都没有。
     */
    @Test
    public void aLitScreenWithTheCarNotPoweredStaysDark() {
        Run r = Run.lit().car(true, ACC, 1_000);
        r.edges.clear();
        r.car(false, ACC, 100_000);
        assertTrue("事件说亮、车没通电：还是黑", r.s.dark());
        assertEquals(Boolean.TRUE, r.s.vehicle);
        r.car(false, ACC, 130_000).car(true, ACC, 160_000);
        assertTrue(r.edges.isEmpty());
        assertTrue(r.s.dark());
    }

    /** 人在车里、一直没黑过：点火掉到 ACC、事件还是 0，照两项「且」还是亮着；事件变 1 才熄屏。 */
    @Test
    public void aLitStateAtAccStaysLit() {
        Run r = Run.lit().car(false, ACC, 1_000);
        assertTrue(r.edges.isEmpty());
        assertEquals(Boolean.FALSE, r.s.vehicle);
        r.car(true, ACC, 2_000);
        assertEquals(Arrays.asList(DARK), r.edges);
    }

    /**
     * 这台车机没有车辆接口（不是极氪 7X）：没有证据可看，只看安卓 —— 接口的结果一到就照安卓算，安卓熄屏是熄屏和车机要睡
     * 一起来，安卓亮就是亮屏（和以前一样）。
     */
    @Test
    public void withoutTheCarInterfaceItIsAndroidOnly() {
        Run r = new Run(false).noInterface(100);
        assertEquals(Arrays.asList(LIT), r.edges);
        assertNull(r.s.vehicle);
        r.androidOff();
        assertEquals(Arrays.asList(LIT, DARK), r.edges);
        assertNull(r.s.vehicle);
        r.androidOnNoInterface(5_000);
        assertEquals(Arrays.asList(LIT, DARK, LIT), r.edges);
        assertFalse(ScreenTransitions.litNeedsEvidence(r.s));
    }

    /** 亮要不要人的证据（诊断报告里写）：黑着、有车辆接口时要。 */
    @Test
    public void litNeedsEvidenceWhileDarkWithACarInterface() {
        assertTrue(ScreenTransitions.litNeedsEvidence(new Run(false).s));
        assertTrue(ScreenTransitions.litNeedsEvidence(new Run(true).s));
        assertFalse(ScreenTransitions.litNeedsEvidence(Run.lit().s));
        assertEquals(Collections.emptyList(), Run.lit().edges);
    }

    /**
     * 等车辆接口的那一段什么时候有了着落（进程刚起来；安卓刚亮、连不上的先再连了一次）：读到屏幕或点火就算；还在连、核心还没登记
     * 不算；连不上、这台车机没有也算有了结果；连上了的那一下读数还是空的（连上的通知比读数早约 40 ms），等发布出有值的那一份。
     */
    @Test
    public void waitingForTheLinkEndsOnTheFirstReadingOrAResult() {
        assertTrue(ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, true, 2));
        assertTrue(ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, false, 1));
        assertFalse("在连", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTING, null, 0));
        assertFalse("核心还没登记", ScreenTransitions.linkSettled(null, null, 0));
        assertTrue("连不上：有结果了", ScreenTransitions.linkSettled(Telemetry.CarLink.FAILED, null, 0));
        assertTrue("这台车机没有：有结果了", ScreenTransitions.linkSettled(Telemetry.CarLink.UNAVAILABLE, null, 0));
        assertFalse("连上的通知先到、读数还是空的", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, null, 0));
        assertTrue("有值的读数到了，两项都读不到", ScreenTransitions.linkSettled(Telemetry.CarLink.CONNECTED, null, 3));
    }
}
