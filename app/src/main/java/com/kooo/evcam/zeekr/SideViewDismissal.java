package com.kooo.evcam.zeekr;

/**
 * 盲区画面的关闭按钮：只关掉这一次打灯，不动设置。
 *
 * <p>关掉之后，同一侧的灯一直亮着就一直不显示；灯灭了、换到另一侧、打了双闪，都算新的一次。
 * 读不到转向灯不算新的一次 —— 不然信号一断，刚关掉的又弹出来。</p>
 *
 * <p>来源：wysie 的 PR #19；GPL-3.0。纯 Java，见 {@code SideViewDismissalTest}。</p>
 */
public final class SideViewDismissal {
    private Integer dismissedTurn;

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
}
