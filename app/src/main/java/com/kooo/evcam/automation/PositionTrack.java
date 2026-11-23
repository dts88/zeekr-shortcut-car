/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

/**
 * 最近一次定位和它此刻的误差（ui-spec §十八「在哪」）：误差 = 定位报的精度 + 那之后车开过的距离（车速积出来，
 * 一直停着不长）。纯逻辑，测得到（{@code PositionTrackTest}）；不是线程安全的，在主线程上用。
 *
 * <p>车速读不到、定位又旧了（{@link #STALE_MS}）的，不知道车动没动过：误差当无穷大（判不清）。定位没带精度的按
 * {@link #ACCURACY_UNKNOWN_M} 算。</p>
 */
public final class PositionTrack {

    static final long STALE_MS = 30_000L;
    static final float ACCURACY_UNKNOWN_M = 100f;

    private boolean has;
    private double lat;
    private double lon;
    private float accuracy;
    private long fixAt;
    private double travelled;
    private Float speedKmh;
    private long speedAt;

    /**
     * 车辆信号发布了一次：位置（纬度、经度、精度、定位时刻；没有的是 null）、车速（km/h，没有 null）、此刻。
     * 定位时刻或者位置变了是一次新的定位：开过的距离从零算起。
     */
    public void update(Double latitude, Double longitude, Float accuracyM, Long fixAtMs, Float speed, long now) {
        advance(now);
        if (latitude != null && longitude != null) {
            long at = fixAtMs != null ? fixAtMs : now;
            boolean fresh = !has || at != fixAt || latitude != lat || longitude != lon;
            if (fresh) {
                has = true;
                lat = latitude;
                lon = longitude;
                accuracy = accuracyM != null && accuracyM >= 0f ? accuracyM : ACCURACY_UNKNOWN_M;
                fixAt = at;
                travelled = Math.max(0d, travelledSince(at, now));
            }
        }
        speedKmh = speed;
        speedAt = now;
    }

    /** 定位之后到 {@code now} 车开过的那一段（定位时刻比上一次发布早的，从定位时刻算到此刻、按此刻的车速）。 */
    private double travelledSince(long at, long now) {
        if (speedKmh == null || now <= at) {
            return 0d;
        }
        return Math.abs(speedKmh) / 3.6d * (now - at) / 1000d;
    }

    private void advance(long now) {
        if (has && speedKmh != null && now > speedAt) {
            travelled += Math.abs(speedKmh) / 3.6d * (now - speedAt) / 1000d;
        }
        speedAt = Math.max(speedAt, now);
    }

    public boolean hasFix() {
        return has;
    }

    public double latitude() {
        return lat;
    }

    public double longitude() {
        return lon;
    }

    /** 此刻的误差（米）；没有定位、或者定位旧了又不知道车速的是无穷大。 */
    public double uncertainty(long now) {
        if (!has) {
            return Double.POSITIVE_INFINITY;
        }
        if (speedKmh == null && now - fixAt > STALE_MS) {
            return Double.POSITIVE_INFINITY;
        }
        double moved = travelled;
        if (speedKmh != null && now > speedAt) {
            moved += Math.abs(speedKmh) / 3.6d * (now - speedAt) / 1000d;
        }
        return accuracy + moved;
    }

    public void clear() {
        has = false;
        travelled = 0d;
        speedKmh = null;
    }
}
