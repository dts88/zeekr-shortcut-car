/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.view.View;
import android.widget.Checkable;

import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

import java.util.function.Consumer;

/**
 * 开关加行内展开加一个按钮（ui-spec §三、§十六）：地点与自动化的每一条自动化 —— 行首的开关管这一条开不开，
 * 点这一行其余的地方展开、收起（{@link #setExpanded}），行尾的按钮单独接（{@link #setAction}，删除）。
 *
 * <p>开关那一块（从行的左边缘到开关右边）点了只切开关，不展开；别的照 {@link ValueActionPreference}。</p>
 */
public class SwitchActionPreference extends ValueActionPreference {

    private boolean checked;
    private Consumer<Boolean> onToggle;

    public SwitchActionPreference(Context context) {
        super(context, null);
        setLayoutResource(R.layout.pref_row_switch_action);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    /** 开关切了叫 {@code onToggle}（切之后的状态）。 */
    void setSwitch(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    void setChecked(boolean checked) {
        if (this.checked != checked) {
            this.checked = checked;
            notifyChanged();
        }
    }

    boolean isChecked() {
        return checked;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View toggle = holder.findViewById(R.id.switchWidget);
        if (toggle instanceof Checkable) {
            ((Checkable) toggle).setChecked(checked);
        }
        View frame = holder.findViewById(android.R.id.widget_frame);
        if (frame != null) {
            frame.setClickable(true);
            frame.setOnClickListener(v -> {
                if (!isEnabled()) {
                    return;
                }
                checked = !checked;
                if (toggle instanceof Checkable) {
                    ((Checkable) toggle).setChecked(checked);
                }
                if (onToggle != null) {
                    onToggle.accept(checked);
                }
            });
        }
    }
}
