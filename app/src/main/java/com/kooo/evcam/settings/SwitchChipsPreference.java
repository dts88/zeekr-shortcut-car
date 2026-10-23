package com.kooo.evcam.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 开关加几个附加项（ui-spec §三，项目所有者 2026-10-23）：一个功能开着时还有几样可以单独开、关的附加项，合成一行 ——
 * 锁定影像（附加项：「自动锁定」的「闪远光」「双闪」「鸣笛」）、超级镜子（附加项：「方向标签」「点按切换」）。
 *
 * <p>行首的开关管整个功能，点这一行其余的地方也是这个开关（没有下一层；那一排按钮所在的地方除外）；
 * 行尾写当前值（{@link #setValue}）。
 * 开着时说明下面多一排：一个小标题（{@link #setOptionsLabel}）加几个按钮（{@link #addOption}，Material 的筛选按钮，
 * 按下是开、再按是关）；关着时那一排不显示。还不能用的附加项照样摆出来，置灰、按不动（{@link #addUnavailableOption}）。</p>
 */
public class SwitchChipsPreference extends SwitchPreferenceCompat implements PreferenceRows.OwnLayout {

    /** 一个附加项按下、松开时叫它。 */
    public interface OptionListener {
        void onChanged(boolean checked);
    }

    private static final class Option {
        final CharSequence label;
        boolean checked;
        /** null：还不能用（置灰、按不动）。 */
        final OptionListener listener;

        Option(CharSequence label, boolean checked, OptionListener listener) {
            this.label = label;
            this.checked = checked;
            this.listener = listener;
        }
    }

    private final List<Option> options = new ArrayList<>();
    private CharSequence value;
    private CharSequence optionsLabel;
    private CharSequence optionsNote;

    public SwitchChipsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_switch_chips);
        setWidgetLayoutResource(R.layout.pref_widget_switch);
    }

    /** 行尾的当前值；空就不写。 */
    public void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    /** 那一排按钮前面的小标题。 */
    public void setOptionsLabel(CharSequence label) {
        if (!TextUtils.equals(optionsLabel, label)) {
            optionsLabel = label;
            notifyChanged();
        }
    }

    /** 那一排按钮下面的一行小字（附加项的说明是次要的，写在下面，ui-spec §三）；空就不写。 */
    public void setOptionsNote(CharSequence note) {
        if (!TextUtils.equals(optionsNote, note)) {
            optionsNote = note;
            notifyChanged();
        }
    }

    /** 加一个附加项按钮（开关开着时才显示）。 */
    public void addOption(CharSequence label, boolean checked, OptionListener listener) {
        options.add(new Option(label, checked, listener));
        notifyChanged();
    }

    /** 加一个还不能用的附加项：摆着、置灰、按不动（鸣笛：车机还读不到喇叭信号）。 */
    public void addUnavailableOption(CharSequence label) {
        options.add(new Option(label, false, null));
        notifyChanged();
    }

    /** 从外面改了某一项（第几个，照加的顺序；比如重置之后）：按钮照新的显示，不叫它的监听。 */
    public void setOptionChecked(int index, boolean checked) {
        if (index >= 0 && index < options.size() && options.get(index).checked != checked) {
            options.get(index).checked = checked;
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
        View row = holder.findViewById(R.id.pref_options);
        ChipGroup chips = (ChipGroup) holder.findViewById(R.id.pref_option_chips);
        if (row == null || chips == null) {
            return;
        }
        boolean show = isChecked() && !options.isEmpty();
        row.setVisibility(show ? View.VISIBLE : View.GONE);
        TextView label = (TextView) holder.findViewById(R.id.pref_options_label);
        if (label != null) {
            label.setText(optionsLabel);
            label.setVisibility(TextUtils.isEmpty(optionsLabel) ? View.GONE : View.VISIBLE);
        }
        TextView note = (TextView) holder.findViewById(R.id.pref_options_note);
        if (note != null) {
            note.setText(optionsNote);
            note.setVisibility(TextUtils.isEmpty(optionsNote) ? View.GONE : View.VISIBLE);
        }
        chips.removeAllViews();
        if (!show) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(chips.getContext());
        for (Option option : options) {
            Chip chip = (Chip) inflater.inflate(R.layout.pref_option_chip, chips, false);
            chip.setText(option.label);
            chip.setChecked(option.checked);
            chip.setEnabled(isEnabled() && option.listener != null);
            if (option.listener != null) {
                chip.setOnCheckedChangeListener((button, checked) -> {
                    option.checked = checked;
                    option.listener.onChanged(checked);
                });
            }
            chips.addView(chip);
        }
    }
}
