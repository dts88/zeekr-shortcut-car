/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import com.kooo.evcam.AppLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地点的存取（{@code AppConfig.getAutomationPlaces}，ui-spec §十六「地点」）：一个 JSON 数组，按添加的先后，最多
 * {@link #MAX} 个。进设置备份；诊断报告里只写有几个（有名称和坐标）。读不懂的那一个不要（记一句日志）。
 *
 * <pre>
 * [{"id":"…","name":"…","lat":1.2345,"lon":103.8765,"r":600}]
 * </pre>
 *
 * <p>删一个地点时用到它的规则跟着改（{@link #withoutPlace}）。</p>
 */
public final class PlaceStore {

    private static final String TAG = "PlaceStore";
    public static final int MAX = 10;

    private PlaceStore() {
    }

    public static List<Place> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Place> places = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && places.size() < MAX; i++) {
                JSONObject o = array.optJSONObject(i);
                Place p = o == null || !o.has("lat") || !o.has("lon") ? null
                        : Place.of(o.optString("id", null), o.optString("name", ""), o.optDouble("lat", Double.NaN),
                        o.optDouble("lon", Double.NaN), o.optInt("r", -1));
                if (p == null || indexOf(places, p.id) >= 0) {
                    AppLog.w(TAG, "skipped place " + (i + 1));
                } else {
                    places.add(p);
                }
            }
        } catch (JSONException e) {
            AppLog.w(TAG, "unreadable places: " + e.getMessage());
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(places);
    }

    public static String format(List<Place> places) {
        JSONArray array = new JSONArray();
        try {
            for (Place p : places) {
                array.put(new JSONObject().put("id", p.id).put("name", p.name).put("lat", p.latitude)
                        .put("lon", p.longitude).put("r", p.radius));
            }
        } catch (JSONException e) {
            // 只放字符串、有限的数字：不会走到这里
            throw new IllegalStateException(e);
        }
        return array.toString();
    }

    /** 第几个（从 0 起），没有 -1。黑匣子里写「地点 序号」用它加一。 */
    public static int indexOf(List<Place> places, String id) {
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 用到这个地点的规则有几条（触发或条件）。 */
    public static int usedBy(List<Rule> rules, String id) {
        int n = 0;
        for (Rule r : rules) {
            if (r.usesPlace(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 删了这个地点以后的规则（ui-spec §十六「删除」）：条件里去掉它；条件里一个地点都不剩的去掉这个条件、这一条关掉；
     * 触发是它的这一条关掉（触发留着，界面上写这个地点已删除，用户自己改）。没用到它的原样。
     */
    public static List<Rule> withoutPlace(List<Rule> rules, String id) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.usesPlace(id)) {
                out.add(r);
                continue;
            }
            boolean off = r.trigger.place != null && r.trigger.place.equals(id);
            List<Condition> conditions = new ArrayList<>();
            for (Condition c : r.conditions) {
                if (c.places.isEmpty() || !c.places.contains(id)) {
                    conditions.add(c);
                    continue;
                }
                Condition rest = c.withoutPlace(id);
                if (rest == null) {
                    off = true;
                } else {
                    conditions.add(rest);
                }
            }
            out.add(new Rule(r.id, r.name, r.enabled && !off, r.trigger, conditions, r.changes, r.restore));
        }
        return out;
    }
}
