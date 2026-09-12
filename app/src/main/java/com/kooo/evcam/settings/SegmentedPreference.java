package com.kooo.evcam.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

/**
 * 选项只有两三个的设置：直接摆在行里，点一下就切。
 *
 * <h3>为什么不用下拉框</h3>
 *
 * <p>下拉框要点两次（开框、选中），而且收起来之后看不见「另一个是什么」。
 * 「操作按钮放左边还是右边」这种问题，两个答案一起摆出来，一眼就知道自己在选什么，
 * 手也只用抬一次 —— 在车上，少一次点击是实在的。</p>
 *
 * <h3>选项多了就别用它</h3>
 *
 * <p>三段是舒服的上限。再多，每一段就窄到看不清字，那时候下拉框反而是对的。</p>
 */
public class SegmentedPreference extends Preference implements PreferenceRows.OwnLayout {

    private static final int SEGMENT_HEIGHT_DP = 46;
    private static final int SEGMENT_MIN_WIDTH_DP = 96;
    private static final int SEGMENT_PADDING_DP = 18;

    private CharSequence[] entries = new CharSequence[0];
    private String[] values = new String[0];
    private String value;

    public SegmentedPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_row_segmented);
        setPersistent(false);
        setSelectable(false);
    }

    /** 名字和取值一一对应，由绑定的地方从 {@link SettingSpec} 取。 */
    void setOptions(CharSequence[] entries, String[] values) {
        if (entries == null || values == null || entries.length != values.length) {
            throw new IllegalArgumentException(getKey() + ": 名字和取值对不上");
        }
        this.entries = entries;
        this.values = values;
        notifyChanged();
    }

    void setValue(String value) {
        this.value = value;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (!(holder.findViewById(R.id.segments) instanceof ViewGroup)) {
            return;
        }
        ViewGroup track = (ViewGroup) holder.findViewById(R.id.segments);
        track.removeAllViews();

        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < entries.length; i++) {
            track.addView(segment(context, density, entries[i], values[i]));
        }
    }

    private TextView segment(Context context, float density, CharSequence label, String target) {
        boolean chosen = target.equals(value);
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextAppearance(R.style.TextAppearance_Cam_Body);
        view.setTextColor(ContextCompat.getColor(context,
                chosen ? R.color.on_energy : R.color.text_secondary));
        view.setBackgroundResource(R.drawable.bg_segment);
        view.setActivated(chosen);
        int padding = Math.round(SEGMENT_PADDING_DP * density);
        view.setPadding(padding, 0, padding, 0);
        view.setMinWidth(Math.round(SEGMENT_MIN_WIDTH_DP * density));
        view.setOnClickListener(v -> pick(target));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(SEGMENT_HEIGHT_DP * density));
        view.setLayoutParams(params);
        return view;
    }

    private void pick(String target) {
        if (target.equals(value)) {
            return;
        }
        if (callChangeListener(target)) {
            setValue(target);
        }
    }
}
