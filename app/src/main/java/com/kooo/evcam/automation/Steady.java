/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

/**
 * 一样东西连着几次读到同一个值。不可变，纯逻辑。照 Calvin 的 OffOn（PR 261：前排空调的电源、陡坡缓降能不能用）推广到
 * 不止开、关两个值：要连着读到 {@link #CONFIRM} 次才算 —— 车机拿不到数据时这些号也会读成 0，一次不作数。
 */
final class Steady {

    /** 连着读到几次才算。 */
    static final int CONFIRM = 2;

    /** 还什么都没读到。 */
    static final Steady NONE = new Steady(null, 0);

    /** 连着读到的值、读到了几次；读不到时 value 是 null、count 是 0。 */
    final Integer value;
    final int count;

    private Steady(Integer value, int count) {
        this.value = value;
        this.count = count;
    }

    /** 读到一次；读不到（null）不算，连着的次数从头数。 */
    Steady next(Integer v) {
        if (v == null) {
            return NONE;
        }
        return v.equals(value) ? new Steady(v, count + 1) : new Steady(v, 1);
    }

    /** 刚好确认的那一下（第 {@link #CONFIRM} 次）：之后再读到同一个值不算又一次变化。 */
    boolean justConfirmed() {
        return count == CONFIRM;
    }

    boolean confirmed() {
        return count >= CONFIRM;
    }
}
