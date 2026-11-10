/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 FerixzTanz - https://github.com/FerixzTanz
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

/**
 * 盲区画面在 HUD 上的窗口（2026-10-21）：和中控屏那个（{@link SideViewWindow}）同一个画面（{@link SideViewPicture}），只换窗口。
 *
 * <h3>窗口</h3>
 *
 * <p>在 HUD 那块屏（{@link HudScreen}）上开一个透明的 {@link Presentation}，画面按设置放在里面（{@link HudPicture#place}：
 * 左灯靠左、右灯靠右）。不接点击、没有关闭按钮：HUD 不是触摸屏。系统在那块屏没了时会自己收掉 Presentation，
 * 所以 {@link #isAttached()} 每次都问它一句，服务发现不挂着了就当这一段 HUD 用不了。</p>
 *
 * <h3>左右两个画面（项目所有者 2026-11-04）</h3>
 *
 * <p>后视镜上的灯左右可能同时亮：这个窗口铺满那块屏、本身透明，左右两个画面各画在自己的位置上，一块画布同时画两个
 * （不多占一块环视的输出）。两个画面各管各的：显示、收起、淡出（{@link SideViewFade}，画的时候乘上去）、
 * 「先盖黑、等新帧」（{@link SideViewPicture} 一侧一份）。画面外面不画东西，原厂在 HUD 上显示的照样看得见。</p>
 *
 * <h3>HUD 是投影</h3>
 *
 * <p>黑色是不亮：黑底、盖黑在 HUD 上都是「什么也没有」。画面过一条对比度曲线（白天、夜间各一档），四边渐隐到黑，
 * 可以左右翻转（像车门上的后视镜）；窗口按设置的透明度显示，夜间模式时再乘夜间亮度（{@link HudPicture}）。
 * 这一侧后视镜上的灯亮着时，画面四周描一圈边（{@link SideViewAlert}），画在对比度曲线和四边渐隐之外，粗细跟着画面大小。</p>
 *
 * <p>备着、显示和中控屏一样：备着时窗口 alpha 0、画布建好后不可见、画面留在环视的会话里。</p>
 *
 * <p>来源：FerixzTanz 的 CAMCORDER（SideViewHudPanel，GPL-3.0）—— 用 Presentation 投 HUD、透明窗口、对比度和渐隐的画法；
 * 他在自己的 7X 上实车用着。代码照我们的窗口和画面结构自己写。</p>
 */
public class SideViewHudWindow extends ViewGroup implements SideViewOutputWindow {

    private static final String TAG = "SideViewHud";

    private static final long TICK_MS = 250L;

    /** 一侧的画面：显示着没有、画面本身（盖黑、等新帧）、淡到哪了。 */
    private final class Picture {
        final int lane;
        final SideViewPicture picture;
        final SideViewFade fade;
        boolean shown;
        /** 收起前的淡出淡到哪了：1 正常，0 看不见。 */
        float alpha = 1f;

        Picture(int lane) {
            this.lane = lane;
            this.picture = new SideViewPicture(appConfig);
            this.fade = new SideViewFade(a -> {
                alpha = a;
                invalidate();
            });
        }
    }

    private final AppConfig appConfig;
    private final Display display;
    private final AutoFitTextureView textureView;
    private final Picture left;
    private final Picture right;
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 四边渐隐用的（和收起前的淡出不是一回事）。 */
    private final Paint fadePaint = new Paint();
    /** 后视镜上的灯亮着时的边框（{@link SideViewAlert}），两侧各一级。 */
    private final SideViewEdge edge = new SideViewEdge();

    private Presentation presentation;
    private FrameLayout root;
    private boolean attached;
    private boolean night;
    /** 画面过的对比度曲线；对比度 100 时是 null（原样）。 */
    private Paint tonePaint;
    private boolean mirrored;
    private int fadePercent;
    /** 渐隐的四条带按这个尺寸建的；尺寸、渐隐变了才重建。 */
    private int fadeWidth;
    private int fadeHeight;
    private float fadePx;
    private Shader[] fadeShaders;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isShowing()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            for (Picture p : new Picture[]{left, right}) {
                if (p.shown && p.picture.checkStale(now)) {
                    if (p.picture.isStale()) {
                        AppLog.i(TAG, "HUD 盲区画面「" + LaneCycle.labelOf(p.lane) + "」" + SideViewPicture.STALE_MS
                                + " ms 没有新帧：盖上");
                    }
                    invalidate();
                }
            }
            // 边框在闪就换亮暗（闪跟着这一下走，见 SideViewEdge）
            if (edge.tick(left.shown, right.shown)) {
                invalidate();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public SideViewHudWindow(Context context, AppConfig appConfig, Display display) {
        super(context);
        this.appConfig = appConfig;
        this.display = display;
        this.left = new Picture(LaneCycle.LEFT);
        this.right = new Picture(LaneCycle.RIGHT);
        // 铺满那块屏、本身透明：只在两个画面的位置上画（原厂在 HUD 上显示的不挡）
        setBackgroundColor(0);
        setWillNotDraw(false);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        readLook();
    }

    /** 这块窗口在哪块屏上（黑匣子用）。 */
    public int displayId() {
        return display.getDisplayId();
    }

    @Override
    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    @Override
    public void setSourceSize(Size size) {
        boolean changed = left.picture.setSourceSize(size);
        changed |= right.picture.setSourceSize(size);
        if (changed) {
            invalidate();
        }
    }

    @Override
    public boolean hasGeometry() {
        return left.picture.hasGeometry();
    }

    /** 挂着：系统在那块屏没了时会自己收掉 Presentation，所以每次问它一句。 */
    @Override
    public boolean isAttached() {
        return attached && presentation != null && presentation.isShowing();
    }

    @Override
    public boolean isShowing() {
        return isAttached() && (left.shown || right.shown);
    }

    @Override
    public boolean isShowing(int side) {
        return isAttached() && picture(side).shown;
    }

    @Override
    public void applyAim() {
        invalidate();
    }

    @Override
    public void setAlert(int side, int level) {
        if (edge.set(side, level) && isShowing(side)) {
            invalidate();
        }
    }

    /** 车辆是不是夜间模式：夜间换夜间对比度，透明度再乘夜间亮度。服务每次判定时给。 */
    public void setNight(boolean night) {
        if (this.night != night) {
            this.night = night;
            readLook();
            applyWindowAlpha();
            invalidate();
        }
    }

    /** 设置页改了 HUD 的设置：照新的大小位置、对比度、透明度、翻转、渐隐重画。 */
    public void applyConfig() {
        readLook();
        if (isAttached()) {
            requestLayout();
            applyWindowAlpha();
        }
        invalidate();
    }

    private void readLook() {
        int contrast = appConfig.getHudContrast(night);
        if (HudPicture.toneIsIdentity(contrast)) {
            tonePaint = null;
        } else {
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(HudPicture.toneMatrix(contrast))));
            tonePaint = paint;
        }
        mirrored = appConfig.isHudMirrored();
        fadePercent = appConfig.getHudFade();
        fadeShaders = null;
    }

    private Picture picture(int side) {
        return side == LaneCycle.RIGHT ? right : left;
    }

    // ------------------------------------------------------------------ 窗口

    /** 挂上，两侧都不显示（备着）；已经挂着就只是把两侧都收起。 */
    @Override
    public void attachHidden(int side) {
        for (Picture p : new Picture[]{left, right}) {
            p.fade.stop();
            p.shown = false;
        }
        attach();
    }

    /** 显示这一侧，另一侧照旧（两侧可以同时显示）。 */
    @Override
    public void show(int side) {
        Picture p = picture(side);
        // 淡出中又显示，或者上一次淡完收起的：回到正常
        p.fade.restore();
        if (!(isAttached() && p.shown)) {
            p.picture.beginShowing(SystemClock.uptimeMillis());
        }
        p.shown = true;
        attach();
    }

    @Override
    public void conceal(int side) {
        Picture p = picture(side);
        p.fade.stop();
        if (!isAttached() || !p.shown) {
            return;
        }
        p.shown = false;
        if (!isShowing()) {
            handler.removeCallbacks(tick);
        }
        applyWindowAlpha();
        syncCanvas();
        invalidate();
    }

    @Override
    public void fadeOut(int side, long durationMs) {
        if (isShowing(side)) {
            picture(side).fade.start(durationMs);
        }
    }

    @Override
    public boolean isFading(int side) {
        return picture(side).fade.isFading();
    }

    /** 当场移走：Presentation 收掉，画布在这一下被收走，服务把它交给管线留着（{@link com.kooo.evcam.camera.CanvasSlot#keep}）。 */
    @Override
    public void detach() {
        handler.removeCallbacks(tick);
        for (Picture p : new Picture[]{left, right}) {
            p.fade.stop();
            p.shown = false;
        }
        Presentation p = presentation;
        FrameLayout frame = root;
        presentation = null;
        root = null;
        attached = false;
        if (frame != null) {
            try {
                frame.removeView(this);
            } catch (Exception ignored) {
                // 已经不在里面了
            }
        }
        if (p != null) {
            try {
                p.dismiss();
            } catch (Exception e) {
                AppLog.w(TAG, "HUD 窗口收起失败: " + e);
            }
        }
    }

    /** 没挂就挂上（铺满那块屏的透明 Presentation），挂着就照两侧此刻显示没有换窗口的透明度、画布可见不可见。 */
    private void attach() {
        if (!isAttached()) {
            if (attached) {
                // 系统收掉了（那块屏没了）：先收干净，服务据此当这一段 HUD 用不了
                detach();
                return;
            }
            try {
                Presentation p = new Presentation(getContext(), display);
                FrameLayout frame = new FrameLayout(p.getContext());
                frame.setBackgroundColor(0);
                frame.addView(this, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                p.setContentView(frame);
                Window window = p.getWindow();
                if (window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    window.setBackgroundDrawable(new ColorDrawable(0));
                }
                presentation = p;
                root = frame;
                attached = true;
                applyWindowAlpha();
                syncCanvas();
                p.show();
            } catch (Throwable t) {
                AppLog.e(TAG, "HUD 窗口添加失败（屏 " + display.getDisplayId() + "）", t);
                detach();
                return;
            }
        } else {
            applyWindowAlpha();
            syncCanvas();
        }
        if (left.shown || right.shown) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, TICK_MS);
        }
        invalidate();
    }

    /** 画面在 HUD 里的位置和大小，照这块窗口（铺满那块屏）的尺寸和设置算（{@link HudPicture#place}）。 */
    private HudPicture.Rect placement(int side) {
        return HudPicture.place(getWidth(), getHeight(),
                appConfig.getHudHeight(), appConfig.getHudShape(), appConfig.getHudEdge(), appConfig.getHudVertical(),
                side == LaneCycle.RIGHT);
    }

    /** 有一侧显示着时按设置的透明度（夜间再乘夜间亮度），两侧都不显示（备着）时全透明。 */
    private void applyWindowAlpha() {
        Presentation p = presentation;
        Window window = p != null ? p.getWindow() : null;
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = left.shown || right.shown
                ? HudPicture.opacity(appConfig.getHudOpacity(), appConfig.getHudNight(), night) : 0f;
        window.setAttributes(attrs);
    }

    @Override
    public void syncCanvas() {
        int visibility = left.shown || right.shown || !textureView.isAvailable() ? View.VISIBLE : View.INVISIBLE;
        if (textureView.getVisibility() != visibility) {
            textureView.setVisibility(visibility);
        }
    }

    @Override
    public void noteFrame() {
        long now = SystemClock.uptimeMillis();
        boolean redraw = left.picture.noteFrame(now, left.shown);
        redraw |= right.picture.noteFrame(now, right.shown);
        if (redraw) {
            invalidate();
        }
    }

    @Override
    public String describe() {
        return "display=" + display.getDisplayId() + " attached=" + attached
                + " presentation=" + (presentation == null ? "none" : presentation.isShowing() ? "showing" : "gone")
                + " left=" + describe(left) + " right=" + describe(right)
                + " canvas=" + (textureView.isAvailable() ? "ready" : "none")
                + " geometry=" + (hasGeometry() ? "known" : "unknown") + " night=" + night;
    }

    private String describe(Picture p) {
        return !p.shown ? "hidden" : (p.picture.covered() ? "covered" : "live") + (p.fade.isFading() ? "+fading" : "")
                + "+edge:" + edge.describe(p.lane);
    }

    // ------------------------------------------------------------------ 布局与绘制

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        // 画布照一个画面的大小（左右两个一样大，互为镜像），实际取景由 dispatchDraw 的网格决定
        HudPicture.Rect rect = HudPicture.place(getMeasuredWidth(), getMeasuredHeight(),
                appConfig.getHudHeight(), appConfig.getHudShape(), appConfig.getHudEdge(), appConfig.getHudVertical(),
                false);
        textureView.measure(MeasureSpec.makeMeasureSpec(Math.max(1, rect.width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, rect.height), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        textureView.layout(0, 0, textureView.getMeasuredWidth(), textureView.getMeasuredHeight());
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (!left.shown && !right.shown) {
            // 备着：窗口是透明的。画布还没建好时照画一次，它才建得出来
            if (!textureView.isAvailable()) {
                drawTextureOnce(canvas);
            }
            return;
        }
        for (Picture p : new Picture[]{left, right}) {
            if (p.shown) {
                drawPicture(canvas, p);
            }
        }
    }

    /** 一侧的画面画在它自己的位置上：底下铺黑（HUD 上不亮），淡出时整个乘上淡到哪了。 */
    private void drawPicture(Canvas canvas, Picture p) {
        HudPicture.Rect rect = placement(p.lane);
        int width = rect.width;
        int height = rect.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        int outer = canvas.save();
        canvas.translate(rect.left, rect.top);
        canvas.clipRect(0, 0, width, height);
        if (p.alpha < 1f) {
            canvas.saveLayerAlpha(0f, 0f, width, height, Math.round(255 * Math.max(0f, p.alpha)));
        }
        canvas.drawColor(0xFF000000);
        // 边框（SideViewAlert）：画在画面之后、对比度曲线和四边渐隐之外，不被它们改色、擦掉；收起前的淡出照乘
        float edgePx = SideViewAlert.hudStrokePx(width, height);
        if (!p.picture.live()) {
            // 盖着：画一次子视图（不画就不取帧），再盖黑 —— HUD 上什么也没有（灯亮着照样描边：那是车给的，不靠画面）
            p.picture.draw(canvas, width, height, p.lane, paintTexture);
            edge.draw(canvas, p.lane, width, height, edgePx);
            canvas.restoreToCount(outer);
            return;
        }
        float fade = HudPicture.fadePx(fadePercent, width, height);
        boolean layer = tonePaint != null || fade > 0f;
        int save = layer ? canvas.saveLayer(0f, 0f, width, height, tonePaint) : canvas.save();
        if (mirrored) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        p.picture.draw(canvas, width, height, p.lane, paintTexture);
        if (fade > 0f) {
            if (mirrored) {
                canvas.scale(-1f, 1f, width / 2f, height / 2f);
            }
            eraseEdges(canvas, width, height, fade);
        }
        canvas.restoreToCount(save);
        edge.draw(canvas, p.lane, width, height, edgePx);
        canvas.restoreToCount(outer);
    }

    /** 四条渐变带，把画面从边上往里擦成透明（底下是黑的：HUD 上不亮）：最外沿擦净，到带的内沿不擦。 */
    private void eraseEdges(Canvas canvas, int width, int height, float fade) {
        if (fadeShaders == null || fadeWidth != width || fadeHeight != height || fadePx != fade) {
            int opaque = 0xFF000000;
            int clear = 0x00000000;
            fadeShaders = new Shader[]{
                    new LinearGradient(0f, 0f, fade, 0f, opaque, clear, Shader.TileMode.CLAMP),
                    new LinearGradient(width, 0f, width - fade, 0f, opaque, clear, Shader.TileMode.CLAMP),
                    new LinearGradient(0f, 0f, 0f, fade, opaque, clear, Shader.TileMode.CLAMP),
                    new LinearGradient(0f, height, 0f, height - fade, opaque, clear, Shader.TileMode.CLAMP)};
            fadeWidth = width;
            fadeHeight = height;
            fadePx = fade;
        }
        fadePaint.setShader(fadeShaders[0]);
        canvas.drawRect(0f, 0f, fade, height, fadePaint);
        fadePaint.setShader(fadeShaders[1]);
        canvas.drawRect(width - fade, 0f, width, height, fadePaint);
        fadePaint.setShader(fadeShaders[2]);
        canvas.drawRect(0f, 0f, width, fade, fadePaint);
        fadePaint.setShader(fadeShaders[3]);
        canvas.drawRect(0f, height - fade, width, height, fadePaint);
        fadePaint.setShader(null);
    }

    private void drawTextureOnce(Canvas canvas) {
        drawChild(canvas, textureView, getDrawingTime());
    }
}
