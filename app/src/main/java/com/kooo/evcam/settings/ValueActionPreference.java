package com.kooo.evcam.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 值加一个按钮（ui-spec §三，项目所有者 2026-10-23）：一行有当前值、点进去改，行尾再带一个直接做的动作 ——
 * 超级镜子的「窗口」（点这一行在整个屏幕上调宽高，行尾「重置」）。
 *
 * <p>点这一行交给 {@code OnPreferenceClickListener}（进下一层）；行尾的按钮单独接（{@link #setAction}），
 * 点它不算点行。当前值写在 › 前面（{@link #setValue}）。行不可用时按钮也不可用。</p>
 */
public class ValueActionPreference extends Preference implements PreferenceRows.OwnLayout {

    private CharSequence value;
    private CharSequence actionLabel;
    private Runnable action;

    public ValueActionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_value_action);
    }

    /** › 前面的当前值；空就不写。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    /** 行尾的按钮：字和点了做什么。 */
    public void setAction(CharSequence label, Runnable action) {
        this.actionLabel = label;
        this.action = action;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView valueView = (TextView) holder.findViewById(R.id.pref_value);
        if (valueView != null) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
        TextView button = (TextView) holder.findViewById(R.id.pref_action);
        if (button != null) {
            boolean shown = action != null && !TextUtils.isEmpty(actionLabel);
            button.setVisibility(shown ? View.VISIBLE : View.GONE);
            button.setText(actionLabel);
            button.setEnabled(isEnabled());
            button.setAlpha(isEnabled() ? 1f : 0.38f);
            button.setOnClickListener(shown ? v -> {
                if (isEnabled()) {
                    action.run();
                }
            } : null);
        }
    }
}
