/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

/**
 * ntfy 这条路的名字。写进黑匣子和诊断报告的就是这一句，别处不再各写一份。
 *
 * The name of the ntfy route, written into the black box and the diagnostic report; nowhere else
 * keeps its own copy.
 */
public final class NtfyGate {

    /** 走 ntfy。ntfy. */
    public static final String NTFY = "ntfy";

    private NtfyGate() {
    }
}
