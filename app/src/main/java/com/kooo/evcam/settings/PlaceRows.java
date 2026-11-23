/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Place;
import com.kooo.evcam.automation.PlaceStore;
import com.kooo.evcam.automation.PositionTrack;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.RuleStore;
import com.kooo.evcam.automation.Whereabouts;
import com.kooo.evcam.button.DeviceLabel;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 地点与自动化页上「地点」那一组（ui-spec §十六）：所在地点（状态行，有地点时才有）、每个地点一行（点了展开名称、坐标、半径，
 * 行尾「删除」）、添加地点（最多 {@link PlaceStore#MAX} 个）。
 *
 * <p>这一页看得见、又有地点时读定位（{@link Telemetry#acquire}），「所在地点」跟着变，误差照自动化一样算
 * （{@link PositionTrack}、{@link Whereabouts}）；看不见了马上放开。名称、坐标只存在设置里，不写进黑匣子和诊断。</p>
 */
final class PlaceRows {

    static final String CATEGORY_KEY = "cat_places";
    static final String HERE_KEY = "pref_place_here";
    static final String ADD_KEY = "pref_place_add";
    private static final String ROW_PREFIX = "place_";
    private static final String TELEMETRY_USER = "settings_places";
    private static final int ADD_ORDER = 1_000_000;

    private final SettingsPreferenceFragment fragment;
    private final PreferenceCategory category;
    private final Set<String> expanded = new HashSet<>();
    private final PositionTrack track = new PositionTrack();
    private AppConfig config;
    private SharedPreferences.OnSharedPreferenceChangeListener placesListener;
    private SharedPreferences.OnSharedPreferenceChangeListener rulesListener;
    private final Telemetry.Listener telemetryListener = readings -> refreshHere();
    private boolean reading;
    private boolean resumed;

    PlaceRows(SettingsPreferenceFragment fragment, PreferenceCategory category) {
        this.fragment = fragment;
        this.category = category;
    }

    void bind() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        config = new AppConfig(context);
        Preference add = category.findPreference(ADD_KEY);
        if (add != null) {
            add.setOrder(ADD_ORDER);
            add.setOnPreferenceClickListener(p -> {
                addPlace();
                return true;
            });
        }
        Preference here = category.findPreference(HERE_KEY);
        if (here != null) {
            here.setOrder(-1);
        }
        placesListener = config.onAutomationPlacesChanged(this::rebuild);
        rulesListener = config.onAutomationRulesChanged(this::rebuild);
        rebuild();
    }

    void detach() {
        pause();
        if (config != null) {
            config.removeChangeListener(placesListener);
            config.removeChangeListener(rulesListener);
        }
        placesListener = null;
        rulesListener = null;
        config = null;
    }

    /** 这一页回到前台：有地点就读定位。 */
    void resume() {
        resumed = true;
        updateReading();
    }

    /** 这一页退到后台：不再读定位。 */
    void pause() {
        resumed = false;
        updateReading();
    }

    private void updateReading() {
        Context context = fragment.getContext();
        boolean want = resumed && context != null && config != null && !places().isEmpty();
        if (want == reading) {
            return;
        }
        reading = want;
        if (want) {
            Telemetry.get().acquire(context, TELEMETRY_USER);
            Telemetry.get().addListener(telemetryListener);
        } else {
            Telemetry.get().removeListener(telemetryListener);
            Telemetry.get().release(TELEMETRY_USER);
            track.clear();
        }
        refreshHere();
    }

    // ================================================================= 读、存

    private List<Place> places() {
        return config == null ? new ArrayList<>() : new ArrayList<>(PlaceStore.parse(config.getAutomationPlaces()));
    }

    private List<Rule> rules() {
        return config == null ? new ArrayList<>() : new ArrayList<>(RuleStore.parse(config.getAutomationRules()));
    }

    private void save(List<Place> places) {
        if (config != null) {
            config.setAutomationPlaces(PlaceStore.format(places));
        }
    }

    private void update(String id, UnaryOperator<Place> change) {
        List<Place> places = places();
        int i = PlaceStore.indexOf(places, id);
        if (i >= 0) {
            places.set(i, change.apply(places.get(i)));
            save(places);
        }
    }

    // ================================================================= 画

    private void rebuild() {
        Context c = fragment.getContext();
        if (c == null || config == null) {
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
        List<Place> places = places();
        List<Rule> rules = rules();
        Set<String> ids = new HashSet<>();
        int order = 0;
        for (int i = 0; i < places.size(); i++) {
            ids.add(places.get(i).id);
            order = add(c, places.get(i), i, rules, order);
        }
        expanded.retainAll(ids);
        Preference add = category.findPreference(ADD_KEY);
        if (add != null) {
            boolean full = places.size() >= PlaceStore.MAX;
            add.setEnabled(!full);
            add.setSummary(full ? c.getString(R.string.auto_place_full, PlaceStore.MAX)
                    : c.getString(R.string.auto_place_add_summary, PlaceStore.MAX));
        }
        updateReading();
    }

    private int add(Context c, Place p, int index, List<Rule> rules, int order) {
        String base = ROW_PREFIX + p.id;
        boolean open = expanded.contains(p.id);
        ValueActionPreference row = new ValueActionPreference(c, null);
        row.setKey(base);
        row.setOrder(order++);
        row.setPersistent(false);
        row.setIconSpaceReserved(false);
        row.setTitle(AutomationTexts.placeName(c, p, index));
        int used = PlaceStore.usedBy(rules, p.id);
        row.setSummary(c.getString(R.string.auto_place_summary, p.radius,
                used == 0 ? c.getString(R.string.auto_place_unused) : c.getString(R.string.auto_place_used, used)));
        row.setAction(c.getString(R.string.action_delete), () -> confirmDelete(p.id));
        row.setExpanded(open);
        row.setOnPreferenceClickListener(x -> {
            if (!expanded.remove(p.id)) {
                expanded.add(p.id);
            }
            rebuild();
            return true;
        });
        category.addPreference(row);

        CardItemPreference name = item(c, base + "/name", order++, R.string.auto_label_name, open);
        name.setValue(AutomationTexts.placeName(c, p, index));
        name.setOnPreferenceClickListener(x -> {
            editName(p, index);
            return true;
        });
        CardItemPreference center = item(c, base + "/center", order++, R.string.auto_place_center, open);
        center.setValue(AutomationTexts.centerShown(p.latitude, p.longitude));
        center.setOnPreferenceClickListener(x -> {
            editCenter(p);
            return true;
        });
        SegmentedItemPreference radius = new SegmentedItemPreference(c);
        radius.setKey(base + "/radius");
        radius.setOrder(order++);
        radius.setTitle(R.string.auto_place_radius);
        radius.setVisible(open);
        CharSequence[] labels = new CharSequence[Place.RADIUS_STEPS.length];
        int chosen = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = c.getString(R.string.auto_place_meters, Place.RADIUS_STEPS[i]);
            if (Place.RADIUS_STEPS[i] == p.radius) {
                chosen = i;
            }
        }
        radius.setSummary(chosen < 0 ? c.getString(R.string.auto_place_radius_custom_value, p.radius) : null);
        radius.setSegments(labels, chosen, i -> update(p.id, x -> x.withRadius(Place.RADIUS_STEPS[i])));
        radius.setAction(c.getString(R.string.auto_place_custom), () -> editRadius(p));
        radius.setLast(true);
        category.addPreference(radius);
        return order;
    }

    private CardItemPreference item(Context c, String key, int order, int title, boolean visible) {
        CardItemPreference p = new CardItemPreference(c);
        p.setKey(key);
        p.setOrder(order);
        p.setTitle(title);
        p.setVisible(visible);
        category.addPreference(p);
        return p;
    }

    /** 「所在地点：家 / 其他地方 / 暂无定位 / 不确定」。 */
    private void refreshHere() {
        Preference here = category.findPreference(HERE_KEY);
        Context c = fragment.getContext();
        if (here == null || c == null || config == null) {
            return;
        }
        List<Place> places = places();
        here.setVisible(!places.isEmpty());
        if (places.isEmpty()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        VehicleState v = reading ? Telemetry.get().latest() : null;
        if (v != null) {
            track.update(v.latitude, v.longitude, v.positionAccuracy, v.positionAt, v.speedKmh, now);
        }
        String where;
        if (!reading || !track.hasFix()) {
            where = c.getString(R.string.auto_place_no_fix);
        } else {
            Place p = Whereabouts.where(places, track.latitude(), track.longitude(), track.uncertainty(now));
            where = p == null ? c.getString(R.string.auto_place_unsure)
                    : p == Whereabouts.ELSEWHERE ? c.getString(R.string.auto_place_elsewhere)
                    : AutomationTexts.placeName(c, p, PlaceStore.indexOf(places, p.id));
        }
        String text = c.getString(R.string.auto_place_here, where);
        if (!text.equals(String.valueOf(here.getTitle()))) {
            // 状态行：次要文字色（ui-spec §十六）
            android.text.SpannableString colored = new android.text.SpannableString(text);
            colored.setSpan(new android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary)), 0, text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            here.setTitle(colored);
        }
    }

    /** 此刻的定位（这一页在读、有定位时）；没有是 null。 */
    private double[] currentFix() {
        if (!reading) {
            Context c = fragment.getContext();
            if (c == null) {
                return null;
            }
            // 还没有地点：这一下现读一次（添加第一个地点时用）
            VehicleState v = Telemetry.get().latest();
            return v != null && v.latitude != null && v.longitude != null ? new double[] {v.latitude, v.longitude} : null;
        }
        return track.hasFix() ? new double[] {track.latitude(), track.longitude()} : null;
    }

    // ================================================================= 改

    private EditText centerInput(Context c, String text) {
        EditText input = CamDialogs.input(c);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setHint(R.string.auto_place_center_hint);
        input.setText(text);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private static LinearLayout box(Context c) {
        int pad = Math.round(24 * c.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    /** 坐标读得懂才能保存（输入框一变就看）。 */
    private static void saveOnlyWhenValid(AlertDialog dialog, EditText... inputs) {
        Runnable sync = () -> {
            boolean ok = true;
            for (EditText input : inputs) {
                ok &= AutomationTexts.parseCenter(input.getText().toString()) != null;
            }
            CamDialogs.enable(dialog, AlertDialog.BUTTON_POSITIVE, ok);
        };
        for (EditText input : inputs) {
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    sync.run();
                }
            });
        }
        sync.run();
    }

    /** 添加地点：名称（灰字是默认名「地点 N」）、坐标（有定位时填好此刻的）。保存后出现在列表最后，展开着。 */
    private void addPlace() {
        Context c = fragment.getContext();
        if (c == null || places().size() >= PlaceStore.MAX) {
            return;
        }
        List<Place> places = places();
        String defaultName = c.getString(R.string.auto_place_default_name, places.size() + 1);
        double[] fix = currentFix();
        EditText name = AccessoryRows.nameInput(c, "", defaultName);
        EditText center = centerInput(c, fix == null ? "" : AutomationTexts.center(fix[0], fix[1]));
        LinearLayout box = box(c);
        box.addView(label(c, R.string.auto_label_name));
        box.addView(name);
        box.addView(label(c, R.string.auto_place_center));
        box.addView(center);
        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_place_add)
                .setMessage(fix != null ? R.string.auto_place_from_fix : R.string.auto_place_no_fix_add)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    double[] at = AutomationTexts.parseCenter(center.getText().toString());
                    if (at == null) {
                        return;
                    }
                    String typed = DeviceLabel.clean(name.getText().toString());
                    Place p = Place.of(UUID.randomUUID().toString().substring(0, 8),
                            typed.isEmpty() ? defaultName : typed, at[0], at[1], Place.RADIUS_DEFAULT);
                    List<Place> now = places();
                    if (p != null && now.size() < PlaceStore.MAX) {
                        now.add(p);
                        expanded.add(p.id);
                        save(now);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
        saveOnlyWhenValid(dialog, center);
    }

    private static TextView label(Context c, int text) {
        TextView label = new TextView(c);
        label.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        label.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.text_secondary));
        label.setText(text);
        int pad = Math.round(8 * c.getResources().getDisplayMetrics().density);
        label.setPadding(0, pad, 0, 0);
        return label;
    }

    /** 名称：最多 20 个字，留空回到默认名。 */
    private void editName(Place p, int index) {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        String defaultName = c.getString(R.string.auto_place_default_name, index + 1);
        EditText input = AccessoryRows.nameInput(c, p.name, defaultName);
        LinearLayout box = box(c);
        box.addView(input);
        CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_label_name)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String typed = DeviceLabel.clean(input.getText().toString());
                    update(p.id, x -> x.withName(typed.isEmpty() ? defaultName : typed));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 坐标：手改，或「使用当前定位」（没有定位时灰着）。 */
    private void editCenter(Place p) {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        EditText input = centerInput(c, AutomationTexts.center(p.latitude, p.longitude));
        LinearLayout box = box(c);
        box.addView(input);
        double[] fix = currentFix();
        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_place_center)
                .setMessage(R.string.auto_place_center_note)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    double[] at = AutomationTexts.parseCenter(input.getText().toString());
                    if (at != null) {
                        update(p.id, x -> x.withCenter(at[0], at[1]));
                    }
                })
                .setNeutralButton(R.string.auto_place_use_fix, null)
                .setNegativeButton(R.string.action_cancel, null));
        // 「使用当前定位」只填进框里，不关框
        android.widget.Button useFix = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        CamDialogs.enable(dialog, AlertDialog.BUTTON_NEUTRAL, fix != null);
        useFix.setOnClickListener(v -> {
            double[] now = currentFix();
            if (now != null) {
                input.setText(AutomationTexts.center(now[0], now[1]));
            }
        });
        saveOnlyWhenValid(dialog, input);
    }

    /** 半径自定义：50–5000 m。 */
    private void editRadius(Place p) {
        Context c = fragment.getContext();
        if (c == null) {
            return;
        }
        EditText input = CamDialogs.input(c);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(p.radius));
        input.setSelectAllOnFocus(true);
        LinearLayout box = box(c);
        box.addView(input);
        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_place_radius)
                .setMessage(c.getString(R.string.auto_place_radius_range, Place.RADIUS_MIN, Place.RADIUS_MAX))
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    Integer m = meters(input.getText().toString());
                    if (m != null) {
                        update(p.id, x -> x.withRadius(m));
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
        Runnable sync = () -> CamDialogs.enable(dialog, AlertDialog.BUTTON_POSITIVE,
                meters(input.getText().toString()) != null);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                sync.run();
            }
        });
        sync.run();
    }

    /** 50–5000 的整数；别的是 null。 */
    static Integer meters(String text) {
        try {
            int m = Integer.parseInt(text.trim());
            return m >= Place.RADIUS_MIN && m <= Place.RADIUS_MAX ? m : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 删除先问一句：用到它的几条自动化会怎么样。 */
    private void confirmDelete(String id) {
        Context c = fragment.getContext();
        if (c == null || config == null) {
            return;
        }
        int used = PlaceStore.usedBy(rules(), id);
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(c, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.auto_place_delete_title)
                .setMessage(used == 0 ? c.getString(R.string.auto_place_delete_msg)
                        : c.getString(R.string.auto_place_delete_msg_used, used))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    if (config == null) {
                        return;
                    }
                    List<Place> places = places();
                    int i = PlaceStore.indexOf(places, id);
                    if (i < 0) {
                        return;
                    }
                    places.remove(i);
                    expanded.remove(id);
                    config.setAutomationPlacesAndRules(PlaceStore.format(places),
                            RuleStore.format(PlaceStore.withoutPlace(rules(), id)));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}
