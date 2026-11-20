/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 自动化的存取（{@link RuleStore}）和一条规则的几个判断。JSON 要真的实现，所以跑在 Robolectric 上。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class RuleStoreTest {

    @Test
    public void rulesSurviveARoundTrip() {
        Rule reverse = new Rule("a", "倒车入库 \"家\"", true, Trigger.gear("R"),
                Arrays.asList(Condition.batteryAtLeast(20), Condition.timeBetween(22 * 60, 6 * 60), Condition.weekdays(0x1F),
                        Condition.gearIs("R"), Condition.stateIs(Setting.FRONT_CLIMATE, "on"), Condition.batteryBelow(90)),
                Arrays.asList(Change.of(Setting.LOW_SPEED_BRAKING, "off"), Change.of(Setting.REAR_CLIMATE, "on")), true);
        Rule climate = new Rule("b", null, false, Trigger.state(Setting.FRONT_CLIMATE, "on"),
                Collections.emptyList(), Collections.singletonList(Change.of(Setting.CIRCULATION, "inner")), false);
        Rule start = new Rule("c", null, true, Trigger.getIn(), Collections.emptyList(),
                Arrays.asList(Change.of(Setting.DRIVE_MODE, "comfort"), Change.of(Setting.HILL_DESCENT, "on")), false);
        Rule leave = new Rule("d", null, true, Trigger.leave(), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.SENTRY, "on")), true);

        List<Rule> back = RuleStore.parse(RuleStore.format(Arrays.asList(reverse, climate, start, leave)));
        assertEquals(4, back.size());
        Rule r = back.get(0);
        assertEquals("a", r.id);
        assertEquals("倒车入库 \"家\"", r.name);
        assertTrue(r.enabled);
        assertEquals(Trigger.gear("R"), r.trigger);
        assertEquals(6, r.conditions.size());
        assertEquals("battery>=20", r.conditions.get(0).describe());
        assertEquals("22:00-06:00", r.conditions.get(1).describe());
        assertEquals(0x1F, r.conditions.get(2).days);
        assertEquals("gear=R", r.conditions.get(3).describe());
        assertEquals("front_climate=on", r.conditions.get(4).describe());
        assertEquals("battery<90", r.conditions.get(5).describe());
        assertEquals("low_speed_braking=off", r.changes.get(0).describe());
        assertEquals("rear_climate=on", r.changes.get(1).describe());
        assertTrue(r.restore);

        assertNull(back.get(1).name);
        assertFalse(back.get(1).enabled);
        assertEquals(Trigger.state(Setting.FRONT_CLIMATE, "on"), back.get(1).trigger);
        assertEquals(Trigger.getIn(), back.get(2).trigger);
        assertEquals(2, back.get(2).changes.size());
        assertEquals(Trigger.leave(), back.get(3).trigger);
    }

    /** 读不懂的那一条整条不要：少了一个条件的规则会在不该做的时候做。 */
    @Test
    public void unreadableRulesAreDroppedWhole() {
        String json = "["
                + "{\"id\":\"ok\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"then\":[{\"setting\":\"sentry\",\"value\":\"on\"}]},"
                + "{\"id\":\"no-trigger\",\"on\":true,\"then\":[{\"setting\":\"sentry\",\"value\":\"on\"}]},"
                + "{\"id\":\"bad-gear\",\"on\":true,\"when\":{\"kind\":\"gear\",\"gear\":\"X\"},\"then\":[{\"setting\":\"sentry\",\"value\":\"on\"}]},"
                + "{\"id\":\"no-change\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"then\":[]},"
                + "{\"id\":\"read-only\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"then\":[{\"setting\":\"front_climate\",\"value\":\"on\"}]},"
                + "{\"id\":\"bad-value\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"then\":[{\"setting\":\"drive_mode\",\"value\":\"eco\"}]},"
                + "{\"id\":\"bad-condition\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"if\":[{\"kind\":\"battery_at_least\",\"n\":150}],"
                + "\"then\":[{\"setting\":\"sentry\",\"value\":\"on\"}]},"
                + "{\"id\":\"unknown-condition\",\"on\":true,\"when\":{\"kind\":\"get_in\"},\"if\":[{\"kind\":\"moon\"}],"
                + "\"then\":[{\"setting\":\"sentry\",\"value\":\"on\"}]},"
                + "42]";
        List<Rule> rules = RuleStore.parse(json);
        assertEquals(1, rules.size());
        assertEquals("ok", rules.get(0).id);

        assertTrue(RuleStore.parse("").isEmpty());
        assertTrue(RuleStore.parse(null).isEmpty());
        assertTrue(RuleStore.parse("not json").isEmpty());
    }

    /** 结束时恢复：有结束的触发才恢复；关掉安全功能的不管勾没勾都恢复。 */
    @Test
    public void safetyOffAlwaysRestoresWhenTheTriggerCanEnd() {
        Rule unticked = new Rule("a", null, true, Trigger.gear("R"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.LOW_SPEED_BRAKING, "off")), false);
        assertTrue(unticked.restores());
        Rule plain = new Rule("b", null, true, Trigger.gear("R"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.REAR_CLIMATE, "on")), false);
        assertFalse(plain.restores());
        Rule noEnd = new Rule("c", null, true, Trigger.leave(), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.LOW_SPEED_BRAKING, "off")), true);
        assertFalse("离车没有结束", noEnd.restores());
        assertTrue(Change.of(Setting.LOW_SPEED_BRAKING, "off").safetyOff());
        assertFalse(Change.of(Setting.LOW_SPEED_BRAKING, "on").safetyOff());
        assertNull("只读的不能当动作", Change.of(Setting.FRONT_CLIMATE, "on"));
    }
}
