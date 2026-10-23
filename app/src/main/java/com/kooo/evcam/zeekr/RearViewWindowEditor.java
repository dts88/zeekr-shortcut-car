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
import android.os.Build;
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
 * 设置 → 超级镜子 → 窗口：在整个屏幕上调窗口宽高的编辑框（项目所有者 2026-10-23）。
 *
 * <p>全屏一层悬浮窗（和超级镜子同一类；后加的在上面，所以盖得住它），框外压暗。框一开始是窗口现在的位置和大小
 * （窗口贴边藏着一半时整个收进屏幕）。拖边、拖角改宽或高（{@link RearViewFrameModel}），双指缩放
 * （{@link RearViewTouchModel#pinch}，和窗口本身一个手感），拖框内挪位置 —— 挪只是为了好调，<b>不保存</b>。
 * 「保存」只存宽和高（{@link AppConfig#setRearViewSize}），窗口还在原来的位置（放不下时照窗口自己的规矩收回屏幕）；
 * 「取消」、返回键不存。</p>
 *
 * <p>同一时刻只有一个；设置页退到后台时由它关掉（{@link #cancel}），不留一层全屏的东西压在别的应用上面。</p>
 */
public final class RearViewWindowEditor {

    private static final String TAG = "RearViewWindowEditor";

    /** 框外压暗：不跟日夜变，和画面上的压暗（bg_status_scrim）一个道理 —— 下面是什么都看得出这一层在编辑。 */
    private static final int DIM = 0x99000000;
    private static final int LABEL_BACKGROUND = 0xB3000000;
    private static final float STROKE_DP = 3f;
    private static final float HANDLE_RADIUS_DP = 11f;
    /** 离边、角多近算抓住（手指比画出来的圆点大得多）。 */
    private static final float REACH_DP = 40f;
    private static final float MARGIN_DP = 32f;

    /** 存了之后叫它（设置页更新那一行的值）。 */
    public interface Listener {
        void onSaved(int width, int height);
    }

    private static RearViewWindowEditor current;

    private final Context context;
    private final AppConfig appConfig;
    private final WindowManager windowManager;
    private final Listener listener;
    private final View root;
    private final FrameView frameView;

    /** 打开编辑框。已经开着就不再开第二个。 */
    public static void show(Context context, AppConfig appConfig, Listener listener) {
        if (current != null) {
            return;
        }
        RearViewWindowEditor editor = new RearViewWindowEditor(context, appConfig, listener);
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

    private RearViewWindowEditor(Context context, AppConfig appConfig, Listener listener) {
        this.context = new ContextThemeWrapper(context, R.style.Theme_Cam);
        this.appConfig = appConfig;
        this.listener = listener;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.frameView = new FrameView(this.context);
        this.root = buildRoot();
    }

    // ------------------------------------------------------------------ 窗口

    private boolean attach() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(root, params);
            root.requestFocus();
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "窗口编辑框添加失败", e);
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
            AppLog.w(TAG, "窗口编辑框移除失败: " + e);
        }
    }

    private void save() {
        DisplayMetrics metrics = displayMetrics();
        RearViewFrameModel.Frame frame = frameView.frame;
        if (frame != null) {
            appConfig.setRearViewSize(frame.width, frame.height, metrics.widthPixels, metrics.heightPixels);
        }
        int width = appConfig.getRearViewWidth(metrics.widthPixels);
        int height = appConfig.getRearViewHeight(metrics.heightPixels);
        AppLog.i(TAG, "窗口编辑框：保存宽高 " + width + "×" + height);
        RearViewMirrorService.applySize(context);
        dismiss();
        if (listener != null) {
            listener.onSaved(width, height);
        }
    }

    /** 窗口能有多大照这一份算（和超级镜子窗口、{@code AppConfig.clampRearViewSize} 用的是同一份屏幕尺寸）。 */
    private DisplayMetrics displayMetrics() {
        return context.getApplicationContext().getResources().getDisplayMetrics();
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 布局：框、上面一句提示、下面取消 / 保存

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

        // 提示和按钮都在下面正中：窗口默认在右上角，放上面容易压着框
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
        hint.setText(R.string.rearview_editor_hint);
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

    // ------------------------------------------------------------------ 框

    /** 画框、接手势的那一层。 */
    @SuppressLint("ViewConstructor")
    private final class FrameView extends View {

        RearViewFrameModel.Frame frame;
        private RearViewFrameModel.Frame start;
        private int grab = RearViewFrameModel.NONE;
        private boolean pinching;
        private float downX;
        private float downY;
        private float pinchStartSpan;
        private float pinchAnchorX;
        private float pinchAnchorY;

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

        /** 第一次排好版时把框放到窗口现在的位置（换成这一层的坐标）。 */
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (frame != null || w <= 0 || h <= 0) {
                frame = frame != null ? RearViewFrameModel.keepInside(frame.x, frame.y,
                        frame.width, frame.height, w, h) : null;
                return;
            }
            int[] origin = new int[2];
            getLocationOnScreen(origin);
            Rect window = RearViewMirrorService.windowOnScreen();
            if (window != null) {
                frame = RearViewFrameModel.keepInside(window.left - origin[0], window.top - origin[1],
                        window.width(), window.height(), w, h);
                return;
            }
            DisplayMetrics metrics = displayMetrics();
            int width = appConfig.getRearViewWidth(metrics.widthPixels);
            int height = appConfig.getRearViewHeight(metrics.heightPixels);
            int x = appConfig.getRearViewX();
            int y = appConfig.getRearViewY();
            if (x < 0 || y < 0) {
                // 没存过位置：窗口默认落在右上角（RearViewMirrorView#show）
                x = metrics.widthPixels - width - 40;
                y = 40;
            }
            frame = RearViewFrameModel.keepInside(x - origin[0], y - origin[1], width, height, w, h);
        }

        private int maxWidth() {
            return Math.min(getWidth(), displayMetrics().widthPixels);
        }

        private int maxHeight() {
            return Math.min(getHeight(), displayMetrics().heightPixels);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (frame == null) {
                canvas.drawColor(DIM);
                return;
            }
            int w = getWidth();
            int h = getHeight();
            // 框外压暗、框内透亮：看得见框底下是什么（一开始就是窗口本身）
            canvas.drawRect(0, 0, w, frame.y, dimPaint);
            canvas.drawRect(0, frame.bottom(), w, h, dimPaint);
            canvas.drawRect(0, frame.y, frame.x, frame.bottom(), dimPaint);
            canvas.drawRect(frame.right(), frame.y, w, frame.bottom(), dimPaint);
            canvas.drawRect(frame.x, frame.y, frame.right(), frame.bottom(), strokePaint);

            float cx = frame.x + frame.width / 2f;
            float cy = frame.y + frame.height / 2f;
            float r = dp(HANDLE_RADIUS_DP);
            float[] xs = {frame.x, cx, frame.right()};
            float[] ys = {frame.y, cy, frame.bottom()};
            for (float hx : xs) {
                for (float hy : ys) {
                    if (hx == cx && hy == cy) {
                        continue;
                    }
                    canvas.drawCircle(hx, hy, r, handleFill);
                    canvas.drawCircle(hx, hy, r, handleStroke);
                }
            }

            String label = context.getString(R.string.set_rearview_window_value,
                    frame.width, frame.height);
            labelPaint.getTextBounds(label, 0, label.length(), textBounds);
            float padH = dp(14);
            float padV = dp(8);
            float labelW = textBounds.width() + 2 * padH;
            float labelH = -labelPaint.ascent() + labelPaint.descent() + 2 * padV;
            // 框里放得下就写在正中，放不下写在框下面（贴着屏幕底就写在上面）
            float top;
            if (labelW + 2 * r <= frame.width && labelH + 2 * r <= frame.height) {
                top = cy - labelH / 2f;
            } else if (frame.bottom() + r + labelH <= h) {
                top = frame.bottom() + r;
            } else {
                top = Math.max(0, frame.y - r - labelH);
            }
            float left = Math.max(0, Math.min(w - labelW, cx - labelW / 2f));
            labelRect.set(left, top, left + labelW, top + labelH);
            canvas.drawRoundRect(labelRect, dp(10), dp(10), labelBackground);
            canvas.drawText(label, left + padH, top + padV - labelPaint.ascent(), labelPaint);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (frame == null) {
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start = frame;
                    downX = event.getX();
                    downY = event.getY();
                    pinching = false;
                    grab = RearViewFrameModel.hit(frame, downX, downY, dp(REACH_DP));
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        beginPinch(event);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (pinching && event.getPointerCount() >= 2) {
                        updatePinch(event);
                    } else if (!pinching && grab != RearViewFrameModel.NONE) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        frame = grab == RearViewFrameModel.MOVE
                                ? RearViewFrameModel.move(start, dx, dy, getWidth(), getHeight())
                                : RearViewFrameModel.resize(start, grab, dx, dy, AppConfig.REARVIEW_MIN_SIZE,
                                        maxWidth(), maxHeight(), getWidth(), getHeight());
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    // 双指松开一根，这一下就到此为止：剩下那根不接着拖（不然框会跳到它底下）
                    if (pinching) {
                        pinching = false;
                        grab = RearViewFrameModel.NONE;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pinching = false;
                    grab = RearViewFrameModel.NONE;
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        private void beginPinch(MotionEvent event) {
            pinching = true;
            start = frame;
            pinchStartSpan = span(event);
            float focusX = (event.getX(0) + event.getX(1)) / 2f;
            float focusY = (event.getY(0) + event.getY(1)) / 2f;
            pinchAnchorX = clamp01((focusX - frame.x) / Math.max(1, frame.width));
            pinchAnchorY = clamp01((focusY - frame.y) / Math.max(1, frame.height));
        }

        /** 以两指中点为锚缩放、跟着中点走（{@link RearViewTouchModel#pinch}），再收回这一层里。 */
        private void updatePinch(MotionEvent event) {
            float span = span(event);
            if (pinchStartSpan <= 0f || span <= 0f) {
                return;
            }
            float focusX = (event.getX(0) + event.getX(1)) / 2f;
            float focusY = (event.getY(0) + event.getY(1)) / 2f;
            RearViewTouchModel.PinchResult result = RearViewTouchModel.pinch(
                    start.x, start.y, start.width, start.height, pinchAnchorX, pinchAnchorY,
                    focusX, focusY, span / pinchStartSpan,
                    AppConfig.REARVIEW_MIN_SIZE, maxWidth(), maxHeight());
            frame = RearViewFrameModel.keepInside(result.x, result.y, result.width, result.height,
                    getWidth(), getHeight());
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
