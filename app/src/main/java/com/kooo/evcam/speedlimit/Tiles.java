/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

/**
 * 限速数据的瓦片：Web 墨卡托 z14（项目本地文档 speed-limits/spec.md §三），和参考打包器同样的算法、同样的运算顺序。
 * 打包器把一小段路放进它外框碰到的每一块；这边查一个点时把离它不到够得着的距离的邻块也查上，边界上差一点没关系。
 */
final class Tiles {

    static final int ZOOM = 14;
    static final int SIZE = 1 << ZOOM;
    /** 赤道一圈（米，WGS84）。 */
    private static final double EARTH_M = 40075016.686;

    private Tiles() {
    }

    /** 经度（度）→ 小数的 x。 */
    static double fx(double lonDeg) {
        return (lonDeg + 180.0) / 360.0 * SIZE;
    }

    /** 纬度（度）→ 小数的 y。 */
    static double fy(double latDeg) {
        double lat = Math.toRadians(latDeg);
        return (1.0 - Math.log(Math.tan(lat) + 1.0 / Math.cos(lat)) / Math.PI) / 2.0 * SIZE;
    }

    static int clamp(double v) {
        int i = (int) Math.floor(v);
        return Math.max(0, Math.min(SIZE - 1, i));
    }

    /** 这个纬度上一块瓦片的边长（米）：墨卡托在同一纬度上横竖一样。 */
    static double tileMetres(double latDeg) {
        return EARTH_M * Math.cos(Math.toRadians(latDeg)) / SIZE;
    }

    static long key(int x, int y) {
        return ((long) x << 16) | (y & 0xFFFF);
    }
}
