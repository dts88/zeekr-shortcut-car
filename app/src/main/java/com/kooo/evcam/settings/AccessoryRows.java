/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.button.Accessory;
import com.kooo.evcam.button.ButtonAction;
import com.kooo.evcam.button.DeviceLabel;
import com.kooo.evcam.button.KnobTurn;
import com.kooo.evcam.button.SmartButtonAd;
import com.kooo.evcam.button.SmartButtons;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.List;

final class AccessoryRows {

    private final Accessory kind;
    private final SettingsPreferenceFragment fragment;
    private final PreferenceCategory category;
    private final ActivityResultLauncher<String[]> permissions;
    private final Runnable onChanged = this::rebuildRows;
    private AlertDialog bindDialog;
    private boolean bindAfterPermission;

    AccessoryRows(Accessory kind, SettingsPreferenceFragment fragment, PreferenceCategory category,
                  ActivityResultLauncher<String[]> permissions) {
        this.kind = kind;
        this.fragment = fragment;
        this.category = category;
        this.permissions = permissions;
    }

    static String categoryKey(Accessory kind) {
        return kind == Accessory.KNOB ? "cat_x_knobs" : "cat_smart_buttons";
    }

    static String switchKey(Accessory kind) {
        return kind == Accessory.KNOB ? "pref_x_knobs" : "pref_smart_buttons";
    }

    private String bindKey() {
        return kind == Accessory.KNOB ? "pref_x_knob_bind" : "pref_smart_button_bind";
    }

    private String rowPrefix() {
        return kind == Accessory.KNOB ? "x_knob." : "smart_button.";
    }

    private int res(int button, int knob) {
        return kind == Accessory.KNOB ? knob : button;
    }

    void bind() {
        Preference bindRow = category.findPreference(bindKey());
        if (bindRow != null) {
            bindRow.setOnPreferenceClickListener(p -> {
                startBinding();
                return true;
            });
        }
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.addChangeListener(onChanged);
        }
        rebuildRows();
    }

    void detach() {
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.removeChangeListener(onChanged);
        }
        if (bindDialog != null) {
            AlertDialog dialog = bindDialog;
            bindDialog = null;
            dialog.dismiss();
            if (buttons != null) {
                buttons.cancelBinding();
            }
        }
    }

    void onPermissions() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null) {
            return;
        }
        buttons.refresh();
        if (!bindAfterPermission) {
            return;
        }
        bindAfterPermission = false;
        if (SmartButtons.canScan(context) && SmartButtons.canConnect(context)) {
            showBindDialog();
        } else {
            toast(context.getString(res(R.string.msg_smart_button_no_permission, R.string.msg_x_knob_no_permission)));
        }
    }


    private void startBinding() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null) {
            return;
        }
        if (!buttons.hasBluetooth()) {
            toast(context.getString(R.string.msg_smart_button_no_bluetooth));
            return;
        }
        if (!buttons.bluetoothOn()) {
            toast(context.getString(R.string.msg_smart_button_bluetooth_off));
            return;
        }
        if (new AppConfig(context).getAccessories(kind).size() >= SmartButtons.MAX_BOUND) {
            toast(context.getString(res(R.string.msg_smart_button_full, R.string.msg_x_knob_full), SmartButtons.MAX_BOUND));
            return;
        }
        if (!SmartButtons.canScan(context) || !SmartButtons.canConnect(context)) {
            bindAfterPermission = true;
            permissions.launch(SmartButtons.permissions());
            return;
        }
        showBindDialog();
    }

    private void showBindDialog() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null || bindDialog != null) {
            return;
        }
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, 0);
        TextView note = new TextView(context);
        note.setTextAppearance(R.style.TextAppearance_Cam_Body);
        int waiting = res(R.string.dlg_smart_button_bind_note, R.string.dlg_x_knob_bind_note);
        int connecting = res(R.string.dlg_smart_button_bind_connecting, R.string.dlg_x_knob_bind_connecting);
        note.setText(waiting);
        box.addView(note);

        SmartButtons.BindListener listener = new SmartButtons.BindListener() {
            @Override
            public void onConnecting(boolean busy) {
                Context c = fragment.getContext();
                if (c != null) {
                    note.setText(c.getString(busy ? connecting : waiting));
                }
            }

            @Override
            public void onBindResult(SmartButtons.BindResult result, String address) {
                AlertDialog dialog = bindDialog;
                bindDialog = null;
                if (dialog != null) {
                    dialog.dismiss();
                }
                showResult(result, address);
                rebuildRows();
            }
        };
        bindDialog = CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(res(R.string.set_smart_button_bind_title, R.string.set_x_knob_bind_title))
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(d -> {
                    if (bindDialog != null) {
                        bindDialog = null;
                        SmartButtons b = SmartButtons.get();
                        if (b != null) {
                            b.cancelBinding();
                        }
                    }
                }));
        buttons.startBinding(kind, listener);
    }

    private void showResult(SmartButtons.BindResult result, String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        switch (result) {
            case BOUND:
                toast(context.getString(res(R.string.msg_smart_button_bound, R.string.msg_x_knob_bound),
                        SmartButtonAd.shortId(address)));
                break;
            case ALREADY_BOUND:
                toast(context.getString(res(R.string.msg_smart_button_already_bound, R.string.msg_x_knob_already_bound)));
                break;
            case CONNECT_FAILED:
                toast(context.getString(res(R.string.msg_smart_button_bind_failed, R.string.msg_x_knob_bind_failed)));
                break;
            case FULL:
                toast(context.getString(res(R.string.msg_smart_button_full, R.string.msg_x_knob_full), SmartButtons.MAX_BOUND));
                break;
            case NOT_FOUND:
            default:
                toast(context.getString(res(R.string.msg_smart_button_not_found, R.string.msg_x_knob_not_found)));
                break;
        }
    }


    private void rebuildRows() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        List<Preference> old = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference p = category.getPreference(i);
            if (p.getKey() != null && p.getKey().startsWith(rowPrefix())) {
                old.add(p);
            }
        }
        for (Preference p : old) {
            category.removePreference(p);
        }
        SmartButtons buttons = SmartButtons.get();
        AppConfig config = new AppConfig(context);
        int order = 2;
        for (String address : config.getAccessories(kind)) {
            ValueActionPreference row = new ValueActionPreference(context, null);
            row.setKey(rowPrefix() + address);
            row.setPersistent(false);
            row.setIconSpaceReserved(false);
            row.setOrder(order++);
            String named = config.getAccessoryName(kind, address);
            row.setTitle(displayName(context, config, address));
            String actions = summary(context, config, address);
            String info = info(context, named.isEmpty() ? null : defaultName(context, address),
                    buttons == null ? null : buttons.version(address), buttons == null ? null : buttons.serial(address));
            row.setSummary(info.isEmpty() ? actions : actions + "\n" + info);
            int battery = buttons == null ? -1 : buttons.battery(address);
            row.setValue(battery < 0 ? null : context.getString(R.string.smart_button_battery, battery));
            row.setAction(context.getString(R.string.action_delete), () -> confirmDelete(address));
            row.setOnPreferenceClickListener(p -> {
                editActions(address);
                return true;
            });
            category.addPreference(row);
            row.setDependency(switchKey(kind));
        }
    }

    private String summary(Context context, AppConfig config, String address) {
        if (kind == Accessory.KNOB && config.isKnobExclusive(address)) {
            return context.getString(R.string.x_knob_summary_exclusive, config.getKnobHome(address).size());
        }
        if (kind == Accessory.KNOB) {
            return context.getString(R.string.x_knob_summary,
                    actionLabel(context, config, address, SmartButtonAd.SINGLE),
                    actionLabel(context, config, address, SmartButtonAd.DOUBLE),
                    config.getKnobTurn(address).label(context));
        }
        return context.getString(R.string.smart_button_summary,
                actionLabel(context, config, address, SmartButtonAd.SINGLE),
                actionLabel(context, config, address, SmartButtonAd.DOUBLE));
    }

    private String defaultName(Context context, String address) {
        return context.getString(res(R.string.smart_button_name, R.string.x_knob_name), SmartButtonAd.shortId(address));
    }

    private String displayName(Context context, AppConfig config, String address) {
        String named = config.getAccessoryName(kind, address);
        return named.isEmpty() ? defaultName(context, address) : named;
    }

    private static String info(Context context, String defaultName, String version, String serial) {
        List<String> parts = new ArrayList<>();
        if (defaultName != null) {
            parts.add(defaultName);
        }
        if (version != null) {
            parts.add(context.getString(R.string.smart_button_version, version));
        }
        if (serial != null) {
            parts.add(context.getString(R.string.smart_button_serial, serial));
        }
        return String.join(" · ", parts);
    }

    private String actionLabel(Context context, AppConfig config, String address, int gesture) {
        return ButtonAction.fromKey(config.getAccessoryAction(kind, address, gesture)).label(context);
    }

    private static int gestureLabel(int gesture) {
        switch (gesture) {
            case SmartButtonAd.DOUBLE:
                return R.string.set_smart_button_double;
            case SmartButtonAd.SINGLE:
            default:
                return R.string.set_smart_button_single;
        }
    }

    private void editActions(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        String name = displayName(context, config, address);
        boolean knob = kind == Accessory.KNOB;
        boolean exclusive = knob && config.isKnobExclusive(address);
        List<String> items = new ArrayList<>();
        List<Runnable> picks = new ArrayList<>();
        items.add(context.getString(R.string.set_smart_button_label) + " · " + name);
        picks.add(() -> editName(address));
        if (knob) {
            items.add(context.getString(R.string.set_x_knob_exclusive) + " · "
                    + context.getString(exclusive ? R.string.quick_on : R.string.quick_off));
            picks.add(() -> pickExclusive(address));
        }
        if (exclusive) {
            items.add(context.getString(R.string.set_x_knob_home) + " · "
                    + context.getString(R.string.x_knob_home_count, config.getKnobHome(address).size()));
            picks.add(() -> pickHome(address));
        } else {
            for (int gesture : kind.actionGestures()) {
                if (!Accessory.isTurn(gesture)) {
                    items.add(context.getString(gestureLabel(gesture)) + " · "
                            + actionLabel(context, config, address, gesture));
                    picks.add(() -> pickAction(address, gesture));
                }
            }
            if (knob) {
                items.add(context.getString(R.string.set_x_knob_turn) + " · " + config.getKnobTurn(address).label(context));
                picks.add(() -> pickTurn(address));
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(name)
                .setItems(items.toArray(new String[0]), (d, which) -> picks.get(which).run())
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void pickExclusive(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        boolean on = new AppConfig(context).isKnobExclusive(address);
        String[] choices = {context.getString(R.string.quick_on), context.getString(R.string.quick_off)};
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_x_knob_exclusive)
                .setSingleChoiceItems(choices, on ? 0 : 1, (d, which) -> {
                    d.dismiss();
                    boolean picked = which == 0;
                    if (picked == on) {
                        return;
                    }
                    new AppConfig(context).setKnobExclusive(address, picked);
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: X旋钮 " + SmartButtonAd.shortId(address)
                            + " 专属模式 → " + (picked ? "开" : "关"));
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void pickHome(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        List<KnobTurn> all = new ArrayList<>();
        for (KnobTurn turn : KnobTurn.values()) {
            if (turn != KnobTurn.NONE) {
                all.add(turn);
            }
        }
        List<KnobTurn> home = new AppConfig(context).getKnobHome(address);
        String[] labels = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).label(context);
            checked[i] = home.contains(all.get(i));
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_x_knob_home)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    List<KnobTurn> picked = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) {
                        if (checked[i]) {
                            picked.add(all.get(i));
                        }
                    }
                    if (picked.isEmpty()) {
                        toast(context.getString(R.string.msg_x_knob_home_empty));
                        return;
                    }
                    new AppConfig(context).setKnobHome(address, picked);
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: X旋钮 " + SmartButtonAd.shortId(address)
                            + " 按下时显示 " + picked.size() + " 项");
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void pickTurn(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        KnobTurn current = new AppConfig(context).getKnobTurn(address);
        List<ButtonAction.Group> groups = KnobTurn.groups();
        String[] labels = new String[groups.size() + 1];
        labels[0] = KnobTurn.NONE.label(context);
        int checked = 0;
        for (int i = 0; i < groups.size(); i++) {
            labels[i + 1] = context.getString(groups.get(i).labelRes);
            if (current.group == groups.get(i)) {
                checked = i + 1;
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_x_knob_turn)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        chooseTurn(address, KnobTurn.NONE);
                    } else {
                        pickTurnIn(address, groups.get(which - 1), current);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void pickTurnIn(String address, ButtonAction.Group group, KnobTurn current) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        List<KnobTurn> turns = KnobTurn.of(group);
        String[] labels = new String[turns.size()];
        for (int i = 0; i < turns.size(); i++) {
            labels[i] = turns.get(i).label(context);
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(group.labelRes)
                .setSingleChoiceItems(labels, turns.indexOf(current), (d, which) -> {
                    d.dismiss();
                    chooseTurn(address, turns.get(which));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void chooseTurn(String address, KnobTurn picked) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        if (config.getKnobTurn(address) == picked) {
            return;
        }
        config.setKnobTurn(address, picked);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: X旋钮 " + SmartButtonAd.shortId(address)
                + " 转动 → " + picked.key);
        rebuildRows();
    }

    private void editName(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        EditText input = nameInput(context, config.getAccessoryName(kind, address), defaultName(context, address));
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_smart_button_label)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    new AppConfig(context).setAccessoryName(kind, address, input.getText().toString());
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    static EditText nameInput(Context context, String current, String hint) {
        EditText input = CamDialogs.input(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(DeviceLabel.MAX)});
        input.setHint(hint);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private void pickAction(String address, int gesture) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        ButtonAction current = ButtonAction.fromKey(new AppConfig(context).getAccessoryAction(kind, address, gesture));
        ButtonActionPicker.show(context, context.getString(gestureLabel(gesture)), current,
                picked -> choose(address, gesture, picked));
    }

    private void choose(String address, int gesture, ButtonAction picked) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        new AppConfig(context).setAccessoryAction(kind, address, gesture, picked.key);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: " + (kind == Accessory.KNOB ? "X旋钮 " : "灵动贴 ")
                + SmartButtonAd.shortId(address) + " " + (gesture == SmartButtonAd.DOUBLE ? "双击" : "单击")
                + " → " + picked.key);
        rebuildRows();
    }

    private void confirmDelete(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(res(R.string.dlg_smart_button_delete_title, R.string.dlg_x_knob_delete_title))
                .setMessage(res(R.string.dlg_smart_button_delete_msg, R.string.dlg_x_knob_delete_msg))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    SmartButtons buttons = SmartButtons.get();
                    if (buttons != null) {
                        buttons.forget(kind, address);
                    } else {
                        new AppConfig(context).removeAccessory(kind, address);
                    }
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void toast(String text) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
