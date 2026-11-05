/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

/**
 * 设置 → 盲区画面 → 中控屏上的位置和大小：在整个屏幕上调盲区画面窗口的编辑框（项目所有者 2026-11-03）。
 * <b>调盲区画面位置和大小只有这一个地方</b>：窗口本身不能拖、不能缩放，开车时动不了它。
 *
 * <p>照超级镜子的编辑框（{@link RearViewWindowEditor}）：一层全屏悬浮窗，框外压暗；拖框内挪、拖边拖角或双指缩放改大小。
 * 不同的是屏幕上有<b>两个框</b>，左侧、右侧各一个，互为镜像（{@link SideViewFrames}）：一样高、一样大，以屏幕竖中线为轴
 * 左右相反，动哪一个另一个跟着动；可以叠在一起。宽 ÷ 高不变。框里标着「左侧」「右侧」。</p>
 *
 * <p>和盲区画面窗口在同一个坐标里：同一类悬浮窗、同样不带「铺满整屏」的标志，屏幕尺寸也取同一份（应用的显示尺寸），
 * 框画在哪，窗口就在哪。「保存」存左边那个框（{@link AppConfig#setSideViewPlacement}），挂着的窗口当场挪过去；
 * 「取消」、返回键不存。同一时刻只有一个；设置页退到后台时由它关掉（{@link #cancel}）。</p>
 */
public final class SideViewPlacementEditor {

    private static final String TAG = "SideViewPlacementEditor";

    /** 框外压暗：不跟日夜变（同超级镜子的编辑框）。 */
    private static final int DIM = 0x99000000;
    private static final int LABEL_BACKGROUND = 0xB3000000;
    private static final float STROKE_DP = 3f;
    private static final float HANDLE_RADIUS_DP = 11f;
    /** 离边、角多近算抓住。 */
    private static final float REACH_DP = 40f;
    private static final float MARGIN_DP = 32f;

    /** 存了之后叫它（设置页更新那一行的值）。 */
    public interface Listener {
        void onSaved();
    }

    private static SideViewPlacementEditor current;

    private final Context context;
    private final AppConfig appConfig;
    private final WindowManager windowManager;
    private final Listener listener;
    private final FrameView frameView;
    private final View root;

    /** 打开编辑框。已经开着就不再开第二个。 */
    public static void show(Context context, AppConfig appConfig, Listener listener) {
        if (current != null) {
            return;
        }
        SideViewPlacementEditor editor = new SideViewPlacementEditor(context, appConfig, listener);
        if (editor.attach()) {
            current = editor;
        }
    }

    /** 开着就关掉、不存（设置页退到后台时）。 */
    public static void cancel() {
        if (current != null) {
            current.dismiss();
        }
    }

    private SideViewPlacementEditor(Context context, AppConfig appConfig, Listener listener) {
        this.context = new ContextThemeWrapper(context, R.style.Theme_Cam);
        this.appConfig = appConfig;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.frameView = new FrameView(this.context);
        this.root = buildRoot();
    }

    // ------------------------------------------------------------------ 窗口

    /** 和盲区画面窗口（{@link SideViewWindow}）同一类、同样的位置基准：编辑框的原点就是窗口位置的原点。 */
    private boolean attach() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(root, params);
            root.requestFocus();
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "盲区画面编辑框添加失败", e);
            return false;
        }
    }

    private void dismiss() {
        if (current == this) {
            current = null;
        }
        try {
            windowManager.removeView(root);
        } catch (Exception e) {
            AppLog.w(TAG, "盲区画面编辑框移除失败: " + e);
        }
    }

    private void save() {
        DisplayMetrics metrics = displayMetrics();
        SideViewPlacement.Spec spec = SideViewPlacement.toSpec(metrics.widthPixels, metrics.heightPixels, frameView.left);
        appConfig.setSideViewPlacement(spec);
        AppLog.i(TAG, "盲区画面编辑框：保存左侧 " + frameView.left.left + "," + frameView.left.top + " "
                + frameView.left.width + "×" + frameView.left.height + "（千分比 高 " + spec.height + " 离边 " + spec.edge
                + " 上 " + spec.top + "）");
        SideViewService.applyPlacement();
        dismiss();
        if (listener != null) {
            listener.onSaved();
        }
    }

    /** 屏幕尺寸取盲区画面窗口用的同一份（应用的显示尺寸，{@link SideViewWindow}）。 */
    private DisplayMetrics displayMetrics() {
        return context.getApplicationContext().getResources().getDisplayMetrics();
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 布局：两个框、下面一句提示和取消 / 保存

    @SuppressLint("ViewConstructor")
    private View buildRoot() {
        FrameLayout layout = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        dismiss();
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        layout.setFocusableInTouchMode(true);
        layout.addView(frameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 提示和按钮在下面正中：两个框默认在左右两边、上下居中
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomParams.bottomMargin = dp(MARGIN_DP);
        layout.addView(bottom, bottomParams);

        TextView hint = new TextView(context);
        hint.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        hint.setTextColor(ContextCompat.getColor(context, R.color.status_overlay_text));
        hint.setText(R.string.side_view_editor_hint);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(20), dp(10), dp(20), dp(10));
        hint.setBackground(pill(LABEL_BACKGROUND));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.bottomMargin = dp(16);
        bottom.addView(hint, hintParams);

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bottom.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MaterialButton cancel = new MaterialButton(context);
        cancel.setText(R.string.action_cancel);
        cancel.setOnClickListener(v -> dismiss());
        bar.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        CamDialogs.paintButton(cancel, false);
        MaterialButton save = new MaterialButton(context);
        save.setText(R.string.action_save);
        save.setOnClickListener(v -> save());
        bar.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        CamDialogs.paintButton(save, true);
        return layout;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));
        return shape;
    }

    // ------------------------------------------------------------------ 两个框

    /** 画框、接手势的那一层。只存左边那个框，右边那个照镜像画。 */
    @SuppressLint("ViewConstructor")
    private final class FrameView extends View {

        SideViewPlacement.Rect left;
        private SideViewPlacement.Rect start;
        private SideViewFrames.Grab grab = new SideViewFrames.Grab(SideViewFrames.NO_FRAME, RearViewFrameModel.NONE);
        private boolean pinching;
        /** 捏的是右边那个框：两指中点换成镜像那一侧再算。 */
        private boolean pinchMirrored;
        private float downX;
        private float downY;
        private float pinchStartSpan;
        private float pinchAnchorX;
        private float pinchAnchorY;
        private boolean originNoted;

        private final String leftLabel;
        private final String rightLabel;
        private final Paint dimPaint = new Paint();
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect textBounds = new Rect();
        private final RectF labelRect = new RectF();

        FrameView(Context context) {
            super(context);
            DisplayMetrics metrics = displayMetrics();
            left = SideViewPlacement.compute(metrics.widthPixels, metrics.heightPixels, LaneCycle.LEFT,
                    appConfig.getSideViewPlacement());
            leftLabel = context.getString(R.string.side_view_editor_left);
            rightLabel = context.getString(R.string.side_view_editor_right);
            dimPaint.setColor(DIM);
            int energy = ContextCompat.getColor(context, R.color.energy);
            strokePaint.setColor(energy);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(STROKE_DP));
            handleFill.setColor(Color.WHITE);
            handleStroke.setColor(energy);
            handleStroke.setStyle(Paint.Style.STROKE);
            handleStroke.setStrokeWidth(dp(STROKE_DP));
            labelPaint.setColor(ContextCompat.getColor(context, R.color.status_overlay_text));
            labelPaint.setTextSize(context.getResources().getDimension(R.dimen.text_caption));
            labelBackground.setColor(LABEL_BACKGROUND);
        }

        private int boundsWidth() {
            return displayMetrics().widthPixels;
        }

        private int boundsHeight() {
            return displayMetrics().heightPixels;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (!originNoted && w > 0 && h > 0) {
                // 车上核对用：编辑框原点、大小和算位置用的屏幕尺寸，应该和盲区画面窗口一样
                originNoted = true;
                int[] origin = new int[2];
                getLocationOnScreen(origin);
                AppLog.i(TAG, "盲区画面编辑框：原点 " + origin[0] + "," + origin[1] + "，大小 " + w + "×" + h
                        + "，屏幕 " + boundsWidth() + "×" + boundsHeight());
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            SideViewPlacement.Rect l = left;
            SideViewPlacement.Rect r = SideViewFrames.right(l, boundsWidth());
            // 框外压暗、框内透亮
            canvas.save();
            canvas.clipOutRect(l.left, l.top, l.right(), l.bottom());
            canvas.clipOutRect(r.left, r.top, r.right(), r.bottom());
            canvas.drawColor(DIM);
            canvas.restore();
            drawFrame(canvas, l);
            drawFrame(canvas, r);
            drawLabel(canvas, l, leftLabel, true);
            drawLabel(canvas, r, rightLabel, false);
        }

        private void drawFrame(Canvas canvas, SideViewPlacement.Rect f) {
            canvas.drawRect(f.left, f.top, f.right(), f.bottom(), strokePaint);
            float cx = f.left + f.width / 2f;
            float cy = f.top + f.height / 2f;
            float r = dp(HANDLE_RADIUS_DP);
            float[] xs = {f.left, cx, f.right()};
            float[] ys = {f.top, cy, f.bottom()};
            for (float hx : xs) {
                for (float hy : ys) {
                    if (hx == cx && hy == cy) {
                        continue;
                    }
                    canvas.drawCircle(hx, hy, r, handleFill);
                    canvas.drawCircle(hx, hy, r, handleStroke);
                }
            }
        }

        /** 标在框里靠外的上角：左侧在左上、右侧在右上 —— 两个框叠在一起时也分得开。 */
        private void drawLabel(Canvas canvas, SideViewPlacement.Rect f, String label, boolean atLeft) {
            labelPaint.getTextBounds(label, 0, label.length(), textBounds);
            float padH = dp(14);
            float padV = dp(8);
            float labelW = textBounds.width() + 2 * padH;
            float labelH = -labelPaint.ascent() + labelPaint.descent() + 2 * padV;
            float inset = dp(HANDLE_RADIUS_DP) + dp(6);
            float x = atLeft ? f.left + inset : f.right() - inset - labelW;
            float y = f.top + inset;
            labelRect.set(x, y, x + labelW, y + labelH);
            canvas.drawRoundRect(labelRect, dp(10), dp(10), labelBackground);
            canvas.drawText(label, x + padH, y + padV - labelPaint.ascent(), labelPaint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start = left;
                    downX = event.getX();
                    downY = event.getY();
                    pinching = false;
                    grab = SideViewFrames.hit(left, boundsWidth(), downX, downY, dp(REACH_DP));
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        beginPinch(event);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (pinching && event.getPointerCount() >= 2) {
                        updatePinch(event);
                    } else if (!pinching && grab.any()) {
                        left = SideViewFrames.drag(start, grab, event.getX() - downX, event.getY() - downY,
                                boundsWidth(), boundsHeight());
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    // 双指松开一根，这一下就到此为止：剩下那根不接着拖
                    if (pinching) {
                        pinching = false;
                        grab = new SideViewFrames.Grab(SideViewFrames.NO_FRAME, RearViewFrameModel.NONE);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pinching = false;
                    grab = new SideViewFrames.Grab(SideViewFrames.NO_FRAME, RearViewFrameModel.NONE);
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        /** 捏的是哪个框：两指中点落在哪个框上；都不在就按中点在屏幕哪一半。 */
        private void beginPinch(MotionEvent event) {
            pinching = true;
            start = left;
            pinchStartSpan = span(event);
            float focusX = (event.getX(0) + event.getX(1)) / 2f;
            float focusY = (event.getY(0) + event.getY(1)) / 2f;
            int frame = SideViewFrames.hit(left, boundsWidth(), focusX, focusY, 0).frame;
            pinchMirrored = frame == SideViewFrames.RIGHT_FRAME
                    || (frame == SideViewFrames.NO_FRAME && focusX > boundsWidth() / 2f);
            float x = pinchMirrored ? boundsWidth() - focusX : focusX;
            pinchAnchorX = clamp01((x - start.left) / Math.max(1, start.width));
            pinchAnchorY = clamp01((focusY - start.top) / Math.max(1, start.height));
        }

        private void updatePinch(MotionEvent event) {
            float span = span(event);
            if (pinchStartSpan <= 0f || span <= 0f) {
                return;
            }
            float focusX = (event.getX(0) + event.getX(1)) / 2f;
            float focusY = (event.getY(0) + event.getY(1)) / 2f;
            float x = pinchMirrored ? boundsWidth() - focusX : focusX;
            left = SideViewFrames.pinch(start, pinchAnchorX, pinchAnchorY, x, focusY, span / pinchStartSpan,
                    boundsWidth(), boundsHeight());
            invalidate();
        }

        private float span(MotionEvent event) {
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
