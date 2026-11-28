/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 现在是不是连着 Wi-Fi（只在 Wi-Fi 下下载，项目本地文档 speed-limits/spec.md §二）。
 *
 * <p>车机自己看不出来（Lab 实测：车机走 T-Box 的以太网，安卓永远说是以太网、按流量计费，连没连 Wi-Fi 都一样），
 * 所以照 Lab 的办法看出网地址：问 zeekr.autos 自带的 {@code /cdn-cgi/trace}（就是清单那个站，不多一个去处），
 * 只留 IP 前两段。记过是 Wi-Fi 的算 Wi-Fi，记过是移动网络的算移动网络；没见过的先不算（{@link Kind#UNCONFIRMED}），
 * 问用户一次、记住 —— 不像 Lab 那样把没见过的当 Wi-Fi（Lab 记着的弱点：没见过的 SIM 段会被当成 Wi-Fi）。
 * 问不到算无法判断。不写死任何人的网络。</p>
 *
 * <p>前两段只存在这里（应用自己的设置），不进日志、黑匣子、诊断。</p>
 */
public final class WifiGate {

    /** Wi-Fi、移动网络、没见过（要问一次）、问不到。 */
    public enum Kind { WIFI, MOBILE, UNCONFIRMED, UNKNOWN }

    static final String TRACE_URL = "https://zeekr.autos/cdn-cgi/trace";

    private static final String PREFS = "speed_limit_network";
    private static final String KEY_MOBILE = "mobile";
    private static final String KEY_WIFI = "wifi";

    /** 一次判断：结果和出网地址的前两段（问不到时 null）。 */
    public static final class Result {
        public final Kind kind;
        public final String prefix;

        Result(Kind kind, String prefix) {
            this.kind = kind;
            this.prefix = prefix;
        }
    }

    private final SharedPreferences prefs;

    public WifiGate(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 照出网地址的前两段判断（{@code prefix} 是 null = 没问到）。 */
    public Result judge(String prefix) {
        return new Result(decide(prefix, mobile(), wifi()), prefix);
    }

    /** 教一次：这个出网地址（前两段）是 / 不是 Wi-Fi。 */
    public void teach(String prefix, boolean isWifi) {
        if (prefix == null) {
            return;
        }
        Set<String> mobile = new HashSet<>(prefs.getStringSet(KEY_MOBILE, Collections.emptySet()));
        Set<String> wifi = new HashSet<>(prefs.getStringSet(KEY_WIFI, Collections.emptySet()));
        if (isWifi) {
            wifi.add(prefix);
            mobile.remove(prefix);
        } else {
            mobile.add(prefix);
            wifi.remove(prefix);
        }
        prefs.edit().putStringSet(KEY_MOBILE, mobile).putStringSet(KEY_WIFI, wifi).apply();
    }

    private Set<String> mobile() {
        return new HashSet<>(prefs.getStringSet(KEY_MOBILE, Collections.emptySet()));
    }

    private Set<String> wifi() {
        return new HashSet<>(prefs.getStringSet(KEY_WIFI, Collections.emptySet()));
    }

    /** 判断本身（纯逻辑）：记过是 Wi-Fi 的算 Wi-Fi；记过是移动网络的算移动网络；没见过的要问；没问到算无法判断。 */
    static Kind decide(String prefix, Set<String> mobile, Set<String> wifi) {
        if (prefix == null) {
            return Kind.UNKNOWN;
        }
        if (wifi.contains(prefix)) {
            return Kind.WIFI;
        }
        return mobile.contains(prefix) ? Kind.MOBILE : Kind.UNCONFIRMED;
    }

    /**
     * 从 trace 的回话里取出网地址的前两段：IPv4 是「a.b」，IPv6 是前两组；没有或看不懂返回 null。
     * 整个地址不往外传、不存。
     */
    static String prefixOf(String trace) {
        if (trace == null) {
            return null;
        }
        for (String line : trace.split("\n")) {
            String l = line.trim();
            if (!l.startsWith("ip=")) {
                continue;
            }
            String ip = l.substring(3).trim().toLowerCase(Locale.ROOT);
            if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                String[] parts = ip.split("\\.");
                for (String p : parts) {
                    if (Integer.parseInt(p) > 255) {
                        return null;
                    }
                }
                return parts[0] + "." + parts[1];
            }
            if (ip.matches("[0-9a-f:]+") && ip.indexOf(':') > 0) {
                String[] groups = ip.split(":", -1);
                if (groups.length >= 3 && !groups[0].isEmpty() && !groups[1].isEmpty()) {
                    return groups[0] + ":" + groups[1];
                }
            }
            return null;
        }
        return null;
    }
}
