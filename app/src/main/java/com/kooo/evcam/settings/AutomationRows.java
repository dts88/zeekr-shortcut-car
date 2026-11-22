/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.RuleStore;
import com.kooo.evcam.automation.Setting;
import com.kooo.evcam.automation.Trigger;
import com.kooo.evcam.button.DeviceLabel;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 地点与自动化页上「自动化」那一组（ui-spec §十六）：每一条一行 —— 开关管这一条开不开，点这一行展开、收起，行尾「删除」；
 * 展开以后从上往下读是一句话：名称、当、而且（每个条件一行）、添加条件、就（每个动作一行）、添加动作、结束时恢复
 * （只给人在车上时会结束的触发，ui-spec §十八）。最后是「添加自动化」，弹常用的几条和「空白」。
 *
 * <p>存在设置里（{@code AppConfig#getAutomationRules}，{@link RuleStore} 的 JSON）：改一下存一下，跑规则的那一个
 * （{@code automation.Automations}）听着它。设置备份导入了、别处改了，照存着的重画。</p>
 */
final class AutomationRows {

    static final String CATEGORY_KEY = "cat_automation";
    static final String ADD_KEY = "pref_automation_add";
    private static final String ROW_PREFIX = "automation_rule_";
    /** 「添加自动化」那一行排在最后（各条照列表先后排在它前面）。 */
    private static final int ADD_ORDER = 1_000_000;

    private final SettingsPreferenceFragment fragment;
    private final PreferenceCategory category;
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Rows> rows = new LinkedHashMap<>();
    /** 照着画的那一份 JSON：自己存的、别处改的都从这里比。 */
    private String drawn;
    private AppConfig config;
    private SharedPreferences.OnSharedPreferenceChangeListener storeListener;

    /** 一条自动化的几行。 */
    private static final class Rows {
        SwitchActionPreference row;
        final List<Preference> items = new ArrayList<>();
    }

    AutomationRows(SettingsPreferenceFragment fragment, PreferenceCategory category) {
        this.fragment = fragment;
        this.category = category;
    }

    void bind() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        Preference add = category.findPreference(ADD_KEY);
        if (add != null) {
            add.setOrder(ADD_ORDER);
            add.setOnPreferenceClickListener(p -> {
                addRule();
                return true;
            });
        }
        config = new AppConfig(context);
        storeListener = config.onAutomationRulesChanged(this::storeChanged);
        rebuild();
    }

    void detach() {
        if (config != null && storeListener != null) {
            config.removeChangeListener(storeListener);
        }
        storeListener = null;
        config = null;
    }

    // ================================================================= 读、存

    private List<Rule> load() {
        Context context = fragment.getContext();
        return context == null ? new ArrayList<>() : new ArrayList<>(RuleStore.parse(new AppConfig(context).getAutomationRules()));
    }

    private void save(List<Rule> rules) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        String json = RuleStore.format(rules);
        drawn = json;
        new AppConfig(context).setAutomationRules(json);
        rebuild(rules);
    }

    /** 改一条（照 id 找）。 */
    private void update(String id, UnaryOperator<Rule> change) {
        List<Rule> rules = load();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(id)) {
                rules.set(i, change.apply(rules.get(i)));
                save(rules);
                return;
            }
        }
    }

    private void storeChanged() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        if (!Objects.equals(drawn, new AppConfig(context).getAutomationRules())) {
            rebuild();
        }
    }

    // ================================================================= 画

    private void rebuild() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        drawn = new AppConfig(context).getAutomationRules();
        rebuild(load());
    }

    private void rebuild(List<Rule> rules) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        List<Preference> old = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference p = category.getPreference(i);
            if (p.getKey() != null && p.getKey().startsWith(ROW_PREFIX)) {
                old.add(p);
            }
        }
        for (Preference p : old) {
            category.removePreference(p);
        }
        rows.clear();
        Set<String> ids = new HashSet<>();
        int order = 0;
        for (Rule r : rules) {
            ids.add(r.id);
            order = add(context, r, order);
        }
        expanded.retainAll(ids);
    }

    private int add(Context c, Rule r, int order) {
        Rows rs = new Rows();
        String base = ROW_PREFIX + r.id;
        rs.row = new SwitchActionPreference(c);
        rs.row.setKey(base);
        rs.row.setOrder(order++);
        rs.row.setTitle(AutomationTexts.title(c, r));
        rs.row.setSummary(AutomationTexts.summary(c, r));
        rs.row.setChecked(r.enabled);
        rs.row.setSwitch(on -> update(r.id, x -> x.withEnabled(on)));
        rs.row.setAction(c.getString(R.string.action_delete), () -> confirmDelete(r.id));
        rs.row.setOnPreferenceClickListener(p -> {
            toggle(r.id);
            return true;
        });
        category.addPreference(rs.row);

        ClauseItemPreference name = clause(c, base + "/name", order++, R.string.auto_label_name,
                AutomationTexts.title(c, r));
        name.setOnPreferenceClickListener(p -> {
            editName(r);
            return true;
        });
        rs.items.add(name);

        ClauseItemPreference when = clause(c, base + "/when", order++, R.string.auto_label_when,
                AutomationTexts.trigger(c, r.trigger));
        when.setOnPreferenceClickListener(p -> {
            AutomationPickers.pickTrigger(c, r.trigger, AutomationTexts.hasSafetyOff(r),
                    t -> update(r.id, x -> with(x, t, x.conditions, x.changes, x.restore)));
            return true;
        });
        rs.items.add(when);

        for (int i = 0; i < r.conditions.size(); i++) {
            int index = i;
            Condition k = r.conditions.get(i);
            ClauseItemPreference row = clause(c, base + "/and" + i, order++, i == 0 ? R.string.auto_label_and : 0,
                    AutomationTexts.condition(c, k));
            row.setOnPreferenceClickListener(p -> {
                AutomationPickers.pickCondition(c, fragment.getChildFragmentManager(), k,
                        picked -> update(r.id, x -> with(x, x.trigger, replace(x.conditions, index, picked), x.changes,
                                x.restore)),
                        () -> update(r.id, x -> with(x, x.trigger, remove(x.conditions, index), x.changes, x.restore)));
                return true;
            });
            rs.items.add(row);
        }
        ClauseItemPreference addCondition = clause(c, base + "/and+", order++,
                r.conditions.isEmpty() ? R.string.auto_label_and : 0, c.getString(R.string.auto_add_condition));
        addCondition.setAdd(true);
        addCondition.setOnPreferenceClickListener(p -> {
            AutomationPickers.pickCondition(c, fragment.getChildFragmentManager(), null,
                    picked -> update(r.id, x -> with(x, x.trigger, append(x.conditions, picked), x.changes, x.restore)),
                    null);
            return true;
        });
        rs.items.add(addCondition);

        for (int i = 0; i < r.changes.size(); i++) {
            int index = i;
            Change ch = r.changes.get(i);
            ClauseItemPreference row = clause(c, base + "/then" + i, order++, i == 0 ? R.string.auto_label_then : 0,
                    AutomationTexts.change(c, ch));
            row.setOnPreferenceClickListener(p -> {
                AutomationPickers.pickChange(c, ch, r.trigger,
                        picked -> update(r.id, x -> with(x, x.trigger, x.conditions, replace(x.changes, index, picked),
                                x.restore)),
                        r.changes.size() > 1
                                ? () -> update(r.id, x -> with(x, x.trigger, x.conditions, remove(x.changes, index), x.restore))
                                : null);
                return true;
            });
            rs.items.add(row);
        }
        ClauseItemPreference addChange = clause(c, base + "/then+", order++, 0, c.getString(R.string.auto_add_change));
        addChange.setAdd(true);
        addChange.setOnPreferenceClickListener(p -> {
            AutomationPickers.pickChange(c, null, r.trigger,
                    picked -> update(r.id, x -> with(x, x.trigger, x.conditions, append(x.changes, picked), x.restore)),
                    null);
            return true;
        });
        rs.items.add(addChange);

        if (AutomationTexts.ends(r.trigger)) {
            CardItemPreference restore = new CardItemPreference(c);
            restore.setKey(base + "/restore");
            restore.setOrder(order++);
            restore.setTitle(R.string.auto_restore);
            restore.setSummary(AutomationTexts.restoreSummary(c, r));
            restore.setSwitch(on -> update(r.id, x -> with(x, x.trigger, x.conditions, x.changes, on)));
            restore.setChecked(r.restores());
            // 关掉安全功能的那一条必须恢复：开着、变灰
            restore.setEnabled(!AutomationTexts.hasSafetyOff(r));
            category.addPreference(restore);
            rs.items.add(restore);
        }
        ((CardItemPreference) rs.items.get(rs.items.size() - 1)).setLast(true);
        rows.put(r.id, rs);
        show(r.id, rs);
        return order;
    }

    private ClauseItemPreference clause(Context c, String key, int order, int label, CharSequence text) {
        ClauseItemPreference p = new ClauseItemPreference(c);
        p.setKey(key);
        p.setOrder(order);
        p.setLabel(label == 0 ? null : c.getString(label));
        p.setTitle(text);
        category.addPreference(p);
        return p;
    }

    private void show(String id, Rows rs) {
        boolean open = expanded.contains(id);
        rs.row.setExpanded(open);
        for (Preference p : rs.items) {
            if (p.isVisible() != open) {
                p.setVisible(open);
            }
        }
    }

    private void toggle(String id) {
        Rows rs = rows.get(id);
        if (rs == null) {
            return;
        }
        if (!expanded.remove(id)) {
            expanded.add(id);
        }
        show(id, rs);
    }

    // ================================================================= 改

    private static Rule with(Rule r, Trigger trigger, List<Condition> conditions, List<Change> changes, boolean restore) {
        return new Rule(r.id, r.name, r.enabled, trigger, conditions, changes,
                restore && AutomationTexts.ends(trigger));
    }

    private static <T> List<T> replace(List<T> list, int index, T item) {
        List<T> copy = new ArrayList<>(list);
        if (item != null && index >= 0 && index < copy.size()) {
            copy.set(index, item);
        }
        return copy;
    }

    private static <T> List<T> remove(List<T> list, int index) {
        List<T> copy = new ArrayList<>(list);
        if (index >= 0 && index < copy.size()) {
            copy.remove(index);
        }
        return copy;
    }

    private static <T> List<T> append(List<T> list, T item) {
        List<T> copy = new ArrayList<>(list);
        if (item != null) {
            copy.add(item);
        }
        return copy;
    }

    private void editName(Rule r) {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        EditText input = AccessoryRows.nameInput(c, AutomationTexts.givenName(r), AutomationTexts.defaultName(c, r));
        int pad = Math.round(24 * c.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_label_name)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String name = DeviceLabel.clean(input.getText().toString());
                    update(r.id, x -> new Rule(x.id, name, x.enabled, x.trigger, x.conditions, x.changes, x.restore));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void confirmDelete(String id) {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_delete_title)
                .setMessage(R.string.auto_delete_msg)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    List<Rule> rules = load();
                    rules.removeIf(x -> x.id.equals(id));
                    expanded.remove(id);
                    save(rules);
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ================================================================= 添加

    /** 「添加自动化」：常用的几条和「空白」。新的一条加在最后、展开着、开着。 */
    private void addRule() {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        List<AutomationPickers.Item> items = new ArrayList<>();
        items.add(new AutomationPickers.Item(AutomationPickers.twoLines(c, c.getString(R.string.auto_template_reverse),
                c.getString(R.string.auto_template_reverse_desc)), true));
        items.add(new AutomationPickers.Item(AutomationPickers.twoLines(c, c.getString(R.string.auto_template_get_in),
                c.getString(R.string.auto_template_get_in_desc)), true));
        items.add(new AutomationPickers.Item(AutomationPickers.twoLines(c, c.getString(R.string.auto_template_blank),
                c.getString(R.string.auto_template_blank_desc)), true));
        AutomationPickers.list(c, c.getString(R.string.auto_add), items, -1, which -> {
            if (which == 0) {
                append(new Rule(newId(), "", true, Trigger.gear("R"), Collections.emptyList(),
                        Collections.singletonList(Change.of(Setting.LOW_SPEED_BRAKING, "off")), true));
            } else if (which == 1) {
                AutomationPickers.pickChange(c, null, Trigger.getIn(), ch -> append(new Rule(newId(), "", true,
                        Trigger.getIn(), Collections.emptyList(), Collections.singletonList(ch), false)), null);
            } else {
                AutomationPickers.pickTrigger(c, null, false, t -> AutomationPickers.pickChange(c, null, t,
                        ch -> append(new Rule(newId(), "", true, t, Collections.emptyList(),
                                Collections.singletonList(ch), false)), null));
            }
        });
    }

    private void append(Rule r) {
        List<Rule> rules = load();
        rules.add(r);
        expanded.add(r.id);
        save(rules);
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 开着的有几条（左栏那一行写「已开启 N 项」）。 */
    static int enabledCount(AppConfig config) {
        int n = 0;
        for (Rule r : RuleStore.parse(config.getAutomationRules())) {
            if (r.enabled) {
                n++;
            }
        }
        return n;
    }
}
