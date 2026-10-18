package com.kooo.evcam.zeekr;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.kooo.evcam.R;
import com.kooo.evcam.settings.Languages;

/** Native accessible corner buttons above the camera picture. */
final class SideViewControls extends FrameLayout {
    private final ImageButton expand;
    private final ImageButton close;
    private boolean mirrorMode;
    private boolean expanded;
    private final int targetSize;
    private final int margin;

    SideViewControls(Context context, Runnable onClose, Runnable onExpand) {
        super(context);
        targetSize = getResources().getDimensionPixelSize(R.dimen.side_control_target);
        margin = getResources().getDimensionPixelSize(R.dimen.side_control_margin);
        expand = button(R.drawable.ic_side_expand, R.string.side_control_expand, onExpand);
        addButton(expand, Gravity.START);
        close = button(R.drawable.ic_side_close, R.string.side_control_close, onClose);
        addButton(close, Gravity.END);
        setExpanded(false);
    }

    private ImageButton button(int icon, int description, Runnable action) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setContentDescription(Languages.localized(getContext()).getString(description));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int padding = getResources().getDimensionPixelSize(R.dimen.side_control_padding);
        button.setPadding(padding, padding, padding, padding);
        applyTheme(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void applyTheme(ImageButton button) {
        button.setImageTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.side_control_icon, null)));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getResources().getColor(R.color.side_control_background, null));
        background.setCornerRadius(getResources().getDimension(R.dimen.side_control_radius));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
                getResources().getColor(R.color.side_control_pressed, null)), background, null));
    }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // These service-owned windows survive Activity recreation. Repaint without touching cameras.
        for (int i = 0; i < getChildCount(); i++) applyTheme((ImageButton) getChildAt(i));
        invalidate();
    }

    private void addButton(ImageButton button, int gravity) {
        LayoutParams layout = new LayoutParams(targetSize, targetSize, Gravity.TOP | gravity);
        layout.setMargins(margin, margin, margin, margin);
        addView(button, layout);
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        expand.setImageResource(expanded ? R.drawable.ic_side_restore : R.drawable.ic_side_expand);
        expand.setContentDescription(Languages.localized(getContext()).getString(
                expanded ? R.string.side_control_restore : mirrorMode
                        ? R.string.mirror_control_expand : R.string.side_control_expand));
    }

    void setMirrorMode(boolean mirrorMode) {
        this.mirrorMode = mirrorMode;
        close.setContentDescription(Languages.localized(getContext()).getString(
                mirrorMode ? R.string.mirror_control_close : R.string.side_control_close));
        setExpanded(expanded);
    }

    int barHeight() {
        return targetSize + 2 * margin;
    }

    int minimumWindowSize() {
        return 2 * targetSize + 3 * margin;
    }

    void layoutIn(int width) {
        // A very narrow mirror still exposes Close; its usual pinch gesture can enlarge it.
        expand.setVisibility(width >= minimumWindowSize() ? View.VISIBLE : View.GONE);
        layout(0, 0, width, barHeight());
    }
}
