package com.kooo.evcam.zeekr;

/** Dismiss one indicator activation, never the persisted feature setting. */
public final class SideViewDismissal {
    private Integer dismissedTurn;

    public void dismiss(int turn) {
        if (turn == 1 || turn == 2) {
            dismissedTurn = turn;
        }
    }

    public boolean suppress(Integer turn, Boolean hazard) {
        if (dismissedTurn == null) {
            return false;
        }
        if (Boolean.TRUE.equals(hazard) || (turn != null && !dismissedTurn.equals(turn))) {
            dismissedTurn = null;
            return false;
        }
        // A missing reading is not evidence of a new indicator activation.
        return true;
    }
}
