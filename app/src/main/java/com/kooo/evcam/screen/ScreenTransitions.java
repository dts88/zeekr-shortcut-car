/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

import com.kooo.evcam.telemetry.Telemetry;

/**
 * 熄屏判断的状态和它的几条边：输入是安卓（熄屏广播 / {@code isInteractive}）、车辆读数（{@link VehicleScreen}）和有人的证据，
 * 输出是跨过了哪一条边。纯函数，{@code ScreenTransitionsTest} 直接走。{@link ScreenState} 照它改状态、照边按固定顺序做事 ——
 * 状态只在那里写。
 *
 * <h3>亮 = 人在车上，要有证据（项目所有者 2026-11-21）</h3>
 *
 * <p>熄屏（{@link State#dark}）= 人不在车上。应用装在容器里，再出问题就装不回来，所以反过来判：<b>不知道就是没人</b>。
 * 黑着时只有人的动作能结束黑（{@link VehicleScreen#becomesLit}）：</p>
 *
 * <ul>
 *   <li>车通电了（点火 ON、DRIVING），而且刚拉开过车门、或者刚碰过应用（{@link #EVIDENCE_FRESH_MS} 之内）；</li>
 *   <li>车辆读不到（接口没连着、点火读不到）：只认刚碰过应用；</li>
 *   <li>车没通电（点火 ACC 或更低）：黑，屏幕亮了也不算（远程开空调、远程看车）。</li>
 * </ul>
 *
 * <p>车自己通电、车自己醒来、进程被拉起来、车辆接口连不上，都不是人的动作：还是黑。进程起来时一律从黑起（{@link #initial}）。</p>
 *
 * <p>亮着时（人在车上）要有黑的证据才黑（{@link VehicleScreen#whileLit}：屏幕电源事件说熄、点火低），或者安卓熄屏了。
 * 亮着时车辆一时读不到，照亮着算，最多 {@link #LOST_MAX_MS}（从读不到起，人碰过应用就从那时重新算）—— 行驶中车机服务抖一下
 * 不能把录像停了；过了还读不到，按人不在算（哨兵模式开着时安卓一直亮着，不然会一直拿着相机）。</p>
 *
 * <p>这台车机根本没有车辆接口（{@link Telemetry.CarLink#UNAVAILABLE}，不是极氪 7X）：没有证据可看，只看安卓
 * （{@link State#vehicle} 是 null），和以前一样。</p>
 *
 * <h3>四条边</h3>
 *
 * <ul>
 *   <li>{@link Edge#DARK}（E1）：亮 → 黑。平常是车辆先说黑（车主下车、车门关上），安卓还亮着；</li>
 *   <li>{@link Edge#HEAD_UNIT_SLEEPS}（E2）：已经黑着，安卓熄屏 —— 在这台车上就是车机下电（哨兵模式没开时离车约 4–6 分钟后）；</li>
 *   <li>{@link Edge#WOKE_WHILE_DARK}（E2'）：黑着，安卓又亮了 —— 车机自己醒了一阵，或者人快到了、还没拉车门；</li>
 *   <li>{@link Edge#LIT}（E3）：黑 → 亮 —— 有了人的证据。</li>
 * </ul>
 */
final class ScreenTransitions {

    /** 有人的证据（拉开过车门、碰过应用）多久之内算数。 */
    static final long EVIDENCE_FRESH_MS = 60_000L;
    /** 亮着时车辆读不到，最多照亮着算这么久：车辆接口再连两次（每 {@link Telemetry#RETRY_MS} 一次）再加一分钟。 */
    static final long LOST_MAX_MS = 2 * Telemetry.RETRY_MS + 60_000L;

    /** 跨过的那条边。 */
    enum Edge {
        /** E1：亮 → 黑。 */
        DARK,
        /** E2：黑着，安卓熄屏（车机要睡）。 */
        HEAD_UNIT_SLEEPS,
        /** E2'：黑着，安卓又亮了（车机醒着，屏幕还算黑着）。 */
        WOKE_WHILE_DARK,
        /** E3：黑 → 亮。 */
        LIT
    }

    /** 判断用的状态。不可变。 */
    static final class State {
        /** 安卓熄屏了（熄屏广播、{@code !isInteractive}）。 */
        final boolean androidOff;
        /** TRUE 黑（人不在车上），FALSE 亮（人在车上），null 这台车机没有车辆接口、只看安卓。 */
        final Boolean vehicle;
        /** 亮着时车辆读不到是从什么时候起的（elapsedRealtime）；0 = 没有。 */
        final long unknownSinceMs;

        State(boolean androidOff, Boolean vehicle, long unknownSinceMs) {
            this.androidOff = androidOff;
            this.vehicle = vehicle;
            this.unknownSinceMs = unknownSinceMs;
        }

        /** 熄屏（人不在车上）：安卓熄屏，或者没有人在车上的证据。 */
        boolean dark() {
            return androidOff || Boolean.TRUE.equals(vehicle);
        }

        @Override
        public String toString() {
            return "androidOff=" + androidOff + " vehicle=" + vehicle + " unknownSince=" + unknownSinceMs;
        }
    }

    private ScreenTransitions() {
    }

    /** 黑着、而且这台车机有车辆接口：亮要有人的证据（诊断报告里写）。 */
    static boolean litNeedsEvidence(State s) {
        return s.vehicle != null && s.dark();
    }

    /** 进程起来时：一律从黑起，等证据（没有车辆接口的车机，接口的结果一到就改成只看安卓，{@link #verdict}）。 */
    static State initial(boolean androidOff) {
        return new State(androidOff, Boolean.TRUE, 0);
    }

    /**
     * 照此刻的读数和证据判一次（读数变了、车辆接口的结果变了、人碰了应用、时限到了再看一次）。
     *
     * @param link      车辆接口此刻的结果（null = 核心还没登记）
     * @param event     屏幕电源事件（TRUE = 熄，FALSE = 亮，null 读不到）；接口没连着时不看
     * @param ignition  点火的原值，null 读不到；接口没连着时不看
     * @param door      刚拉开过车门（四扇侧门有一扇读到开着，{@link #EVIDENCE_FRESH_MS} 之内）
     * @param touch     刚碰过应用（同上）
     * @param touchAtMs 人最后一次碰应用的时刻（elapsedRealtime），0 = 没有
     * @param now       此刻（elapsedRealtime）
     */
    static State verdict(State s, Telemetry.CarLink link, Boolean event, Integer ignition, boolean door, boolean touch,
                         long touchAtMs, long now) {
        if (link == Telemetry.CarLink.UNAVAILABLE) {
            // 这台车机没有车辆接口：只看安卓
            return new State(s.androidOff, null, 0);
        }
        boolean connected = link == Telemetry.CarLink.CONNECTED;
        Boolean e = connected ? event : null;
        Integer i = connected ? ignition : null;
        if (s.androidOff) {
            // 安卓熄屏着：不算亮，醒来再凭证据
            return new State(true, Boolean.TRUE, 0);
        }
        Boolean plain = VehicleScreen.whileLit(e, i);
        if (Boolean.FALSE.equals(s.vehicle)) {
            // 亮着（人在车上）：有黑的证据才黑
            if (plain != null) {
                return new State(false, plain, 0);
            }
            long since = s.unknownSinceMs != 0 ? s.unknownSinceMs : now;
            if (now - Math.max(since, touchAtMs) >= LOST_MAX_MS) {
                return new State(false, Boolean.TRUE, 0);
            }
            return new State(false, Boolean.FALSE, since);
        }
        // 黑着：有人的证据才亮。凭碰过应用亮起来而车辆读不到的，从此刻起算读不到的时限
        if (!VehicleScreen.becomesLit(i, door, touch)) {
            return new State(false, Boolean.TRUE, 0);
        }
        return new State(false, Boolean.FALSE, plain == null ? now : 0);
    }

    /** 安卓熄屏了：人在不在都不算亮了，醒来再凭证据。 */
    static State androidOff(State s) {
        return new State(true, s.vehicle == null ? null : Boolean.TRUE, 0);
    }

    /**
     * 安卓亮了：只是安卓那一半变了，黑着的还黑着（没有车辆接口的车机就此算亮）。调用方接着拿醒来后读到的那一遍判一次
     * （{@link #verdict}）—— 有人的证据才亮。
     */
    static State androidOn(State s) {
        return new State(false, s.vehicle, 0);
    }

    /**
     * 等车辆接口的那一段有了着落（{@code ScreenState}：进程刚起来、安卓亮着；安卓刚亮、接口在连）：这一批读数里屏幕、点火
     * 读到了一项，或者接口有了结果 —— 连不上、这台车机没有，或者连上了、发布出了有值的读数（连上的通知比那一遍读数早约 40 ms，
     * 那一下读数还是空的，要等读数）。还在连、核心还没登记都不算。
     *
     * @param link       车辆接口此刻的结果（{@code Telemetry.carLink}；null = 核心还没登记）
     * @param fresh      这一批读数判出来的（{@link VehicleScreen#whileLit}；没连着时 null）
     * @param knownCount 这一批读数里有值的信号有几个
     */
    static boolean linkSettled(Telemetry.CarLink link, Boolean fresh, int knownCount) {
        if (fresh != null) {
            return true;
        }
        if (link == null || link == Telemetry.CarLink.CONNECTING) {
            return false;
        }
        return link != Telemetry.CarLink.CONNECTED || knownCount > 0;
    }

    /** 从 {@code before} 到 {@code after} 跨过了哪一条边；没跨是 null。 */
    static Edge edge(State before, State after) {
        boolean wasDark = before.dark();
        boolean isDark = after.dark();
        if (!wasDark) {
            return isDark ? Edge.DARK : null;
        }
        if (!isDark) {
            return Edge.LIT;
        }
        if (!before.androidOff && after.androidOff) {
            return Edge.HEAD_UNIT_SLEEPS;
        }
        if (before.androidOff && !after.androidOff) {
            return Edge.WOKE_WHILE_DARK;
        }
        return null;
    }
}
