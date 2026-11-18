/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import java.util.EnumMap;
import java.util.Map;

/**
 * 测试里造一份真的读数快照：给的是车上原始的值，走的是 {@link Signal#decode} 同一条归一 ——
 * 断言才看得见「快照里存的是什么类型」，而不是测试自己编的一个值。
 *
 * Builds a real readings snapshot in tests: raw on-car values go through the same
 * {@link Signal#decode} normalisation, so a test sees what the snapshot actually holds
 * (Boolean, Integer, Float) instead of a value it invented.
 */
public final class TestSnapshots {

    private TestSnapshots() {
    }

    /**
     * 一对一对来：{@code of(Signal.DOOR_DRIVER, 1, Signal.SCREEN_POWER_EVENT, 1)}.
     *
     * Pairs: {@code of(Signal.DOOR_DRIVER, 1, Signal.SCREEN_POWER_EVENT, 1)}.
     */
    public static Readings of(Object... pairs) {
        Map<Signal, Object> values = new EnumMap<>(Signal.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Signal signal = (Signal) pairs[i];
            Object decoded = signal.decode(pairs[i + 1]);
            if (decoded != null) {
                values.put(signal, decoded);
            }
        }
        return new Readings(values, 1);
    }
}
