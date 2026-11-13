/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.kooo.evcam.R;

/**
 * 多路布局里画面区怎么分：环视那一块（{@code composite_wrapper}）和座舱那一列（{@code cabin_column}）各占哪里，
 * 照此刻看得见的是哪几样定（ui-spec §一「主界面画面区怎么分」）。只在这一处定：主界面只管哪一格显示、哪一格藏起来。
 *
 * <table>
 *   <tr><th>看得见的</th><th>摆法</th></tr>
 *   <tr><td>只有环视，或者只有座舱</td><td>{@link Arrangement#SINGLE}：那一样占满</td></tr>
 *   <tr><td>环视 + 两路座舱</td><td>{@link Arrangement#SIDE_BY_SIDE}：环视 22 份、座舱那一列 14 份，中间一道缝</td></tr>
 *   <tr><td>环视 + 一路座舱</td><td>{@link Arrangement#THREE_BY_TWO}：三列两行，上面是「前」和座舱（两格宽），
 *       下面是「左」「后」「右」</td></tr>
 * </table>
 *
 * <h3>为什么有三列两行</h3>
 *
 * <p>座舱的画面是 16 : 9。左右并排时那一列又窄又高：两路上下叠放刚好，只有一路时上下各空一大块
 * （实车 2026-11-13，应用区 1600 × 848 dp）。三列两行把六格排满：环视四格各占一格（接近它本来的 1 : 1），
 * 座舱占右上两格、铺满，四边和别的格子对齐（项目所有者 2026-11-13）。</p>
 *
 * <p>三列两行时环视那一块占满整块画面区，座舱那一列盖在它的右上两格上；环视四格摆在哪由 {@link #threeByTwoCell}
 * 说，主界面照它交给四宫格容器。放大一格时别的都藏起来，剩下的那一样占满（{@link Arrangement#SINGLE}）。</p>
 */
public class PreviewStageLayout extends FrameLayout {

    /** 画面区的摆法。 */
    public enum Arrangement {
        /** 只有一样看得见（或者都看不见）：它占满。 */
        SINGLE,
        /** 环视在左、座舱那一列在右。 */
        SIDE_BY_SIDE,
        /** 三列两行：环视占满，座舱那一列盖在右上两格。 */
        THREE_BY_TWO
    }

    /** 左右并排时两块的份数（原来 LinearLayout 上的 layout_weight）。 */
    private static final float SURROUND_SHARE = 22f;
    private static final float CABIN_SHARE = 14f;

    /** 合成流里四路的次序：前、后、左、右（{@code CompositeStreamGeometry}）。 */
    private static final int LANE_FRONT = 0;
    private static final int LANE_REAR = 1;
    private static final int LANE_LEFT = 2;
    private static final int LANE_RIGHT = 3;

    private final Rect surroundRect = new Rect();
    private final Rect cabinRect = new Rect();
    private final int gutter;
    private View surround;
    private ViewGroup cabins;

    public PreviewStageLayout(Context context) {
        this(context, null);
    }

    public PreviewStageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        surround = findViewById(R.id.composite_wrapper);
        View column = findViewById(R.id.cabin_column);
        cabins = column instanceof ViewGroup ? (ViewGroup) column : null;
    }

    /** 此刻的摆法：照看得见的是哪几样现算，不等下一次布局。 */
    public Arrangement arrangement() {
        return arrangementOf(shown(surround), shownCabins());
    }

    /** @param cabins 看得见的座舱有几路 */
    public static Arrangement arrangementOf(boolean surround, int cabins) {
        if (!surround || cabins <= 0) {
            return Arrangement.SINGLE;
        }
        return cabins == 1 ? Arrangement.THREE_BY_TWO : Arrangement.SIDE_BY_SIDE;
    }

    /**
     * 两块各占哪里（画面区自己的坐标）。
     *
     * @param gutter 左右并排时中间那道缝
     */
    public static void place(Arrangement arrangement, int width, int height, int gutter,
                             Rect surroundOut, Rect cabinsOut) {
        switch (arrangement) {
            case THREE_BY_TWO:
                surroundOut.set(0, 0, width, height);
                cabinsOut.set(Math.round(width / 3f), 0, width, Math.round(height / 2f));
                break;
            case SIDE_BY_SIDE:
                int surroundWidth = Math.round((width - gutter) * SURROUND_SHARE / (SURROUND_SHARE + CABIN_SHARE));
                surroundOut.set(0, 0, surroundWidth, height);
                cabinsOut.set(surroundWidth + gutter, 0, width, height);
                break;
            default:
                surroundOut.set(0, 0, width, height);
                cabinsOut.set(0, 0, width, height);
        }
    }

    /**
     * 三列两行时环视的某一路摆在哪：{x, y, 宽, 高}，环视那一块宽高的比例。上面一行第一格是「前」（后两格是座舱），
     * 下面一行是「左」「后」「右」。不是这四路的返回 null。
     */
    public static float[] threeByTwoCell(int laneIndex) {
        float third = 1f / 3f;
        switch (laneIndex) {
            case LANE_FRONT:
                return new float[]{0f, 0f, third, 0.5f};
            case LANE_LEFT:
                return new float[]{0f, 0.5f, third, 0.5f};
            case LANE_REAR:
                return new float[]{third, 0.5f, third, 0.5f};
            case LANE_RIGHT:
                return new float[]{2f * third, 0.5f, third, 0.5f};
            default:
                return null;
        }
    }

    private static boolean shown(View view) {
        return view != null && view.getVisibility() == VISIBLE;
    }

    private int shownCabins() {
        if (!shown(cabins)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < cabins.getChildCount(); i++) {
            if (cabins.getChildAt(i).getVisibility() == VISIBLE) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        place(arrangement(), width, height, gutter, surroundRect, cabinRect);
        measureIn(surround, surroundRect);
        measureIn(cabins, cabinRect);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutIn(surround, surroundRect);
        layoutIn(cabins, cabinRect);
    }

    private static void measureIn(View child, Rect rect) {
        if (child != null && child.getVisibility() != GONE) {
            child.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY));
        }
    }

    private static void layoutIn(View child, Rect rect) {
        if (child != null && child.getVisibility() != GONE) {
            child.layout(rect.left, rect.top, rect.right, rect.bottom);
        }
    }
}
