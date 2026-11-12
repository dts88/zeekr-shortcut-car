/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 分段选择里选中的那一块（ui-spec §十四「值变了」）：实心能量色，画在底槽上、段的字下面；换了一段，它从原来那一段滑过去，不跳。
 *
 * <p>两种分段共用它：设置行尾的（{@code settings.Segments}）和「详细参数」里一排档位的（{@link SegmentedBar}）。那两处每次显示都把段
 * 整排重摆，段自己不带底色；选中块是底槽背景上的一层，底槽还在，它就记得上一次停在哪。</p>
 *
 * <p>只有「同一排段、换了选中的」才滑：行被列表拿去显示别的设置、段的字变了，直接摆到位。不放装饰动效时
 * （{@link MotionPolicy}）也直接到位。</p>
 */
public final class SegmentThumb extends Drawable implements View.OnLayoutChangeListener {

    /** 不可用时的不透明度（Material 的禁用值），和段上的字一起灰。 */
    private static final int DISABLED_ALPHA = Math.round(0.38f * 255);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;

    private CharSequence[] labels = new CharSequence[0];
    private int chosen = -1;
    /** 下一次排好以后要不要滑过去。 */
    private boolean slide;
    private ValueAnimator animator;
    /** 正在滑向哪里：排版又来一遍、目的地没变时不打断。 */
    private float toLeft;
    private float toRight;

    private SegmentThumb(Context context) {
        paint.setColor(ContextCompat.getColor(context, R.color.energy));
        radius = 9 * context.getResources().getDisplayMetrics().density;
    }

    /** 这条底槽的选中块；第一次要时装上（垫在底槽原来的背景上面）。 */
    public static SegmentThumb on(ViewGroup track) {
        Object tag = track.getTag(R.id.segment_thumb);
        if (tag instanceof SegmentThumb) {
            return (SegmentThumb) tag;
        }
        SegmentThumb thumb = new SegmentThumb(track.getContext());
        // 换背景会把内边距换成新背景的：先记下，换完放回去
        int left = track.getPaddingLeft();
        int top = track.getPaddingTop();
        int right = track.getPaddingRight();
        int bottom = track.getPaddingBottom();
        Drawable base = track.getBackground();
        track.setBackground(base != null ? new LayerDrawable(new Drawable[]{base, thumb}) : thumb);
        track.setPadding(left, top, right, bottom);
        track.addOnLayoutChangeListener(thumb);
        track.setTag(R.id.segment_thumb, thumb);
        return thumb;
    }

    /**
     * 这一排现在是这几段、选中第几段。在把段重新摆进底槽<b>之前</b>叫；段排好以后选中块才落位。
     *
     * @param chosen 选中第几段；不在范围里就不画
     */
    public void show(ViewGroup track, CharSequence[] labels, int chosen, boolean enabled) {
        slide = sameLabels(labels) && this.chosen >= 0 && chosen >= 0 && this.chosen != chosen && !rect.isEmpty()
                && track.isLaidOut() && MotionPolicy.decorative(track.getContext());
        this.labels = labels.clone();
        this.chosen = chosen;
        paint.setAlpha(enabled ? 255 : DISABLED_ALPHA);
        invalidateSelf();
    }

    private boolean sameLabels(CharSequence[] other) {
        if (other.length != labels.length) {
            return false;
        }
        for (int i = 0; i < other.length; i++) {
            if (!TextUtils.equals(other[i], labels[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
        place((ViewGroup) v);
    }

    /** 段排好了：选中块落到选中的那一段上，该滑就滑过去。 */
    private void place(ViewGroup track) {
        View target = chosen >= 0 && chosen < track.getChildCount() ? track.getChildAt(chosen) : null;
        if (target == null) {
            stop();
            slide = false;
            rect.setEmpty();
            invalidateSelf();
            return;
        }
        float left = target.getLeft();
        float right = target.getRight();
        if (animator != null && animator.isRunning() && toLeft == left && toRight == right) {
            return;
        }
        stop();
        boolean sliding = slide;
        slide = false;
        float fromLeft = rect.left;
        float fromRight = rect.right;
        rect.top = target.getTop();
        rect.bottom = target.getBottom();
        if (!sliding) {
            rect.left = left;
            rect.right = right;
            invalidateSelf();
            return;
        }
        toLeft = left;
        toRight = right;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Motion.FAST);
        animator.setInterpolator(Motion.standard());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            rect.left = fromLeft + (left - fromLeft) * t;
            rect.right = fromRight + (right - fromRight) * t;
            invalidateSelf();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!rect.isEmpty()) {
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // 透明度由可不可用定（show）
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
