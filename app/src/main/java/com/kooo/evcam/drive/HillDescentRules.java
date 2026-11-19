/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

/**
 * 每次上车、陡坡缓降能用了就把它打开的规矩（issue 138），纯函数，{@code HillDescentRulesTest} 直接调；照着做的是 {@link HillDescent}。
 *
 * <ul>
 *   <li>陡坡缓降的开关不是一上电就能用：ACC 时、上电后还没挂过 D 挡时车机说用不了（notactive），挂过 D 挡才能用（active），
 *       之后回到 P 挡也还能用（澳洲 7X 车主实测，PR 145）。所以上车后等它能用，能用了打开一次。用不了时不写：
 *       车机说用不了时写的，车主没试过。</li>
 *   <li>一次上车只打开一次：熄屏（人下车了）、车机真正开过机之后的那一次上车才算新的一次（{@link HillDescent}）。打开之后
 *       手动关掉的不动；行驶中它一会儿用不了、又能用了，也不再写。</li>
 *   <li>能用要连着读到 {@link OffOn#CONFIRM} 次才算（{@link HillDescent} 每 {@link #POLL_MS} 问一次）。</li>
 *   <li>写了没成（车机不受理、读回没变）：隔 {@link #RETRY_MS} 再写，一共最多 {@link #MAX_WRITES} 次。</li>
 * </ul>
 *
 * <p>不看挡位、车速：能用多半是刚挂上 D 挡，这时车可能已经在走（和车上那个开关一样，开着才在下坡时起作用）。</p>
 */
final class HillDescentRules {

    /** 多久问一次能不能用。 */
    static final long POLL_MS = 1_000L;
    /** 写了没成，隔这么久再写。 */
    static final long RETRY_MS = 3_000L;
    /** 一次上车最多写几次。 */
    static final int MAX_WRITES = 3;

    /** 这一刻该怎样。 */
    enum Decision {
        /** 再等等（这一次上车已经打开过、还没能用、刚写过没成）。 */
        WAIT,
        /** 写。 */
        WRITE,
        /** 写满了次数都没成：这一次上车不再写。 */
        FAILED
    }

    private HillDescentRules() {
    }

    /**
     * @param awaiting         这一次上车还没打开过
     * @param availableInARow  连着读到几次能用
     * @param writes           这一次已经写过几次（都没成）
     * @param sinceLastWriteMs 上一次写完过了多久；没写过随便
     */
    static Decision decide(boolean awaiting, int availableInARow, int writes, long sinceLastWriteMs) {
        if (!awaiting || availableInARow < OffOn.CONFIRM) {
            return Decision.WAIT;
        }
        if (writes >= MAX_WRITES) {
            return Decision.FAILED;
        }
        if (writes > 0 && sinceLastWriteMs < RETRY_MS) {
            return Decision.WAIT;
        }
        return Decision.WRITE;
    }
}
