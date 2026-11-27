/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 分享码（{@link ShareCode}）过跟 zeekr.autos 共用的那一份用例（测试资源 sharecode/vectors.json，由本地参考实现生成）。
 * 规则照存的写法读（{@link RuleStore#read}），JSON 要真的实现，所以跑在 Robolectric 上。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ShareCodeTest {

    private static JsonObject vectors;

    @BeforeClass
    public static void load() throws Exception {
        try (InputStream in = ShareCodeTest.class.getResourceAsStream("/sharecode/vectors.json")) {
            assertNotNull("sharecode/vectors.json", in);
            vectors = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals(1, vectors.get("version").getAsInt());
    }

    @Test
    public void tablesMatchTheVectorsAndTheApp() {
        assertEquals(vectors.get("letters").getAsString(), ShareCode.LETTERS);
        assertEquals(vectors.getAsJsonObject("display").get("group").getAsInt(), ShareCode.GROUP);
        assertEquals(" ", vectors.getAsJsonObject("display").get("separator").getAsString());
        assertEquals(strings(vectors.getAsJsonArray("gears")), Arrays.asList(ShareCode.GEARS));

        JsonArray settings = vectors.getAsJsonArray("settings");
        assertEquals(settings.size(), ShareCode.SETTINGS.length);
        for (JsonElement e : settings) {
            JsonObject s = e.getAsJsonObject();
            int no = s.get("no").getAsInt();
            Setting setting = ShareCode.SETTINGS[no];
            String key = s.get("key").getAsString();
            assertEquals(key, setting.key);
            if (s.has("values")) {
                assertFalse(key, setting.range());
                assertEquals(key, strings(s.getAsJsonArray("values")), Arrays.asList(ShareCode.VALUES[no]));
                // 码里的值跟应用里的是同一套名字，只是先后由码定
                assertEquals(key, new HashSet<>(Arrays.asList(setting.valueNames())),
                        new HashSet<>(Arrays.asList(ShareCode.VALUES[no])));
            } else {
                assertTrue(key, setting.range());
                assertNull(key, ShareCode.VALUES[no]);
                assertEquals(key, s.get("min").getAsInt(), setting.min());
                assertEquals(key, s.get("max").getAsInt(), setting.max());
            }
            assertEquals(key, s.has("readOnly"), setting.readOnly());
            assertEquals(key, s.has("noRestore"), !setting.restorable());
            assertEquals(key, s.has("safety"), setting.isSafetyOff("off"));
        }
        // 应用加了新设置，分享码的编号表也要跟着定（不然那一项分享不了）
        assertEquals(new HashSet<>(Arrays.asList(Setting.values())), new HashSet<>(Arrays.asList(ShareCode.SETTINGS)));
    }

    @Test
    public void encodesEveryCase() throws Exception {
        for (JsonElement e : vectors.getAsJsonArray("encode")) {
            JsonObject c = e.getAsJsonObject();
            String name = c.get("name").getAsString();
            String code = c.get("code").getAsString();
            Rule rule = read(c.getAsJsonObject("rule"));
            assertNotNull(name, rule);
            assertEquals(name, code, ShareCode.encode(rule));
            assertEquals(name, c.get("display").getAsString(), ShareCode.display(code));
            assertEquals(name, code, ShareCode.normalize(ShareCode.display(code)));
            assertEquals(name, code, ShareCode.encode(ShareCode.decode(code)));
        }
    }

    @Test
    public void sameSettingsGiveTheSameCode() throws Exception {
        for (JsonElement e : vectors.getAsJsonArray("sameCode")) {
            JsonObject c = e.getAsJsonObject();
            String name = c.get("name").getAsString();
            for (JsonElement r : c.getAsJsonArray("rules")) {
                Rule rule = read(r.getAsJsonObject());
                assertNotNull(name, rule);
                assertEquals(name, c.get("code").getAsString(), ShareCode.encode(rule));
            }
        }
    }

    @Test
    public void decodesEveryCase() throws Exception {
        for (JsonElement e : vectors.getAsJsonArray("decode")) {
            JsonObject c = e.getAsJsonObject();
            String name = c.get("name").getAsString();
            Rule rule = ShareCode.decode(c.get("input").getAsString());
            assertNull(name, rule.id);
            assertFalse(name, rule.enabled);
            assertEquals(name, c.getAsJsonObject("rule"), json(rule));
        }
    }

    @Test
    public void rejectsEveryCase() {
        for (JsonElement e : vectors.getAsJsonArray("reject")) {
            JsonObject c = e.getAsJsonObject();
            String name = c.get("name").getAsString();
            try {
                ShareCode.decode(c.get("input").getAsString());
                fail("accepted: " + name);
            } catch (ShareCode.Rejected r) {
                assertEquals(name, c.get("error").getAsString(), r.problem.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Test
    public void refusesRulesThatCannotBeShared() throws Exception {
        for (JsonElement e : vectors.getAsJsonArray("encodeError")) {
            JsonObject c = e.getAsJsonObject();
            String name = c.get("name").getAsString();
            String kind = c.get("error").getAsString();
            Rule rule = read(c.getAsJsonObject("rule"));
            if (rule == null) {
                // 值不对的规则在应用里根本存不下（RuleStore 读不出来），也就轮不到分享
                assertEquals(name, "value", kind);
                continue;
            }
            try {
                ShareCode.encode(rule);
                fail("encoded: " + name);
            } catch (ShareCode.Rejected r) {
                assertEquals(name, kind, r.problem.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Test
    public void codesAreShownInGroupsOfFour() {
        assertEquals(Arrays.asList("1EC1", "2567"), ShareCode.groups("1EC12567"));
        assertEquals("1L1W 1234 5C01 293", ShareCode.display("1L1W12345C01293"));
        assertEquals("", ShareCode.display(""));
        // 照显示的样子打、小写、连字符、全角、O 和 I、换行、竖向制表符都认（不换行空格经 NFKC 成了空格）；
        // 别的空白照原样留着，验的时候按字符不对拦下（三种语言的 \s 不一样，只认列出来的这几个）
        assertEquals("1EC12567", ShareCode.normalize(" 1ec1 - 2567\n"));
        assertEquals("1EC12567", ShareCode.normalize("ＩＥＣＩ　２５６７"));
        assertEquals("1EC10567", ShareCode.normalize("1EC1\u000BO567"));
        assertEquals("1EC12567", ShareCode.normalize("1EC1 2567"));
        assertEquals("1EC1 2567", ShareCode.normalize("1ec1 2567"));
    }

    @Test
    public void importedRulesUseTheUsersPlaces() throws Exception {
        // 上车，在地点甲或乙，不在地点丙 → 陡坡缓降开启、外循环
        String code = vectors.getAsJsonArray("encode").get(6).getAsJsonObject().get("code").getAsString();
        Rule shared = ShareCode.decode(code);
        assertEquals(3, ShareCode.places(shared));

        Rule mine = ShareCode.adopt(shared, "new-1", Arrays.asList("home", "office", "gym"));
        assertNotNull(mine);
        assertEquals("new-1", mine.id);
        assertTrue(mine.enabled);
        assertTrue(mine.complete());
        assertEquals(Arrays.asList("home", "office"), mine.conditions.get(0).places);
        assertEquals(Collections.singletonList("gym"), mine.conditions.get(1).places);
        assertEquals(code, ShareCode.encode(mine));

        assertNull("same place twice", ShareCode.adopt(shared, "x", Arrays.asList("home", "home", "gym")));
        assertNull("too few", ShareCode.adopt(shared, "x", Arrays.asList("home", "office")));

        // 当里的地点
        Rule arrive = ShareCode.decode("1A1G0C580963");
        Rule garage = ShareCode.adopt(arrive, "new-2", Collections.singletonList("garage"));
        assertEquals(Trigger.arrive("garage"), garage.trigger);
        assertEquals("1A1G0C580963", ShareCode.encode(garage));

        // 没有地点的
        Rule plain = ShareCode.decode("1EC12567");
        assertEquals(0, ShareCode.places(plain));
        assertEquals("1EC12567", ShareCode.encode(ShareCode.adopt(plain, "new-3", Collections.emptyList())));
    }

    @Test
    public void storedRulesAlwaysSurviveTheRoundTrip() throws Exception {
        // 应用里存得下的规则（地点不超过 9 个、「在…」「不在…」各一个）都能分享，解回来再编一字不差
        Set<String> seen = new HashSet<>();
        for (JsonElement e : vectors.getAsJsonArray("encode")) {
            String code = e.getAsJsonObject().get("code").getAsString();
            Rule back = ShareCode.decode(code);
            List<String> places = Arrays.asList("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9")
                    .subList(0, ShareCode.places(back));
            Rule mine = ShareCode.adopt(back, "id", places);
            assertTrue(code, mine.complete());
            List<Rule> stored = RuleStore.parse(RuleStore.format(Collections.singletonList(mine)));
            assertEquals(code, 1, stored.size());
            assertEquals(code, ShareCode.encode(stored.get(0)));
            assertTrue(code, seen.add(code));
        }
    }

    /** 用例里的规则 → 应用的规则（照存的写法读；没有 id 的补一个）。值不对的读不出来，是 null。 */
    private static Rule read(JsonObject rule) throws JSONException {
        JSONObject o = new JSONObject(rule.toString());
        if (!o.has("id")) {
            o.put("id", "t");
        }
        return RuleStore.read(o);
    }

    /** 应用的规则 → 存的写法，去掉不进码的 id、名字、开关。 */
    private static JsonObject json(Rule rule) {
        JsonObject o = JsonParser.parseString(RuleStore.format(Collections.singletonList(rule)))
                .getAsJsonArray().get(0).getAsJsonObject();
        o.remove("id");
        o.remove("name");
        o.remove("on");
        return o;
    }

    private static List<String> strings(JsonArray array) {
        String[] out = new String[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsString();
        }
        return Arrays.asList(out);
    }
}
