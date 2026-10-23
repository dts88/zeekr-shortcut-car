package com.kooo.evcam.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

import java.util.List;

/**
 * 一个框里几根拖动条（ui-spec §三「看着调的」，项目所有者 2026-10-23）：拖的时候就生效、看得见效果，「保存」才算数 ——
 * 取消、点框外、返回键都回到打开时的值。超级镜子的视野角度、悬浮按钮的外观、盲区画面标定用它；
 * 界面的鱼眼校正框里那一根也是这里画的（{@link #block}）。说明写在拖动条下面（{@link CamDialogs#note}）。
 */
final class SliderDialog {

    /** 不可用的那一根（随上一项）的不透明度，和 SegmentedPreference 一样是 Material 的禁用值。 */
    private static final float DISABLED_ALPHA = 0.38f;

    /** 拖到某个值时做什么：存下来、推给正在显示的窗口。 */
    interface Apply {
        void set(int value);
    }

    /** 一根拖动条。 */
    static final class Slider {
        /** 名字；null 时不写（框的标题就是它）。 */
        final CharSequence label;
        final int min;
        final int max;
        /** 打开时的值：取消时回到它。 */
        final int start;
        final String unit;
        /** 拖动条下面的说明，可以没有。 */
        final CharSequence note;
        /** false：随上一项、这会儿不起作用 —— 灰着、拖不动（悬浮按钮的时长文字大小）。 */
        final boolean enabled;
        /**
         * true：拖的时候每动一下就生效（推一次很便宜的，超级镜子的视野）；false：松手时生效
         * （每推一次都要走一趟服务的，悬浮按钮、盲区画面）。
         */
        final boolean continuous;
        final Apply apply;
        private SeekBar bar;

        Slider(CharSequence label, int min, int max, int start, String unit, CharSequence note,
               boolean enabled, boolean continuous, Apply apply) {
            this.label = label;
            this.min = min;
            this.max = Math.max(min, max);
            this.start = Math.max(min, Math.min(this.max, start));
            this.unit = unit;
            this.note = note;
            this.enabled = enabled;
            this.continuous = continuous;
            this.apply = apply;
        }

        int value() {
            return bar != null ? min + bar.getProgress() : start;
        }
    }

    private SliderDialog() {
    }

    /** 弹框：标题、几根拖动条、「取消」「保存」。存了之后叫 {@code onSaved}（更新那一行的值）。 */
    static void show(Context context, CharSequence title, List<Slider> sliders, Runnable onSaved) {
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        for (Slider slider : sliders) {
            box.addView(block(context, slider));
        }
        final boolean[] saved = {false};
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    saved[0] = true;
                    for (Slider slider : sliders) {
                        if (slider.enabled) {
                            slider.apply.set(slider.value());
                        }
                    }
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(dialog -> {
                    if (saved[0]) {
                        return;
                    }
                    for (Slider slider : sliders) {
                        if (slider.enabled && slider.value() != slider.start) {
                            slider.apply.set(slider.start);
                        }
                    }
                }));
    }

    /** 一根拖动条那一块：上面名字和当前值，中间拖动条，下面说明。 */
    static View block(Context context, Slider slider) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(context, 8), 0, dp(context, 8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(context);
        label.setTextAppearance(R.style.TextAppearance_Cam_Body);
        label.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        if (slider.label != null) {
            label.setText(slider.label);
        }
        header.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(context);
        value.setTextAppearance(R.style.TextAppearance_Cam_Body);
        value.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        value.setText(slider.start + slider.unit);
        header.addView(value);
        block.addView(header);

        SeekBar bar = new SeekBar(context);
        bar.setMax(slider.max - slider.min);
        bar.setProgress(slider.start - slider.min);
        bar.setPadding(0, dp(context, 14), 0, dp(context, 14));
        bar.setEnabled(slider.enabled);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText((slider.min + progress) + slider.unit);
                if (fromUser && slider.continuous) {
                    slider.apply.set(slider.min + progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!slider.continuous) {
                    slider.apply.set(slider.min + seekBar.getProgress());
                }
            }
        });
        slider.bar = bar;
        block.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (slider.note != null) {
            block.addView(CamDialogs.note(context, slider.note));
        }
        if (!slider.enabled) {
            block.setAlpha(DISABLED_ALPHA);
        }
        return block;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
