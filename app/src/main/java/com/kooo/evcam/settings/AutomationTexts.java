/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.Setting;
import com.kooo.evcam.automation.Trigger;
import com.kooo.evcam.button.DeviceLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 地点与自动化页上的字（ui-spec §十六「地点与自动化」）：一条自动化那一行的名字和说明、展开以后每一行的内容、选择框里的每一项。
 * 原厂设置的名字照车上原厂界面逐字写（strings_automation.xml）。
 */
final class AutomationTexts {

    private AutomationTexts() {
    }

    /** 一样设置的名字（原厂界面上的）。 */
    static String settingName(Context c, Setting s) {
        switch (s) {
            case SENTRY:
                return c.getString(R.string.auto_setting_sentry);
            case DRIVE_MODE:
                return c.getString(R.string.auto_setting_drive_mode);
            case CIRCULATION:
                return c.getString(R.string.auto_setting_circulation);
            case REAR_CLIMATE:
                return c.getString(R.string.auto_setting_rear_climate);
            case HILL_DESCENT:
                return c.getString(R.string.auto_setting_hill_descent);
            case LOW_SPEED_BRAKING:
                return c.getString(R.string.auto_setting_low_speed_braking);
            case FRONT_CLIMATE:
            default:
                return c.getString(R.string.auto_setting_front_climate);
        }
    }

    /** 一个值的名字（存的是 on / off / comfort …）。 */
    static String valueName(Context c, String value) {
        switch (value) {
            case "on":
                return c.getString(R.string.auto_value_on);
            case "off":
                return c.getString(R.string.auto_value_off);
            case "comfort":
                return c.getString(R.string.auto_value_comfort);
            case "standard":
                return c.getString(R.string.auto_value_standard);
            case "sport":
                return c.getString(R.string.auto_value_sport);
            case "auto":
                return c.getString(R.string.auto_value_auto);
            case "outer":
                return c.getString(R.string.auto_value_outer);
            case "inner":
                return c.getString(R.string.auto_value_inner);
            default:
                return value;
        }
    }

    /** 只有开启、关闭两种的（写成「空调开启」），别的写成「驾驶模式变为运动」。 */
    static boolean onOff(Setting s) {
        String[] values = s.valueNames();
        return values.length == 2 && "on".equals(values[0]) && "off".equals(values[1]);
    }

    /** 开启、关闭的另一个。 */
    private static String opposite(String value) {
        return "on".equals(value) ? "off" : "on";
    }

    /** 「当」那一行的内容：上车、挂入 R 挡、空调开启、驾驶模式变为运动。 */
    static String trigger(Context c, Trigger t) {
        switch (t.kind) {
            case GET_IN:
                return c.getString(R.string.auto_trigger_get_in);
            case GEAR:
                return c.getString(R.string.auto_trigger_gear, t.gear);
            case STATE:
            default:
                return onOff(t.setting)
                        ? c.getString(R.string.auto_trigger_state_switch, settingName(c, t.setting), valueName(c, t.value))
                        : c.getString(R.string.auto_trigger_state_value, settingName(c, t.setting), valueName(c, t.value));
        }
    }

    /** 触发人在车上时会不会结束（上车不会：熄屏时人已下车，不恢复，ui-spec §十八）。 */
    static boolean ends(Trigger t) {
        return t != null && t.kind != Trigger.Kind.GET_IN;
    }

    /** 结束的那一下：离开 R 挡时、空调关闭时、驾驶模式不再是运动时；不会结束的是 null。 */
    static String end(Context c, Trigger t) {
        if (!ends(t)) {
            return null;
        }
        if (t.kind == Trigger.Kind.GEAR) {
            return c.getString(R.string.auto_end_gear, t.gear);
        }
        return onOff(t.setting)
                ? c.getString(R.string.auto_end_state_switch, settingName(c, t.setting), valueName(c, opposite(t.value)))
                : c.getString(R.string.auto_end_state_value, settingName(c, t.setting), valueName(c, t.value));
    }

    /** 「而且」一行的内容。 */
    static String condition(Context c, Condition k) {
        switch (k.kind) {
            case GEAR_IS:
                return c.getString(R.string.auto_cond_gear, k.gear);
            case BATTERY_AT_LEAST:
                return c.getString(R.string.auto_cond_battery_at_least, k.number);
            case BATTERY_BELOW:
                return c.getString(R.string.auto_cond_battery_below, k.number);
            case TIME_BETWEEN:
                return c.getString(R.string.auto_cond_time, time(k.from), time(k.to));
            case WEEKDAYS:
                return days(c, k.days);
            case STATE_IS:
            default:
                return onOff(k.setting)
                        ? c.getString(R.string.auto_cond_state_switch, settingName(c, k.setting), valueName(c, k.value))
                        : c.getString(R.string.auto_cond_state_value, settingName(c, k.setting), valueName(c, k.value));
        }
    }

    /** 「就」一行的内容：低速紧急制动：关闭。 */
    static String change(Context c, Change ch) {
        return c.getString(R.string.auto_change, settingName(c, ch.setting), valueName(c, ch.value));
    }

    /** 一天里的第几分钟写成 22:00。 */
    static String time(int minuteOfDay) {
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** 星期：每天、周一至周五、周六、周日，或者一个一个列出来。第 0 位是星期一。 */
    static String days(Context c, int mask) {
        int d = mask & 0x7F;
        if (d == 0x7F) {
            return c.getString(R.string.auto_days_every);
        }
        if (d == 0x1F) {
            return c.getString(R.string.auto_days_weekdays);
        }
        if (d == 0x60) {
            return c.getString(R.string.auto_days_weekend);
        }
        String[] names = c.getResources().getStringArray(R.array.auto_day_names);
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if ((d & (1 << i)) != 0) {
                picked.add(names[i]);
            }
        }
        return String.join(c.getString(R.string.auto_list_sep), picked);
    }

    /** 名字没起过时的默认名：第一个动作。 */
    static String defaultName(Context c, Rule r) {
        return r.changes.isEmpty() ? c.getString(R.string.auto_template_blank) : change(c, r.changes.get(0));
    }

    /** 起过的名字（洗过）；没起过是空串。 */
    static String givenName(Rule r) {
        return r.name == null ? "" : DeviceLabel.clean(r.name);
    }

    /** 那一行的名字：起过名字的用名字，没起过的是第一个动作。 */
    static String title(Context c, Rule r) {
        String given = givenName(r);
        return given.isEmpty() ? defaultName(c, r) : given;
    }

    /**
     * 那一行的说明：什么时候 · 条件 ……；起过名字的再加上动作；会恢复的末尾一句「离开 R 挡时恢复」。
     */
    static String summary(Context c, Rule r) {
        List<String> parts = new ArrayList<>();
        parts.add(c.getString(R.string.auto_when, trigger(c, r.trigger)));
        for (Condition k : r.conditions) {
            parts.add(condition(c, k));
        }
        if (!givenName(r).isEmpty()) {
            List<String> changes = new ArrayList<>();
            for (Change ch : r.changes) {
                changes.add(change(c, ch));
            }
            parts.add(String.join(c.getString(R.string.auto_list_sep), changes));
        }
        String end = end(c, r.trigger);
        if (end != null && r.restores()) {
            parts.add(c.getString(R.string.auto_restore_short, end));
        }
        return String.join(c.getString(R.string.auto_summary_sep), parts);
    }

    /** 「结束时恢复」那一行下面的说明。 */
    static String restoreSummary(Context c, Rule r) {
        String end = end(c, r.trigger);
        boolean safety = hasSafetyOff(r);
        return c.getString(safety ? R.string.auto_restore_summary_safety : R.string.auto_restore_summary,
                end == null ? "" : end);
    }

    /** 有没有关掉安全功能的动作（必须结束时恢复）。 */
    static boolean hasSafetyOff(Rule r) {
        for (Change ch : r.changes) {
            if (ch.safetyOff()) {
                return true;
            }
        }
        return false;
    }
}
