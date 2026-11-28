/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * zeekr.autos 上的限速包清单（项目本地文档 speed-limits/spec.md §四）。
 *
 * <p>整份只认 {@code format} 1；里面看不懂的一条跳过，不连累别的。包的地址照清单的地址相对解析，只认 https、只认 zeekr.autos。
 * 同一个国家写了几条的，留日期最新的。国家名不靠清单：界面照系统的叫法显示。</p>
 */
public final class LimitCatalog {

    /** 清单的地址。 */
    public static final String URL = "https://zeekr.autos/speed-limits/catalog.json";
    static final String HOST = "zeekr.autos";
    /** 一个包最大这么多，再大当清单写错了。 */
    public static final long MAX_PACKAGE_BYTES = 200L * 1024 * 1024;

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 清单整份看不懂。 */
    public static final class Invalid extends IOException {
        Invalid(String message) {
            super(message);
        }
    }

    /** 清单里的一个包。 */
    public static final class Entry {
        public final String country;
        /** yyyymmdd。 */
        public final int date;
        public final String url;
        public final long bytes;
        /** 小写十六进制。 */
        public final String sha256;

        Entry(String country, int date, String url, long bytes, String sha256) {
            this.country = country;
            this.date = date;
            this.url = url;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    public final List<Entry> entries;

    private LimitCatalog(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public Entry find(String country) {
        for (Entry e : entries) {
            if (e.country.equals(country)) {
                return e;
            }
        }
        return null;
    }

    /**
     * @param json 清单原文
     * @param base 清单的地址（包的地址照它相对解析）
     */
    public static LimitCatalog parse(String json, String base) throws Invalid {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new Invalid("catalog is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Invalid("catalog is not JSON");
        }
        JsonElement format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()
                || format.getAsDouble() != LimitPackage.FORMAT) {
            throw new Invalid("catalog format");
        }
        JsonElement packages = root.get("packages");
        if (packages == null || !packages.isJsonArray()) {
            throw new Invalid("catalog has no packages");
        }
        Map<String, Entry> newest = new LinkedHashMap<>();
        for (JsonElement item : (JsonArray) packages) {
            Entry entry = entry(item, base);
            if (entry == null) {
                continue;
            }
            Entry seen = newest.get(entry.country);
            if (seen == null || entry.date > seen.date) {
                newest.put(entry.country, entry);
            }
        }
        return new LimitCatalog(new ArrayList<>(newest.values()));
    }

    /** 一条看不懂就是 null。 */
    private static Entry entry(JsonElement item, String base) {
        if (item == null || !item.isJsonObject()) {
            return null;
        }
        JsonObject o = item.getAsJsonObject();
        String country = text(o, "country");
        String date = text(o, "date");
        String file = text(o, "file");
        String sha = text(o, "sha256");
        Long bytes = number(o, "bytes");
        if (country == null || !COUNTRY.matcher(country).matches() || date == null || file == null || file.isEmpty()
                || sha == null || !SHA256.matcher(sha).matches() || bytes == null
                || bytes < 1 || bytes > MAX_PACKAGE_BYTES) {
            return null;
        }
        int day = date(date);
        String url = resolve(base, file);
        if (day == 0 || url == null) {
            return null;
        }
        return new Entry(country, day, url, bytes, sha);
    }

    /** 「YYYY-MM-DD」→ yyyymmdd；不是一个真的日子返回 0。 */
    static int date(String text) {
        java.util.regex.Matcher m = DATE.matcher(text);
        if (!m.matches()) {
            return 0;
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (year < 1970 || month < 1 || month > 12 || day < 1 || day > 31) {
            return 0;
        }
        return year * 10000 + month * 100 + day;
    }

    /** 包的地址：照清单的地址相对解析，只认 https、只认 zeekr.autos、不带用户名和端口。不合格返回 null。 */
    static String resolve(String base, String file) {
        try {
            URL url = new URL(new URL(base), file);
            if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost().toLowerCase(Locale.ROOT))
                    || url.getUserInfo() != null || url.getPort() != -1 || url.getRef() != null) {
                return null;
            }
            return url.toString();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static Long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double d = e.getAsDouble();
        return d == Math.rint(d) ? (long) d : null;
    }
}
