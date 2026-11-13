/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.PreviewStageLayout;
import com.kooo.evcam.ui.PreviewStageLayout.Arrangement;
import com.kooo.evcam.ui.StatusLine;
import com.kooo.evcam.zeekr.FourLaneContainer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 多路布局里画面区怎么分（{@link PreviewStageLayout}，ui-spec §一「主界面画面区怎么分」）：照实车的尺寸摆出来量，
 * 顺带画成图片（{@link Shots}）。电脑上没有相机，画面是空的 —— 图上每一格垫了一块色、写着是哪一路，看的是格子摆在哪。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PreviewStageShotsTest {

    /** 合成流里四路的次序。 */
    private static final int FRONT = 0;
    private static final int REAR = 1;
    private static final int LEFT = 2;
    private static final int RIGHT = 3;

    @Test
    public void arrangementFollowsWhatIsShown() {
        assertEquals(Arrangement.SINGLE, PreviewStageLayout.arrangementOf(true, 0));
        assertEquals(Arrangement.SINGLE, PreviewStageLayout.arrangementOf(false, 1));
        assertEquals(Arrangement.SINGLE, PreviewStageLayout.arrangementOf(false, 2));
        assertEquals(Arrangement.SINGLE, PreviewStageLayout.arrangementOf(false, 0));
        assertEquals("环视 + 一路座舱：三列两行", Arrangement.THREE_BY_TWO, PreviewStageLayout.arrangementOf(true, 1));
        assertEquals("环视 + 两路座舱：左右并排", Arrangement.SIDE_BY_SIDE, PreviewStageLayout.arrangementOf(true, 2));
    }

    @Test
    public void threeByTwoCellsAreFrontOnTopAndLeftRearRightBelow() {
        float third = 1f / 3f;
        assertCell("前在左上", PreviewStageLayout.threeByTwoCell(FRONT), 0f, 0f);
        assertCell("左在左下", PreviewStageLayout.threeByTwoCell(LEFT), 0f, 0.5f);
        assertCell("后在下面中间", PreviewStageLayout.threeByTwoCell(REAR), third, 0.5f);
        assertCell("右在右下", PreviewStageLayout.threeByTwoCell(RIGHT), 2f * third, 0.5f);
        assertNull("不是环视四路里的一路", PreviewStageLayout.threeByTwoCell(-1));
        assertNull(PreviewStageLayout.threeByTwoCell(4));
    }

    /** 环视 + 后座舱（实车 2026-11-13 的那种配置）：三列两行，座舱占右上两格，四边和别的格子对齐。 */
    /** 环视四格也铺满：照「适应」的话画面上下各留一条黑边，和铺满的座舱对不齐（实车 2026-11-13）。 */
    @Test
    public void threeByTwoSurroundCellsFill() {
        for (FourLaneContainer.Cell cell : threeByTwoCells()) {
            assertEquals(FourLaneContainer.ScaleMode.FILL, cell.fit);
            assertEquals(FourLaneContainer.ScaleMode.FILL, cell.fitCorrected);
        }
        FourLaneContainer.Cell other = new FourLaneContainer.Cell();
        other.laneIndex = -1;
        assertFalse("不是环视四路里的一路：不动", PreviewStageLayout.placeThreeByTwo(other));
        assertNull(other.fit);
        assertEquals(1f, other.width, 0f);
    }

    @Test
    public void surroundWithOneCabinIsThreeByTwo() {
        Stage stage = inflate();
        stage.frontCabin.setVisibility(View.GONE);
        topMargin(stage.rearCabin, 0);
        stage.surround.setCells(threeByTwoCells());
        stage.layout();

        assertEquals(Arrangement.THREE_BY_TWO, stage.panes.arrangement());
        int width = stage.panes.getWidth();
        int height = stage.panes.getHeight();
        assertTrue("画面区没量出来", width > 0 && height > 0);
        assertEquals("环视那一块占满整块画面区", new Rect(0, 0, width, height), bounds(stage.wrapper));
        Rect cabin = bounds(stage.column);
        assertEquals("座舱那一列在右上两格", new Rect(Math.round(width / 3f), 0, width, Math.round(height / 2f)), cabin);
        assertEquals("后座舱那一格占满这一列", cabin.width(), stage.rearCabin.getWidth());
        assertEquals(cabin.height(), stage.rearCabin.getHeight());

        // 和环视的格子对齐：左边对着「后」的左边，右边对着「右」的右边，下边对着「前」的下边，左边贴着「前」的右边
        assertEquals("座舱的左边和「后」那一格的左边不齐", lane(stage, REAR).left, cabin.left, 1f);
        assertEquals("座舱的右边和「右」那一格的右边不齐", lane(stage, RIGHT).right, cabin.right, 1f);
        assertEquals("座舱的下边和「前」那一格的下边不齐", lane(stage, FRONT).bottom, cabin.bottom, 1f);
        assertEquals("座舱没有贴着「前」那一格", lane(stage, FRONT).right, cabin.left, 1f);
        assertEquals("六格一样大：座舱是两格宽", lane(stage, REAR).width() * 2f, cabin.width(), 2f);
        assertEquals("六格一样大：座舱是一格高", lane(stage, LEFT).height(), cabin.height(), 1f);

        stage.tint();
        File file = Shots.save(stage.activity, "stage-three-by-two-day");
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /** 环视 + 前后两路座舱：照旧左右并排，22 份对 14 份，中间一道缝。 */
    @Test
    public void surroundWithBothCabinsStaysSideBySide() {
        Stage stage = inflate();
        stage.layout();

        assertEquals(Arrangement.SIDE_BY_SIDE, stage.panes.arrangement());
        int width = stage.panes.getWidth();
        int height = stage.panes.getHeight();
        int gutter = stage.activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        int surroundWidth = Math.round((width - gutter) * 22f / 36f);
        assertEquals(new Rect(0, 0, surroundWidth, height), bounds(stage.wrapper));
        assertEquals(new Rect(surroundWidth + gutter, 0, width, height), bounds(stage.column));

        stage.tint();
        File file = Shots.save(stage.activity, "stage-side-by-side-day");
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /** 放大一格时别的藏起来：剩下的那一样占满，收回（都显示回来）又是原来的摆法。 */
    @Test
    public void whatIsLeftFillsTheStage() {
        Stage stage = inflate();
        stage.frontCabin.setVisibility(View.GONE);
        stage.layout();
        int width = stage.panes.getWidth();
        int height = stage.panes.getHeight();
        Rect whole = new Rect(0, 0, width, height);

        // 放大环视的一格：座舱那一列让出来
        stage.column.setVisibility(View.GONE);
        stage.layout();
        assertEquals(Arrangement.SINGLE, stage.panes.arrangement());
        assertEquals(whole, bounds(stage.wrapper));

        // 放大座舱：环视让出来
        stage.column.setVisibility(View.VISIBLE);
        stage.wrapper.setVisibility(View.GONE);
        stage.layout();
        assertEquals(Arrangement.SINGLE, stage.panes.arrangement());
        assertEquals(whole, bounds(stage.column));

        // 收回
        stage.wrapper.setVisibility(View.VISIBLE);
        stage.layout();
        assertEquals(Arrangement.THREE_BY_TWO, stage.panes.arrangement());
        assertEquals(new Rect(Math.round(width / 3f), 0, width, Math.round(height / 2f)), bounds(stage.column));

        // 座舱那一格没建出来（藏了）：这一列里没有看得见的，环视占满，不留一条空列
        stage.rearCabin.setVisibility(View.GONE);
        stage.layout();
        assertEquals(Arrangement.SINGLE, stage.panes.arrangement());
        assertEquals(whole, bounds(stage.wrapper));
    }

    // ------------------------------------------------------------------

    private static void assertCell(String message, float[] cell, float x, float y) {
        assertNotNull(message, cell);
        assertEquals(message, x, cell[0], 0.0001f);
        assertEquals(message, y, cell[1], 0.0001f);
        assertEquals(message + "：一格宽三分之一", 1f / 3f, cell[2], 0.0001f);
        assertEquals(message + "：一格高一半", 0.5f, cell[3], 0.0001f);
    }

    /** 主界面交给四宫格容器的那一份：位置、铺满照三列两行，别的都是默认。 */
    private static FourLaneContainer.Cell[] threeByTwoCells() {
        FourLaneContainer.Cell[] cells = new FourLaneContainer.Cell[4];
        for (int lane = 0; lane < cells.length; lane++) {
            FourLaneContainer.Cell cell = new FourLaneContainer.Cell();
            cell.laneIndex = lane;
            assertTrue(PreviewStageLayout.placeThreeByTwo(cell));
            cells[lane] = cell;
        }
        return cells;
    }

    private static Rect bounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    /** 环视的某一路在画面区里占的矩形（环视那一块就在画面区的左上角起，容器铺满那一块）。 */
    private static RectF lane(Stage stage, int lane) {
        RectF bounds = new RectF();
        assertTrue("环视容器还没量出尺寸", stage.surround.laneBounds(lane, bounds));
        bounds.offset(stage.wrapper.getLeft(), stage.wrapper.getTop());
        return bounds;
    }

    private static void topMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = margin;
        view.setLayoutParams(params);
    }

    private static Stage inflate() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.activity_main_zeekr_7x_multi, frame, true);
        Shots.settle();
        StatusLine.fill(frame);
        Stage stage = new Stage();
        stage.activity = activity;
        stage.panes = frame.findViewById(R.id.preview_panes);
        stage.wrapper = frame.findViewById(R.id.composite_wrapper);
        stage.surround = frame.findViewById(R.id.composite_container);
        stage.column = frame.findViewById(R.id.cabin_column);
        stage.frontCabin = frame.findViewById(R.id.pane_cabin_front);
        stage.rearCabin = frame.findViewById(R.id.pane_cabin_rear);
        assertNotNull("多路布局里没有分画面区的那一层", stage.panes);
        return stage;
    }

    private static final class Stage {
        ShotActivity activity;
        PreviewStageLayout panes;
        FrameLayout wrapper;
        FourLaneContainer surround;
        View column;
        View frontCabin;
        View rearCabin;

        /** 照实车的尺寸量一遍、摆一遍（和 {@link Shots#save} 画之前做的一样）。 */
        void layout() {
            Shots.settle();
            View root = activity.getWindow().getDecorView();
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
            Shots.settle();
        }

        /** 每一格垫一块色、写上是哪一路：电脑上没有画面，不垫的话图上是一片白。 */
        void tint() {
            int[] names = {R.string.zeekr_lane_front, R.string.zeekr_lane_back, R.string.zeekr_lane_left,
                    R.string.zeekr_lane_right};
            int[] colors = {0xFFD5DBE1, 0xFFC3CBD3, 0xFFE2E6EA, 0xFFCCD3DA};
            RectF at = new RectF();
            for (int lane = 0; lane < names.length; lane++) {
                surround.laneBounds(lane, at);
                TextView cell = new TextView(activity);
                cell.setText(names[lane]);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(28);
                cell.setTextColor(0xFF46515C);
                cell.setBackgroundColor(colors[lane]);
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(Math.round(at.width()), Math.round(at.height()));
                params.leftMargin = Math.round(at.left);
                params.topMargin = Math.round(at.top);
                wrapper.addView(cell, 1, params);
            }
            for (View cabin : new View[]{frontCabin, rearCabin}) {
                if (cabin.getVisibility() == View.VISIBLE) {
                    // 垫在画布上面、角标下面（画布在电脑上是一块白）
                    View fill = new View(activity);
                    fill.setBackgroundColor(0xFF9FD3C2);
                    ((ViewGroup) cabin).addView(fill, 1, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }
        }
    }
}
