/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.guard;

import java.util.Locale;

/**
 * 「离家远不远」—— 一个点、一段距离、一个保守的结论（纯逻辑，{@code GuardHomeTest}）。
 *
 * <h3>为什么是纯逻辑而不是系统地理围栏</h3>
 *
 * <p>应用里没有 {@code Geofence}，车机也不会替我们收系统定位；手上的只有 {@code VehicleState.latitude/longitude}
 * （{@code telemetry.LocationSource} 订的 GPS）。自己算一段距离就够，不用权限、不用后台定位、不依赖
 * Play 服务。</p>
 *
 * <h3>不知道就说不知道</h3>
 *
 * <p>没设家、没有定位、定位超过十分钟没更新、坐标不在范围内 —— 全都算 {@link Where#UNKNOWN}。
 * 猜一个「在外面」去动车，比不提醒更糟。</p>
 *
 * <h3>A point, a distance, and a conclusion that refuses to guess</h3>
 *
 * <p>The app has no system geofence and no background location; it has the car's own GPS fix. Haversine
 * is enough. No home, no fix, a stale fix or a nonsense coordinate all come back UNKNOWN, and the
 * caller decides what that is allowed to do.</p>
 */
public final class GuardHome {

    /** 离家多远算「在外面」。停车场的漂移是几十米，200 米够用又不至于把隔壁街当成家。 */
    public static final double RADIUS_M = 200.0;

    /** 定位超过这么久没更新就不算数（车在地库里本来就没有定位）。 */
    public static final long FIX_MAX_AGE_MS = 10 * 60_000L;

    /** 结论。Where the car is, as far as the car can tell. */
    public enum Where {
        /** 在家里。At home. */
        HOME,
        /** 在外面。Away. */
        AWAY,
        /** 不知道：没设家、没有定位、定位太旧。Unknown. */
        UNKNOWN
    }

    /** 地球平均半径，米。Mean Earth radius in metres. */
    private static final double EARTH_M = 6_371_000.0;

    /** 一个点。One point on the map. */
    public static final class Point {
        public final double lat;
        public final double lon;

        Point(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private GuardHome() {
    }

    /**
     * 把设置里存的 {@code "lat,lon"} 读回来。认不出来、越界、两个 0 都算没有。
     *
     * @return 点；{@code null} 表示这个字符串不是一个点
     */
    public static Point parse(String text) {
        if (text == null) {
            return null;
        }
        String body = text.trim();
        if (body.isEmpty()) {
            return null;
        }
        int comma = body.indexOf(',');
        if (comma <= 0 || comma == body.length() - 1) {
            return null;
        }
        Double lat = number(body.substring(0, comma));
        Double lon = number(body.substring(comma + 1));
        if (lat == null || lon == null) {
            return null;
        }
        Point point = new Point(lat, lon);
        return looksReal(point) ? point : null;
    }

    /** 存成设置里的样子：六位小数，够到十厘米。 */
    public static String format(double lat, double lon) {
        return String.format(Locale.US, "%.6f,%.6f", lat, lon);
    }

    /**
     * 两点之间多少米（haversine）。
     *
     * <p>车机上的点离得不远，用球面公式就够；这里不做椭球修正 —— 那点差别远小于 GPS 自己的误差。</p>
     */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 现在算哪儿。
     *
     * @param home     设置里的家；{@code null} 表示没设
     * @param lat      这一下的纬度；{@code null} 表示没有定位
     * @param lon      这一下的经度
     * @param fixAgeMs 定位多久没更新了（毫秒）；负数是不知道
     */
    public static Where classify(Point home, Double lat, Double lon, long fixAgeMs) {
        if (home == null) {
            return Where.UNKNOWN;
        }
        if (lat == null || lon == null) {
            return Where.UNKNOWN;
        }
        if (fixAgeMs < 0 || fixAgeMs > FIX_MAX_AGE_MS) {
            return Where.UNKNOWN;
        }
        if (!looksReal(new Point(lat, lon))) {
            return Where.UNKNOWN;
        }
        return distanceM(home.lat, home.lon, lat, lon) <= RADIUS_M ? Where.HOME : Where.AWAY;
    }

    /** 这个点像不像一个真实的坐标：0,0 是「没定位」的常见写法。Whether the point is a real coordinate. */
    static boolean looksReal(Point point) {
        if (point == null) {
            return false;
        }
        if (point.lat < -90 || point.lat > 90 || point.lon < -180 || point.lon > 180) {
            return false;
        }
        return point.lat != 0 || point.lon != 0;
    }

    private static Double number(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
