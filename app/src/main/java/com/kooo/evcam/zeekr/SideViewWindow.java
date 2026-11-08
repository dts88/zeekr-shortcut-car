/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 wysie - https://github.com/wysie
 * Portions Copyright (C) 2026 FerixzTanz - https://github.com/FerixzTanz
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;
import com.kooo.evcam.R;

/**
 * 盲区画面在中控屏上的窗口：打转向灯时在屏幕那一侧显示该侧摄像头的画面（拉直、往后看）。
 *
 * <h3>只看，不操作（项目所有者 2026-10-16）</h3>
 *
 * <p>窗口本身不能拖、不能缩放；只有一个关闭按钮，点了关掉这一次（{@link SideViewDismissal}）。不镜像。
 * 位置和大小左右各一个、互为镜像（{@link SideViewPlacement}），只能在设置里调（{@link SideViewPlacementEditor}，
 * 项目所有者 2026-11-03），开车时动不了它。</p>
 *
 * <h3>画法和超级镜子一样</h3>
 *
 * <p>子视图是普通 TextureView，本容器在 {@code dispatchDraw} 里分格反投影，画面本身和「不显示过时的画面」那条规矩在
 * {@link SideViewPicture}（HUD 的窗口共用）。不新建 GL 管线，不动相机会话（为什么见 {@link RearViewMirrorView}）。
 * 这一侧后视镜上的灯亮着时，画面四周描一圈边（{@link SideViewAlert}，画在 {@link SideViewEdge}）。</p>
 *
 * <p>一个窗口一个画面。中控屏上有两块画布时左右各一个窗口（项目所有者 2026-11-04：后视镜上的灯左右可能同时亮），
 * 只有一块时这一个窗口显示优先的那一侧、换侧就挪过去（{@link SideViewTrigger.Result#lane}）。</p>
 *
 * <h3>三种状态</h3>
 *
 * <ul>
 *   <li>没挂：没有窗口。</li>
 *   <li><b>备着</b>：窗口挂着、完全透明（窗口 alpha 0）、点击穿过去；画布建好之后设为不可见，不逐帧画 ——
 *       画面照样留在环视的会话里：相机往不画的画布送帧是异步的，没人取就丢掉旧的一帧，不会卡住相机
 *       （同超级镜子贴边，大纲 §6 风险 3）。画布要先画一次才建得出来，所以建好之前照常画（窗口是透明的）。</li>
 *   <li>显示：alpha 1、接点击。显示时不能带 FLAG_NOT_TOUCHABLE：安卓 12 起带它的悬浮窗不透明度会被压到 0.8
 *       （PR #19 实车看到「有点透明」）。</li>
 * </ul>
 *
 * <p>来源：备着 / 显示两种窗口状态（备着时窗口透明、不接点击，显示时不能带 FLAG_NOT_TOUCHABLE）和分格拉直的画法，
 * 照 FerixzTanz 的 PR #4、wysie 的 PR #19 里的侧视弹窗写；GPL-3.0。</p>
 */
public class SideViewWindow extends ViewGroup implements SideViewOutputWindow {

    private static final String TAG = "SideViewWindow";

    private static final long TICK_MS = 250L;
    /** 关闭按钮的直径、离窗口边多远（dp）。 */
    private static final float CLOSE_SIZE_DP = 48f;
    private static final float CLOSE_MARGIN_DP = 10f;
    /** 边框多粗（dp，{@link SideViewAlert}）。 */
    private static final float EDGE_DP = 6f;

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;
    private final CloseButton closeButton;
    private final SideViewPicture picture;
    /** 后视镜上的灯亮着时的边框（{@link SideViewAlert}）。 */
    private final SideViewEdge edge = new SideViewEdge();
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 收起前的淡出（{@link #fadeOut}）。 */
    private final SideViewFade fadeAway = new SideViewFade(this::setAlpha);

    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean shown;
    private int lane = LaneCycle.LEFT;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!attached || !shown) {
                return;
            }
            boolean redraw = false;
            if (picture.checkStale(SystemClock.uptimeMillis())) {
                if (picture.isStale()) {
                    AppLog.i(TAG, "盲区画面 " + SideViewPicture.STALE_MS + " ms 没有新帧：盖上");
                }
                redraw = true;
            }
            // 边框在闪就换亮暗（闪跟着这一下走，见 SideViewEdge）
            redraw |= edge.tick(lane == LaneCycle.LEFT, lane == LaneCycle.RIGHT);
            if (redraw) {
                invalidate();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** 点了关闭按钮：关掉的是这个窗口此刻显示的那一侧。 */
    interface CloseListener {
        void onClose(int lane);
    }

    public SideViewWindow(Context context, AppConfig appConfig, CloseListener onClose) {
        super(context);
        this.picture = new SideViewPicture(appConfig);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        closeButton = new CloseButton(context);
        closeButton.setOnClickListener(v -> {
            if (shown && onClose != null) {
                onClose.onClose(lane);
            }
        });
        addView(closeButton);
    }

    @Override
    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    @Override
    public void setSourceSize(Size size) {
        if (picture.setSourceSize(size)) {
            invalidate();
        }
    }

    @Override
    public boolean hasGeometry() {
        return picture.hasGeometry();
    }

    @Override
    public boolean isAttached() {
        return attached;
    }

    @Override
    public boolean isShowing() {
        return attached && shown;
    }

    @Override
    public boolean isShowing(int side) {
        return attached && shown && lane == side;
    }

    /**
     * 设置页改了标定角度（开发者选项）：马上重画一次。角度在本容器重画时照 AppConfig 读（{@link SideViewPicture#draw}）——
     * 显示出来、换一侧、盖上 / 露出、这里叫重画时；新的一帧只重画画布，不重画容器。所以开发者选项锁上、打开
     * （归它管的设置按没存过算）不当场跟：等本容器下一次重画 —— 显示着时是换一侧、盖上 / 露出，最晚下一次显示
     * （AppConfig.DEVELOPER_KEYS）。
     */
    @Override
    public void applyAim() {
        invalidate();
    }

    /** 两侧各记一级（这个窗口换侧时跟着换），画的是此刻显示的那一侧的。 */
    @Override
    public void setAlert(int side, int level, boolean motion) {
        if (edge.set(side, level, motion) && attached && shown && lane == side) {
            invalidate();
        }
    }

    // ------------------------------------------------------------------ 窗口

    @Override
    public void attachHidden(int side) {
        attachAt(side, false);
    }

    @Override
    public void show(int side) {
        attachAt(side, true);
    }

    /** 一个窗口一个画面：显示着 {@code side} 才收。 */
    @Override
    public void conceal(int side) {
        if (lane != side) {
            return;
        }
        fadeAway.stop();
        if (!attached || !shown) {
            return;
        }
        shown = false;
        handler.removeCallbacks(tick);
        applyWindowState();
        update();
    }

    @Override
    public void fadeOut(int side, long durationMs) {
        if (isShowing(side)) {
            fadeAway.start(durationMs);
        }
    }

    @Override
    public boolean isFading(int side) {
        return lane == side && fadeAway.isFading();
    }

    /**
     * 把窗口当场移走（服务退出、离开 D 挡 30 秒、熄屏、原厂画面出现时）：画布在这一下被收走，
     * 服务在 onSurfaceTextureDestroyed 里把它交给管线留着（同超级镜子，{@link MultiCameraManager#keepSideCanvas}）。
     * 只从服务调，不在本窗口的绘制、触摸分发途中，当场摘是安全的（理由同 {@link RearViewMirrorView#hide}）。
     */
    @Override
    public void detach() {
        handler.removeCallbacks(tick);
        fadeAway.stop();
        if (!attached) {
            return;
        }
        attached = false;
        shown = false;
        try {
            windowManager.removeViewImmediate(this);
        } catch (Exception e) {
            AppLog.w(TAG, "盲区画面窗口移除失败: " + e);
        }
    }

    private void attachAt(int side, boolean show) {
        boolean wasShown = attached && shown;
        lane = side;
        if (show) {
            // 淡出中又显示、换一侧，或者上一次淡完收起的：回到正常
            fadeAway.restore();
        }
        if (show && !wasShown) {
            // 新的一次显示：先盖着，等真正的新帧（显示着换一侧不用：画布一直可见，帧一直是新的）
            picture.beginShowing(SystemClock.uptimeMillis());
            closeButton.refreshLabel();
        }
        shown = show;
        if (!attached) {
            params = new WindowManager.LayoutParams(
                    1, 1,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            place();
            applyWindowState();
            try {
                windowManager.addView(this, params);
                attached = true;
            } catch (Exception e) {
                AppLog.e(TAG, "盲区画面窗口添加失败", e);
                shown = false;
                return;
            }
        } else {
            place();
            applyWindowState();
            update();
        }
        if (shown) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, TICK_MS);
        }
        invalidate();
    }

    /**
     * 设置里改了位置和大小（{@link SideViewPlacementEditor} 保存、重置）：挂着的话当场挪过去。只从主线程叫。
     */
    public void applyPlacement() {
        if (!attached || params == null) {
            return;
        }
        place();
        update();
    }

    /** 位置和大小：左右各一个、互为镜像，照设置里存的（{@link SideViewPlacement}，{@link AppConfig#getSideViewPlacement}）。 */
    private void place() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        SideViewPlacement.Rect rect = SideViewPlacement.compute(w, h, lane, appConfig.getSideViewPlacement());
        params.x = rect.left;
        params.y = rect.top;
        params.width = rect.width;
        params.height = rect.height;
    }

    /** 显示时不透明、接点击、画布可见；备着时全透明、点击穿过去、画布建好后不可见。 */
    private void applyWindowState() {
        params.alpha = shown ? 1f : 0f;
        if (shown) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        syncCanvas();
    }

    /**
     * 画布可见不可见：显示着、或者还没建好（要先画一次才建得出来）时可见，备着时不可见、不逐帧画。
     * 服务在画布建好、接回留着的画布之后也叫一次。
     */
    @Override
    public void syncCanvas() {
        int visibility = shown || !textureView.isAvailable() ? View.VISIBLE : View.INVISIBLE;
        if (textureView.getVisibility() != visibility) {
            textureView.setVisibility(visibility);
        }
    }

    private void update() {
        if (!attached || params == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "盲区画面窗口更新失败: " + e);
        }
    }

    @Override
    public void noteFrame() {
        if (picture.noteFrame(SystemClock.uptimeMillis(), shown)) {
            invalidate();
        }
    }

    @Override
    public String describe() {
        return "attached=" + attached + " shown=" + shown + " lane=" + LaneCycle.labelOf(lane)
                + " canvas=" + (textureView.isAvailable() ? "ready" : "none")
                + " geometry=" + (hasGeometry() ? "known" : "unknown")
                + " covered=" + (shown && picture.covered())
                + " edge=" + edge.describe(lane)
                + " fov=" + Math.round(picture.effectiveFovDegrees()) + "°";
    }

    // ------------------------------------------------------------------ 布局与绘制

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        textureView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        int size = closeSize();
        closeButton.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = getWidth();
        // 子视图铺满，实际取景由 dispatchDraw 的网格决定；关闭按钮在右上角
        textureView.layout(0, 0, width, getHeight());
        int size = closeSize();
        int margin = Math.round(CLOSE_MARGIN_DP * getResources().getDisplayMetrics().density);
        closeButton.layout(width - margin - size, margin, width - margin, margin + size);
    }

    private int closeSize() {
        return Math.round(CLOSE_SIZE_DP * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!shown) {
            // 备着：窗口是透明的。画布还没建好时照画一次，它才建得出来
            if (!textureView.isAvailable()) {
                drawTextureOnce(canvas);
            }
            return;
        }
        picture.draw(canvas, width, height, lane, paintTexture);
        // 边框压在画面上（盖黑时也画：灯亮着是车给的，不靠画面），关闭按钮在最上面
        edge.draw(canvas, lane, width, height, EDGE_DP * getResources().getDisplayMetrics().density);
        drawChild(canvas, closeButton, getDrawingTime());
    }

    private void drawTextureOnce(Canvas canvas) {
        drawChild(canvas, textureView, getDrawingTime());
    }

    /**
     * 关闭按钮：压在画面上的深色圆、浅色叉。固定深色，不跟日夜模式走 —— 底下永远是视频（同超级镜子的提示层）。
     */
    private static final class CloseButton extends View {
        private static final int FILL = 0x8C000000;
        private static final int FILL_PRESSED = 0xCC000000;
        private static final int CROSS = 0xFFF2F2F3;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context context) {
            super(context);
            setClickable(true);
            refreshLabel();
            cross.setColor(CROSS);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeCap(Paint.Cap.ROUND);
            cross.setStrokeWidth(2.5f * context.getResources().getDisplayMetrics().density);
        }

        /**
         * 无障碍描述照应用语言取：服务的 context 在安卓 13 以下跟系统语言走（Languages.localized）。
         * 每次显示时再取一次 —— 窗口在 D 挡一直挂着，这期间改了应用语言也跟得上。
         */
        void refreshLabel() {
            setContentDescription(com.kooo.evcam.settings.Languages.localized(getContext())
                    .getString(R.string.side_view_close));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            fill.setColor(isPressed() ? FILL_PRESSED : FILL);
            canvas.drawCircle(cx, cy, radius, fill);
            float arm = radius * 0.36f;
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross);
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross);
        }
    }
}
