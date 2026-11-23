/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import java.util.List;

/**
 * 在不在某个地点（ui-spec §十八「在哪」）：纯算术，测得到（{@code WhereaboutsTest}）。
 *
 * <p>离中心的距离 d、地点半径 r、定位的误差 u（定位报的精度，加上那之后车开过的距离，{@link PositionTrack}）：
 * d + u ≤ r 一定在，d − u &gt; r 一定不在，之间判不清 —— 判不清的不当成在、也不当成不在（判不清不做）。</p>
 *
 * <p>到达、离开留一段余量 m（半径的十分之一，25 到 100 m），免得在边上来回跳：在里面的，要一定出了 r + m 才算离开；
 * 不在里面的，一定进了 r 才算到达。</p>
 */
public final class Whereabouts {

    /** 地球平均半径（米）。 */
    private static final double EARTH_M = 6_371_008.8d;

    public enum Side {
        IN, OUT, UNSURE
    }

    private Whereabouts() {
    }

    /** 两点之间的地面距离（米，球面）。 */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /** 到达、离开的余量：半径的十分之一，25 到 100 m。 */
    static double margin(int radius) {
        return Math.max(25d, Math.min(100d, radius / 10d));
    }

    /**
     * 这一刻在哪一边。{@code wasIn}：上一次判出来在里面（TRUE）、不在（FALSE）、还没判出来（null）。
     * 误差 {@code uncertainty} 是无穷大（没有定位）时只能判不清。
     */
    public static Side side(Place place, double lat, double lon, double uncertainty, Boolean wasIn) {
        if (Double.isNaN(uncertainty) || Double.isInfinite(uncertainty)) {
            return Side.UNSURE;
        }
        double d = distance(lat, lon, place.latitude, place.longitude);
        if (Boolean.TRUE.equals(wasIn)) {
            // 在里面的：一定出了 r + m 才算离开，否则还在
            return d - uncertainty > place.radius + margin(place.radius) ? Side.OUT : Side.IN;
        }
        if (d + uncertainty <= place.radius) {
            return Side.IN;
        }
        return d - uncertainty > place.radius ? Side.OUT : Side.UNSURE;
    }

    /**
     * 「所在地点」那一行（没有前后之分，不带余量）：一定在的里面离中心最近的那一个；一个都不在、每一个都一定不在是
     * {@link #ELSEWHERE}；判不清是 null。
     */
    public static Place where(List<Place> places, double lat, double lon, double uncertainty) {
        Place nearest = null;
        double nearestD = Double.MAX_VALUE;
        boolean unsure = false;
        for (Place p : places) {
            Side s = side(p, lat, lon, uncertainty, null);
            if (s == Side.IN) {
                double d = distance(lat, lon, p.latitude, p.longitude);
                if (d < nearestD) {
                    nearestD = d;
                    nearest = p;
                }
            } else if (s == Side.UNSURE) {
                unsure = true;
            }
        }
        if (nearest != null) {
            return nearest;
        }
        return unsure ? null : ELSEWHERE;
    }

    /** {@link #where} 的「其他地方」（不是哪一个地点）。 */
    public static final Place ELSEWHERE = Place.of("elsewhere", "", 0d, 0d, Place.RADIUS_MIN);
}
