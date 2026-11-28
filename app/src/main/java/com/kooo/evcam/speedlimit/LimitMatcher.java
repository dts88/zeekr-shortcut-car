/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import java.io.IOException;
import java.util.List;

/**
 * 车在哪条路上、那条路限多少（项目本地文档 speed-limits/spec.md §五）。
 *
 * <ul>
 *   <li>定位精度差于 {@link #MAX_ACCURACY_M} 不匹配；只找 {@link #REACH_M} 以内的路段。</li>
 *   <li>车速到 {@link #MIN_HEADING_KMH} 而且定位给了方向时看方向：和路段方向差 60° 以内是顺向，和反方向差 60° 以内是逆向，
 *       单行只认它那一个方向；得分 = 距离 + 每度 0.25 m，取最小。横着过（60°–120°）不算。</li>
 *   <li>没方向（慢、停着）：取最近的；双向路两个方向限速不一样就算不知道。</li>
 * </ul>
 *
 * <p>不知道的路也算匹配到（结果的 {@link Match#limit} 是 0）：这样车不会被贴到旁边有限速的那条路上。</p>
 */
public final class LimitMatcher {

    public static final double REACH_M = 35.0;
    public static final float MAX_ACCURACY_M = 50f;
    public static final float MIN_HEADING_KMH = 8f;
    static final double MAX_TURN_DEG = 60.0;
    static final double TURN_COST_M_PER_DEG = 0.25;
    private static final double M_PER_DEG = 111320.0;

    private LimitMatcher() {
    }

    /** 匹配到的那条路的限速：0 不知道，1–250 牌子上的数（{@link #mph} 时是英里），255 不限速。 */
    public static final class Match {
        public final int limit;
        public final boolean mph;

        public Match(int limit, boolean mph) {
            this.limit = limit;
            this.mph = mph && limit >= 1 && limit <= LimitRoad.MAX_LIMIT;
        }

        /** 按钮上能写的数：牌子上的数才写，不知道、不限速不写。 */
        public boolean shown() {
            return limit >= 1 && limit <= LimitRoad.MAX_LIMIT;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Match && ((Match) o).limit == limit && ((Match) o).mph == mph;
        }

        @Override
        public int hashCode() {
            return limit * 2 + (mph ? 1 : 0);
        }

        @Override
        public String toString() {
            return limit + (mph ? " mph" : "");
        }
    }

    /**
     * 一次定位匹配一次。
     *
     * @param accuracy 精度（米），没有 null
     * @param bearing  行进方向（度，正北 0、顺时针），没有 null
     * @param speedKmh 车速，没有 null
     * @return 35 m 内没有路（或者精度太差）返回 null
     */
    public static Match match(LimitAtlas atlas, double lat, double lon, Float accuracy, Float bearing, Float speedKmh)
            throws IOException {
        if (accuracy != null && accuracy > MAX_ACCURACY_M) {
            return null;
        }
        double fx = Tiles.fx(lon);
        double fy = Tiles.fy(lat);
        double reach = REACH_M / Tiles.tileMetres(lat);
        int x0 = Tiles.clamp(fx - reach);
        int x1 = Tiles.clamp(fx + reach);
        int y0 = Tiles.clamp(fy - reach);
        int y1 = Tiles.clamp(fy + reach);
        boolean heading = bearing != null && speedKmh != null && speedKmh >= MIN_HEADING_KMH;
        // 以这个点为原点的平面（米）：35 m 之内用不着更准的算法
        double kx = Math.cos(Math.toRadians(lat)) * M_PER_DEG / 100000.0;
        double ky = M_PER_DEG / 100000.0;
        double latE5 = lat * 100000.0;
        double lonE5 = lon * 100000.0;

        Match best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                List<LimitRoad> roads = atlas.roads(x, y);
                for (LimitRoad road : roads) {
                    for (int i = 0; i + 1 < road.lat.length; i++) {
                        double ax = (road.lon[i] - lonE5) * kx;
                        double ay = (road.lat[i] - latE5) * ky;
                        double bx = (road.lon[i + 1] - lonE5) * kx;
                        double by = (road.lat[i + 1] - latE5) * ky;
                        double d = distanceToOrigin(ax, ay, bx, by);
                        if (d > REACH_M || d >= bestScore) {
                            continue;
                        }
                        double score;
                        int limit;
                        if (heading) {
                            if (ax == bx && ay == by) {
                                continue;
                            }
                            double theta = Math.toDegrees(Math.atan2(bx - ax, by - ay));
                            double ahead = turn(bearing, theta);
                            double back = turn(bearing, theta + 180.0);
                            if (ahead <= MAX_TURN_DEG && road.oneway != LimitRoad.BACKWARD_ONLY) {
                                score = d + TURN_COST_M_PER_DEG * ahead;
                                limit = road.forward;
                            } else if (back <= MAX_TURN_DEG && road.oneway != LimitRoad.FORWARD_ONLY) {
                                score = d + TURN_COST_M_PER_DEG * back;
                                limit = road.backward;
                            } else {
                                continue;
                            }
                        } else {
                            score = d;
                            limit = road.oneway == LimitRoad.FORWARD_ONLY ? road.forward
                                    : road.oneway == LimitRoad.BACKWARD_ONLY ? road.backward
                                    : road.forward == road.backward ? road.forward : LimitRoad.UNKNOWN;
                        }
                        if (score < bestScore) {
                            bestScore = score;
                            best = new Match(limit, road.mph);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 原点到线段 ab 的距离。 */
    static double distanceToOrigin(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double length2 = dx * dx + dy * dy;
        double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length2));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    /** 两个方向差多少度（0–180）。 */
    static double turn(double a, double b) {
        double d = Math.abs(((a - b) % 360.0 + 360.0) % 360.0);
        return d > 180.0 ? 360.0 - d : d;
    }

    /**
     * 按钮上显示哪一个：连着 {@link #SWITCH_AFTER} 次匹配到同一个才换上，连着 {@link #CLEAR_AFTER} 次没匹配到才收回，
     * 免得在两条路之间来回跳。
     */
    public static final class Tracker {
        static final int SWITCH_AFTER = 2;
        static final int CLEAR_AFTER = 3;

        private Match shown;
        private Match pending;
        private int pendingCount;
        private int misses;

        /** 记下这一次的结果（null = 没匹配到），返回该显示的。 */
        public Match update(Match match) {
            if (match == null) {
                pending = null;
                pendingCount = 0;
                if (++misses >= CLEAR_AFTER) {
                    shown = null;
                }
                return shown;
            }
            misses = 0;
            if (match.equals(shown)) {
                pending = null;
                pendingCount = 0;
                return shown;
            }
            if (match.equals(pending)) {
                pendingCount++;
            } else {
                pending = match;
                pendingCount = 1;
            }
            if (pendingCount >= SWITCH_AFTER) {
                shown = pending;
                pending = null;
                pendingCount = 0;
            }
            return shown;
        }

        /** 定位没了、数据换了：从头来。 */
        public void reset() {
            shown = null;
            pending = null;
            pendingCount = 0;
            misses = 0;
        }

        public Match shown() {
            return shown;
        }
    }
}
