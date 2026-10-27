package com.kooo.evcam.screen;

import com.kooo.evcam.telemetry.Telemetry;

/**
 * 熄屏判断的状态和它的几条边（设计 v3 §1.3–1.4、v3.1 C3）：输入是安卓（熄屏广播 / {@code isInteractive}）和车辆的判断
 * （{@link VehicleScreen}），输出是跨过了哪一条边。纯函数，{@code ScreenTransitionsTest} 拿 10-12 车上那几段直接走一遍。
 * {@link ScreenState} 照它改状态、照边按固定顺序做事 —— 状态只在那里写。
 *
 * <h3>熄屏 = 屏幕真黑了（项目所有者 2026-10-12 D1）</h3>
 *
 * <p>安卓熄屏，或者车辆说黑着（{@link State#dark}）。两样各管一件事：</p>
 *
 * <ul>
 *   <li>{@link Edge#DARK}（E1）：亮 → 黑。平常是车辆先说黑（车主下车、车门关上），安卓还亮着；车辆信号读不到时是安卓熄屏，
 *       那就是 E1 和 E2 一起来（降级，和 2.11.1 一样）；</li>
 *   <li>{@link Edge#HEAD_UNIT_SLEEPS}（E2）：已经黑着，安卓熄屏 —— 在这台车上就是车机下电（哨兵模式没开时离车约 4–6 分钟后）；</li>
 *   <li>{@link Edge#WOKE_WHILE_DARK}（E2'）：安卓又亮了，车辆还说黑着 —— 车机自己醒了一阵（10-12 01:40 那一次），还算熄屏；
 *       2026-10-27 起屏幕亮了、车没通电（点火 ACC 或更低）也是这一条；</li>
 *   <li>{@link Edge#LIT}（E3）：黑 → 亮 —— 车通电了（点火 ON、DRIVING：开车门；早上回到车上）。</li>
 * </ul>
 *
 * <h3>亮要有证据（项目所有者 2026-10-27）</h3>
 *
 * <p>黑着、或者还没有车辆的判断（进程刚起来、车辆接口刚连上、读不到的时限过了）时（{@link #litNeedsEvidence}），
 * 车辆的判断带着这段历史算（{@link VehicleScreen#verdict}）：车没通电（点火 ACC 或更低）就还是黑，屏幕电源事件说亮也不算 ——
 * 远程开空调、远程看车时屏幕亮了，主界面、超级镜子、座舱都不动，也没有边；点火不知道、事件说亮：说不上来，照下面那条
 * 照上一次的判断算，过了只看安卓。进程在 ACC、屏幕亮着时起来，读到的第一份就是黑（E1，起点不明）。人真回来全是车先通电再亮屏
 * （10-13 到 10-24 的 13 次），所以黑着时结束黑的是车通电了。只有车辆判过亮的那一段（人在车里，一直没黑过）点火掉到 ACC、
 * 事件还是亮，才照两项「且」算亮着。</p>
 *
 * <h3>车辆说不上来的时候（C3）</h3>
 *
 * <p>车辆接口连着、两项却一时读不到（占位值来回跳，比如车门「关 → ?」那种）：最多 {@link #KEEP_UNKNOWN_MS}（两次对账）
 * 照上一次的判断算，过了还读不到就丢掉、只看安卓（D1：都读不到只看安卓）—— 不然车机服务一坏，熄屏能一直黑到下一趟开车。
 * 接口没连着（在连、连不上、这台车没有）当场只看安卓。安卓刚亮的那一下只认醒来后读到的（{@link #androidOn}）：
 * 读不到就是读不到，不拿睡之前的「黑」顶上 —— 那会把每天早上的醒来当成车机自己醒了。</p>
 */
final class ScreenTransitions {

    /** 车辆接口连着、读不到时，照上一次的判断算多久：两次对账（{@link Telemetry#RECONCILE_MS}）。 */
    static final long KEEP_UNKNOWN_MS = 2 * Telemetry.RECONCILE_MS;

    /** 跨过的那条边。 */
    enum Edge {
        /** E1：亮 → 黑。 */
        DARK,
        /** E2：黑着，安卓熄屏（车机要睡）。 */
        HEAD_UNIT_SLEEPS,
        /** E2'：黑着，安卓又亮了（车机醒着，屏幕还黑着）。 */
        WOKE_WHILE_DARK,
        /** E3：黑 → 亮。 */
        LIT
    }

    /** 判断用的状态。不可变。 */
    static final class State {
        /** 安卓熄屏了（熄屏广播、{@code !isInteractive}）。 */
        final boolean androidOff;
        /** 用着的车辆判断：TRUE 黑，FALSE 亮，null 只看安卓。 */
        final Boolean vehicle;
        /** 车辆接口连着、两项读不到是从什么时候起的（elapsedRealtime）；0 = 没有。这期间 {@link #vehicle} 是读不到之前那一次的。 */
        final long unknownSinceMs;

        State(boolean androidOff, Boolean vehicle, long unknownSinceMs) {
            this.androidOff = androidOff;
            this.vehicle = vehicle;
            this.unknownSinceMs = unknownSinceMs;
        }

        /** 熄屏（屏幕真黑了）：安卓熄屏，或者车辆说黑。 */
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

    /**
     * 亮要有证据：黑着，或者还没有车辆的判断。这时车辆的判断按 {@link VehicleScreen#verdict} 带历史的那一半算 ——
     * 车没通电就是黑。
     */
    static boolean litNeedsEvidence(State s) {
        return s.dark() || s.vehicle == null;
    }

    /** 进程起来时：安卓此刻熄没熄屏，车辆还没读（读到之前只看安卓）。 */
    static State initial(boolean androidOff) {
        return new State(androidOff, null, 0);
    }

    /**
     * 车辆的判断来了（读数变了、车辆接口的结果变了、读不到的时限到了再看一次）。
     *
     * @param fresh     此刻读数判出来的（{@link VehicleScreen#verdict}，亮要不要证据照 {@code s}，{@link #litNeedsEvidence}）；
     *                  null = 说不上来
     * @param connected 车辆接口连着
     * @param now       此刻（elapsedRealtime）
     */
    static State verdict(State s, Boolean fresh, boolean connected, long now) {
        if (!connected) {
            return new State(s.androidOff, null, 0);
        }
        if (fresh != null) {
            return new State(s.androidOff, fresh, 0);
        }
        if (s.vehicle == null) {
            return new State(s.androidOff, null, 0);
        }
        if (s.unknownSinceMs == 0) {
            return new State(s.androidOff, s.vehicle, now);
        }
        if (now - s.unknownSinceMs >= KEEP_UNKNOWN_MS) {
            return new State(s.androidOff, null, 0);
        }
        return s;
    }

    /** 安卓熄屏了。 */
    static State androidOff(State s) {
        return new State(true, s.vehicle, s.unknownSinceMs);
    }

    /**
     * 安卓亮了，醒来那一遍车辆信号也读完了（或者等满了、车辆接口没连着）：只认这一遍读到的。
     *
     * @param fresh     醒来后读到的判断（安卓熄屏着就是黑着，亮要有证据：车没通电还是黑，E2'）；没读回来、读不到是 null（只看安卓）
     * @param connected 车辆接口连着
     */
    static State androidOn(State s, Boolean fresh, boolean connected) {
        return new State(false, connected ? fresh : null, 0);
    }

    /**
     * 等车辆接口的那一段有了着落（{@code ScreenState}：进程刚起来、安卓亮着；安卓刚亮、接口在连）：这一批读数判出了屏幕，
     * 或者接口有了结果 —— 连不上、这台车机没有（只看安卓），或者连上了、发布出了有值的读数（连上的通知比那一遍读数早约 40 ms，
     * 那一下读数还是空的，要等读数）。还在连、核心还没登记都不算。
     *
     * @param link       车辆接口此刻的结果（{@code Telemetry.carLink}；null = 核心还没登记）
     * @param fresh      这一批读数判出来的（{@link VehicleScreen#verdict}；没连着时 null）
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
