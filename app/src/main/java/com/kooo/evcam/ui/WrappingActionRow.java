package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import com.kooo.evcam.R;
import java.util.ArrayList;
import java.util.List;

/** Content-sized native actions, wrapping when needed with equal height within each row.
 * No baseline alignment, fixed text heights, truncated labels or reduced font sizes.
 */
public final class WrappingActionRow extends ViewGroup {
    private final List<List<View>> rows = new ArrayList<>();
    private final int gap;
    public WrappingActionRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        gap = getResources().getDimensionPixelSize(R.dimen.playback_action_gap);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        rows.clear();
        List<View> row = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int desired = child.getMeasuredWidth();
            if (!row.isEmpty() && used + gap + desired > available) {
                rows.add(row); row = new ArrayList<>(); used = 0;
            }
            used += (row.isEmpty() ? 0 : gap) + desired;
            row.add(child);
        }
        if (!row.isEmpty()) rows.add(row);
        int height = getPaddingTop() + getPaddingBottom();
        for (List<View> group : rows) {
            int natural = 0;
            for (View child : group) natural += child.getMeasuredWidth();
            int extra = Math.max(0, available - natural - gap * (group.size() - 1));
            int rowHeight = 0;
            for (int i = 0; i < group.size(); i++) {
                View child = group.get(i);
                int childWidth = child.getMeasuredWidth() + extra/group.size() + (i < extra%group.size() ? 1 : 0);
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            for (View child : group) child.measure(
                    MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            height += rowHeight + gap;
        }
        if (!rows.isEmpty()) height -= gap;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int top = getPaddingTop();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (List<View> row : rows) {
            int cursor = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
            for (View child : row) {
                int width = child.getMeasuredWidth();
                int left = rtl ? cursor - width : cursor;
                child.layout(left, top, left + width, top + child.getMeasuredHeight());
                cursor += (rtl ? -1 : 1) * (width + gap);
            }
            top += row.get(0).getMeasuredHeight() + gap;
        }
    }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(-2, -2); }
    @Override public LayoutParams generateLayoutParams(AttributeSet attrs) { return new LayoutParams(getContext(), attrs); }
}
