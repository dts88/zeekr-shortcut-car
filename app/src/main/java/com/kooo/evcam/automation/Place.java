/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import com.kooo.evcam.button.DeviceLabel;

import java.util.Objects;

/**
 * 一个地点（ui-spec §十六「地点」、§十八「在哪」）：名称、中心的纬度经度、半径。不可变。
 *
 * <p>名称只当字显示、存取（洗过，最多 20 个字，{@link DeviceLabel}）；名称、坐标都不写进黑匣子和诊断报告，
 * 黑匣子里只写它是第几个（{@link PlaceStore}）。</p>
 */
public final class Place {

    public static final int RADIUS_MIN = 50;
    public static final int RADIUS_MAX = 5000;
    /** 新地点的半径（项目所有者 2026-11-20）。 */
    public static final int RADIUS_DEFAULT = 600;
    /** 半径那一行的分段（项目所有者 2026-11-20 选 B：分段 + 行尾「自定义」）。 */
    public static final int[] RADIUS_STEPS = {200, 400, 600, 1000};

    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final int radius;

    private Place(String id, String name, double latitude, double longitude, int radius) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius;
    }

    /** 坐标、半径不在范围里、没有 id 的是 null。名称洗一遍（空的就是空的，界面上写默认名）。 */
    public static Place of(String id, String name, double latitude, double longitude, int radius) {
        if (id == null || id.isEmpty() || !validLatitude(latitude) || !validLongitude(longitude)
                || radius < RADIUS_MIN || radius > RADIUS_MAX) {
            return null;
        }
        return new Place(id, name == null ? "" : DeviceLabel.clean(name), latitude, longitude, radius);
    }

    public static boolean validLatitude(double v) {
        return !Double.isNaN(v) && v >= -90d && v <= 90d;
    }

    public static boolean validLongitude(double v) {
        return !Double.isNaN(v) && v >= -180d && v <= 180d;
    }

    public Place withName(String name) {
        return new Place(id, name == null ? "" : DeviceLabel.clean(name), latitude, longitude, radius);
    }

    /** 新坐标；不在范围里的不改（返回自己）。 */
    public Place withCenter(double lat, double lon) {
        return validLatitude(lat) && validLongitude(lon) ? new Place(id, name, lat, lon, radius) : this;
    }

    /** 新半径，夹到 50–5000 m。 */
    public Place withRadius(int meters) {
        return new Place(id, name, latitude, longitude, Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, meters)));
    }

    /** 半径是不是分段里的一档（不是的，那一行写「自定义」）。 */
    public boolean stepRadius() {
        for (int step : RADIUS_STEPS) {
            if (step == radius) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Place)) {
            return false;
        }
        Place p = (Place) o;
        return id.equals(p.id) && name.equals(p.name) && Double.compare(latitude, p.latitude) == 0
                && Double.compare(longitude, p.longitude) == 0 && radius == p.radius;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, latitude, longitude, radius);
    }
}
