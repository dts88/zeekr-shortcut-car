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
 * 盲区画面的窗口：打转向灯时在屏幕那一侧显示该侧摄像头的画面（拉直、往后看）。
 *
 * <h3>只看，不操作（项目所有者 2026-10-16）</h3>
 *
 * <p>固定大小、固定位置（左右各一个，{@link SideViewPlacement}），不能拖、不能缩放；只有一个关闭按钮，
 * 点了关掉这一次打灯（{@link SideViewDismissal}）。不镜像。</p>
 *
 * <h3>画法和超级镜子一样</h3>
 *
 * <p>子视图是普通 TextureView，本容器在 {@code dispatchDraw} 里分格反投影（{@link FisheyeMesh}），
 * 虚拟相机先转过角度（{@link SideViewFraming}）。不新建 GL 管线，不动相机会话（为什么见 {@link RearViewMirrorView}）。</p>
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
 * <h3>不显示过时的画面</h3>
 *
 * <p>画布设回可见那一下，TextureView 先取一次缓冲区里留着的那一帧 —— 相机要是停着，那一帧可能是很久以前的。
 * 所以每次显示先盖成黑的，显示之后来了第二帧才露出来；显示着时 {@link #STALE_MS} 没有新帧也盖上。
 * 盯着一张过时的侧方画面变道，比看见一块黑的危险得多（同超级镜子画面停住时盖上的道理）。</p>
 *
 * <p>来源：备着 / 显示两种窗口状态（备着时窗口透明、不接点击，显示时不能带 FLAG_NOT_TOUCHABLE）和分格拉直的画法，
 * 照 FerixzTanz 的 PR #4、wysie 的 PR #19 里的侧视弹窗写；GPL-3.0。</p>
 */
public class SideViewWindow extends ViewGroup {

    private static final String TAG = "SideViewWindow";

    /** 显示着时多久没有新帧就盖上：最省的档位也有 10 fps，半秒是五帧没来。 */
    static final long STALE_MS = 500L;
    private static final long TICK_MS = 250L;
    /** 关闭按钮的直径、离窗口边多远（dp）。 */
    private static final float CLOSE_SIZE_DP = 48f;
    private static final float CLOSE_MARGIN_DP = 10f;

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;
    private final CloseButton closeButton;
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;
    private final SideViewFraming framing = new SideViewFraming();
    private final FisheyeMesh.SourceMap turnedMap = framing::sourcePoint;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean shown;
    private int lane = LaneCycle.LEFT;
    /** 合成流的真实尺寸算出来的拆分几何；不知道时画面是黑的。 */
    private CompositeStreamGeometry.Plan plan;
    /** 这一次显示以来来了几帧：第一帧可能是画布设回可见时取的旧帧。 */
    private int framesSinceShown;
    private long lastFrameUptimeMs;
    private boolean stale;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!attached || !shown) {
                return;
            }
            boolean nowStale = SystemClock.uptimeMillis() - lastFrameUptimeMs > STALE_MS;
            if (nowStale != stale) {
                stale = nowStale;
                if (stale) {
                    AppLog.i(TAG, "盲区画面 " + STALE_MS + " ms 没有新帧：盖上");
                }
                invalidate();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    public SideViewWindow(Context context, AppConfig appConfig, Runnable onClose) {
        super(context);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        closeButton = new CloseButton(context);
        closeButton.setOnClickListener(v -> {
            if (shown && onClose != null) {
                onClose.run();
            }
        });
        addView(closeButton);
    }

    /** 相机要写进的 TextureView：服务把它的 SurfaceTexture 声明成环视的盲区画面输出，会话由通道调度建。 */
    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    /** 合成流的真实尺寸；不像合成流的忽略（同 {@link RearViewMirrorView#setSourceSize}）。 */
    public void setSourceSize(Size size) {
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return;
        }
        String id = StreamLayoutTable.compositeCameraId();
        if (!CompositeStreamGeometry.looksLikeComposite(id, size.getWidth(), size.getHeight())) {
            AppLog.d(TAG, "忽略非合成流尺寸 " + size);
            return;
        }
        plan = CompositeStreamGeometry.analyse(id, size.getWidth(), size.getHeight());
        invalidate();
    }

    public boolean hasGeometry() {
        return plan != null && plan.isComposite();
    }

    public boolean isAttached() {
        return attached;
    }

    public boolean isShowing() {
        return attached && shown;
    }

    public int lane() {
        return lane;
    }

    /**
     * 设置页改了标定角度（开发者选项）：马上重画一次。角度在本容器重画时照 AppConfig 读（{@link #dispatchDraw}）——
     * 显示出来、换一侧、盖上 / 露出、这里叫重画时；新的一帧只重画画布，不重画容器。所以开发者选项锁上、打开
     * （归它管的设置按没存过算）不当场跟：等本容器下一次重画 —— 显示着时是换一侧、盖上 / 露出，最晚下一次显示
     * （AppConfig.DEVELOPER_KEYS）。
     */
    public void applyAim() {
        invalidate();
    }

    // ------------------------------------------------------------------ 窗口

    /** 挂上但不显示（备着）；已经挂着就只是藏起来。 */
    public void attachHidden(int side) {
        attachAt(side, false);
    }

    /** 显示 {@code side} 那一侧：没挂就挂上，备着就显示出来，显示着另一侧就换过去。 */
    public void show(int side) {
        attachAt(side, true);
    }

    /** 藏起来，窗口留着、画面留在会话里（备着）。 */
    public void conceal() {
        if (!attached || !shown) {
            return;
        }
        shown = false;
        handler.removeCallbacks(tick);
        applyWindowState();
        update();
    }

    /**
     * 把窗口当场移走（服务退出、离开 D 挡 30 秒、熄屏、原厂画面出现时）：画布在这一下被收走，
     * 服务在 onSurfaceTextureDestroyed 里把它交给管线留着（同超级镜子，{@link MultiCameraManager#keepSideCanvas}）。
     * 只从服务调，不在本窗口的绘制、触摸分发途中，当场摘是安全的（理由同 {@link RearViewMirrorView#hide}）。
     */
    public void detach() {
        handler.removeCallbacks(tick);
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
        if (show && !wasShown) {
            // 新的一次显示：先盖着，等真正的新帧（显示着换一侧不用：画布一直可见，帧一直是新的）
            framesSinceShown = 0;
            closeButton.refreshLabel();
            stale = false;
            lastFrameUptimeMs = SystemClock.uptimeMillis();
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

    /** 位置和大小：左右各一个，固定的（{@link SideViewPlacement}）。 */
    private void place() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        SideViewPlacement.Rect rect = SideViewPlacement.compute(w, h, lane);
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

    /** 服务每收到一帧（onSurfaceTextureUpdated）调一次。 */
    public void noteFrame() {
        lastFrameUptimeMs = SystemClock.uptimeMillis();
        if (!shown) {
            return;
        }
        boolean wasCovered = covered();
        framesSinceShown++;
        stale = false;
        if (wasCovered != covered()) {
            invalidate();
        }
    }

    /** 盖着：这一次显示之后还没来真正的新帧，或者新帧停了。 */
    private boolean covered() {
        return framesSinceShown < 2 || stale;
    }

    /** 黑匣子里的一行：窗口此刻的样子。 */
    public String describe() {
        return "attached=" + attached + " shown=" + shown + " lane=" + LaneCycle.labelOf(lane)
                + " canvas=" + (textureView.isAvailable() ? "ready" : "none")
                + " geometry=" + (hasGeometry() ? "known" : "unknown")
                + " covered=" + (shown && covered())
                + " fov=" + Math.round(framing.effectiveFovDegrees()) + "°";
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
        if (covered() || !hasGeometry()) {
            // 照样画一次子视图（不画它就不取帧，新帧也就不来），再整个盖黑
            drawTextureOnce(canvas);
            canvas.drawColor(0xFF000000);
        } else {
            // 角度在容器重画时照设置读（内存里的值；开发者选项锁着时是默认值）；没变的时候 SideViewFraming 不重算
            framing.configure(appConfig.getSideViewFov(), appConfig.getSideViewBack(), appConfig.getSideViewUp(),
                    lane, width / (float) height);
            RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(
                    plan, lane, RearViewGeometry.Viewport.full());
            mesh.prepare(FisheyeProjection.MESH_DIVISIONS,
                    r.laneOffsetX * width, r.laneOffsetY * height,
                    r.laneScaleX * width, r.laneScaleY * height, turnedMap);
            mesh.draw(canvas, 0f, 0f, width, height, paintTexture);
        }
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

        private int paletteMode = -1, normalFill, pressedFill;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context context) {
            super(context);
            setClickable(true);
            refreshLabel();
            com.kooo.evcam.settings.AppTheme.observe(this);
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
            int mode = com.kooo.evcam.settings.AppTheme.nightMode(getContext());
            if (mode != paletteMode) {
                paletteMode = mode;
                Context palette = com.kooo.evcam.settings.AppTheme.palette(getContext());
                normalFill = palette.getColor(R.color.surface);
                pressedFill = palette.getColor(R.color.sunken);
                cross.setColor(palette.getColor(R.color.text_primary));
            }
            fill.setColor(isPressed() ? pressedFill : normalFill);
            canvas.drawCircle(cx, cy, radius, fill);
            float arm = radius * 0.36f;
            canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross);
            canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, cross);
        }
    }
}
