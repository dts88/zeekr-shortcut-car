/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

/**
 * 匹配（{@link LimitMatcher}）：用例里手写的场景 —— 双向两个方向不同的限速、单行逆着走、横着过、跨瓦片、
 * 没限速标签的路离得最近、service 不收、不限速、精度太差、够不着、抽稀后的弯。
 */
public class LimitMatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void everyScenario() throws Exception {
        File f = Fixture.write(tmp.getRoot(), "ZZ.zslp", Fixture.bytes());
        try (LimitAtlas atlas = LimitAtlas.open(Collections.singletonList(f), null)) {
            for (JsonElement e : Fixture.vectors().getAsJsonArray("queries")) {
                JsonObject q = e.getAsJsonObject();
                String name = q.get("name").getAsString();
                LimitMatcher.Match m = LimitMatcher.match(atlas, q.get("lat").getAsDouble(), q.get("lon").getAsDouble(),
                        number(q, "accuracy"), number(q, "bearing"), number(q, "speedKmh"));
                JsonElement expect = q.get("expect");
                if (expect == null || expect.isJsonNull()) {
                    assertNull(name + " → " + m, m);
                } else {
                    JsonObject x = expect.getAsJsonObject();
                    assertEquals(name, new LimitMatcher.Match(x.get("limit").getAsInt(), x.get("mph").getAsBoolean()), m);
                }
            }
        }
    }

    @Test
    public void onlySignedNumbersAreShown() {
        assertTrue(new LimitMatcher.Match(50, false).shown());
        assertFalse("不知道", new LimitMatcher.Match(LimitRoad.UNKNOWN, false).shown());
        assertFalse("不限速", new LimitMatcher.Match(LimitRoad.NO_LIMIT, false).shown());
        assertFalse("不是牌子上的数就不带 mph", new LimitMatcher.Match(LimitRoad.UNKNOWN, true).mph);
    }

    @Test
    public void turnsAreMeasuredBothWays() {
        assertEquals(0, LimitMatcher.turn(90, 90), 1e-9);
        assertEquals(20, LimitMatcher.turn(350, 10), 1e-9);
        assertEquals(20, LimitMatcher.turn(10, 350), 1e-9);
        assertEquals(180, LimitMatcher.turn(0, 180), 1e-9);
        assertEquals(90, LimitMatcher.turn(-90, 0), 1e-9);
        assertEquals(0, LimitMatcher.turn(90, 450), 1e-9);
    }

    @Test
    public void trackerWaitsForTwoAgreeingFixes() {
        LimitMatcher.Tracker t = new LimitMatcher.Tracker();
        LimitMatcher.Match fifty = new LimitMatcher.Match(50, false);
        LimitMatcher.Match seventy = new LimitMatcher.Match(70, false);
        assertNull("一次不算", t.update(fifty));
        assertEquals(fifty, t.update(fifty));
        assertEquals("换路也要连着两次", fifty, t.update(seventy));
        assertEquals("中间夹一次别的，重新数", fifty, t.update(fifty));
        assertEquals(fifty, t.update(seventy));
        assertEquals(seventy, t.update(seventy));
    }

    @Test
    public void trackerLetsGoAfterThreeMisses() {
        LimitMatcher.Tracker t = new LimitMatcher.Tracker();
        LimitMatcher.Match fifty = new LimitMatcher.Match(50, false);
        t.update(fifty);
        t.update(fifty);
        assertEquals(fifty, t.update(null));
        assertEquals(fifty, t.update(null));
        assertNull("连着三次没匹配到才收回", t.update(null));
        t.update(fifty);
        t.update(fifty);
        t.update(null);
        assertEquals("匹配到一次就重新数", fifty, t.update(fifty));
        t.reset();
        assertNull(t.shown());
    }

    private static Float number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsFloat();
    }
}
