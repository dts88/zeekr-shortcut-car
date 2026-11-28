/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 清单（{@link LimitCatalog}）：只认 format 1；看不懂的一条跳过；包只认 https 的 zeekr.autos；同一个国家留最新的。
 */
public class LimitCatalogTest {

    private static final String SHA = "21e025c48b852be33d4c15f26d05ed540578ea37ff0272c6530e90b64a668225";

    private static String entry(String country, String date, String file, String bytes, String sha) {
        return "{\"country\":\"" + country + "\",\"date\":\"" + date + "\",\"file\":\"" + file + "\",\"bytes\":" + bytes
                + ",\"sha256\":\"" + sha + "\"}";
    }

    private static LimitCatalog parse(String... entries) throws LimitCatalog.Invalid {
        return LimitCatalog.parse("{\"format\":1,\"packages\":[" + String.join(",", entries) + "]}", LimitCatalog.URL);
    }

    @Test
    public void readsGoodEntriesAndResolvesFiles() throws Exception {
        LimitCatalog c = parse(entry("SG", "2026-11-28", "SG-20261128.zslp", "412345", SHA),
                entry("MY", "2026-11-20", "/speed-limits/MY.zslp", "13000000", SHA));
        assertEquals(2, c.entries.size());
        LimitCatalog.Entry sg = c.find("SG");
        assertEquals(20261128, sg.date);
        assertEquals("https://zeekr.autos/speed-limits/SG-20261128.zslp", sg.url);
        assertEquals(412345L, sg.bytes);
        assertEquals(SHA, sg.sha256);
        assertEquals("https://zeekr.autos/speed-limits/MY.zslp", c.find("MY").url);
    }

    @Test
    public void skipsWhatItCannotRead() throws Exception {
        LimitCatalog c = parse(
                entry("sg", "2026-11-28", "a.zslp", "1", SHA),                         // 小写
                entry("SGP", "2026-11-28", "a.zslp", "1", SHA),                        // 三个字母
                entry("HK", "2026-13-01", "a.zslp", "1", SHA),                         // 没有 13 月
                entry("NZ", "2026-11-28", "a.zslp", "0", SHA),                         // 0 字节
                entry("AU", "2026-11-28", "a.zslp", "999999999999", SHA),              // 太大
                entry("TH", "2026-11-28", "a.zslp", "1", SHA.toUpperCase()),           // 大写十六进制
                entry("ID", "2026-11-28", "http://zeekr.autos/a.zslp", "1", SHA),      // 不是 https
                entry("PH", "2026-11-28", "https://example.com/a.zslp", "1", SHA),     // 别的站
                entry("VN", "2026-11-28", "https://zeekr.autos:8443/a.zslp", "1", SHA), // 带端口
                entry("KR", "2026-11-28", "//evil.example/a.zslp", "1", SHA),          // 换站的相对地址
                entry("JP", "2026-11-28", "a.zslp", "1.5", SHA),                       // 不是整数
                "\"SG\"",
                entry("SG", "2026-11-28", "SG.zslp", "100", SHA));
        assertEquals(1, c.entries.size());
        assertEquals("SG", c.entries.get(0).country);
    }

    @Test
    public void keepsTheNewestOfACountry() throws Exception {
        LimitCatalog c = parse(entry("SG", "2026-11-01", "old.zslp", "1", SHA),
                entry("SG", "2026-11-28", "new.zslp", "1", SHA),
                entry("SG", "2026-11-15", "mid.zslp", "1", SHA));
        assertEquals(1, c.entries.size());
        assertEquals("https://zeekr.autos/speed-limits/new.zslp", c.find("SG").url);
    }

    @Test
    public void refusesOtherFormats() {
        for (String json : new String[]{"", "[]", "{}", "{\"format\":2,\"packages\":[]}",
                "{\"format\":\"1\",\"packages\":[]}", "{\"format\":1}", "{\"format\":1,\"packages\":{}}", "not json"}) {
            try {
                LimitCatalog.parse(json, LimitCatalog.URL);
                fail("应该不认：" + json);
            } catch (LimitCatalog.Invalid expected) {
                // 对
            }
        }
    }

    @Test
    public void datesMustBeRealDays() {
        assertEquals(20261128, LimitCatalog.date("2026-11-28"));
        assertEquals(0, LimitCatalog.date("2026-1-28"));
        assertEquals(0, LimitCatalog.date("1969-12-31"));
        assertEquals(0, LimitCatalog.date("2026-00-10"));
        assertEquals(0, LimitCatalog.date("2026-11-32"));
        assertNull(LimitCatalog.resolve("https://zeekr.autos/speed-limits/catalog.json", "https://user@zeekr.autos/a"));
        assertNull(LimitCatalog.resolve("https://zeekr.autos/speed-limits/catalog.json", "a.zslp#x"));
    }
}
