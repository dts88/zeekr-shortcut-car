/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

/**
 * 一样东西读到现在的样子：连着几次关着、连着几次开着，是不是在等它重新打开（{@link #awaiting}）。不可变，纯逻辑。
 * 前排空调的电源（{@link ClimateOn}）、陡坡缓降能不能用（{@link HillDescent}，它只用连着几次能用）都这样数：关着、开着都要连着读到
 * {@link #CONFIRM} 次才算 —— 车机拿不到数据时这些号也会读成关着，一次不作数。
 */
final class OffOn {

    /** 连着读到几次才算关着、才算开着。 */
    static final int CONFIRM = 2;

    /** 连着读到几次关着、几次开着（另一个是 0）。 */
    final int off;
    final int on;
    /** 确认关过，还没用掉：在等它重新打开。 */
    final boolean awaiting;

    private OffOn(int off, int on, boolean awaiting) {
        this.off = off;
        this.on = on;
        this.awaiting = awaiting;
    }

    /** 还什么都没读到。 */
    static OffOn start(boolean awaiting) {
        return new OffOn(0, 0, awaiting);
    }

    /** 读到一次：1 开着、0 关着；别的（读不到、占位值）不算，连着的次数从头数。 */
    OffOn next(Integer value) {
        if (value != null && value == 0) {
            return new OffOn(off + 1, 0, awaiting || off + 1 >= CONFIRM);
        }
        if (value != null && value == 1) {
            return new OffOn(0, on + 1, awaiting);
        }
        return unknown();
    }

    /** 这一会儿读不了（车没通电、车辆接口没连着）：连着的次数从头数，在不在等照旧。 */
    OffOn unknown() {
        return off == 0 && on == 0 ? this : new OffOn(0, 0, awaiting);
    }

    /** 用掉了（恢复过了、或者不再写了）：不等了。 */
    OffOn done() {
        return new OffOn(off, on, false);
    }
}
