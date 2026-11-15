/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.button.ButtonAction;
import com.kooo.evcam.ui.CamDialogs;

import java.util.List;
import java.util.function.Consumer;

/**
 * 选一个按键动作（{@link ButtonAction}）：悬浮按钮的点击 / 长按、灵动贴的单击 / 双击共用这一套选法
 * （项目所有者 2026-11-15，两边合成一份列表；选法是 2026-11-05 灵动贴定的那一套）。
 * 先选组，最上面是「不使用」（选了直接生效）；再在组里单选，标题是组名。两层都勾着现在选的那一项（所在的组）。
 */
final class ButtonActionPicker {

    private ButtonActionPicker() {
    }

    /** 弹第一层（组）。{@code title} 是这一下按法的名字（「单击」「长按」……）；选好了交给 {@code onPick}。 */
    static void show(Context context, CharSequence title, ButtonAction current, Consumer<ButtonAction> onPick) {
        ButtonAction.Group[] groups = ButtonAction.Group.values();
        String[] labels = new String[groups.length + 1];
        labels[0] = ButtonAction.NONE.label(context);
        int checked = 0;
        for (int i = 0; i < groups.length; i++) {
            labels[i + 1] = context.getString(groups[i].labelRes);
            if (current.group == groups[i]) {
                checked = i + 1;
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        onPick.accept(ButtonAction.NONE);
                    } else {
                        pickInGroup(context, groups[which - 1], current, onPick);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 组里单选一项：标题是组名。 */
    private static void pickInGroup(Context context, ButtonAction.Group group, ButtonAction current,
                                    Consumer<ButtonAction> onPick) {
        List<ButtonAction> actions = ButtonAction.of(group);
        String[] labels = new String[actions.size()];
        int checked = -1;
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = actions.get(i).label(context);
            if (actions.get(i) == current) {
                checked = i;
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(group.labelRes)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    onPick.accept(actions.get(which));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}
