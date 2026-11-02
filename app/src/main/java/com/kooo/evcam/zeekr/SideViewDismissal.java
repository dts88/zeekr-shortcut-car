package com.kooo.evcam.zeekr;

/**
 * 盲区画面的关闭按钮：只关掉这一次，不动设置。
 *
 * <p>打灯的那一次：关掉之后，同一侧的灯一直亮着就一直不显示；灯灭了、换到另一侧、打了双闪，都算新的一次。
 * 读不到转向灯不算新的一次 —— 不然信号一断，刚关掉的又弹出来。</p>
 *
 * <p>后视镜上的灯亮的那一次（盲区指示灯、开门预警，项目所有者 2026-11-03）：关掉之后那一侧的灯一直亮着就一直不显示，
 * 灭了算结束。读不到当灭了算（灯的读数本来就是变了才推，读不到多半是连不上了）。</p>
 *
 * <p>来源：wysie 的 PR #19；GPL-3.0。纯 Java，见 {@code SideViewDismissalTest}。</p>
 */
public final class SideViewDismissal {
    private Integer dismissedTurn;
    /** 关掉的是哪一侧后视镜上的灯亮（{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}）；{@link SideViewDecision#NONE} 是没有。 */
    private int dismissedLight = SideViewDecision.NONE;

    /** 关掉这一次：{@code turn} 是 {@code VehicleState.TURN_LEFT} / {@code TURN_RIGHT}，别的值不算。 */
    public void dismiss(int turn) {
        if (turn == 1 || turn == 2) {
            dismissedTurn = turn;
        }
    }

    /** 此刻要不要压着不显示；关掉的那一次已经结束（灯灭、换侧、双闪）时清掉它，返回 false。 */
    public boolean suppress(Integer turn, Boolean hazard) {
        if (dismissedTurn == null) {
            return false;
        }
        if (Boolean.TRUE.equals(hazard) || (turn != null && !dismissedTurn.equals(turn))) {
            dismissedTurn = null;
            return false;
        }
        // 读不到不算新的一次
        return true;
    }

    /** 关掉这一侧后视镜上的灯亮的这一次（{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}）。 */
    public void dismissLight(int lane) {
        if (lane == LaneCycle.LEFT || lane == LaneCycle.RIGHT) {
            dismissedLight = lane;
        }
    }

    /** 这一侧的灯亮着、而这一次被关掉了：压着。灯灭了清掉，返回 false。每一次判定两侧都要问一遍（清掉靠它）。 */
    public boolean suppressLight(int lane, boolean lit) {
        if (dismissedLight != lane) {
            return false;
        }
        if (!lit) {
            dismissedLight = SideViewDecision.NONE;
            return false;
        }
        return true;
    }
}
