/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.kooo.evcam.R;

/**
 * 录制键那一行：录制键，加上只在需要时从它右边切出来的那个键（「开启哨兵模式」，项目所有者 2026-11-13）。
 *
 * <h3>只管怎么分</h3>
 *
 * <p>这一行不知道那个键是干什么的、什么时候该在 —— 那由 {@link RecordButtonUi} 说（{@link #splitTo}）。这里只有一个数
 * {@link #split}：0 是录制键占满整行，1 是右边让给那个键、中间一道缝（{@code @dimen/gutter}）。两头之间是过渡：
 * 录制键的右边往左收，那个键贴着它从这一行的右边滑进来、同时由淡到实；收起时反过来，滑出去、录制键回到原来的大小。</p>
 *
 * <h3>那个键多宽</h3>
 *
 * <p>照它自己的内容（字的宽加两边留白，布局里写着最窄多少），最宽不超过这一行去掉一道缝之后的四分之一。项目所有者起初说
 * 「右侧四分之一」，看过样子后说那只是估的、能窄就窄（2026-11-13）：中文两行字约 82 dp，四分之一是 92 dp。</p>
 *
 * <h3>为什么自己量、自己摆</h3>
 *
 * <p>过渡时录制键的宽每一帧都在变，里面的字要跟着重新排；那个键的宽不变、只挪位置（平移不触发重新布局）。
 * 用现成的 LinearLayout 加权重也能分，但两头都要跟着变宽，那个键里的字会在过渡里来回折行。</p>
 *
 * <p>过渡是装饰性的（去掉之后信息不少，{@link MotionPolicy}）：系统关了动画、或者录制时减少动效开着时直接到位。
 * 这个键只在录制中出现，所以开着「录制时减少动效」的人看不到过渡。</p>
 */
public class RecordKeyRow extends ViewGroup {

    /** 分出去多少变了（0–1）：录制键里面的留白、字的大小跟着它调（{@link RecordButtonUi}）。 */
    public interface OnSplitListener {
        void onSplit(float split);
    }

    /** 出现之前先等这么久：录制键自己的变形（圆变方、底色）先走完，主界面刚打开时也等画面出来。 */
    static final long ENTER_DELAY_MS = Motion.SLOW;
    /** 时长照 ui-spec §十四 的三档：出来 300 ms，收回 250 ms；挪位置用标准曲线。 */
    static final long ENTER_MS = Motion.SLOW;
    static final long LEAVE_MS = Motion.MEDIUM;

    private View record;
    private View key;
    private final int gutter;
    /** 0 = 录制键占满；1 = 右边是那个键。 */
    private float split;
    /** 过渡要去的那一头。 */
    private boolean open;
    /** 那个键这一次量出来多宽（看不见时是 0）。 */
    private int keyWidth;
    @Nullable
    private ValueAnimator animator;
    @Nullable
    private OnSplitListener listener;

    public RecordKeyRow(Context context) {
        this(context, null);
    }

    public RecordKeyRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        record = getChildAt(0);
        key = getChildAt(1);
        applySplit(0f);
    }

    public void setOnSplitListener(@Nullable OnSplitListener listener) {
        this.listener = listener;
    }

    /** 此刻分出去多少（过渡中是中间的值）。 */
    public float split() {
        return split;
    }

    /** 那个键此刻算不算在（过渡要去的那一头）。 */
    public boolean isOpen() {
        return open;
    }

    /**
     * 把右边分给那个键，或者收回来。已经在那一头（或者正往那一头去）就不动。
     *
     * @param animate false 直接到位；true 也要看 {@link MotionPolicy} 让不让放
     */
    public void splitTo(boolean open, boolean animate) {
        if (this.open == open && (animator != null || split == (open ? 1f : 0f))) {
            return;
        }
        this.open = open;
        cancel();
        float target = open ? 1f : 0f;
        if (!animate || !isLaidOut() || !MotionPolicy.decorative(getContext())) {
            applySplit(target);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(split, target);
        a.setDuration(open ? ENTER_MS : LEAVE_MS);
        a.setStartDelay(open ? ENTER_DELAY_MS : 0);
        a.setInterpolator(Motion.standard());
        a.addUpdateListener(v -> applySplit((float) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                }
            }
        });
        animator = a;
        a.start();
    }

    private void cancel() {
        if (animator != null) {
            ValueAnimator running = animator;
            animator = null;
            running.cancel();
        }
    }

    private void applySplit(float value) {
        split = Math.max(0f, Math.min(1f, value));
        if (key != null) {
            key.setVisibility(split > 0f ? VISIBLE : GONE);
            key.setAlpha(split);
            placeKey();
        }
        if (listener != null) {
            listener.onSplit(split);
        }
        requestLayout();
    }

    /** 主界面不在眼前了：回到没分的样子，下次打开时那个键重新滑进来（项目所有者 2026-11-13：打开主界面时放一次）。 */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && (open || split > 0f)) {
            open = false;
            cancel();
            applySplit(0f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 量和摆

    /** 那个键最宽多少：整行去掉一道缝之后的四分之一。 */
    static int widestKey(int rowWidth, int gutter) {
        return Math.max(0, Math.round((rowWidth - gutter) / 4f));
    }

    /** 录制键此刻的宽：分出去多少，就让出多少（那个键的宽加一道缝）。 */
    static int recordWidth(int rowWidth, int gutter, int keyWidth, float split) {
        return rowWidth - Math.round(split * (keyWidth + gutter));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        keyWidth = 0;
        if (key != null && key.getVisibility() != GONE) {
            // 照它自己的内容量，封顶四分之一
            key.measure(MeasureSpec.makeMeasureSpec(widestKey(width, gutter), MeasureSpec.AT_MOST), exactHeight);
            keyWidth = key.getMeasuredWidth();
        }
        if (record != null) {
            record.measure(MeasureSpec.makeMeasureSpec(recordWidth(width, gutter, keyWidth, split), MeasureSpec.EXACTLY),
                    exactHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        if (record != null) {
            record.layout(0, 0, recordWidth(width, gutter, keyWidth, split), height);
        }
        if (key != null && key.getVisibility() != GONE) {
            key.layout(width - keyWidth, 0, width, height);
            placeKey();
        }
    }

    /** 那个键贴着录制键的右边走：没分时整个在这一行右边外面（被这一行裁掉），分完正好贴着这一行的右边。 */
    private void placeKey() {
        if (key != null && keyWidth > 0) {
            key.setTranslationX((1f - split) * (keyWidth + gutter));
        }
    }
}
