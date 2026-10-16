package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Keeps profile cards readable as a group; narrow panes/large text use one column. */
public final class ResponsiveCardRow extends LinearLayout {
    public ResponsiveCardRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBaselineAligned(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) count++;
        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(12 * density);
        int minimum = Math.round(240 * density * getResources().getConfiguration().fontScale);
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean row = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                || available >= count * minimum + Math.max(0, count - 1) * gap;
        setOrientation(row ? HORIZONTAL : VERTICAL);
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            params.width = row ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = row ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            params.weight = row ? 1 : 0;
            params.leftMargin = 0;
            params.rightMargin = 0;
            params.setMarginStart(row && visible > 0 ? gap : 0);
            params.setMarginEnd(0);
            params.resolveLayoutDirection(getLayoutDirection());
            params.topMargin = !row && visible > 0 ? gap : 0;
            visible++;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
