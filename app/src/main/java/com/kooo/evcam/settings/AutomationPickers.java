/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Setting;
import com.kooo.evcam.automation.Trigger;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 地点与自动化的选择框（ui-spec §十六）：「当」「而且」「就」都是先选组、再在组里单选（{@code ButtonActionPicker} 那一套），
 * 两层都勾着现在选的那一项；要填值的（百分比、时间段、星期）再弹对应的框。这台车上不能选的灰着、点不动，后面写为什么。
 * 改一个已有的条件、动作时，第一层最上面多一项「删除这个条件 / 动作」（动作只剩一个时没有）。
 */
final class AutomationPickers {

    /** 「当」「而且」能看的车辆状态，照这个先后。 */
    static final Setting[] STATES = {Setting.FRONT_CLIMATE, Setting.DRIVE_MODE, Setting.CIRCULATION,
            Setting.REAR_CLIMATE, Setting.HILL_DESCENT, Setting.LOW_SPEED_BRAKING, Setting.SENTRY};
    private static final String[] GEARS = {"P", "R", "D", "N"};

    /** 「就」的三组：驾驶、空调、场景（组名和按键动作共用）。 */
    enum ChangeGroup {
        DRIVE(R.string.smart_button_group_drive, R.string.auto_pick_then_drive_desc,
                Setting.DRIVE_MODE, Setting.HILL_DESCENT, Setting.LOW_SPEED_BRAKING),
        CLIMATE(R.string.smart_button_group_climate, R.string.auto_pick_then_climate_desc,
                Setting.CIRCULATION, Setting.REAR_CLIMATE),
        SCENES(R.string.smart_button_group_scenes, R.string.auto_pick_then_scenes_desc, Setting.SENTRY);

        final int label;
        final int desc;
        final Setting[] settings;

        ChangeGroup(int label, int desc, Setting... settings) {
            this.label = label;
            this.desc = desc;
            this.settings = settings;
        }

        static ChangeGroup of(Setting s) {
            for (ChangeGroup g : values()) {
                for (Setting x : g.settings) {
                    if (x == s) {
                        return g;
                    }
                }
            }
            return null;
        }
    }

    /** 列表里的一项：字（可以两行：名字 + 小字说明）、能不能点。 */
    static final class Item {
        final CharSequence text;
        final boolean enabled;

        Item(CharSequence text, boolean enabled) {
            this.text = text;
            this.enabled = enabled;
        }
    }

    private AutomationPickers() {
    }

    // ================================================================= 当

    /**
     * 选「当」：上车（直接选中）、挡位、车辆状态。{@code safetyOff}：这一条有关掉安全功能的动作 —— 上车不会在人在车上时结束，
     * 恢复不了，灰着。
     */
    static void pickTrigger(Context c, Trigger current, boolean safetyOff, Consumer<Trigger> onPick) {
        List<Item> groups = new ArrayList<>();
        groups.add(safetyOff ? new Item(c.getString(R.string.auto_pick_get_in_blocked), false)
                : new Item(twoLines(c, c.getString(R.string.auto_trigger_get_in),
                c.getString(R.string.auto_pick_when_get_in_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_when_gear),
                c.getString(R.string.auto_pick_when_gear_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_state),
                c.getString(R.string.auto_pick_when_state_desc)), true));
        int checked = current == null ? -1 : current.kind.ordinal();
        list(c, c.getString(R.string.auto_label_when), groups, checked, which -> {
            if (which == 0) {
                onPick.accept(Trigger.getIn());
            } else if (which == 1) {
                List<Item> gears = new ArrayList<>();
                int at = -1;
                for (int i = 0; i < GEARS.length; i++) {
                    gears.add(new Item(c.getString(R.string.auto_trigger_gear, GEARS[i]), true));
                    if (current != null && current.kind == Trigger.Kind.GEAR && GEARS[i].equals(current.gear)) {
                        at = i;
                    }
                }
                list(c, c.getString(R.string.auto_pick_when_gear), gears, at, i -> onPick.accept(Trigger.gear(GEARS[i])));
            } else {
                List<Trigger> all = new ArrayList<>();
                List<Item> items = new ArrayList<>();
                int at = -1;
                for (Setting s : STATES) {
                    for (String v : s.valueNames()) {
                        Trigger t = Trigger.state(s, v);
                        if (t.equals(current)) {
                            at = all.size();
                        }
                        all.add(t);
                        items.add(new Item(AutomationTexts.trigger(c, t), true));
                    }
                }
                list(c, c.getString(R.string.auto_pick_state), items, at, i -> onPick.accept(all.get(i)));
            }
        });
    }

    // ================================================================= 而且

    /**
     * 选「而且」：挡位是、电量、时间段、星期、车辆状态。{@code current} 是改的那一个（新加的是 null）；{@code onRemove} 不是 null 时
     * 第一层最上面是「删除这个条件」。
     */
    static void pickCondition(Context c, FragmentManager fm, Condition current, Consumer<Condition> onPick,
                              Runnable onRemove) {
        List<Item> groups = new ArrayList<>();
        int offset = 0;
        if (onRemove != null) {
            groups.add(new Item(c.getString(R.string.auto_remove_condition), true));
            offset = 1;
        }
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_when_gear),
                c.getString(R.string.auto_pick_and_gear_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_battery),
                c.getString(R.string.auto_pick_battery_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_time),
                c.getString(R.string.auto_pick_time_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_days),
                c.getString(R.string.auto_pick_days_desc)), true));
        groups.add(new Item(twoLines(c, c.getString(R.string.auto_pick_state),
                c.getString(R.string.auto_pick_and_state_desc)), true));
        int checked = current == null ? -1 : offset + conditionGroup(current.kind);
        int base = offset;
        list(c, c.getString(R.string.auto_label_and), groups, checked, which -> {
            if (onRemove != null && which == 0) {
                onRemove.run();
                return;
            }
            switch (which - base) {
                case 0:
                    pickGear(c, current, onPick);
                    break;
                case 1:
                    pickBattery(c, current, onPick);
                    break;
                case 2:
                    pickTime(c, fm, current, onPick);
                    break;
                case 3:
                    pickDays(c, current, onPick);
                    break;
                default:
                    pickStateIs(c, current, onPick);
                    break;
            }
        });
    }

    private static int conditionGroup(Condition.Kind kind) {
        switch (kind) {
            case GEAR_IS:
                return 0;
            case BATTERY_AT_LEAST:
            case BATTERY_BELOW:
                return 1;
            case TIME_BETWEEN:
                return 2;
            case WEEKDAYS:
                return 3;
            default:
                return 4;
        }
    }

    private static void pickGear(Context c, Condition current, Consumer<Condition> onPick) {
        List<Item> items = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < GEARS.length; i++) {
            items.add(new Item(c.getString(R.string.auto_cond_gear, GEARS[i]), true));
            if (current != null && current.kind == Condition.Kind.GEAR_IS && GEARS[i].equals(current.gear)) {
                at = i;
            }
        }
        list(c, c.getString(R.string.auto_pick_when_gear), items, at, i -> onPick.accept(Condition.gearIs(GEARS[i])));
    }

    private static void pickBattery(Context c, Condition current, Consumer<Condition> onPick) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(c.getString(R.string.auto_pick_battery_at_least), true));
        items.add(new Item(c.getString(R.string.auto_pick_battery_below), true));
        int at = current == null ? -1 : current.kind == Condition.Kind.BATTERY_AT_LEAST ? 0
                : current.kind == Condition.Kind.BATTERY_BELOW ? 1 : -1;
        list(c, c.getString(R.string.auto_pick_battery), items, at, which -> {
            boolean atLeast = which == 0;
            int start = current != null && at >= 0 ? current.number : 20;
            int[] chosen = {start};
            SliderDialog.Slider slider = new SliderDialog.Slider(null, 5, 100, start, "%", null, true, false,
                    v -> chosen[0] = v);
            SliderDialog.show(c, items.get(which).text, Collections.singletonList(slider), () ->
                    onPick.accept(atLeast ? Condition.batteryAtLeast(chosen[0]) : Condition.batteryBelow(chosen[0])));
        });
    }

    private static void pickTime(Context c, FragmentManager fm, Condition current, Consumer<Condition> onPick) {
        boolean had = current != null && current.kind == Condition.Kind.TIME_BETWEEN;
        int from = had ? current.from : 22 * 60;
        int to = had ? current.to : 6 * 60;
        clock(fm, c.getString(R.string.auto_pick_time_from), from, start ->
                clock(fm, c.getString(R.string.auto_pick_time_to), to, end -> {
                    Condition k = Condition.timeBetween(start, end);
                    if (k != null) {
                        onPick.accept(k);
                    }
                }));
    }

    private static void clock(FragmentManager fm, CharSequence title, int minuteOfDay, Consumer<Integer> onPick) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(minuteOfDay / 60)
                .setMinute(minuteOfDay % 60)
                .setTitleText(title)
                .build();
        picker.addOnPositiveButtonClickListener(v -> onPick.accept(picker.getHour() * 60 + picker.getMinute()));
        picker.show(fm, "automation_time");
    }

    private static void pickDays(Context c, Condition current, Consumer<Condition> onPick) {
        String[] names = c.getResources().getStringArray(R.array.auto_day_names);
        boolean[] on = new boolean[7];
        int mask = current != null && current.kind == Condition.Kind.WEEKDAYS ? current.days : 0x1F;
        for (int i = 0; i < 7; i++) {
            on[i] = (mask & (1 << i)) != 0;
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_pick_days)
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    int picked = 0;
                    for (int i = 0; i < 7; i++) {
                        if (on[i]) {
                            picked |= 1 << i;
                        }
                    }
                    Condition k = Condition.weekdays(picked);
                    if (k != null) {
                        onPick.accept(k);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static void pickStateIs(Context c, Condition current, Consumer<Condition> onPick) {
        List<Condition> all = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        int at = -1;
        for (Setting s : STATES) {
            for (String v : s.valueNames()) {
                Condition k = Condition.stateIs(s, v);
                if (current != null && current.kind == Condition.Kind.STATE_IS && current.setting == s
                        && v.equals(current.value)) {
                    at = all.size();
                }
                all.add(k);
                items.add(new Item(AutomationTexts.condition(c, k), true));
            }
        }
        list(c, c.getString(R.string.auto_pick_state), items, at, i -> onPick.accept(all.get(i)));
    }

    // ================================================================= 就

    /**
     * 选「就」：驾驶、空调、场景。{@code trigger} 是这一条的「当」—— 上车时关掉安全功能的那一项灰着（人下车时不恢复）。
     * {@code onRemove} 不是 null 时第一层最上面是「删除这个动作」。
     */
    static void pickChange(Context c, Change current, Trigger trigger, Consumer<Change> onPick, Runnable onRemove) {
        List<Item> groups = new ArrayList<>();
        int offset = 0;
        if (onRemove != null) {
            groups.add(new Item(c.getString(R.string.auto_remove_change), true));
            offset = 1;
        }
        ChangeGroup[] all = ChangeGroup.values();
        int checked = -1;
        for (int i = 0; i < all.length; i++) {
            groups.add(new Item(twoLines(c, c.getString(all[i].label), c.getString(all[i].desc)), true));
            if (current != null && ChangeGroup.of(current.setting) == all[i]) {
                checked = offset + i;
            }
        }
        int base = offset;
        list(c, c.getString(R.string.auto_label_then), groups, checked, which -> {
            if (onRemove != null && which == 0) {
                onRemove.run();
                return;
            }
            ChangeGroup group = all[which - base];
            List<Change> changes = new ArrayList<>();
            List<Item> items = new ArrayList<>();
            int at = -1;
            boolean noEnd = !AutomationTexts.ends(trigger);
            for (Setting s : group.settings) {
                for (String v : s.valueNames()) {
                    Change ch = Change.of(s, v);
                    if (ch == null) {
                        continue;
                    }
                    if (current != null && current.setting == s && v.equals(current.value)) {
                        at = changes.size();
                    }
                    boolean blocked = noEnd && ch.safetyOff();
                    String text = AutomationTexts.change(c, ch);
                    changes.add(ch);
                    items.add(new Item(blocked ? c.getString(R.string.auto_pick_get_in_unavailable, text) : text,
                            !blocked));
                }
            }
            list(c, c.getString(group.label), items, at, i -> onPick.accept(changes.get(i)));
        });
    }

    // ================================================================= 画法

    /** 名字一行、小字说明一行（次要文字色），放在同一项里。 */
    static CharSequence twoLines(Context c, String name, String desc) {
        SpannableStringBuilder b = new SpannableStringBuilder(name);
        int start = b.length();
        b.append('\n').append(desc);
        float scaled = c.getResources().getDisplayMetrics().scaledDensity;
        b.setSpan(new AbsoluteSizeSpan(Math.round(16 * scaled)), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, R.color.text_secondary)), start, b.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /**
     * 单选列表框：点了一项就关、交给 {@code onPick}；灰着的点不动。每一项用对话框自己的主题画（字号、勾选的样子和
     * 按键动作的选择框一样，{@code MaterialAlertDialog.Cam.Choice}）。
     */
    static void list(Context c, CharSequence title, List<Item> items, int checked, Consumer<Integer> onPick) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog);
        ArrayAdapter<Item> adapter = new ArrayAdapter<Item>(builder.getContext(),
                com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice, android.R.id.text1, items) {
            @Override
            public boolean isEnabled(int position) {
                return getItem(position).enabled;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                Item item = getItem(position);
                TextView text = row.findViewById(android.R.id.text1);
                if (text != null) {
                    text.setText(item.text);
                    // 名字带一行小字说明的，上下各留一点，几项之间才分得开
                    int pad = android.text.TextUtils.indexOf(item.text, '\n') >= 0
                            ? Math.round(10 * text.getResources().getDisplayMetrics().density) : 0;
                    text.setPadding(text.getPaddingLeft(), pad, text.getPaddingRight(), pad);
                }
                row.setEnabled(item.enabled);
                row.setAlpha(item.enabled ? 1f : 0.38f);
                return row;
            }
        };
        CamDialogs.show(builder
                .setTitle(title)
                .setSingleChoiceItems(adapter, checked, (d, which) -> {
                    if (!items.get(which).enabled) {
                        return;
                    }
                    d.dismiss();
                    onPick.accept(which);
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}
