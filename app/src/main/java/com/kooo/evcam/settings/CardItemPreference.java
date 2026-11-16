/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.kooo.evcam.R;

import java.util.function.Consumer;

public class CardItemPreference extends Preference implements PreferenceRows.OwnLayout {

    static final int ALONE = 0;
    static final int TOP = 1;
    static final int MIDDLE = 2;
    static final int BOTTOM = 3;

    private CharSequence value;
    private boolean last;
    private boolean wanted = true;
    private boolean checked;
    private Consumer<Boolean> onToggle;

    public CardItemPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.pref_row_card_item);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    void setValue(CharSequence value) {
        if (!TextUtils.equals(this.value, value)) {
            this.value = value;
            notifyChanged();
        }
    }

    CharSequence getValue() {
        return value;
    }

    void setLast(boolean last) {
        if (this.last != last) {
            this.last = last;
            notifyChanged();
        }
    }

    void setSwitch(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
        setWidgetLayoutResource(R.layout.pref_widget_switch);
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

    boolean isWanted() {
        return wanted;
    }

    void setWanted(boolean wanted) {
        this.wanted = wanted;
    }

    @Override
    protected void onClick() {
        if (onToggle != null) {
            checked = !checked;
            notifyChanged();
            onToggle.accept(checked);
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View frame = holder.findViewById(android.R.id.widget_frame);
        if (frame != null) {
            frame.setVisibility(View.VISIBLE);
        }
        View toggle = holder.findViewById(R.id.switchWidget);
        if (toggle instanceof Checkable) {
            ((Checkable) toggle).setChecked(checked);
        }
        TextView valueView = (TextView) holder.findViewById(R.id.pref_value);
        if (valueView != null) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }
        View chevron = holder.findViewById(R.id.pref_chevron);
        if (chevron != null) {
            chevron.setVisibility(isSelectable() && onToggle == null ? View.VISIBLE : View.GONE);
        }
        shape(holder.itemView, last ? BOTTOM : MIDDLE, isSelectable());
    }

    static void shape(View row, int position, boolean clickable) {
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        int gap = position == ALONE || position == BOTTOM ? Math.round(8 * density) : 0;
        if (lp instanceof ViewGroup.MarginLayoutParams && ((ViewGroup.MarginLayoutParams) lp).bottomMargin != gap) {
            ((ViewGroup.MarginLayoutParams) lp).bottomMargin = gap;
            row.setLayoutParams(lp);
        }
        if (position == ALONE) {
            row.setBackgroundResource(clickable ? R.drawable.bg_pref_row : R.drawable.bg_pref_row_static);
            return;
        }
        float r = context.getResources().getDimension(R.dimen.corner_radius);
        float top = position == TOP ? r : 0f;
        float bottom = position == BOTTOM ? r : 0f;
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        GradientDrawable card = new GradientDrawable();
        card.setColor(context.getColor(R.color.sunken));
        card.setCornerRadii(radii);
        Drawable content = card;
        if (position != TOP) {
            GradientDrawable line = new GradientDrawable();
            line.setColor(context.getColor(R.color.line));
            LayerDrawable layers = new LayerDrawable(new Drawable[] {card, line});
            layers.setLayerInsetRelative(1, context.getResources().getDimensionPixelSize(R.dimen.pref_text_start), 0, 0, 0);
            layers.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            layers.setLayerHeight(1, Math.max(1, Math.round(density)));
            content = layers;
        }
        if (!clickable) {
            row.setBackground(content);
            return;
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadii(radii);
        row.setBackground(new RippleDrawable(ContextCompat.getColorStateList(context, R.color.nav_ripple_color),
                content, mask));
    }
}
