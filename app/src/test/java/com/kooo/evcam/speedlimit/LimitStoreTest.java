/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * 校验通过才启用（{@link LimitStore#install}）：整个包解得开、国家和日期对上清单才换上；不过的连残件一起删。
 */
public class LimitStoreTest {

    private static final String SHA = "21e025c48b852be33d4c15f26d05ed540578ea37ff0272c6530e90b64a668225";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static LimitCatalog.Entry entry(String country, int date) {
        return new LimitCatalog.Entry(country, date, "https://zeekr.autos/speed-limits/x.zslp", 212, SHA);
    }

    @Test
    public void installsAGoodPackage() throws Exception {
        LimitStore store = new LimitStore(new File(tmp.getRoot(), "speed-limits"));
        assertTrue(store.installed().isEmpty());
        File part = store.part("ZZ");
        Fixture.write(part.getParentFile(), part.getName(), Fixture.bytes());
        store.install(entry("ZZ", 20261128), part);
        assertFalse(part.exists());
        LimitStore.Installed i = store.find("ZZ");
        assertNotNull(i);
        assertEquals(20261128, i.date);
        assertEquals(1, store.files().size());

        // 再装一次（更新）：换掉原来的
        part = store.part("ZZ");
        Fixture.write(part.getParentFile(), part.getName(), Fixture.bytes());
        store.install(entry("ZZ", 20261128), part);
        assertEquals(1, store.installed().size());

        assertTrue(store.delete("ZZ"));
        assertNull(store.find("ZZ"));
    }

    @Test
    public void refusesWhatDoesNotMatchTheCatalog() throws Exception {
        LimitStore store = new LimitStore(new File(tmp.getRoot(), "speed-limits"));
        refused(store, entry("ZZ", 20261127), Fixture.bytes());                       // 日期对不上
        refused(store, entry("SG", 20261128), Fixture.bytes());                       // 国家对不上
        byte[] bad = Fixture.bytes();
        bad[bad.length - 6] ^= 0x5A;                                                  // 有一块解不开
        refused(store, entry("ZZ", 20261128), bad);
        assertTrue("没过的不启用", store.installed().isEmpty());
    }

    @Test
    public void ignoresFilesThatAreNotItsOwn() throws Exception {
        File dir = new File(tmp.getRoot(), "speed-limits");
        assertTrue(dir.mkdirs());
        Fixture.write(dir, "SG.zslp", Fixture.bytes());                               // 名字和包头的国家不一样
        Fixture.write(dir, "MY.zslp", new byte[]{1, 2, 3});                          // 不是包
        Fixture.write(dir, "ZZ.zslp.part", Fixture.bytes());                         // 没下完的
        LimitStore store = new LimitStore(dir);
        assertTrue(store.installed().isEmpty());
    }

    private static void refused(LimitStore store, LimitCatalog.Entry entry, byte[] data) throws IOException {
        File part = store.part(entry.country);
        Fixture.write(part.getParentFile(), part.getName(), data);
        try {
            store.install(entry, part);
            fail("应该不认：" + entry.country + " " + entry.date);
        } catch (LimitPackage.Invalid expected) {
            assertFalse("残件删掉", part.exists());
        }
    }
}
