/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条自动化（ui-spec §十八「一条自动化」）：当（一个触发）+ 而且（零到几个条件）+ 就（一到几个动作）+ 结束时恢复。不可变。
 * 名字是用户打的字（可以没有）：只当字显示和存取，不写进黑匣子（同灵动贴的名字）。
 */
public final class Rule {

    /** 存的时候认它（改名、改内容不变）。 */
    public final String id;
    /** 用户起的名字；没起过是 null。 */
    public final String name;
    public final boolean enabled;
    public final Trigger trigger;
    public final List<Condition> conditions;
    public final List<Change> changes;
    /** 用户勾的「结束时恢复」（关掉安全功能的那一条不管勾没勾都恢复，{@link #restores}）。 */
    public final boolean restore;

    public Rule(String id, String name, boolean enabled, Trigger trigger, List<Condition> conditions,
                List<Change> changes, boolean restore) {
        this.id = id;
        this.name = name;
        this.enabled = enabled;
        this.trigger = trigger;
        this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        this.restore = restore;
    }

    /**
     * 能不能跑：有触发、至少一个动作；有关掉安全功能的动作的，触发要在人在车上时会结束（必须恢复，ui-spec §十八），
     * 不然整条不跑。存取时不能跑的整条不要（{@code RuleStore}）。
     */
    boolean complete() {
        return id != null && trigger != null && !changes.isEmpty() && (!hasSafetyOff() || trigger.endsWhileLit());
    }

    /** 有没有关掉安全功能的动作（必须结束时恢复）。 */
    public boolean hasSafetyOff() {
        for (Change c : changes) {
            if (c.safetyOff()) {
                return true;
            }
        }
        return false;
    }

    /** 用没用到这个地点（触发或条件）。 */
    public boolean usesPlace(String id) {
        if (id.equals(trigger.place)) {
            return true;
        }
        for (Condition c : conditions) {
            if (c.places.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 要不要定位：触发是到达 / 离开某个地点，或者有地点条件。 */
    boolean needsPlace() {
        if (trigger.place != null) {
            return true;
        }
        for (Condition c : conditions) {
            if (c.needsPlace()) {
                return true;
            }
        }
        return false;
    }

    /** 结束时恢复不恢复：勾了、或者有关掉安全功能的动作（必须恢复）。会不会结束看触发（{@link Trigger#endsWhileLit}）。 */
    public boolean restores() {
        return (restore || hasSafetyOff()) && trigger.endsWhileLit();
    }

    /** 要不要读电量（{@link Condition#needsBattery}）。 */
    boolean needsBattery() {
        for (Condition c : conditions) {
            if (c.needsBattery()) {
                return true;
            }
        }
        return false;
    }

    public Rule withEnabled(boolean on) {
        return new Rule(id, name, on, trigger, conditions, changes, restore);
    }
}
