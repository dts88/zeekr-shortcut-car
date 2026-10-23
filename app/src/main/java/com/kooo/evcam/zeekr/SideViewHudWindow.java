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
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
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
 * <h3>HUD 是投影</h3>
 *
 * <p>黑色是不亮：黑底、盖黑在 HUD 上都是「什么也没有」。画面过一条对比度曲线（白天、夜间各一档），四边渐隐到黑，
 * 可以左右翻转（像车门上的后视镜）；窗口按设置的透明度显示，夜间模式时再乘夜间亮度（{@link HudPicture}）。</p>
 *
 * <p>备着、显示和中控屏一样：备着时窗口 alpha 0、画布建好后不可见、画面留在环视的会话里。</p>
 *
 * <p>来源：FerixzTanz 的 CAMCORDER（SideViewHudPanel，GPL-3.0）—— 用 Presentation 投 HUD、透明窗口、对比度和渐隐的画法；
 * 他在自己的 7X 上实车用着。代码照我们的窗口和画面结构自己写。</p>
 */
public class SideViewHudWindow extends ViewGroup implements SideViewOutputWindow {

    private static final String TAG = "SideViewHud";

    private static final long TICK_MS = 250L;

    private final AppConfig appConfig;
    private final Display display;
    private final AutoFitTextureView textureView;
    private final SideViewPicture picture;
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fadePaint = new Paint();

    private Presentation presentation;
    private FrameLayout root;
    private boolean attached;
    private boolean shown;
    private int lane = LaneCycle.LEFT;
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
            if (!isAttached() || !shown) {
                return;
            }
            if (picture.checkStale(SystemClock.uptimeMillis())) {
                if (picture.isStale()) {
                    AppLog.i(TAG, "HUD 盲区画面 " + SideViewPicture.STALE_MS + " ms 没有新帧：盖上");
                }
                invalidate();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public SideViewHudWindow(Context context, AppConfig appConfig, Display display) {
        super(context);
        this.appConfig = appConfig;
        this.display = display;
        this.picture = new SideViewPicture(appConfig);
        setBackgroundColor(0xFF000000);
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
        if (picture.setSourceSize(size)) {
            invalidate();
        }
    }

    @Override
    public boolean hasGeometry() {
        return picture.hasGeometry();
    }

    /** 挂着：系统在那块屏没了时会自己收掉 Presentation，所以每次问它一句。 */
    @Override
    public boolean isAttached() {
        return attached && presentation != null && presentation.isShowing();
    }

    @Override
    public boolean isShowing() {
        return isAttached() && shown;
    }

    @Override
    public int lane() {
        return lane;
    }

    @Override
    public void applyAim() {
        invalidate();
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
            place();
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

    // ------------------------------------------------------------------ 窗口

    @Override
    public void attachHidden(int side) {
        attachAt(side, false);
    }

    @Override
    public void show(int side) {
        attachAt(side, true);
    }

    @Override
    public void conceal() {
        if (!isAttached() || !shown) {
            return;
        }
        shown = false;
        handler.removeCallbacks(tick);
        applyWindowAlpha();
        syncCanvas();
    }

    /** 当场移走：Presentation 收掉，画布在这一下被收走，服务把它交给管线留着（{@link MultiCameraManager#keepHudCanvas}）。 */
    @Override
    public void detach() {
        handler.removeCallbacks(tick);
        Presentation p = presentation;
        FrameLayout frame = root;
        presentation = null;
        root = null;
        attached = false;
        shown = false;
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

    private void attachAt(int side, boolean show) {
        boolean wasShown = isAttached() && shown;
        boolean sideChanged = side != lane;
        lane = side;
        if (show && !wasShown) {
            picture.beginShowing(SystemClock.uptimeMillis());
        }
        shown = show;
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
                frame.addView(this, placement());
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
            if (sideChanged) {
                place();
            }
            applyWindowAlpha();
            syncCanvas();
        }
        if (shown) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, TICK_MS);
        }
        invalidate();
    }

    /** 画面在 HUD 里的位置和大小，照 HUD 的真实尺寸和设置算（{@link HudPicture#place}）。 */
    private FrameLayout.LayoutParams placement() {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        HudPicture.Rect rect = HudPicture.place(metrics.widthPixels, metrics.heightPixels,
                appConfig.getHudHeight(), appConfig.getHudShape(), appConfig.getHudEdge(), appConfig.getHudVertical(),
                lane == LaneCycle.RIGHT);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(rect.width, rect.height, Gravity.TOP | Gravity.START);
        lp.leftMargin = rect.left;
        lp.topMargin = rect.top;
        return lp;
    }

    private void place() {
        if (root != null && getParent() == root) {
            setLayoutParams(placement());
        }
    }

    /** 显示时按设置的透明度（夜间再乘夜间亮度），备着时全透明。 */
    private void applyWindowAlpha() {
        Presentation p = presentation;
        Window window = p != null ? p.getWindow() : null;
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.alpha = shown ? HudPicture.opacity(appConfig.getHudOpacity(), appConfig.getHudNight(), night) : 0f;
        window.setAttributes(attrs);
    }

    @Override
    public void syncCanvas() {
        int visibility = shown || !textureView.isAvailable() ? View.VISIBLE : View.INVISIBLE;
        if (textureView.getVisibility() != visibility) {
            textureView.setVisibility(visibility);
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
        return "display=" + display.getDisplayId() + " attached=" + attached
                + " presentation=" + (presentation == null ? "none" : presentation.isShowing() ? "showing" : "gone")
                + " shown=" + shown + " lane=" + LaneCycle.labelOf(lane)
                + " canvas=" + (textureView.isAvailable() ? "ready" : "none")
                + " geometry=" + (hasGeometry() ? "known" : "unknown")
                + " covered=" + (shown && picture.covered()) + " night=" + night;
    }

    // ------------------------------------------------------------------ 布局与绘制

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        textureView.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // 子视图铺满，实际取景由 dispatchDraw 的网格决定
        textureView.layout(0, 0, getWidth(), getHeight());
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
        if (!picture.live()) {
            // 盖着：画一次子视图（不画就不取帧），再盖黑 —— HUD 上什么也没有
            picture.draw(canvas, width, height, lane, paintTexture);
            return;
        }
        float fade = HudPicture.fadePx(fadePercent, width, height);
        boolean layer = tonePaint != null || fade > 0f;
        int save = layer ? canvas.saveLayer(0f, 0f, width, height, tonePaint) : canvas.save();
        if (mirrored) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        picture.draw(canvas, width, height, lane, paintTexture);
        if (fade > 0f) {
            if (mirrored) {
                canvas.scale(-1f, 1f, width / 2f, height / 2f);
            }
            eraseEdges(canvas, width, height, fade);
        }
        canvas.restoreToCount(save);
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
