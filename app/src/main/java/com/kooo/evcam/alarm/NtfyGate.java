/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * ntfy 这道门：一个键，两个状态，纯函数。
 *
 * <h3>为什么只有一个键</h3>
 *
 * <p>{@code alerts_transport} 同时是「走哪条路」和「这个功能现在有没有」。它列在
 * {@code AppConfig.DEVELOPER_KEYS} 里，所以开发者选项一关，读出来就是 telegram ——
 * 不需要第二个开关，也不会出现「选了 ntfy 但功能藏着」这种半截状态。</p>
 *
 * <p>开关本身不擦任何设置：两边的配置一直留着，切回来就是原来那一份。</p>
 *
 * <h3>One key, two states, pure functions</h3>
 *
 * <p>{@code alerts_transport} is both "which route" and "is this feature on". It is listed in
 * {@code AppConfig.DEVELOPER_KEYS}, so with developer options closed it reads as telegram — no second
 * switch, and no half state where ntfy is stored but invisible.</p>
 *
 * <p>Flipping it erases nothing: both configurations stay put, and switching back is the same
 * configuration as before.</p>
 */
public final class NtfyGate {

    /** 走 Telegram。Telegram. */
    public static final String TELEGRAM = "telegram";

    /** 走 ntfy（开发者选项里才选得了）。ntfy, selectable only under developer options. */
    public static final String NTFY = "ntfy";

    private NtfyGate() {
    }

    /** 存着的值清一下，认不出来的按 telegram 算。Unknown values collapse to telegram. */
    public static String stored(String stored) {
        return NTFY.equals(stored) ? NTFY : TELEGRAM;
    }

    /**
     * 现在真正走哪条路。
     *
     * @param developerUnlocked 开发者选项开着没有（{@code DeveloperMode.isUnlocked()}）
     * @param stored            {@code alerts_transport} 存着的值
     *
     * Which route actually sends. Developer options closed means telegram, whatever is stored.
     */
    public static String effective(boolean developerUnlocked, String stored) {
        return developerUnlocked ? stored(stored) : TELEGRAM;
    }

    /** 这条路现在是 ntfy 吗。Whether the effective route is ntfy. */
    public static boolean available(boolean developerUnlocked, String stored) {
        return NTFY.equals(effective(developerUnlocked, stored));
    }
}
