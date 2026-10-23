package com.kooo.evcam.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.kooo.evcam.R;

/**
 * 开关加下一层（ui-spec §三，项目所有者 2026-10-23）：一个功能既要开关、又要到下一层去填写或选择时，合成一行 ——
 * 车牌号水印（下一层是输入车牌号）、行驶信息条（下一层是「系统信息」里选显示哪些信号）。
 *
 * <p>行首的开关只管开关；点这一行其余的地方不切换开关，交给 {@code OnPreferenceClickListener} 进下一层；
 * 行尾写当前值（{@link #setValue}）再加 ›。和安卓系统设置里 Wi-Fi、蓝牙那一行是同一种，开关照车机放在行首。</p>
 *
 * <p>开关那一块从行的左边缘到开关右边都算点开关（{@code pref_row_switch_nav}）。切换照 SwitchPreferenceCompat 的规矩：
 * 先问 {@code OnPreferenceChangeListener}，它说不行就不变 —— 车牌号还空着时打开，由它先弹输入框。</p>
 */
public class SwitchNavPreference extends SwitchPreferenceCompat implements PreferenceRows.OwnLayout {

    /** 行尾的当前值；空 = 不写，只有 ›。 */
    private CharSequence value;

    public SwitchNavPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_switch_nav);
        setWidgetLayoutResource(R.layout.pref_widget_switch);
    }

    /** 行尾的当前值（车牌号、选了几项）；空就只有 ›。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView valueView = (TextView) holder.findViewById(R.id.pref_value);
        if (valueView != null) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
        View toggle = holder.findViewById(android.R.id.widget_frame);
        if (toggle != null) {
            toggle.setEnabled(isEnabled());
            toggle.setContentDescription(getTitle());
            toggle.setOnClickListener(v -> {
                if (!isEnabled()) {
                    return;
                }
                boolean next = !isChecked();
                if (callChangeListener(next)) {
                    setChecked(next);
                }
            });
        }
    }

    /** 点这一行（开关以外的地方）不切换开关：交给 OnPreferenceClickListener 进下一层。 */
    @Override
    protected void onClick() {
    }
}
