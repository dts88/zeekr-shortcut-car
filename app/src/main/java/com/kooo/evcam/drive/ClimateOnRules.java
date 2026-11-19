/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.button.CarMoves;

/**
 * 前排空调重新打开后恢复循环模式、二排空调的规矩（issue 138），纯函数，{@code ClimateOnRulesTest} 直接调；照着做的是 {@link ClimateOn}。
 *
 * <ul>
 *   <li>这两样不是每次上车都丢：下车再回来还在。丢是在整个空调关掉的时候 —— 循环模式回到自动、二排空调关掉，再打开也不回来
 *       （澳洲 7X 车主实测，PR 145）。所以看的是前排空调的电源：读到关着，再读到开着，就恢复一次。</li>
 *   <li>关着、开着都要连着读到 {@link OffOn#CONFIRM} 次才算（{@link ClimateOn} 每 {@link #POLL_MS} 读一次）：车机拿不到数据时这个号也会读成 0，
 *       一次不作数。车主实测成功的那一次也是这样等的。关了不到两秒又打开的会漏掉。</li>
 *   <li>恢复一次之后不再写，手动改的不动，直到空调下一次关掉再打开。</li>
 *   <li>写了没成（车机不受理、读回没变）：隔 {@link #RETRY_MS} 再写，一共最多 {@link #MAX_WRITES} 次。</li>
 * </ul>
 *
 * <p>不看挡位、车速：行驶中把空调关掉再打开，也恢复（和灵动贴的空调动作一样，按了就写）。</p>
 */
final class ClimateOnRules {

    /** 多久读一次前排空调的电源。 */
    static final long POLL_MS = 1_000L;
    /** 写了没成，隔这么久再写。 */
    static final long RETRY_MS = 3_000L;
    /** 空调打开一次最多写几次。 */
    static final int MAX_WRITES = 3;

    /** 这一刻该怎样。 */
    enum Decision {
        /** 再等等（空调没关过、还没开稳、刚写过没成）。 */
        WAIT,
        /** 写。 */
        WRITE,
        /** 写满了次数都没成：这一次打开不再写。 */
        FAILED
    }

    private ClimateOnRules() {
    }

    /** 设置里存的值（{@code SettingsRegistry.CIRCULATION_AT_CLIMATE_ON}）→ 交给 {@link CarMoves#restoreClimate} 的循环模式；关闭、不认识的是 0。 */
    static int circulation(String setting) {
        if (AppConfig.CIRCULATION_AT_CLIMATE_ON_INNER.equals(setting)) {
            return CarMoves.CIRCULATION_INNER;
        }
        if (AppConfig.CIRCULATION_AT_CLIMATE_ON_OUTER.equals(setting)) {
            return CarMoves.CIRCULATION_OUTER;
        }
        return 0;
    }

    /**
     * @param pending          确认关过，还有没恢复的
     * @param onInARow         连着读到几次开着
     * @param writes           这一次打开已经写过几次（都没成）
     * @param sinceLastWriteMs 上一次写完过了多久；没写过随便
     */
    static Decision decide(boolean pending, int onInARow, int writes, long sinceLastWriteMs) {
        if (!pending) {
            return Decision.WAIT;
        }
        if (writes >= MAX_WRITES) {
            return Decision.FAILED;
        }
        if (onInARow < OffOn.CONFIRM) {
            return Decision.WAIT;
        }
        if (writes > 0 && sinceLastWriteMs < RETRY_MS) {
            return Decision.WAIT;
        }
        return Decision.WRITE;
    }
}
