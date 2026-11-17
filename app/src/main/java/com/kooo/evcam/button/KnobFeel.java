/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import java.util.HashMap;
import java.util.Map;

final class KnobFeel {

    static final long IDLE_MS = 1_500L;
    static final long FLOW_MS = 500L;
    static final long EVENT_MS = 400L;
    static final int GAP_MAX = 2;

    private static final class Run {
        long at;
        int direction;
        int events;
        boolean restored;
    }

    private final Map<String, Run> runs = new HashMap<>();

    synchronized int feelTS(String address, int direction, int steps, int missed, long now) {
        Run run = runs.get(address);
        int felt = Math.max(1, steps);
        if (run == null || now - run.at > IDLE_MS || run.direction != direction) {
            run = new Run();
            run.direction = direction;
            runs.put(address, run);
        } else if (missed > 0 && missed <= GAP_MAX && now - run.at <= EVENT_MS * (missed + 1)) {
            felt += missed;
        }
        run.events++;
        boolean flowing = run.events == 1 ? steps >= 2 : run.events == 2 && now - run.at <= FLOW_MS;
        if (!run.restored && flowing) {
            felt++;
            run.restored = true;
        }
        run.at = now;
        return direction * felt;
    }

    synchronized void forget(String address) {
        runs.remove(address);
    }

    static int missed(int counter, int last) {
        if (counter < 0 || last < 0) {
            return 0;
        }
        int gap = (counter - last) & 0xFF;
        return gap >= 2 && gap <= GAP_MAX + 1 ? gap - 1 : 0;
    }
}
