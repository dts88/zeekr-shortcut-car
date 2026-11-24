/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一条自动化的「而且」（ui-spec §十八「而且（条件）」）：触发那一下要同时满足的。不可变；在触发那一下判一次
 * （{@link Automations}，读数没就绪时等，最多等到时限）。在不在某些地点判不清时也是等（判不清不做）。
 */
public final class Condition {

    public enum Kind {
        /** 挡位是（{@link #gear}）。 */
        GEAR_IS,
        /** 电量不低于 {@link #number}%。 */
        BATTERY_AT_LEAST,
        /** 电量低于 {@link #number}%。 */
        BATTERY_BELOW,
        /** 某项设置是某个值（{@link #setting}、{@link #value}）。 */
        STATE_IS,
        /** 时间段：从 {@link #from} 到 {@link #to}（一天里的第几分钟，含头不含尾；头比尾晚是跨午夜）。 */
        TIME_BETWEEN,
        /** 星期（{@link #days}：星期一是第 0 位，星期日是第 6 位）。 */
        WEEKDAYS,
        /** 在这几个地点里的一个（{@link #places}）。 */
        IN_PLACES,
        /** 不在这几个地点里的任何一个。 */
        NOT_IN_PLACES
    }

    public final Kind kind;
    public final String gear;
    public final int number;
    public final Setting setting;
    public final String value;
    public final int from;
    public final int to;
    public final int days;
    /** {@link Kind#IN_PLACES}、{@link Kind#NOT_IN_PLACES}：哪几个地点（{@link Place#id}，至少一个）；别的是空的。 */
    public final List<String> places;

    private Condition(Kind kind, String gear, int number, Setting setting, String value, int from, int to, int days) {
        this(kind, gear, number, setting, value, from, to, days, Collections.emptyList());
    }

    private Condition(Kind kind, String gear, int number, Setting setting, String value, int from, int to, int days,
                      List<String> places) {
        this.kind = kind;
        this.gear = gear;
        this.number = number;
        this.setting = setting;
        this.value = value;
        this.from = from;
        this.to = to;
        this.days = days;
        this.places = places;
    }

    /** 挡位是；不是 P、R、N、D 的是 null。 */
    public static Condition gearIs(String gear) {
        return Trigger.isGear(gear) ? new Condition(Kind.GEAR_IS, gear, 0, null, null, 0, 0, 0) : null;
    }

    /** 电量不低于；不在 0 到 100 的是 null。 */
    public static Condition batteryAtLeast(int percent) {
        return percent >= 0 && percent <= 100
                ? new Condition(Kind.BATTERY_AT_LEAST, null, percent, null, null, 0, 0, 0) : null;
    }

    /** 电量低于；不在 0 到 100 的是 null。 */
    public static Condition batteryBelow(int percent) {
        return percent >= 0 && percent <= 100
                ? new Condition(Kind.BATTERY_BELOW, null, percent, null, null, 0, 0, 0) : null;
    }

    /** 某项设置是某个值；值不是它的是 null。 */
    public static Condition stateIs(Setting setting, String value) {
        return setting != null && !setting.range() && setting.code(value) != null
                ? new Condition(Kind.STATE_IS, null, 0, setting, value, 0, 0, 0) : null;
    }

    /** 时间段（一天里的第几分钟）；不在 0 到 1439、头尾一样的是 null。 */
    public static Condition timeBetween(int from, int to) {
        return from >= 0 && from < 1440 && to >= 0 && to < 1440 && from != to
                ? new Condition(Kind.TIME_BETWEEN, null, 0, null, null, from, to, 0) : null;
    }

    /** 星期（第 0 位星期一 …… 第 6 位星期日）；一天都没有的是 null。 */
    public static Condition weekdays(int days) {
        int d = days & 0x7F;
        return d != 0 ? new Condition(Kind.WEEKDAYS, null, 0, null, null, 0, 0, d) : null;
    }

    /** 在这几个地点里的一个；一个地点都没有的是 null。重复的只算一次。 */
    public static Condition inPlaces(List<String> places) {
        List<String> ids = ids(places);
        return ids == null ? null : new Condition(Kind.IN_PLACES, null, 0, null, null, 0, 0, 0, ids);
    }

    /** 不在这几个地点里的任何一个；一个地点都没有的是 null。 */
    public static Condition notInPlaces(List<String> places) {
        List<String> ids = ids(places);
        return ids == null ? null : new Condition(Kind.NOT_IN_PLACES, null, 0, null, null, 0, 0, 0, ids);
    }

    private static List<String> ids(List<String> places) {
        if (places == null) {
            return null;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String id : places) {
            if (id != null && !id.isEmpty()) {
                set.add(id);
            }
        }
        return set.isEmpty() ? null : Collections.unmodifiableList(new ArrayList<>(set));
    }

    /** 去掉一个地点以后的这个条件；不剩地点了是 null。不是地点条件的原样。 */
    Condition withoutPlace(String id) {
        if (places.isEmpty()) {
            return this;
        }
        List<String> rest = new ArrayList<>(places);
        rest.remove(id);
        return kind == Kind.IN_PLACES ? inPlaces(rest) : notInPlaces(rest);
    }

    /** 此刻的读数（{@link Automations} 交进来；读不到的是 null）。 */
    interface Facts {
        String gear();

        Integer battery();

        /** 这一样确认过的值（{@link Setting} 的码），读不到 null。 */
        Integer state(Setting setting);

        /** 一天里的第几分钟（0 到 1439）。 */
        int minuteOfDay();

        /** 星期几：0 星期一 …… 6 星期日。 */
        int weekday();

        /** 在不在这个地点：在 TRUE、不在 FALSE、判不清 null。没有这个地点（删了）是 FALSE。 */
        Boolean inPlace(String id);
    }

    /** 判一次：成立 TRUE，不成立 FALSE，要的读数读不到 null（再等等）。 */
    Boolean holds(Facts facts) {
        switch (kind) {
            case GEAR_IS: {
                String g = facts.gear();
                return g == null ? null : gear.equals(g);
            }
            case BATTERY_AT_LEAST: {
                Integer b = facts.battery();
                return b == null ? null : b >= number;
            }
            case BATTERY_BELOW: {
                Integer b = facts.battery();
                return b == null ? null : b < number;
            }
            case STATE_IS: {
                Integer v = facts.state(setting);
                return v == null ? null : v.equals(setting.code(value));
            }
            case TIME_BETWEEN: {
                int m = facts.minuteOfDay();
                return from < to ? m >= from && m < to : m >= from || m < to;
            }
            case IN_PLACES:
            case NOT_IN_PLACES: {
                // 在任何一个里：在；每一个都一定不在：不在；别的判不清
                boolean unsure = false;
                for (String id : places) {
                    Boolean in = facts.inPlace(id);
                    if (Boolean.TRUE.equals(in)) {
                        return kind == Kind.IN_PLACES;
                    }
                    unsure |= in == null;
                }
                return unsure ? null : kind == Kind.NOT_IN_PLACES;
            }
            default:
                return (days & (1 << facts.weekday())) != 0;
        }
    }

    /** 要不要定位（地点条件）。 */
    boolean needsPlace() {
        return !places.isEmpty();
    }

    /** 要不要读电量（电量是浮点读数，要登记完整的车辆信号才读得到）。 */
    boolean needsBattery() {
        return kind == Kind.BATTERY_AT_LEAST || kind == Kind.BATTERY_BELOW;
    }

    /** 黑匣子里的写法（不带用户打的字；界面上的写法在 strings 里）。 */
    public String describe() {
        switch (kind) {
            case GEAR_IS:
                return "gear=" + gear;
            case BATTERY_AT_LEAST:
                return "battery>=" + number;
            case BATTERY_BELOW:
                return "battery<" + number;
            case STATE_IS:
                return setting.key + "=" + value;
            case TIME_BETWEEN:
                return String.format(java.util.Locale.US, "%02d:%02d-%02d:%02d", from / 60, from % 60, to / 60, to % 60);
            case IN_PLACES:
                return "in " + String.join("|", places);
            case NOT_IN_PLACES:
                return "not in " + String.join("|", places);
            default:
                return "days=" + Integer.toBinaryString(days);
        }
    }
}
