/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.view.View;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.RuleStore;
import com.kooo.evcam.automation.Setting;
import com.kooo.evcam.automation.Trigger;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 「地点与自动化」页（ui-spec §十六）：三条自动化的列表、展开以后、添加自动化的框、「当」「而且」「就」的选择框，
 * 白天 / 夜间 / 英文 / 阿拉伯文各一套；顺带看开关、删除、改了以后存进设置。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AutomationShotsTest {

    /** 倒车那条（关安全功能、必须恢复）、起了名字的上车那条（三个条件、两个动作）、关着的一条。 */
    static List<Rule> sample() {
        Rule reverse = new Rule("a1", "", true, Trigger.gear("R"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.LOW_SPEED_BRAKING, "off")), true);
        Rule morning = new Rule("a2", "工作日早上", true, Trigger.getIn(),
                Arrays.asList(Condition.weekdays(0x1F), Condition.timeBetween(7 * 60, 9 * 60 + 30),
                        Condition.batteryAtLeast(30)),
                Arrays.asList(Change.of(Setting.DRIVE_MODE, "comfort"), Change.of(Setting.REAR_CLIMATE, "off")), false);
        Rule sport = new Rule("a3", "", false, Trigger.state(Setting.DRIVE_MODE, "sport"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.CIRCULATION, "inner")), true);
        return Arrays.asList(reverse, morning, sport);
    }

    @Test
    public void byDay() {
        shoot(Shots.DAY, "day", true);
    }

    @Test
    public void inEnglish() {
        shoot(Shots.DAY_EN, "day-en", true);
    }

    @Test
    public void byNight() {
        shoot(Shots.NIGHT, "night", false);
    }

    @Test
    public void inArabic() {
        shoot(Shots.DAY_AR, "day-ar", false);
    }

    private void shoot(String qualifiers, String tag, boolean dialogs) {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAutomationRules(RuleStore.format(sample()));

        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_automation");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);

        SwitchActionPreference reverse = section.findPreference("automation_rule_a1");
        SwitchActionPreference morning = section.findPreference("automation_rule_a2");
        SwitchActionPreference sport = section.findPreference("automation_rule_a3");
        assertNotNull(reverse);
        assertNotNull(morning);
        assertNotNull(sport);
        assertNotNull(section.findPreference(AutomationRows.ADD_KEY));
        assertTrue(reverse.isChecked());
        assertFalse(sport.isChecked());
        assertEquals("没起名字的用第一个动作",
                activity.getString(R.string.auto_change, activity.getString(R.string.auto_setting_low_speed_braking),
                        activity.getString(R.string.auto_value_off)), String.valueOf(reverse.getTitle()));
        assertEquals("工作日早上", String.valueOf(morning.getTitle()));
        assertFalse("收着", section.findPreference("automation_rule_a1/when").isVisible());
        File list = Shots.save(activity, "settings-automation-" + tag + "-rules");
        assertTrue(list.length() > 10_000);

        // 展开上车那条：名称、当、而且三行、添加条件、就两行、添加动作；上车不会结束，没有「结束时恢复」
        click(morning);
        for (String row : new String[] {"name", "when", "and0", "and1", "and2", "and+", "then0", "then1", "then+"}) {
            assertTrue(row, section.findPreference("automation_rule_a2/" + row).isVisible());
        }
        assertNull(section.findPreference("automation_rule_a2/restore"));
        assertEquals(activity.getString(R.string.auto_label_and),
                String.valueOf(((ClauseItemPreference) section.findPreference("automation_rule_a2/and0")).getLabel()));
        assertNull("同一个标签下的第二行起空着",
                ((ClauseItemPreference) section.findPreference("automation_rule_a2/and1")).getLabel());
        section.scrollToPreference("automation_rule_a2/then+");
        Shots.settle();
        File open = Shots.save(activity, "settings-automation-" + tag + "-open");
        assertTrue(open.length() > 10_000);
        click(morning);
        assertFalse(section.findPreference("automation_rule_a2/name").isVisible());

        // 展开倒车那条：关安全功能，「结束时恢复」开着、变灰
        click(reverse);
        CardItemPreference restore = section.findPreference("automation_rule_a1/restore");
        assertNotNull(restore);
        assertTrue(restore.isVisible());
        assertTrue(restore.isChecked());
        assertFalse(restore.isEnabled());
        section.scrollToPreference("automation_rule_a1/restore");
        Shots.settle();
        File safety = Shots.save(activity, "settings-automation-" + tag + "-open-safety");
        assertTrue(safety.length() > 10_000);

        if (dialogs) {
            dialogs(activity, section, tag);
        }
        config.setAutomationRules("");
    }

    private void dialogs(ShotActivity activity, SettingsPreferenceFragment section, String tag) {
        AppConfig config = new AppConfig(activity);

        // 添加自动化：常用的两条 + 空白
        Preference add = section.findPreference(AutomationRows.ADD_KEY);
        add.getOnPreferenceClickListener().onPreferenceClick(add);
        Shots.settle();
        AlertDialog templates = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("常用的四条 + 空白", 5, templates.getListView().getCount());
        assertFalse("没有地点：停在常用地点以外那一条灰着", templates.getListView().getAdapter().isEnabled(2));
        assertFalse("没有地点：按地点设置尾门开启上限那一条灰着", templates.getListView().getAdapter().isEnabled(3));
        save(templates, "settings-automation-" + tag + "-add");
        // 选倒车那条：加在最后、开着、结束时恢复
        pick(templates, 0);
        List<Rule> rules = RuleStore.parse(config.getAutomationRules());
        assertEquals(4, rules.size());
        Rule added = rules.get(3);
        assertTrue(added.enabled);
        assertEquals(Trigger.gear("R"), added.trigger);
        assertTrue(added.restores());
        assertTrue("新加的展开着", section.findPreference("automation_rule_" + added.id + "/when").isVisible());

        // 倒车那条的「当」：有关安全功能的动作，上车灰着
        click(section.findPreference("automation_rule_a1/when"));
        AlertDialog when = (AlertDialog) ShadowDialog.getLatestDialog();
        assertFalse(when.getListView().getAdapter().isEnabled(0));
        assertEquals("勾着挡位", 1, when.getListView().getCheckedItemPosition());
        save(when, "settings-automation-" + tag + "-when");
        when.dismiss();

        // 上车那条的「添加条件」
        click(section.findPreference("automation_rule_a2/and+"));
        AlertDialog and = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(6, and.getListView().getCount());
        assertFalse("没有地点：地点灰着", and.getListView().getAdapter().isEnabled(0));
        save(and, "settings-automation-" + tag + "-and");
        and.dismiss();

        // 上车那条的「添加动作」→ 驾驶：关低速紧急制动灰着（上车不会结束、恢复不了）
        click(section.findPreference("automation_rule_a2/then+"));
        AlertDialog then = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(AutomationPickers.ChangeGroup.values().length, then.getListView().getCount());
        save(then, "settings-automation-" + tag + "-then");
        pick(then, 0);
        AlertDialog drive = (AlertDialog) ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotSame(then, drive);
        int blocked = -1;
        for (int i = 0; i < drive.getListView().getCount(); i++) {
            if (!drive.getListView().getAdapter().isEnabled(i)) {
                blocked = i;
            }
        }
        assertTrue("有一项灰着", blocked >= 0);
        save(drive, "settings-automation-" + tag + "-then-drive");
        // 选「驾驶模式：运动」：加在上车那条的最后
        pick(drive, 2);
        Rule morning = find(config, "a2");
        assertEquals(3, morning.changes.size());
        assertEquals(Change.of(Setting.DRIVE_MODE, "sport").value, morning.changes.get(2).value);

        // 改一个动作：第一项是「删除这个动作」
        click(section.findPreference("automation_rule_a2/then0"));
        AlertDialog edit = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(1 + AutomationPickers.ChangeGroup.values().length, edit.getListView().getCount());
        assertEquals("勾着驾驶", 1, edit.getListView().getCheckedItemPosition());
        pick(edit, 0);
        assertEquals(2, find(config, "a2").changes.size());

        // 开关：打开关着的那一条
        toggle(section, section.findPreference("automation_rule_a3"));
        assertTrue(find(config, "a3").enabled);

        // 删除：先问
        clickAction(section, section.findPreference("automation_rule_a3"));
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        save(confirm, "settings-automation-" + tag + "-delete");
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertNull(find(config, "a3"));
        assertNull(section.findPreference("automation_rule_a3"));
    }

    private static Rule find(AppConfig config, String id) {
        for (Rule r : RuleStore.parse(config.getAutomationRules())) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    private static void click(Preference p) {
        assertNotNull(p);
        p.getOnPreferenceClickListener().onPreferenceClick(p);
        Shots.settle();
    }

    private static void pick(AlertDialog dialog, int position) {
        ListView list = dialog.getListView();
        list.performItemClick(list.getChildAt(position), position, list.getItemIdAtPosition(position));
        Shots.settle();
    }

    private static void save(Dialog dialog, String name) {
        File file = Shots.save(dialog, name);
        assertTrue(file + " 是空的", file.length() > 5_000);
    }

    /** 这一行画出来的样子（滚到它）。 */
    private static View rowView(SettingsPreferenceFragment section, Preference p) {
        RecyclerView list = section.getListView();
        int position = ((PreferenceGroup.PreferencePositionCallback) list.getAdapter()).getPreferenceAdapterPosition(p);
        list.scrollToPosition(position);
        Shots.settle();
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        return holder.itemView;
    }

    private static void toggle(SettingsPreferenceFragment section, Preference p) {
        rowView(section, p).findViewById(android.R.id.widget_frame).performClick();
        Shots.settle();
    }

    private static void clickAction(SettingsPreferenceFragment section, Preference p) {
        rowView(section, p).findViewById(R.id.pref_action).performClick();
        Shots.settle();
    }
}
