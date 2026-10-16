package com.kooo.evcam.zeekr;

/**
 * 盲区画面的触发：此刻窗口该不该挂着（备着）、该不该显示、显示哪一侧，以及到什么时候再算一次。
 *
 * <h3>规则（项目所有者 2026-10-16）</h3>
 *
 * <ul>
 *   <li><b>显示</b>：照 {@link SideViewDecision} —— 左灯左侧、右灯右侧，原厂画面优先，低于最低车速不显示。
 *       灯灭后再留 {@link #HOLD_AFTER_SIGNAL_MS} 才收；这期间同一侧又打灯就接着显示，换一侧就换过去。</li>
 *   <li><b>备着</b>（预备方式 B）：挂 D 挡、原厂画面不在时窗口一直挂着、隐藏，画面留在环视的会话里，打灯只是显示出来；
 *       离开 D 挡 {@link #READY_GRACE_MS} 后才不再备着（等红灯挂 N、P 不至于来回挂、摘）。显示着的一定挂着。</li>
 *   <li><b>立刻全收</b>：熄屏、原厂画面出现（倒挡、原厂 360、原厂侧方小窗、泊车辅助）、相机层拒绝了盲区画面 ——
 *       不显示、不备着，30 秒也不等：原厂要用环视。</li>
 *   <li><b>关闭按钮</b>：关掉这一次打灯（{@link SideViewDismissal}），窗口照规则回到备着或者移走。</li>
 * </ul>
 *
 * <p>以后找到了盲区报警灯的信号，也接在这里（框架第 10 条），不另起一套。</p>
 *
 * <p>纯 Java，时间由调用方给（单调时钟），见 {@code SideViewTriggerTest}。</p>
 */
final class SideViewTrigger {

    /** 灯灭后再留多久（项目所有者 2026-10-16：事件结束后约 1 秒消失）。 */
    static final long HOLD_AFTER_SIGNAL_MS = 1000L;
    /** 离开 D 挡后再备多久（项目所有者 2026-10-16：离开 D 挡 30 秒后摘掉）。 */
    static final long READY_GRACE_MS = 30_000L;

    /** 一次判定的结果。 */
    static final class Result {
        /** 窗口该挂着：登记 SIDE_VIEW、声明画布。 */
        final boolean attached;
        /** 该显示出来。 */
        final boolean shown;
        /** 显示哪一侧；不显示时是窗口停着的那一侧（上一次显示的）。 */
        final int lane;
        /** 到这个时刻（单调时钟）再算一次：灯灭后的保留、离开 D 挡后的 30 秒到点；不用是 0。 */
        final long wakeAt;

        Result(boolean attached, boolean shown, int lane, long wakeAt) {
            this.attached = attached;
            this.shown = shown;
            this.lane = lane;
            this.wakeAt = wakeAt;
        }
    }

    private SideViewDismissal dismissal = new SideViewDismissal();
    /** 显示着的那一侧，没显示是 {@link SideViewDecision#NONE}。 */
    private int showing = SideViewDecision.NONE;
    private int lastLane = LaneCycle.LEFT;
    /** 灯灭后的保留到什么时候；没在保留是 0。 */
    private long holdUntil;
    /** 上一次判定时在 D 挡（备着的条件成立）。 */
    private boolean driving;
    /** 离开 D 挡后的备着到什么时候（从离开那一刻起算）；0 = 没在等。 */
    private long readyUntil;

    /**
     * @param in      这一刻的读数；{@code showing} 由这里填
     * @param blocked 熄屏、或者相机层拒绝了盲区画面：什么都不挂
     */
    Result update(long now, SideViewDecision.Input in, boolean blocked) {
        if (blocked || SideViewDecision.factoryViewActive(in)) {
            showing = SideViewDecision.NONE;
            holdUntil = 0;
            driving = false;
            readyUntil = 0;
            return new Result(false, false, lastLane, 0);
        }
        in.showing = showing;
        boolean suppressed = dismissal.suppress(in.turnSignal, in.hazard);
        int want = suppressed ? SideViewDecision.NONE : SideViewDecision.decide(in);
        if (want != SideViewDecision.NONE) {
            showing = want;
            lastLane = want;
            holdUntil = 0;
        } else if (showing != SideViewDecision.NONE) {
            if (holdUntil == 0) {
                holdUntil = now + HOLD_AFTER_SIGNAL_MS;
            }
            if (now >= holdUntil) {
                showing = SideViewDecision.NONE;
                holdUntil = 0;
            }
        }
        boolean drivingNow = SideViewDecision.shouldStayReady(in);
        if (drivingNow) {
            readyUntil = 0;
        } else if (driving) {
            // 刚离开 D 挡：从这一刻起算（读数没变化时不会来判定，不能从上一次判定起算）
            readyUntil = now + READY_GRACE_MS;
        } else if (readyUntil != 0 && now >= readyUntil) {
            readyUntil = 0;
        }
        driving = drivingNow;
        boolean shown = showing != SideViewDecision.NONE;
        boolean attached = shown || drivingNow || readyUntil != 0;
        long wakeAt = holdUntil;
        if (readyUntil != 0 && (wakeAt == 0 || readyUntil < wakeAt)) {
            wakeAt = readyUntil;
        }
        return new Result(attached, shown, shown ? showing : lastLane, wakeAt);
    }

    /** 点了关闭按钮：关掉这一次打灯（显示着的那一侧）。下一次 {@link #update} 照规则回到备着或者移走。 */
    void dismiss() {
        if (showing != SideViewDecision.NONE) {
            dismissal.dismiss(showing == LaneCycle.LEFT ? 1 : 2);
        }
        showing = SideViewDecision.NONE;
        holdUntil = 0;
    }

    /** 熄屏、服务退出：从头来（关掉的那一次也不记了）。 */
    void reset() {
        showing = SideViewDecision.NONE;
        holdUntil = 0;
        driving = false;
        readyUntil = 0;
        dismissal = new SideViewDismissal();
    }
}
