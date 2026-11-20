/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import java.util.Objects;

/**
 * 一条自动化的「当」（ui-spec §十八「当（触发）」）：一件事发生的那一下。不可变。
 *
 * <ul>
 *   <li>{@link Kind#GET_IN} 上车（亮屏、车通电；判法用 Calvin 的 PR 261），到离车结束。</li>
 *   <li>{@link Kind#LEAVE} 离车（熄屏），没有结束。</li>
 *   <li>{@link Kind#GEAR} 挂入某一挡（P、R、N、D），到离开这一挡结束。</li>
 *   <li>{@link Kind#STATE} 某项设置变为某个值，到变成别的值结束。</li>
 * </ul>
 *
 * <p>地点（到达、离开）、时间（每天某个时刻）下一步加。</p>
 */
public final class Trigger {

    public enum Kind {
        GET_IN, LEAVE, GEAR, STATE
    }

    public final Kind kind;
    /** {@link Kind#GEAR}：「P」「R」「N」「D」；别的是 null。 */
    public final String gear;
    /** {@link Kind#STATE}：哪一样、变为什么值；别的是 null。 */
    public final Setting setting;
    public final String value;

    private Trigger(Kind kind, String gear, Setting setting, String value) {
        this.kind = kind;
        this.gear = gear;
        this.setting = setting;
        this.value = value;
    }

    public static Trigger getIn() {
        return new Trigger(Kind.GET_IN, null, null, null);
    }

    public static Trigger leave() {
        return new Trigger(Kind.LEAVE, null, null, null);
    }

    /** 挂入某一挡；不是 P、R、N、D 的是 null。 */
    public static Trigger gear(String gear) {
        return isGear(gear) ? new Trigger(Kind.GEAR, gear, null, null) : null;
    }

    /** 某项设置变为某个值；值不是它的（{@link Setting#code}）是 null。 */
    public static Trigger state(Setting setting, String value) {
        return setting != null && setting.code(value) != null ? new Trigger(Kind.STATE, null, setting, value) : null;
    }

    /** 有没有「结束」（结束时恢复只给有结束的）。 */
    public boolean hasEnd() {
        return kind != Kind.LEAVE;
    }

    /** 黑匣子里的写法（不带用户打的字；界面上的写法在 strings 里）。 */
    public String describe() {
        switch (kind) {
            case GET_IN:
                return "get-in";
            case LEAVE:
                return "leave";
            case GEAR:
                return "gear " + gear;
            default:
                return setting.key + "->" + value;
        }
    }

    static boolean isGear(String gear) {
        return "P".equals(gear) || "R".equals(gear) || "N".equals(gear) || "D".equals(gear);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Trigger)) {
            return false;
        }
        Trigger t = (Trigger) o;
        return kind == t.kind && Objects.equals(gear, t.gear) && setting == t.setting && Objects.equals(value, t.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, gear, setting, value);
    }

    @Override
    public String toString() {
        return describe();
    }
}
