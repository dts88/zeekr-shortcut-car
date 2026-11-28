/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * 读包（{@link LimitPackage}）对着参考打包器的用例：每一块解出来的路和参考实现一样；坏包一律不认。
 */
public class LimitPackageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void fixtureIsTheListedFile() throws Exception {
        byte[] data = Fixture.bytes();
        JsonObject v = Fixture.vectors();
        assertEquals(v.get("bytes").getAsLong(), data.length);
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        assertEquals(v.get("sha256").getAsString(), hex.toString());
    }

    @Test
    public void decodesEveryTileLikeTheReference() throws Exception {
        JsonObject expected = Fixture.vectors().getAsJsonObject("package");
        File f = Fixture.write(tmp.getRoot(), "ZZ.zslp", Fixture.bytes());
        try (LimitPackage p = LimitPackage.open(f)) {
            assertEquals(expected.get("country").getAsString(), p.country);
            assertEquals(expected.get("date").getAsInt(), p.date);
            JsonArray bounds = expected.getAsJsonArray("bounds");
            assertEquals(bounds.get(0).getAsInt(), p.minLat);
            assertEquals(bounds.get(1).getAsInt(), p.minLon);
            assertEquals(bounds.get(2).getAsInt(), p.maxLat);
            assertEquals(bounds.get(3).getAsInt(), p.maxLon);
            JsonArray tiles = expected.getAsJsonArray("tiles");
            assertEquals(tiles.size(), p.tileCount());
            for (int t = 0; t < tiles.size(); t++) {
                JsonObject tile = tiles.get(t).getAsJsonObject();
                int x = tile.get("x").getAsInt();
                int y = tile.get("y").getAsInt();
                assertTrue(p.has(x, y));
                List<LimitRoad> roads = p.tile(x, y);
                JsonArray want = tile.getAsJsonArray("roads");
                assertEquals("tile " + x + "/" + y, want.size(), roads.size());
                for (int r = 0; r < want.size(); r++) {
                    JsonObject w = want.get(r).getAsJsonObject();
                    LimitRoad road = roads.get(r);
                    String where = "tile " + x + "/" + y + " road " + r;
                    assertEquals(where, w.get("class").getAsInt(), road.roadClass);
                    assertEquals(where, w.get("oneway").getAsInt(), road.oneway);
                    assertEquals(where, w.get("mph").getAsBoolean(), road.mph);
                    assertEquals(where, w.get("forward").getAsInt(), road.forward);
                    assertEquals(where, w.get("backward").getAsInt(), road.backward);
                    assertEquals(where, w.get("level").getAsInt(), road.level);
                    JsonArray points = w.getAsJsonArray("points");
                    assertEquals(where, points.size(), road.points());
                    for (int i = 0; i < points.size(); i++) {
                        assertEquals(where, points.get(i).getAsJsonArray().get(0).getAsInt(), road.latE5(i));
                        assertEquals(where, points.get(i).getAsJsonArray().get(1).getAsInt(), road.lonE5(i));
                    }
                }
            }
            assertNull("包里没有的那一块", p.tile(0, 0));
            p.checkAll();
        }
    }

    @Test
    public void refusesBrokenPackages() throws Exception {
        byte[] good = Fixture.bytes();
        refused("cut short", Arrays.copyOf(good, good.length - 1));
        refused("one byte too many", Arrays.copyOf(good, good.length + 1));
        refused("empty", new byte[0]);
        byte[] magic = good.clone();
        magic[0] = 'X';
        refused("magic", magic);
        byte[] version = good.clone();
        version[4] = 2;
        refused("version", version);
        byte[] zoom = good.clone();
        zoom[5] = 13;
        refused("zoom", zoom);
        refused("country", Fixture.withCountry(good, "z1"));
        byte[] count = good.clone();
        count[28] = (byte) (count[28] + 1);
        refused("tile count", count);
    }

    @Test
    public void damagedTileIsFoundByTheFullCheck() throws Exception {
        byte[] bad = Fixture.bytes();
        // 最后一块的数据中间改一个字节：头和索引都对，打得开；解到那一块时 zlib 校验不过
        bad[bad.length - 6] ^= 0x5A;
        File f = Fixture.write(tmp.getRoot(), "bad.zslp", bad);
        try (LimitPackage p = LimitPackage.open(f)) {
            p.checkAll();
            fail("坏的那一块应该解不开");
        } catch (LimitPackage.Invalid expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private void refused(String what, byte[] data) throws IOException {
        File f = Fixture.write(tmp.getRoot(), what.replace(' ', '-') + ".zslp", data);
        try (LimitPackage p = LimitPackage.open(f)) {
            p.checkAll();
            fail(what + "：应该不认");
        } catch (LimitPackage.Invalid expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
