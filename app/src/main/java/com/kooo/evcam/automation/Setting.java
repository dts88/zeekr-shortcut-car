/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.telemetry.Signal;

/**
 * 自动化能设、能看的车辆设置（ui-spec §十八「动作清单」；车辆状态的触发、条件也看它们）。每一样有几种值：存的时候用名字
 * （{@link #keys}），写车时换成 {@link CarMoves.Target} 的码（{@link #codes}）。读写都在 {@code button.CarMoves}。
 *
 * <p>前提照各自车上的情形（Calvin 的 PR 261）：驾驶模式只在 P 挡写；陡坡缓降刚通电时车机说不能用，挂过 D 挡才能用，
 * 所以等它能用了再写；低速紧急制动是安全功能，关掉它的那一条必须结束时恢复。前排空调只读（空调开没开）。</p>
 */
public enum Setting {
    SENTRY("sentry", CarMoves.Target.SENTRY, new String[]{"on", "off"}, new int[]{1, 0}, 0),
    DRIVE_MODE("drive_mode", CarMoves.Target.DRIVE_MODE, new String[]{"comfort", "standard", "sport"},
            new int[]{Signal.DRIVE_MODE_COMFORT, Signal.DRIVE_MODE_STANDARD, Signal.DRIVE_MODE_SPORT}, Setting.PARK_ONLY),
    CIRCULATION("circulation", CarMoves.Target.CIRCULATION, new String[]{"auto", "outer", "inner"},
            new int[]{CarMoves.CIRCULATION_AUTO, CarMoves.CIRCULATION_OUTER, CarMoves.CIRCULATION_INNER}, 0),
    REAR_CLIMATE("rear_climate", CarMoves.Target.REAR_CLIMATE, new String[]{"on", "off"}, new int[]{1, 0}, 0),
    HILL_DESCENT("hill_descent", CarMoves.Target.HILL_DESCENT, new String[]{"on", "off"}, new int[]{1, 0},
            Setting.WAIT_AVAILABLE),
    /** 尾门开启上限：50–95 的整数百分比（{@link #range}）。只在 P 挡写；每写一次车尾响两声，只写一次、不重试（{@link #ONCE}）。 */
    TAILGATE_LIMIT("tailgate_limit", CarMoves.Target.TAILGATE_LIMIT, CarMoves.TAILGATE_MIN, CarMoves.TAILGATE_MAX,
            Setting.PARK_ONLY | Setting.ONCE),
    LOW_SPEED_BRAKING("low_speed_braking", CarMoves.Target.LOW_SPEED_BRAKING, new String[]{"on", "off"},
            new int[]{1, 0}, Setting.SAFETY),
    FRONT_CLIMATE("front_climate", CarMoves.Target.FRONT_CLIMATE, new String[]{"on", "off"}, new int[]{1, 0},
            Setting.READ_ONLY);

    /** 只在 P 挡写（读到别的挡，这一次就算了）。 */
    static final int PARK_ONLY = 1;
    /** 车机说暂时不能用时，等它能用了再写（这一次结束为止）。 */
    static final int WAIT_AVAILABLE = 2;
    /** 安全功能：写「关」（{@link #isSafetyOff}）的那一条必须结束时恢复。 */
    static final int SAFETY = 4;
    /** 只读：只能当触发、条件，不能当动作。 */
    static final int READ_ONLY = 8;
    /** 只写一次、写不成不重试，结束时也不写回（尾门开启上限：每写一次车尾响两声，Lab 2026-11-24、车主确认每次都响）。 */
    static final int ONCE = 16;

    /** 存的名字（{@code RuleStore}、黑匣子）。 */
    public final String key;
    public final CarMoves.Target target;
    /** 几种值的名字；一段整数的（{@link #range}）是 null。 */
    private final String[] keys;
    private final int[] codes;
    /** 一段整数的两头（含）；几种值的没有意义。 */
    private final int min;
    private final int max;
    private final int flags;

    Setting(String key, CarMoves.Target target, String[] keys, int[] codes, int flags) {
        this.key = key;
        this.target = target;
        this.keys = keys;
        this.codes = codes;
        this.min = 0;
        this.max = 0;
        this.flags = flags;
    }

    /** 值是 {@code min}–{@code max} 之间的整数（存成「70」这样的字），写车的码就是这个数。 */
    Setting(String key, CarMoves.Target target, int min, int max, int flags) {
        this.key = key;
        this.target = target;
        this.keys = null;
        this.codes = null;
        this.min = min;
        this.max = max;
        this.flags = flags;
    }

    /** 值是一段整数（尾门开启上限），不是几种值里挑一种：不能当「当」「而且」里的车辆状态，选的时候用拖动条。 */
    public boolean range() {
        return keys == null;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    /** 几种值的名字，照选择框里的先后；一段整数的是空的。 */
    public String[] valueNames() {
        return keys == null ? new String[0] : keys.clone();
    }

    /** 值的名字 → 写车的码；不认识的是 null。一段整数的：范围里的整数就是它自己。 */
    public Integer code(String value) {
        if (keys == null) {
            try {
                int n = Integer.parseInt(value);
                return n >= min && n <= max ? n : null;
            } catch (NumberFormatException | NullPointerException e) {
                return null;
            }
        }
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(value)) {
                return codes[i];
            }
        }
        return null;
    }

    /** 写车的码 → 值的名字；不认识的是 null。 */
    public String value(Integer code) {
        if (code == null) {
            return null;
        }
        if (keys == null) {
            return code >= min && code <= max ? String.valueOf(code) : null;
        }
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == code) {
                return keys[i];
            }
        }
        return null;
    }

    public boolean parkOnly() {
        return (flags & PARK_ONLY) != 0;
    }

    public boolean waitsUntilAvailable() {
        return (flags & WAIT_AVAILABLE) != 0;
    }

    public boolean readOnly() {
        return (flags & READ_ONLY) != 0;
    }

    /** 最多写几次（写了没成再写）：只写一次的是 1。 */
    int maxWrites() {
        return (flags & ONCE) != 0 ? 1 : RunRules.MAX_WRITES;
    }

    /** 结束时恢复不恢复这一样：只写一次的不恢复（写回又要响，离开 P 挡时写也没测过）。 */
    public boolean restorable() {
        return (flags & ONCE) == 0;
    }

    /** 把这一样设成 {@code value} 是不是关掉安全功能（低速紧急制动设成关）。 */
    public boolean isSafetyOff(String value) {
        return (flags & SAFETY) != 0 && "off".equals(value);
    }

    /** 存的名字 → 哪一样；不认识的是 null。 */
    public static Setting of(String key) {
        for (Setting s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
