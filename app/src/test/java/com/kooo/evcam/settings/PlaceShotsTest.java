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
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Place;
import com.kooo.evcam.automation.PlaceStore;
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
 * 「地点与自动化」页的地点（ui-spec §十六「地点」）：地点那一组、展开一个地点、添加 / 坐标 / 半径 / 删除的框，
 * 规则里用到地点时「当」「而且」的选择框和一行说明；白天 / 英文 / 阿拉伯文。坐标是随便编的。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaceShotsTest {

    private static final Place HOME = Place.of("home", "家", 10d, 20d, 600);
    private static final Place WORK = Place.of("work", "公司", 10.05d, 20.02d, 350);

    static List<Rule> rules() {
        Rule away = new Rule("p1", "", true, Trigger.gear("P"),
                Collections.singletonList(Condition.notInPlaces(Arrays.asList("home", "work"))),
                Collections.singletonList(Change.of(Setting.SENTRY, "on")), false);
        Rule home = new Rule("p2", "", true, Trigger.arrive("home"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.LOW_SPEED_BRAKING, "off")), true);
        return Arrays.asList(away, home);
    }

    @Test
    public void byDay() {
        shoot(Shots.DAY, "day", true);
    }

    @Test
    public void inEnglish() {
        shoot(Shots.DAY_EN, "day-en", false);
    }

    @Test
    public void inArabic() {
        shoot(Shots.DAY_AR, "day-ar", false);
    }

    private void shoot(String qualifiers, String tag, boolean dialogs) {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAutomationPlacesAndRules(PlaceStore.format(Arrays.asList(HOME, WORK)), RuleStore.format(rules()));

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

        // 规则的说明里写地点名
        Preference away = section.findPreference("automation_rule_p1");
        assertTrue(String.valueOf(away.getSummary()),
                String.valueOf(away.getSummary()).contains(activity.getString(R.string.auto_cond_not_in_places,
                        "家" + activity.getString(R.string.auto_list_sep) + "公司")));
        Preference arrive = section.findPreference("automation_rule_p2");
        assertTrue(String.valueOf(arrive.getSummary()), String.valueOf(arrive.getSummary())
                .contains(activity.getString(R.string.auto_restore_short, activity.getString(R.string.auto_end_arrive, "家"))));
        File rules = Shots.save(activity, "settings-automation-places-" + tag + "-rules");
        assertTrue(rules.length() > 10_000);

        // 地点那一组：所在地点、两个地点、添加地点
        Preference here = section.findPreference(PlaceRows.HERE_KEY);
        assertTrue(here.isVisible());
        assertEquals(activity.getString(R.string.auto_place_here, activity.getString(R.string.auto_place_no_fix)),
                String.valueOf(here.getTitle()));
        Preference home = section.findPreference("place_home");
        assertNotNull(home);
        assertEquals("家", String.valueOf(home.getTitle()));
        assertTrue(String.valueOf(home.getSummary()).contains(activity.getString(R.string.auto_place_used, 2)));
        Preference add = section.findPreference(PlaceRows.ADD_KEY);
        assertTrue(add.isEnabled());
        section.scrollToPreference(PlaceRows.ADD_KEY);
        Shots.settle();
        File list = Shots.save(activity, "settings-automation-places-" + tag);
        assertTrue(list.length() > 10_000);

        // 展开公司：坐标、半径（350 m 不在分段里：一段都不亮，写「自定义：350 m」）
        click(section.findPreference("place_work"));
        SegmentedItemPreference radius = section.findPreference("place_work/radius");
        assertNotNull(radius);
        assertTrue(radius.isVisible());
        assertEquals(-1, radius.getChosen());
        assertEquals(activity.getString(R.string.auto_place_radius_custom_value, 350), String.valueOf(radius.getSummary()));
        CardItemPreference center = section.findPreference("place_work/center");
        assertEquals("⁦10.0500, 20.0200⁩", String.valueOf(center.getValue()));
        section.scrollToPreference("place_work/radius");
        Shots.settle();
        File open = Shots.save(activity, "settings-automation-places-" + tag + "-open");
        assertTrue(open.length() > 10_000);

        if (dialogs) {
            dialogs(activity, section, config);
        }
        config.setAutomationPlacesAndRules("", "");
    }

    private void dialogs(ShotActivity activity, SettingsPreferenceFragment section, AppConfig config) {
        // 点分段 400 m：存进去
        View radiusRow = rowView(section, section.findPreference("place_work/radius"));
        ViewGroup segments = radiusRow.findViewById(R.id.segments);
        segments.getChildAt(1).performClick();
        Shots.settle();
        assertEquals(400, PlaceStore.parse(config.getAutomationPlaces()).get(1).radius);
        assertEquals(1, ((SegmentedItemPreference) section.findPreference("place_work/radius")).getChosen());

        // 自定义
        rowView(section, section.findPreference("place_work/radius")).findViewById(R.id.pref_action).performClick();
        Shots.settle();
        AlertDialog custom = (AlertDialog) ShadowDialog.getLatestDialog();
        save(custom, "settings-automation-places-day-radius");
        custom.dismiss();

        // 坐标：没有定位时「使用当前定位」灰着
        click(section.findPreference("place_work/center"));
        AlertDialog centerDialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertFalse(centerDialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        assertTrue(centerDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        save(centerDialog, "settings-automation-places-day-center");
        centerDialog.dismiss();

        // 添加地点：没有定位，坐标要自己填，填之前「保存」点不了
        click(section.findPreference(PlaceRows.ADD_KEY));
        AlertDialog addDialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertFalse(addDialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        save(addDialog, "settings-automation-places-day-add");
        addDialog.dismiss();

        // 「当」：地点一组，到达 / 离开每个地点；有关安全功能的动作，离开灰着
        click(section.findPreference("automation_rule_p2"));
        click(section.findPreference("automation_rule_p2/when"));
        AlertDialog when = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(4, when.getListView().getCount());
        assertEquals("勾着地点", 2, when.getListView().getCheckedItemPosition());
        save(when, "settings-automation-places-day-when");
        pick(when, 2);
        AlertDialog placeTriggers = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(4, placeTriggers.getListView().getCount());
        assertTrue(placeTriggers.getListView().getAdapter().isEnabled(0));
        assertFalse("离开家：关安全功能，灰着", placeTriggers.getListView().getAdapter().isEnabled(1));
        save(placeTriggers, "settings-automation-places-day-when-place");
        placeTriggers.dismiss();

        // 「而且」：改停在常用地点以外那一条的地点条件 → 不在… → 多选
        click(section.findPreference("automation_rule_p1"));
        click(section.findPreference("automation_rule_p1/and0"));
        AlertDialog and = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("删除这个条件 + 六组", 7, and.getListView().getCount());
        assertEquals("勾着地点", 1, and.getListView().getCheckedItemPosition());
        pick(and, 1);
        AlertDialog inOut = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(1, inOut.getListView().getCheckedItemPosition());
        pick(inOut, 1);
        AlertDialog which = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(which.getListView().isItemChecked(0));
        assertTrue(which.getListView().isItemChecked(1));
        save(which, "settings-automation-places-day-not-in");
        which.dismiss();

        // 添加自动化：有地点了，「停在常用地点以外」能选
        click(section.findPreference(AutomationRows.ADD_KEY));
        AlertDialog templates = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(templates.getListView().getAdapter().isEnabled(2));
        save(templates, "settings-automation-places-day-add-rule");
        templates.dismiss();

        // 删家：两条都用到它 —— 停在常用地点以外那一条还剩公司、照样开着；到达家那一条关掉
        rowView(section, section.findPreference("place_home")).findViewById(R.id.pref_action).performClick();
        Shots.settle();
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        save(confirm, "settings-automation-places-day-delete");
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals(1, PlaceStore.parse(config.getAutomationPlaces()).size());
        List<Rule> after = RuleStore.parse(config.getAutomationRules());
        assertEquals(Collections.singletonList("work"), after.get(0).conditions.get(0).places);
        assertTrue(after.get(0).enabled);
        assertFalse(after.get(1).enabled);
        assertNull(section.findPreference("place_home"));
        assertTrue(String.valueOf(section.findPreference("automation_rule_p2").getSummary())
                .contains(activity.getString(R.string.auto_place_deleted)));
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

    private static View rowView(SettingsPreferenceFragment section, Preference p) {
        RecyclerView list = section.getListView();
        int position = ((PreferenceGroup.PreferencePositionCallback) list.getAdapter()).getPreferenceAdapterPosition(p);
        list.scrollToPosition(position);
        Shots.settle();
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        return holder.itemView;
    }
}
