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

    /** 能不能跑：有触发、至少一个动作。存取时缺了的整条不要（{@code RuleStore}）。 */
    boolean complete() {
        return id != null && trigger != null && !changes.isEmpty();
    }

    /** 结束时恢复不恢复：勾了、或者有关掉安全功能的动作（必须恢复）。每一种触发都有结束（{@link Trigger}）。 */
    public boolean restores() {
        if (restore) {
            return true;
        }
        for (Change c : changes) {
            if (c.safetyOff()) {
                return true;
            }
        }
        return false;
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
