/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

/**
 * 从两盏转向灯「此刻亮不亮」推出「在打哪边」。
 *
 * <p>转向灯一闪一闪（7X 上周期约 730 ms），熄灭的半拍不等于不打了：某一侧在最近
 * {@link #HOLD_MS} 内亮过，就算那一侧在打；两侧都在打就是双闪（车上双闪那个功能号读出来
 * 是 255，只能这样推）。1.5 秒能跨过熄灭的半拍，松手后也不至于拖太久。</p>
 *
 * <p>两盏灯都读不到时结果是「不知道」，不是「没打」。纯函数，{@code TurnSignalHoldTest} 里测。</p>
 */
final class TurnSignalHold {

    static final long HOLD_MS = 1500L;

    /** 一次判定：转向灯（{@link VehicleState#TURN_NONE} 等）和双闪；读不到时两个都是 null。 */
    static final class Result {
        final Integer turn;
        final Boolean hazard;

        Result(Integer turn, Boolean hazard) {
            this.turn = turn;
            this.hazard = hazard;
        }
    }

    private boolean everLeft;
    private boolean everRight;
    private long lastLeftOnMs;
    private long lastRightOnMs;

    /**
     * @param nowMs   单调时钟
     * @param leftOn  左灯此刻亮着；null = 读不到
     * @param rightOn 右灯此刻亮着；null = 读不到
     */
    Result update(long nowMs, Boolean leftOn, Boolean rightOn) {
        if (leftOn == null && rightOn == null) {
            return new Result(null, null);
        }
        if (Boolean.TRUE.equals(leftOn)) {
            everLeft = true;
            lastLeftOnMs = nowMs;
        }
        if (Boolean.TRUE.equals(rightOn)) {
            everRight = true;
            lastRightOnMs = nowMs;
        }
        boolean left = everLeft && nowMs - lastLeftOnMs < HOLD_MS;
        boolean right = everRight && nowMs - lastRightOnMs < HOLD_MS;
        if (left && right) {
            return new Result(VehicleState.TURN_NONE, true);
        }
        if (left) {
            return new Result(VehicleState.TURN_LEFT, false);
        }
        if (right) {
            return new Result(VehicleState.TURN_RIGHT, false);
        }
        return new Result(VehicleState.TURN_NONE, false);
    }
}
