/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

/**
 * 盲区画面的触发：此刻窗口该不该挂着（备着）、左右两侧各该不该显示，以及到什么时候再算一次。
 *
 * <h3>规则（项目所有者 2026-10-16）</h3>
 *
 * <ul>
 *   <li><b>显示</b>：照 {@link SideViewDecision} —— 左灯左侧、右灯右侧，原厂画面优先，低于最低车速不显示；
 *       后视镜上的灯（盲区指示灯、开门预警，设置里开着的，2026-11-03）亮了也显示那一侧，不看车速。
 *       <b>左右各管各的</b>（项目所有者 2026-11-04：后视镜上的灯左右可能同时亮）：两侧可以同时显示，
 *       打着转向灯时另一侧的灯亮着也显示。</li>
 *   <li><b>收</b>：一侧不再要了，再留 {@link #HOLD_AFTER_SIGNAL_MS}，再用 {@link #FADE_MS} 从正常淡到看不见才收
 *       （项目所有者 2026-11-04；装饰动效不放时不淡，留够就收）；这期间那一侧又亮就接着显示（淡着的回到正常）。</li>
 *   <li><b>只能显示一侧时</b>（中控屏第二块画布让出来了，{@link Result#lane}）：先打着转向灯的那一侧，其次灯正亮着的、
 *       先亮起来的那一侧（同时亮先左边），最后才是在保留、淡出的那一侧 —— 灯一灭就换到另一侧亮着的，和以前一样。</li>
 *   <li><b>备着</b>（预备方式 B）：挂 D 挡、原厂画面不在时窗口一直挂着、隐藏，画面留在环视的会话里，打灯只是显示出来；
 *       离开 D 挡 {@link #READY_GRACE_MS} 后才不再备着（等红灯挂 N、P 不至于来回挂、摘）。显示着的一定挂着。</li>
 *   <li><b>立刻全收</b>：熄屏、原厂画面出现（倒挡、原厂 360、原厂侧方小窗、泊车辅助）—— 不显示、不备着，30 秒也不等：
 *       原厂要用环视。</li>
 *   <li>相机层这一回不带盲区画面的那一段不经这里，由 {@link SideViewService} 自己管（它的 {@code refused}），到这里说「不挂」为止：
 *       当成 {@code blocked} 传进来的话，下一次判定就说「不挂」、它就当这一段结束了，每来一次读数重挂一次、重建一次会话。</li>
 *   <li><b>关闭按钮</b>：关掉那一侧这一次（{@link SideViewDismissal}：打灯的那一次，和那一侧后视镜上的灯亮的那一次），
 *       另一侧不动；窗口照规则回到备着或者移走，不淡。</li>
 * </ul>
 *
 * <p>纯 Java，时间由调用方给（单调时钟），见 {@code SideViewTriggerTest}。</p>
 */
final class SideViewTrigger {

    /** 灯灭后再留多久（项目所有者 2026-10-16：事件结束后约 1 秒消失）。 */
    static final long HOLD_AFTER_SIGNAL_MS = 1000L;
    /**
     * 留够之后淡出多久（项目所有者 2026-11-04：在原来的效果后面加一个逐渐淡化，从正常到画面完全消失，1 秒）。
     * 淡的时候窗口照旧显示着；装饰动效不放时（{@link SideViewDecision.Input#fade}）不淡，留够就收，和以前一样。
     */
    static final long FADE_MS = 1000L;
    /** 离开 D 挡后再备多久（项目所有者 2026-10-16：离开 D 挡 30 秒后摘掉）。 */
    static final long READY_GRACE_MS = 30_000L;

    /** 为什么显示（黑匣子那一行照它写）。 */
    enum Cause {
        /** 打了转向灯。 */
        TURN,
        /** 后视镜上的盲区指示灯亮了。 */
        BLIND_SPOT,
        /** 停车后的开门预警亮了。 */
        DOOR_WARNING
    }

    /** 一侧此刻的样子。 */
    static final class Side {
        final int lane;
        /** 该显示（含不再要了之后的保留、淡出）。 */
        final boolean shown;
        /** 此刻为什么要它；不显示、在保留或淡出是 null。 */
        final Cause cause;
        /** 在淡出：还要淡多久（毫秒），窗口照旧显示着、淡完再收；0 = 没在淡出。 */
        final long fadeMs;

        Side(int lane, boolean shown, Cause cause, long fadeMs) {
            this.lane = lane;
            this.shown = shown;
            this.cause = cause;
            this.fadeMs = fadeMs;
        }
    }

    /** 一次判定的结果。 */
    static final class Result {
        /** 窗口该挂着：登记 SIDE_VIEW、声明画布。 */
        final boolean attached;
        final Side left;
        final Side right;
        /**
         * 只能显示一侧时显示哪一侧（打着转向灯的、灯正亮着先亮起来的、在保留淡出的，见类注释）；
         * 两侧都不显示时是窗口停着的那一侧（上一次显示的）。
         */
        final int lane;
        /** {@link #lane} 那一侧该显示（也就是至少有一侧该显示）。 */
        final boolean shown;
        /** {@link #lane} 那一侧为什么显示；不显示、在保留或淡出是 null。 */
        final Cause cause;
        /** {@link #lane} 那一侧在淡出：还要淡多久（毫秒）；0 = 没在淡出。 */
        final long fadeMs;
        /** 到这个时刻（单调时钟）再算一次：哪一侧灯灭后的保留、淡出，离开 D 挡后的 30 秒到点；不用是 0。 */
        final long wakeAt;
        /** 这一次判定时离开 D 挡后的 30 秒刚到点（黑匣子里写准为什么移走窗口）。 */
        final boolean graceEnded;

        Result(boolean attached, Side left, Side right, int lane, boolean shown, Cause cause, long fadeMs,
               long wakeAt, boolean graceEnded) {
            this.attached = attached;
            this.left = left;
            this.right = right;
            this.lane = lane;
            this.shown = shown;
            this.cause = cause;
            this.fadeMs = fadeMs;
            this.wakeAt = wakeAt;
            this.graceEnded = graceEnded;
        }

        Side side(int lane) {
            return lane == LaneCycle.RIGHT ? right : left;
        }
    }

    /** 一侧的账：显示着没有、从什么时候起、保留和淡出到什么时候。 */
    private static final class Track {
        final int lane;
        boolean showing;
        /** 这一次从什么时候显示起（只能显示一侧时排先后）；没显示是 0。 */
        long since;
        /** 不再要了之后的保留到什么时候；没在保留是 0。 */
        long holdUntil;
        /** 淡出到什么时候淡完、收起（从留够那一刻起算）；没在淡出是 0。 */
        long fadeUntil;
        /** 此刻为什么要它；不要了是 null。 */
        Cause cause;

        Track(int lane) {
            this.lane = lane;
        }

        /** 这一刻要不要它（{@code want} 是 null = 不要了）：要的话显示着、取消保留和淡出；不要了先留、再淡，到点收。 */
        void step(long now, Cause want, boolean fade) {
            if (want != null) {
                if (!showing) {
                    since = now;
                }
                showing = true;
                holdUntil = 0;
                fadeUntil = 0;
                cause = want;
                return;
            }
            cause = null;
            if (!showing) {
                return;
            }
            if (holdUntil == 0) {
                holdUntil = now + HOLD_AFTER_SIGNAL_MS;
            }
            if (now >= holdUntil && fadeUntil == 0) {
                // 留够了：淡出，从留够那一刻起算（判定来晚了也不往后拖）；装饰动效不放时不淡，当场收
                fadeUntil = fade ? holdUntil + FADE_MS : holdUntil;
            }
            if (fadeUntil != 0 && now >= fadeUntil) {
                clear();
            }
        }

        void clear() {
            showing = false;
            since = 0;
            holdUntil = 0;
            fadeUntil = 0;
            cause = null;
        }

        /** 下一次要来算的时刻：在淡出是淡完那一刻，在保留是留够那一刻；不用是 0。 */
        long wakeAt() {
            return !showing ? 0 : fadeUntil != 0 ? fadeUntil : holdUntil;
        }

        Side snapshot(long now) {
            return new Side(lane, showing, cause, showing && fadeUntil != 0 ? fadeUntil - now : 0);
        }
    }

    private SideViewDismissal dismissal = new SideViewDismissal();
    private final Track left = new Track(LaneCycle.LEFT);
    private final Track right = new Track(LaneCycle.RIGHT);
    private int lastLane = LaneCycle.LEFT;
    /** 上一次判定时在 D 挡（备着的条件成立）。 */
    private boolean driving;
    /** 离开 D 挡后的备着到什么时候（从离开那一刻起算）；0 = 没在等。 */
    private long readyUntil;
    /** 上一次判定时两侧后视镜上的灯亮没亮（设置开着的那几样）：点关闭时照它关掉灯亮的那一次。 */
    private boolean leftLit;
    private boolean rightLit;

    /**
     * @param in      这一刻的读数；{@code showing} 由这里填
     * @param blocked 熄屏：什么都不挂
     */
    Result update(long now, SideViewDecision.Input in, boolean blocked) {
        if (blocked || SideViewDecision.factoryViewActive(in)) {
            left.clear();
            right.clear();
            driving = false;
            readyUntil = 0;
            return result(now, false, false, false);
        }
        // 转向灯那一侧已经显示着就不再看车速（SideViewDecision.turnLane 照 in.showing 看）
        int signalled = in.turnSignal == null ? SideViewDecision.NONE
                : in.turnSignal == 1 ? LaneCycle.LEFT : in.turnSignal == 2 ? LaneCycle.RIGHT : SideViewDecision.NONE;
        in.showing = signalled != SideViewDecision.NONE && track(signalled).showing ? signalled : SideViewDecision.NONE;
        boolean turnSuppressed = dismissal.suppress(in.turnSignal, in.hazard);
        int turn = turnSuppressed ? SideViewDecision.NONE : SideViewDecision.turnLane(in);
        leftLit = SideViewDecision.lit(in, LaneCycle.LEFT);
        rightLit = SideViewDecision.lit(in, LaneCycle.RIGHT);
        // 两侧都要问一遍：关掉的那一次灯灭了就在这里清掉
        boolean leftOk = !dismissal.suppressLight(LaneCycle.LEFT, leftLit) && leftLit;
        boolean rightOk = !dismissal.suppressLight(LaneCycle.RIGHT, rightLit) && rightLit;
        left.step(now, want(in, LaneCycle.LEFT, turn, leftOk), in.fade);
        right.step(now, want(in, LaneCycle.RIGHT, turn, rightOk), in.fade);

        boolean drivingNow = SideViewDecision.shouldStayReady(in);
        boolean graceEnded = false;
        if (drivingNow) {
            readyUntil = 0;
        } else if (driving) {
            // 刚离开 D 挡：从这一刻起算（读数没变化时不会来判定，不能从上一次判定起算）
            readyUntil = now + READY_GRACE_MS;
        } else if (readyUntil != 0 && now >= readyUntil) {
            readyUntil = 0;
            graceEnded = true;
        }
        driving = drivingNow;
        return result(now, true, drivingNow || readyUntil != 0, graceEnded);
    }

    /** 这一侧此刻为什么要显示：打着这一侧的转向灯，或者这一侧后视镜上的灯亮着（设置开着、没被关掉）；不要是 null。 */
    private static Cause want(SideViewDecision.Input in, int lane, int turn, boolean lightOk) {
        if (turn == lane) {
            return Cause.TURN;
        }
        if (!lightOk) {
            return null;
        }
        return SideViewDecision.blindSpotLit(in, lane) ? Cause.BLIND_SPOT : Cause.DOOR_WARNING;
    }

    /**
     * 把两侧的账写成结果。
     *
     * @param open  这一次没被熄屏、原厂画面全收
     * @param ready 备着的条件成立（D 挡，或者离开 D 挡还不到 30 秒）
     */
    private Result result(long now, boolean open, boolean ready, boolean graceEnded) {
        int first = first();
        if (first != SideViewDecision.NONE) {
            lastLane = first;
        }
        Side l = left.snapshot(now);
        Side r = right.snapshot(now);
        Side p = first == LaneCycle.RIGHT ? r : first == LaneCycle.LEFT ? l : null;
        boolean attached = open && (p != null || ready);
        long wakeAt = earliest(earliest(left.wakeAt(), right.wakeAt()), readyUntil);
        return new Result(attached, l, r, p != null ? first : lastLane, p != null, p != null ? p.cause : null,
                p != null ? p.fadeMs : 0, wakeAt, graceEnded);
    }

    /** 只能显示一侧时显示哪一侧（类注释里的次序）；两侧都不显示是 {@link SideViewDecision#NONE}。 */
    private int first() {
        if (left.cause == Cause.TURN) {
            return LaneCycle.LEFT;
        }
        if (right.cause == Cause.TURN) {
            return LaneCycle.RIGHT;
        }
        int wanted = earlier(left, right, true);
        return wanted != SideViewDecision.NONE ? wanted : earlier(left, right, false);
    }

    /** 两侧里先显示起来的那一侧（同时起来先左边）；{@code wanted}：只看此刻要它的，不然只看在保留、淡出的。 */
    private static int earlier(Track a, Track b, boolean wanted) {
        boolean aIn = a.showing && (a.cause != null) == wanted;
        boolean bIn = b.showing && (b.cause != null) == wanted;
        if (aIn && bIn) {
            return a.since <= b.since ? a.lane : b.lane;
        }
        return aIn ? a.lane : bIn ? b.lane : SideViewDecision.NONE;
    }

    private static long earliest(long a, long b) {
        if (a == 0) {
            return b;
        }
        return b == 0 ? a : Math.min(a, b);
    }

    private Track track(int lane) {
        return lane == LaneCycle.RIGHT ? right : left;
    }

    /**
     * 点了那一侧窗口的关闭按钮：关掉那一侧这一次 —— 打灯的那一次，那一侧后视镜上的灯正亮着的话连灯亮的这一次也关掉
     * （不然灯还亮着，下一次判定又弹出来）。另一侧不动。下一次 {@link #update} 照规则回到备着或者移走，不淡（人点的关，当场收）。
     */
    void dismiss(int lane) {
        Track t = track(lane);
        if (t.showing) {
            dismissal.dismiss(lane == LaneCycle.LEFT ? 1 : 2);
            if (lane == LaneCycle.LEFT ? leftLit : rightLit) {
                dismissal.dismissLight(lane);
            }
        }
        t.clear();
    }

    /** 熄屏、服务退出：从头来（关掉的那一次也不记了）。 */
    void reset() {
        left.clear();
        right.clear();
        driving = false;
        readyUntil = 0;
        leftLit = false;
        rightLit = false;
        dismissal = new SideViewDismissal();
    }
}
