/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

/**
 * 一块瓦片里的一段路（speed-limits/spec.md §三）。坐标是 1e-5 度的整数。
 */
public final class LimitRoad {

    /** 限速不知道（没有标签、或者看不懂）。 */
    public static final int UNKNOWN = 0;
    /** 牌子上的数最大到这里。 */
    public static final int MAX_LIMIT = 250;
    /** 不限速（{@code maxspeed=none}）。 */
    public static final int NO_LIMIT = 255;

    public static final int TWO_WAY = 0;
    /** 只能顺着画的方向走。 */
    public static final int FORWARD_ONLY = 1;
    /** 只能逆着画的方向走。 */
    public static final int BACKWARD_ONLY = 2;

    /** 类别 0–13（motorway … service）。 */
    public final int roadClass;
    public final int oneway;
    /** 限速的单位是 mph（不是 km/h）。 */
    public final boolean mph;
    public final int forward;
    public final int backward;
    /** 低 4 位 layer + 8，第 4 位桥，第 5 位隧道。现在不用来匹配。 */
    public final int level;
    final int[] lat;
    final int[] lon;

    LimitRoad(int roadClass, int oneway, boolean mph, int forward, int backward, int level, int[] lat, int[] lon) {
        this.roadClass = roadClass;
        this.oneway = oneway;
        this.mph = mph;
        this.forward = forward;
        this.backward = backward;
        this.level = level;
        this.lat = lat;
        this.lon = lon;
    }

    public int points() {
        return lat.length;
    }

    public int latE5(int i) {
        return lat[i];
    }

    public int lonE5(int i) {
        return lon[i];
    }
}
