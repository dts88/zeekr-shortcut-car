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
 * 自动化的存取（{@code AppConfig.getAutomationRules}，ui-spec §十八「数据」）：一个 JSON 数组，一条一个对象。
 * 读不懂的那一条整条不要（记一句日志，不抛）；读不懂的条件、动作也是整条不要 —— 少了一个条件的规则会在不该做的时候做。
 *
 * <pre>
 * [{"id":"…","name":"…","on":true,
 *   "when":{"kind":"gear","gear":"R"},
 *   "if":[{"kind":"battery_at_least","n":20},{"kind":"not_in_places","places":["…"]}],
 *   "then":[{"setting":"low_speed_braking","value":"off"}],
 *   "restore":true}]
 * </pre>
 */
public final class RuleStore {

    private static final String TAG = "RuleStore";

    private RuleStore() {
    }

    /** 读；空的、读不懂的是空列表。 */
    public static List<Rule> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                Rule rule = o == null ? null : rule(o);
                if (rule == null) {
                    AppLog.w(TAG, "skipped rule " + (i + 1));
                } else {
                    rules.add(rule);
                }
            }
        } catch (JSONException e) {
            AppLog.w(TAG, "unreadable rules: " + e.getMessage());
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(rules);
    }

    /** 写成 JSON。 */
    public static String format(List<Rule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                if (r.name != null) {
                    o.put("name", r.name);
                }
                o.put("on", r.enabled);
                o.put("when", trigger(r.trigger));
                JSONArray conditions = new JSONArray();
                for (Condition c : r.conditions) {
                    conditions.put(condition(c));
                }
                o.put("if", conditions);
                JSONArray changes = new JSONArray();
                for (Change c : r.changes) {
                    changes.put(new JSONObject().put("setting", c.setting.key).put("value", c.value));
                }
                o.put("then", changes);
                o.put("restore", r.restore);
                array.put(o);
            }
        } catch (JSONException e) {
            // 只放字符串、数字、布尔：不会走到这里
            throw new IllegalStateException(e);
        }
        return array.toString();
    }

    private static Rule rule(JSONObject o) {
        Rule rule = read(o);
        return rule != null && rule.complete() ? rule : null;
    }

    /** 照存的写法读一条，不管能不能跑（分享码的测试要把不完整的规则也读出来）；读不懂的是 null。 */
    static Rule read(JSONObject o) {
        String id = o.optString("id", null);
        Trigger trigger = trigger(o.optJSONObject("when"));
        if (id == null || id.isEmpty() || trigger == null) {
            return null;
        }
        List<Condition> conditions = new ArrayList<>();
        JSONArray ifs = o.optJSONArray("if");
        for (int i = 0; ifs != null && i < ifs.length(); i++) {
            Condition c = condition(ifs.optJSONObject(i));
            if (c == null) {
                return null;
            }
            conditions.add(c);
        }
        List<Change> changes = new ArrayList<>();
        JSONArray thens = o.optJSONArray("then");
        for (int i = 0; thens != null && i < thens.length(); i++) {
            JSONObject t = thens.optJSONObject(i);
            Change c = t == null ? null : Change.of(Setting.of(t.optString("setting")), t.optString("value"));
            if (c == null) {
                return null;
            }
            changes.add(c);
        }
        String name = o.isNull("name") ? null : o.optString("name", null);
        return new Rule(id, name, o.optBoolean("on", false), trigger, conditions, changes,
                o.optBoolean("restore", false));
    }

    private static JSONObject trigger(Trigger t) throws JSONException {
        JSONObject o = new JSONObject();
        switch (t.kind) {
            case GET_IN:
                return o.put("kind", "get_in");
            case GEAR:
                return o.put("kind", "gear").put("gear", t.gear);
            case ARRIVE:
                return o.put("kind", "arrive").put("place", t.place);
            case LEAVE:
                return o.put("kind", "leave").put("place", t.place);
            default:
                return o.put("kind", "state").put("setting", t.setting.key).put("value", t.value);
        }
    }

    private static Trigger trigger(JSONObject o) {
        if (o == null) {
            return null;
        }
        switch (o.optString("kind")) {
            case "get_in":
                return Trigger.getIn();
            case "gear":
                return Trigger.gear(o.optString("gear"));
            case "state":
                return Trigger.state(Setting.of(o.optString("setting")), o.optString("value"));
            case "arrive":
                return Trigger.arrive(o.optString("place", null));
            case "leave":
                return Trigger.leave(o.optString("place", null));
            default:
                return null;
        }
    }

    private static JSONObject condition(Condition c) throws JSONException {
        JSONObject o = new JSONObject();
        switch (c.kind) {
            case GEAR_IS:
                return o.put("kind", "gear_is").put("gear", c.gear);
            case BATTERY_AT_LEAST:
                return o.put("kind", "battery_at_least").put("n", c.number);
            case BATTERY_BELOW:
                return o.put("kind", "battery_below").put("n", c.number);
            case STATE_IS:
                return o.put("kind", "state_is").put("setting", c.setting.key).put("value", c.value);
            case TIME_BETWEEN:
                return o.put("kind", "time_between").put("from", c.from).put("to", c.to);
            case IN_PLACES:
                return o.put("kind", "in_places").put("places", new JSONArray(c.places));
            case NOT_IN_PLACES:
                return o.put("kind", "not_in_places").put("places", new JSONArray(c.places));
            default:
                return o.put("kind", "weekdays").put("days", c.days);
        }
    }

    private static Condition condition(JSONObject o) {
        if (o == null) {
            return null;
        }
        switch (o.optString("kind")) {
            case "gear_is":
                return Condition.gearIs(o.optString("gear"));
            case "battery_at_least":
                return o.has("n") ? Condition.batteryAtLeast(o.optInt("n", -1)) : null;
            case "battery_below":
                return o.has("n") ? Condition.batteryBelow(o.optInt("n", -1)) : null;
            case "state_is":
                return Condition.stateIs(Setting.of(o.optString("setting")), o.optString("value"));
            case "time_between":
                return o.has("from") && o.has("to") ? Condition.timeBetween(o.optInt("from", -1), o.optInt("to", -1)) : null;
            case "weekdays":
                return Condition.weekdays(o.optInt("days", 0));
            case "in_places":
                return Condition.inPlaces(strings(o.optJSONArray("places")));
            case "not_in_places":
                return Condition.notInPlaces(strings(o.optJSONArray("places")));
            default:
                return null;
        }
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String s = array.optString(i, null);
            if (s == null || s.isEmpty()) {
                return null;
            }
            out.add(s);
        }
        return out;
    }
}
