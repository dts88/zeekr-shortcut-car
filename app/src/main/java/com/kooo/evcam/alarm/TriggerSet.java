/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 哪些事情算「有事」。存成一串逗号分开的名字，纯逻辑（{@code TriggerSetTest}）。
 *
 * <p>车主可以自己选：每台车上能看到的信号不一样，不能看到的那一项开着只会让人以为它在工作。<\/p>
 */
public final class TriggerSet {

    /** 画面里有东西在动（编码器读回的小图相差）。 */
    public static final String MOTION = "motion";
    /** 双闪亮起。 */
    public static final String HAZARD = "hazard";
    /** 远光闪一下。 */
    public static final String FLASH = "flash";
    /** 车门 / 前备箱 / 后备箱被打开。 */
    public static final String DOORS = "doors";
    /** 车灯自己亮起（日行灯 / 位置灯 / 近光）。 */
    public static final String LAMPS = "lamps";
    /** 中控屏亮起。 */
    public static final String SCREEN = "screen";

    /** 设置里列出来的顺序。 */
    public static final String[] ALL = {MOTION, HAZARD, FLASH, DOORS, LAMPS, SCREEN};

    /**
     * 默认只开车自己会做的那几项：它觉得有事时间亮灯、闪双闪、亮屏。
     *
     * <p>其余三项（画面移动、远光闪、车门）默认关：它们可能比车自己的判断更敏敏或更钝，
     * 留给车主自己决定 —— 一个默认值不该替他们挑。</p>
     */
    public static final String DEFAULT = HAZARD + "," + LAMPS + "," + SCREEN;

    /** “全开”：设置界面上一键勾全部时用的就是它自己。 */
    public static final String ALL_ON = MOTION + "," + HAZARD + "," + FLASH + ","
            + DOORS + "," + LAMPS + "," + SCREEN;

    private final Set<String> enabled;

    private TriggerSet(Set<String> enabled) {
        this.enabled = enabled;
    }

    /** 读一串逗号分开的名字；空串或全是认不出的名字时当默认全开。 */
    public static TriggerSet parse(String csv) {
        Set<String> kept = new LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String name = part.trim().toLowerCase(java.util.Locale.US);
                for (String known : ALL) {
                    if (known.equals(name)) {
                        kept.add(known);
                    }
                }
            }
        }
        if (kept.isEmpty()) {
            for (String known : ALL) {
                kept.add(known);
            }
        }
        return new TriggerSet(kept);
    }

    /** 把选中的名字写回字符串（按 {@link #ALL} 的顺序）。一个都没选时返回空串。 */
    public static String value(Set<String> names) {
        StringBuilder out = new StringBuilder();
        for (String known : ALL) {
            if (names == null || !names.contains(known)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(known);
        }
        return out.toString();
    }

    public boolean has(String name) {
        return enabled.contains(name);
    }

    /** 开着几项。 */
    public int size() {
        return enabled.size();
    }
}
