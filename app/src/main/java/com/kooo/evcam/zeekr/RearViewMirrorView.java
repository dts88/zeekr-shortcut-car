package com.kooo.evcam.zeekr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;
import com.kooo.evcam.R;

/**
 * 超级后视镜：把环视合成流里<b>后方那一路</b>单独放大成一个悬浮窗。
 *
 * <h3>为什么用重画而不是 GL</h3>
 *
 * <p>这台车机上有一条硬性平台事实（见 {@code docs/zeekr-platform-notes.md} 2.1）：
 * 用 GL 自建 SurfaceTexture 顶替相机的生产者会崩，而「一个普通 TextureView + 父容器
 * 用矩阵把它重画一遍」是验证可行的结构 —— 四宫格预览就是这么做的。</p>
 *
 * <p>纯裁切是线性变换，两层（取哪一路 + 取景蒙版）可以直接相乘成一个矩形，
 * 所以这里沿用同一条安全路径：子视图是普通 TextureView，本容器在 {@code dispatchDraw}
 * 里把它按合成矩形放大重画。<b>不新建 GL 管线，不动相机会话。</b></p>
 *
 * <p>鱼眼校正是非线性的，矩阵做不了，需要着色器 —— 那部分单独处理，见类末尾说明。</p>
 *
 * <h3>手势</h3>
 *
 * <p>横向分三段，规则见 {@link RearViewTouchModel}：左右三分之一拖动窗口，
 * 中间三分之一上下滑调整取景高低、左右划切换显示哪一路（窗口不动）。双指缩放窗口大小，推出屏幕一半或朝边上甩一下即贴边隐藏；贴边后点一下、或往回拉一下就滑回来。</p>
 *
 * <p>显示框可以拉成任意宽高，但<b>画面比例永远不变</b>：框的形状决定看到多大一块，
 * 不决定画面被拉成什么样。</p>
 *
 * <p>后视那一路做<b>左右镜像</b>，和真正的后视镜一致；前视、侧视不翻。
 * 预览、录制、回放都不受影响。</p>
 */
public class RearViewMirrorView extends ViewGroup {

    private static final String TAG = "RearViewMirror";

    /** 贴边后仍然露出的宽度，用来把它再拖回来。 */
    private static final int PEEK_WIDTH_PX = 72;

    // 贴边的前提是窗口能探出屏幕，靠的是 show() 里的 FLAG_LAYOUT_NO_LIMITS。
    // 没有那个标志，WindowManager 会把悬浮窗按回显示区域内 ——
    // 这边算得再准，x 一提交就被改回去，窗口根本出不去。
    //
    // 判定条件本身见 RearViewTouchModel.deliberateDock：要么已经推出去一半，
    // 要么朝边上甩了一下。只要窗口还整个在屏幕里就绝不贴边。

    /** 超过这个位移才算拖动，避免点一下就漂移。 */
    private static final int DRAG_SLOP_PX = 12;
    /** 横向划多远算「明确要换一路」。 */
    private static final int LANE_SWIPE_MIN_PX = 90;
    /**
     * 贴边之后往回拉多少算「要拿回来」。
     *
     * <p>固定像素，不按窗口宽度取比例 —— 按比例的话窗口越大越难拉回来，
     * 而大窗口恰恰是最想拿回来的那个。</p>
     */
    private static final int UNDOCK_MIN_PX = 60;
    /** 滑回屏幕的时长范围。 */
    private static final long GLIDE_MIN_MS = 120L;
    private static final long GLIDE_MAX_MS = 380L;

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;

    private WindowManager.LayoutParams params;
    private boolean attached;

    /** 合成流的真实尺寸决定拆分几何，不能用缓冲区尺寸（HAL 可能给个压扁的提示值）。 */
    private CompositeStreamGeometry.Plan plan;
    /** 当前显示哪一路。中间三分之一左右划切换，见 {@link LaneCycle}。 */
    private int laneIndex;
    /** 取景在画面上的高低位置，0..1。宽高比锁死后，可调的就只剩这个。 */
    private float pan;

    // 手势状态
    private RearViewTouchModel.Zone activeZone = RearViewTouchModel.Zone.MOVE_WINDOW;
    private float touchStartX;
    private float touchStartY;
    private int windowStartX;
    private int windowStartY;
    private float panAtTouchStart;
    /** 中间三分之一这一下锁定的方向；null 表示还没定。 */
    private Boolean horizontalDrag;
    private float lastDx;
    private float lastDy;
    /** 滑回屏幕的动画。新的手势一来就取消，免得和手指抢位置。 */
    private ValueAnimator glide;
    /** 鱼眼校正开关与目标视野，进入时读一次，设置页改了再推过来。 */
    private boolean fisheyeCorrection;
    private float fovDegrees;
    /** 分片绘制用的临时数组，避免每帧、每格都新建。 */
    private final float[] meshSource = new float[8];
    private final float[] meshDest = new float[8];
    /**
     * 多久没有新画面就不再拿它当实时画面看。
     *
     * <p>最省的档位也有 10fps，2.5 秒是二十多帧没来 —— 到这个地步已经不是卡顿，
     * 是这条流停了。</p>
     */
    private static final long FROZEN_AFTER_MS = 2500L;
    /** 没有画面时靠它定期重画 —— 画面停了就没有帧来驱动重画了。 */
    private static final long IDLE_TICK_MS = 1000L;

    /** 碰一下之后，那四个按钮还显示多久。 */
    private static final long BUTTONS_VISIBLE_MS = 5000L;
    /** 没选中的按钮：深底，八成不透明 —— 压得住画面，又不至于挡死。 */
    private static final int BUTTON_IDLE_FILL = 0xCC1A1C1F;
    private static final int BUTTON_IDLE_TEXT = 0xFFF0F1F2;

    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 最后一帧新画面的时刻，单调时钟。 */
    private long lastFrameUptimeMs;
    /** 画面停了：盖住那张过时的画面，改成一句「点击恢复」。 */
    private boolean frozen;
    /** 贴边收起：那一条窄边上不画画面，只写名字。 */
    private boolean docked;
    /** 按键模式：左右划换路换成四个按钮。 */
    private boolean buttonMode;
    /** 最后碰这个窗口的时刻 —— 按钮从这一刻起再显示 5 秒。 */
    private long lastTouchUptimeMs;
    /** 正按着哪个按钮；-1 表示没有。 */
    private int pressedButton = -1;
    /** 点一下要做的恢复动作，由服务给。 */
    private Runnable resumeAction;
    /** 贴边状态变了通知服务，好把相机那一路的推流停掉 / 接回来。 */
    private DockListener dockListener;

    private boolean dragging;
    /** 松手时要知道甩得多快，用系统自带的这个就够，不必自己算。 */
    private VelocityTracker velocityTracker;
    /** 平台认定的最小甩动速度，手感与其他应用一致。 */
    private final int minFlingVelocity;
    private boolean pinching;
    /** 这一次触摸里发生过缩放。抬起第二根手指时 pinching 就清了，松手时还要用。 */
    private boolean pinchedThisGesture;
    private float pinchStartSpan;
    private int pinchStartWidth;
    private int pinchStartHeight;
    /** 按下那一刻两指中点落在窗口的哪个相对位置，缩放时让这个点待在指下不动。 */
    private float pinchAnchorRatioX;
    private float pinchAnchorRatioY;

    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();

    public RearViewMirrorView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.pan = appConfig.getRearViewPan();
        this.laneIndex = appConfig.getRearViewLane();
        this.minFlingVelocity =
                ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        this.fisheyeCorrection = appConfig.isRearViewFisheyeCorrection();
        this.fovDegrees = appConfig.getRearViewFov();

        setBackgroundColor(0xFF000000);

        textureView = new AutoFitTextureView(context);
        addView(textureView);

    }

    /** 相机预览要写进的 TextureView。绑定方式与其它悬浮窗一致，不改相机会话。 */
    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    /**
     * 告诉后视镜合成流的真实尺寸。
     *
     * <p>不像合成流的尺寸会被忽略 —— 那多半是 HAL 给的压扁提示值，
     * 拿它算几何会把取景框算到错误的位置。</p>
     */
    public void setSourceSize(Size size) {
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return;
        }
        if (!CompositeStreamGeometry.looksLikeComposite(StreamLayoutTable.compositeCameraId(), size.getWidth(), size.getHeight())) {
            AppLog.d(TAG, "忽略非合成流尺寸 " + size + "（多半是 HAL 的小尺寸提示）");
            return;
        }
        plan = CompositeStreamGeometry.analyse(StreamLayoutTable.compositeCameraId(), size.getWidth(), size.getHeight());
        AppLog.i(TAG, "后视镜取景: " + plan + " 第 " + laneIndex + " 路");
        invalidate();
    }

    // ------------------------------------------------------------------ 窗口

    public void show() {
        if (attached) {
            return;
        }
        int width = appConfig.getRearViewWidth(screenWidth());
        int height = appConfig.getRearViewHeight(screenHeight());
        params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        int savedX = appConfig.getRearViewX();
        int savedY = appConfig.getRearViewY();
        if (savedX < 0 || savedY < 0) {
            // 默认落在右上角，和车内后视镜的位置感一致
            savedX = screenWidth() - width - 40;
            savedY = 40;
        }
        params.x = RearViewTouchModel.clampX(savedX, width, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(savedY, height, screenHeight());

        try {
            windowManager.addView(this, params);
            attached = true;
            lastFrameUptimeMs = SystemClock.uptimeMillis();
            buttonMode = appConfig.isRearViewButtonMode();
            docked = RearViewTouchModel.dockedAt(params.x, params.width, screenWidth())
                    != RearViewTouchModel.Dock.NONE;
            idleHandler.postDelayed(idleTick, IDLE_TICK_MS);
        } catch (Exception e) {
            AppLog.e(TAG, "后视镜窗口添加失败", e);
        }
    }

    /**
     * 设置里改了按键模式之后推过来。
     *
     * <p>打开时可能要把窗口撑大：按钮不跟着缩放，窗口比那一组还小的话，
     * 按钮就会画到框外面去。</p>
     */
    public void applyButtonModeFromConfig() {
        buttonMode = appConfig.isRearViewButtonMode();
        if (buttonMode && params != null) {
            int min = minWindowSize();
            if (params.width < min || params.height < min) {
                params.width = Math.max(params.width, min);
                params.height = Math.max(params.height, min);
                params.x = RearViewTouchModel.clampX(
                        params.x, params.width, screenWidth(), PEEK_WIDTH_PX);
                params.y = RearViewTouchModel.clampY(params.y, params.height, screenHeight());
                applyLayout();
                savePosition();
                AppLog.i(TAG, "按键模式：窗口撑到 " + min + "px，否则按钮会画到框外");
            }
        }
        lastTouchUptimeMs = SystemClock.uptimeMillis();
        invalidate();
    }

    /** 这个模式下窗口最小能多小。按键模式要装得下那一组按钮。 */
    private int minWindowSize() {
        if (!buttonMode) {
            return AppConfig.REARVIEW_MIN_SIZE;
        }
        return Math.max(AppConfig.REARVIEW_MIN_SIZE,
                LaneButtonPad.minWindowPx(getResources().getDisplayMetrics().density));
    }

    /** 那四个现在该不该显示。碰一下出现，5 秒不碰就消失。 */
    private boolean buttonsShowing() {
        return buttonMode && !docked && !frozen
                && SystemClock.uptimeMillis() - lastTouchUptimeMs < BUTTONS_VISIBLE_MS;
    }

    /** 服务每收到一帧新画面调一次。 */
    public void noteFrame() {
        lastFrameUptimeMs = SystemClock.uptimeMillis();
        if (frozen) {
            frozen = false;
            invalidate();
        }
    }

    /** 画面停了、点了那句提示时要做的事。 */
    public void setResumeAction(Runnable action) {
        this.resumeAction = action;
    }

    public void setDockListener(DockListener listener) {
        this.dockListener = listener;
    }

    public boolean isDocked() {
        return docked;
    }

    /** 贴边收起 / 放回来。 */
    public interface DockListener {
        void onDockChanged(boolean docked);
    }

    /**
     * 没有帧的时候也要有人推着重画。
     *
     * <p>平时每一帧新画面都会触发重画，可画面一停就没人推了 —— 而「画面停了」
     * 恰恰是这时候唯一需要画出来的东西。</p>
     */
    private final Runnable idleTick = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            boolean nowFrozen = !docked
                    && SystemClock.uptimeMillis() - lastFrameUptimeMs > FROZEN_AFTER_MS;
            if (buttonMode) {
                // 那四个到点就该自己退场，而没有帧的时候没人推着重画
                invalidate();
            }
            if (nowFrozen != frozen) {
                frozen = nowFrozen;
                AppLog.i(TAG, frozen ? "后视镜画面停了，改显示「点击恢复」" : "后视镜画面回来了");
                invalidate();
            }
            syncDockState();
            idleHandler.postDelayed(this, IDLE_TICK_MS);
        }
    };

    /**
     * 贴边状态变了就通知一次。
     *
     * <p>贴边之后那条窄边只有 72px，画面在里面既看不清也没有意义，
     * 却要相机一直多推一路流。所以贴边即停流，放回来再接上。</p>
     */
    private void syncDockState() {
        if (params == null) {
            return;
        }
        if (dragging || pinching || glide != null) {
            // 手指还在上面、或者还在滑回去的路上：这中间窗口会短暂地探出屏幕，
            // 那不是「收起来了」。只认落定之后的位置，否则拖一下就会停一次流
            return;
        }
        boolean nowDocked = RearViewTouchModel.dockedAt(
                params.x, params.width, screenWidth()) != RearViewTouchModel.Dock.NONE;
        if (nowDocked == docked) {
            return;
        }
        docked = nowDocked;
        if (docked) {
            frozen = false;   // 收起来不算「画面停了」，是我们自己停的
        } else {
            lastFrameUptimeMs = SystemClock.uptimeMillis();   // 给重新接上留出时间
        }
        AppLog.i(TAG, docked ? "后视镜贴边收起，停止推流" : "后视镜放回来，恢复推流");
        invalidate();
        if (dockListener != null) {
            dockListener.onDockChanged(docked);
        }
    }

    public void hide() {
        idleHandler.removeCallbacks(idleTick);
        cancelGlide();
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "后视镜窗口移除失败: " + e);
        }
        attached = false;
    }

    /**
     * 换到相邻的一路。
     *
     * <p>从车顶往下看，顺时针就是 <b>后 → 左 → 前 → 右</b>，逆时针反之。
     * 环是首尾相接的 —— 后视镜是用来快速扫一圈的，转到头停住反而要多划几下回去。</p>
     */
    /**
     * 直接切到某一路（按键模式用）。
     *
     * <p>不走 {@link LaneCycle} 那个环：按按钮是点名要哪一路，不是「往下一个」。
     * 「只显示前后视」也不拦它 —— 那个设置管的是划动那个环，而按钮是明确的指名。</p>
     */
    private void selectLane(int lane) {
        if (lane == laneIndex) {
            return;
        }
        laneIndex = lane;
        appConfig.setRearViewLane(laneIndex);
        AppLog.i(TAG, "按键模式：切到「" + LaneCycle.labelOf(laneIndex) + "」路");
        invalidate();
    }

    private void switchLane(boolean clockwise) {
        int next = LaneCycle.next(laneIndex, clockwise, appConfig.isRearViewFrontRearOnly());
        if (next == laneIndex) {
            return;
        }
        laneIndex = next;
        appConfig.setRearViewLane(laneIndex);
        AppLog.i(TAG, "后视镜切到「" + LaneCycle.labelOf(laneIndex) + "」路"
                + (LaneCycle.isMirrored(laneIndex) ? "（镜像）" : "（不镜像）"));
        invalidate();
    }

    /**
     * 设置页改了「只看前后」之后推过来。
     *
     * <p>如果当前停在侧视，而侧视已经不在环上了，就退回后视 ——
     * 否则会停在一个划不动的画面上。</p>
     */
    public void applyLaneModeFromConfig() {
        int lane = appConfig.getRearViewLane();
        if (lane != laneIndex) {
            laneIndex = lane;
            AppLog.i(TAG, "后视镜回到「" + LaneCycle.labelOf(laneIndex) + "」路");
            invalidate();
        }
    }

    /** 设置页改了尺寸后，直接套用到正在显示的窗口上。 */
    public void applySizeFromConfig() {
        if (params == null) {
            return;
        }
        params.width = appConfig.getRearViewWidth(screenWidth());
        params.height = appConfig.getRearViewHeight(screenHeight());
        params.x = RearViewTouchModel.clampX(params.x, params.width, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(params.y, params.height, screenHeight());
        applyLayout();
    }

    public boolean isShowing() {
        return attached;
    }

    // ------------------------------------------------------------------ 布局与绘制

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // 子视图铺满，实际取景由 dispatchDraw 的矩阵决定
        textureView.layout(0, 0, getWidth(), getHeight());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        measureChildren(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (docked) {
            // 贴边收起：那条窄边上不画画面，见 drawDockedLabel
            drawDockedLabel(canvas, width, height);
            return;
        }
        if (plan == null || !plan.isComposite()) {
            // 还不知道几何，先原样显示，总比全黑好
            super.dispatchDraw(canvas);
            if (frozen) {
                drawFrozenHint(canvas, width, height);
            }
            return;
        }

        RearViewGeometry.Viewport viewport = viewport();
        float[] rect = RearViewGeometry.combinedSourceRect(plan, laneIndex, viewport);

        // 左右镜像只给后视那一路。后视镜照出来的本来就是反的 —— 看到有车从画面
        // 右侧靠近，手就该往左让，这个对应关系是开车时的肌肉记忆。
        //
        // 前视和侧视不翻：那是「朝那个方向看过去」的画面，翻了反而与实际相反。
        //
        // 只作用于这个悬浮窗：预览、录制、回放拿到的都还是原始画面，
        // 镜像是「怎么看」的问题，不是「存什么」的问题。
        int mirrorSave = canvas.save();
        if (LaneCycle.isMirrored(laneIndex)) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }

        if (fisheyeCorrection) {
            drawCorrected(canvas, width, height, viewport);
        } else {
            // 源矩形在子视图坐标系里的位置。用归一化坐标是关键：
            // HAL 给的缓冲区可能被压扁，但比例关系不变。
            sourceRect.set(rect[0] * width, rect[1] * height,
                    (rect[0] + rect[2]) * width, (rect[1] + rect[3]) * height);
            destRect.set(0, 0, width, height);

            drawMatrix.setRectToRect(sourceRect, destRect, Matrix.ScaleToFit.FILL);

            int save = canvas.save();
            canvas.clipRect(destRect);
            canvas.concat(drawMatrix);
            drawChild(canvas, textureView, getDrawingTime());
            canvas.restoreToCount(save);
        }

        canvas.restoreToCount(mirrorSave);

        // 盖在最上面，而且在镜像之外 —— 提示文字不该跟着画面一起左右翻
        if (frozen) {
            drawFrozenHint(canvas, width, height);
        }
        if (buttonsShowing()) {
            drawLaneButtons(canvas, width, height);
        }
    }

    /**
     * 按键模式那四个「前 后 左 右」，摆成菱形。
     *
     * <p>画在这里而不是放四个真的 View：这个窗口的画面是用矩阵重画出来的，
     * 混进子视图会跟着那套变换一起被缩放 —— 而按钮恰恰是<b>不该跟着缩放</b>的东西。
     * 画在视图坐标系里，窗口拉大拉小都不影响它们的大小。</p>
     *
     * <p>选中的那个用极氪橙实底，其余是深色底、八成不透明。
     * 位置为什么是菱形、各自摆在哪，见 {@link LaneButtonPad}。</p>
     */
    private void drawLaneButtons(Canvas canvas, int width, int height) {
        float density = getResources().getDisplayMetrics().density;
        float corner = LaneButtonPad.corner(density);
        int active = LaneButtonPad.indexForLane(laneIndex);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(buttonTextSize(density));
        Paint.FontMetrics fm = labelPaint.getFontMetrics();

        for (int i = 0; i < LaneButtonPad.COUNT; i++) {
            float[] r = LaneButtonPad.rectFor(i, width, height, density);
            boolean selected = i == active;
            scrimPaint.setColor(selected
                    ? androidx.core.content.ContextCompat.getColor(getContext(), R.color.energy)
                    : BUTTON_IDLE_FILL);
            canvas.drawRoundRect(r[0], r[1], r[2], r[3], corner, corner, scrimPaint);

            labelPaint.setColor(selected
                    ? androidx.core.content.ContextCompat.getColor(getContext(), R.color.on_energy)
                    : BUTTON_IDLE_TEXT);
            canvas.drawText(getContext().getString(laneLabelRes(i)),
                    (r[0] + r[2]) / 2f,
                    (r[1] + r[3]) / 2f - (fm.ascent + fm.descent) / 2f, labelPaint);
        }
    }

    /**
     * 四个按钮上的字用多大。
     *
     * <p>先按按钮高度取一个字号，再拿<b>最长的那一个</b>去比按钮的宽：
     * 中文是一个字，英文是「Front」五个字母，同一个字号下宽度差着三倍。
     * 收就四个一起收 —— 一个键的字比旁边小一号，比四个都小一号更显眼。</p>
     */
    private float buttonTextSize(float density) {
        float size = LaneButtonPad.buttonHeight(density) * 0.42f;
        float room = LaneButtonPad.buttonWidth(density) * 0.82f;
        labelPaint.setTextSize(size);
        float widest = 0f;
        for (int i = 0; i < LaneButtonPad.COUNT; i++) {
            widest = Math.max(widest,
                    labelPaint.measureText(getContext().getString(laneLabelRes(i))));
        }
        return widest > room ? size * room / widest : size;
    }

    private static int laneLabelRes(int index) {
        switch (index) {
            case LaneCycle.REAR: return R.string.zeekr_lane_back;
            case LaneCycle.LEFT: return R.string.zeekr_lane_left;
            case LaneCycle.RIGHT: return R.string.zeekr_lane_right;
            default: return R.string.zeekr_lane_front;
        }
    }

    /**
     * 当前该看画面的哪一块。
     *
     * <p>由显示框的形状加上下平移量算出来 —— <b>比例是锁死的</b>，
     * 所以这里没有第三个自由度可调。</p>
     */
    private RearViewGeometry.Viewport viewport() {
        // 开了校正的话，视野角度已经在反投影里消化掉了，这里再收一次就重复了。
        // 关掉校正时它没人消化，于是由取景来兑现 —— 两边都得让那根滑块管用。
        float fraction = fisheyeCorrection
                ? 1f
                : RearViewGeometry.visibleFractionForFov(fovDegrees);
        return RearViewGeometry.Viewport.forWindow(getWidth(), getHeight(), pan, fraction);
    }

    // ------------------------------------------------------------------ 手势

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 按钮看不见的时候不能被点中 —— 所以先问「这一下之前它们在不在」，
                // 再把计时器拨回去。反过来的话，第一下就会误触到一个还没出现的按钮
                boolean buttonsWereShowing = buttonsShowing();
                lastTouchUptimeMs = SystemClock.uptimeMillis();
                if (buttonsWereShowing) {
                    pressedButton = LaneButtonPad.hitTest(event.getX(), event.getY(),
                            getWidth(), getHeight(),
                            getResources().getDisplayMetrics().density);
                    if (pressedButton >= 0) {
                        invalidate();
                        return true;   // 这一下归按钮，不拖窗口也不调取景
                    }
                }
                if (buttonMode) {
                    invalidate();      // 让那四个现身
                }
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                velocityTracker.addMovement(event);
                beginTouch(event);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    beginPinch(event);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                if (pinching && event.getPointerCount() >= 2) {
                    updatePinch(event);
                } else if (!pinching) {
                    updateDrag(event);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 双指里抬起一根，缩放就结束了 —— 尺寸必须在这里落盘。
                //
                // 原先只是把 pinching 置回 false 就返回，等 ACTION_UP 再统一处理；
                // 可等到那时 pinching 已经是 false、dragging 也一直是 false，
                // endTouch 里那道判断走不进去，savePosition() 根本不会被调用。
                // 表现就是：窗口当场变了大小，配置里还是旧的 ——
                // 设置页的「窗口宽度 / 高度」于是永远对不上眼前这个窗口。
                if (event.getPointerCount() <= 2 && pinching) {
                    pinching = false;
                    pinchedThisGesture = true;
                    savePosition();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (pressedButton >= 0) {
                    int released = LaneButtonPad.hitTest(event.getX(), event.getY(),
                            getWidth(), getHeight(),
                            getResources().getDisplayMetrics().density);
                    // 按下和抬起要在同一个按钮上 —— 按下之后滑开，算作反悔
                    if (released == pressedButton) {
                        selectLane(LaneButtonPad.laneFor(pressedButton));
                    }
                    pressedButton = -1;
                    lastTouchUptimeMs = SystemClock.uptimeMillis();
                    invalidate();
                    return true;
                }
            case MotionEvent.ACTION_CANCEL:
                endTouch(takeXVelocity(event));
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void beginTouch(MotionEvent event) {
        cancelGlide();
        activeZone = RearViewTouchModel.zoneFor(event.getX(), getWidth());
        touchStartX = event.getRawX();
        touchStartY = event.getRawY();
        windowStartX = params != null ? params.x : 0;
        windowStartY = params != null ? params.y : 0;
        panAtTouchStart = pan;
        horizontalDrag = null;
        lastDx = 0f;
        lastDy = 0f;
        dragging = false;
        pinching = false;
        pinchedThisGesture = false;
    }

    private void beginPinch(MotionEvent event) {
        // 按着按钮又落下第二根手指：这一下是要缩放，不是要切换
        pressedButton = -1;
        pinching = true;
        dragging = false;
        pinchStartSpan = spanOf(event);
        pinchStartWidth = params != null ? params.width : appConfig.getRearViewWidth(screenWidth());
        pinchStartHeight = params != null ? params.height : appConfig.getRearViewHeight(screenHeight());

        // 两指中点在窗口里的相对位置，之后一直用它当锚
        float focusInViewX = (event.getX(0) + event.getX(1)) / 2f;
        float focusInViewY = (event.getY(0) + event.getY(1)) / 2f;
        pinchAnchorRatioX = clamp01(focusInViewX / Math.max(1, pinchStartWidth));
        pinchAnchorRatioY = clamp01(focusInViewY / Math.max(1, pinchStartHeight));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * 两指中点的屏幕坐标。
     *
     * <p>{@code getRawX()} 只给得到第一根手指的屏幕坐标，而 {@code getRawX(int)}
     * 要 API 29。好在两根手指的视图坐标之差与坐标系无关，
     * 所以从第一根的屏幕坐标推出中点即可 —— 不用管窗口此刻在哪。</p>
     */
    private static float rawFocusX(MotionEvent event) {
        return event.getRawX() + (event.getX(1) - event.getX(0)) / 2f;
    }

    private static float rawFocusY(MotionEvent event) {
        return event.getRawY() + (event.getY(1) - event.getY(0)) / 2f;
    }

    private void updatePinch(MotionEvent event) {
        if (params == null || pinchStartSpan <= 0f) {
            return;
        }
        float span = spanOf(event);
        if (span <= 0f) {
            return;
        }

        // 以两指中点为锚缩放，同时跟着中点走 —— 和系统相册一个手感。
        // 不能在「尺寸没变」时提前返回：两指平移而不改变跨距是纯挪窗口，
        // 那时候尺寸本来就不该变。
        RearViewTouchModel.PinchResult result = RearViewTouchModel.pinch(
                params.x, params.y, pinchStartWidth, pinchStartHeight,
                pinchAnchorRatioX, pinchAnchorRatioY,
                rawFocusX(event), rawFocusY(event), span / pinchStartSpan,
                minWindowSize(), screenWidth(), screenHeight());

        params.width = result.width;
        params.height = result.height;
        // 仍然夹一下，保证窗口不会被推到完全抓不回来的地方
        params.x = RearViewTouchModel.clampX(
                result.x, result.width, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(result.y, result.height, screenHeight());
        applyLayout();
    }

    private void updateDrag(MotionEvent event) {
        float dx = event.getRawX() - touchStartX;
        float dy = event.getRawY() - touchStartY;
        if (!dragging && Math.abs(dx) < DRAG_SLOP_PX && Math.abs(dy) < DRAG_SLOP_PX) {
            return;
        }
        dragging = true;
        lastDx = dx;
        lastDy = dy;

        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP) {
            // 一开始就锁定方向。不锁的话，横着划的过程中那点竖直位移会让取景上下抖 ——
            // 明明在换路，画面却动了。
            if (horizontalDrag == null) {
                horizontalDrag = RearViewTouchModel.isHorizontalIntent(dx, dy);
            }
            if (horizontalDrag) {
                // 换不换路等松手再定，划到一半不该跳来跳去
                return;
            }
            float shift = RearViewTouchModel.panShiftForDrag(
                    dy, getHeight(), viewport().verticalHeadroom());
            pan = RearViewGeometry.clampPan(panAtTouchStart + shift);
            invalidate();
            return;
        }

        if (params == null) {
            return;
        }
        params.x = RearViewTouchModel.clampX(
                windowStartX + (int) dx, params.width, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(
                windowStartY + (int) dy, params.height, screenHeight());
        applyLayout();
    }

    /** 取出松手瞬间的横向速度，顺手把 tracker 还回去。 */
    private float takeXVelocity(MotionEvent event) {
        if (velocityTracker == null) {
            return 0f;
        }
        float velocity = 0f;
        try {
            velocityTracker.addMovement(event);
            velocityTracker.computeCurrentVelocity(1000);   // px/s
            velocity = velocityTracker.getXVelocity();
        } catch (Exception e) {
            AppLog.w(TAG, "取速度失败: " + e);
        } finally {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        return velocity;
    }

    private void endTouch(float velocityX) {
        if (!dragging && !pinching) {
            // 贴着边的时候点一下就是「拿回来」—— 那时候屏幕上只剩一条窄边，
            // 除了把它拉回来也没别的可做，不该还要求先拖一段
            if (params != null) {
                RearViewTouchModel.Dock dock = RearViewTouchModel.dockedAt(
                        params.x, params.width, screenWidth());
                if (dock != RearViewTouchModel.Dock.NONE) {
                    glideTo(RearViewTouchModel.flushX(dock, params.width, screenWidth()), 0f);
                } else if (frozen && resumeAction != null) {
                    // 画面停住时点一下就是「把它接回来」—— 那时候除了这个也没别的可做
                    AppLog.i(TAG, "点了停住的后视镜，重新接相机");
                    resumeAction.run();
                }
            }
            return;
        }

        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP && dragging) {
            if (horizontalDrag != null && horizontalDrag) {
                // 按键模式下横划不换路：那件事交给了按钮，两条路都留着的话，
                // 调取景时手一歪就会莫名其妙跳到别的一路
                if (!buttonMode && RearViewTouchModel.isDeliberateSwipe(
                        lastDx, velocityX, LANE_SWIPE_MIN_PX, minFlingVelocity)) {
                    // 右滑走顺时针（后 左 前 右），左滑走逆时针（后 右 前 左）
                    switchLane(lastDx > 0f);
                }
            } else {
                appConfig.setRearViewPan(pan);
            }
        } else if (params != null && (dragging || pinching || pinchedThisGesture)) {
            // pinchedThisGesture 也要算：缩放在抬起第二根手指时就结束了，
            // 到这里 pinching 已经是 false，但窗口该收回屏幕内还是得收
            settleAfterDrag(velocityX);
        }
        dragging = false;
        pinching = false;
        pinchedThisGesture = false;
        syncDockState();
    }

    /**
     * 松手之后停在哪。
     *
     * <p>只有三种落点：<b>贴边</b>、<b>整个在屏幕里</b>、或者<b>原样贴回去</b>。
     * 以前还有第四种 —— 半截露在外面停着，那是拖到哪算哪，看着就像没做完。</p>
     *
     * <p>贴边和取回来用的是<b>不对称</b>的门槛：推出去要够狠（一半宽度或甩一下），
     * 拿回来只要 {@link #UNDOCK_MIN_PX} 像素或往回甩一下。
     * 藏错了拿不回来是个死结，而多滑一次只是麻烦 —— 代价不对称，门槛就不该对称。</p>
     */
    private void settleAfterDrag(float velocityX) {
        int screenW = screenWidth();
        RearViewTouchModel.Dock wasDocked = RearViewTouchModel.dockedAt(
                windowStartX, params.width, screenW);

        if (wasDocked != RearViewTouchModel.Dock.NONE) {
            if (RearViewTouchModel.shouldUndock(wasDocked, lastDx, velocityX,
                    UNDOCK_MIN_PX, minFlingVelocity)) {
                glideTo(RearViewTouchModel.flushX(wasDocked, params.width, screenW), velocityX);
            } else {
                // 没拉够就贴回去，而不是停在中间
                glideTo(RearViewTouchModel.dockedX(
                        wasDocked, params.x, params.width, screenW, PEEK_WIDTH_PX), velocityX);
            }
            savePosition();
            return;
        }

        RearViewTouchModel.Dock dock = RearViewTouchModel.deliberateDock(
                params.x, params.width, screenW, velocityX, minFlingVelocity);
        if (dock != RearViewTouchModel.Dock.NONE) {
            glideTo(RearViewTouchModel.dockedX(
                    dock, params.x, params.width, screenW, PEEK_WIDTH_PX), velocityX);
            AppLog.d(TAG, "后视镜贴边: " + dock + "，露出 " + PEEK_WIDTH_PX + "px");
        } else {
            // 没到贴边的程度就整个收回屏幕里，不留半截在外面
            int onScreen = Math.max(0, Math.min(screenW - params.width, params.x));
            if (onScreen != params.x) {
                glideTo(onScreen, velocityX);
            }
        }
        savePosition();
    }

    private void savePosition() {
        appConfig.setRearViewPosition(params.x, params.y);
        appConfig.setRearViewSize(params.width, params.height,
                screenWidth(), screenHeight());
    }

    /**
     * 横向滑到目标位置。
     *
     * <p>用动画而不是直接跳过去：跳过去看不出它从哪来、去了哪，
     * 尤其贴边只露一条窄边时，会像是凭空冒出来的。</p>
     *
     * <p>时长跟着甩动速度走 —— 手上使了多大劲，画面就该多快跟上。</p>
     */
    private void glideTo(int targetX, float velocityX) {
        if (params == null || targetX == params.x) {
            return;
        }
        cancelGlide();
        final int from = params.x;
        long duration = RearViewTouchModel.glideDurationMs(
                targetX - from, velocityX, GLIDE_MIN_MS, GLIDE_MAX_MS);

        glide = ValueAnimator.ofInt(from, targetX);
        glide.setDuration(duration);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(animation -> {
            if (params == null) {
                return;
            }
            params.x = (Integer) animation.getAnimatedValue();
            applyLayout();
        });
        glide.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                glide = null;
                savePosition();
                // 落定了才算数：贴边收起 / 放回来都在这一刻定下来
                syncDockState();
            }
        });
        glide.start();
    }

    private void cancelGlide() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
    }

    private static float spanOf(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void applyLayout() {
        if (!attached || params == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "后视镜窗口更新失败: " + e);
        }
        syncDockState();
    }

    // ------------------------------------------------------------------ 没有画面时画什么

    /**
     * 画面停住时盖上去的一层。压暗而不是盖死 —— 让人看得出底下是张画面，只是不再更新了。
     *
     * <p>这一层盖的是摄像头画面，不是应用自己的界面，所以不跟日夜模式走：
     * 底下永远是视频，压暗 + 浅色字在两种模式下都读得清。</p>
     */
    private static final int FROZEN_SCRIM = 0xCC000000;
    private static final int FROZEN_TEXT = 0xFFF2F2F3;

    /**
     * 贴边那条窄边的底色与字色。<b>固定深色，不跟日夜模式走。</b>
     *
     * <p>它浮在车机桌面上，背后是什么完全不可控 —— 跟着日夜模式走只保证和自己的设置页
     * 一致，不保证和背后的东西分得开。先前用的 {@code surface} 在日间是近白色，
     * 落在浅色壁纸上就糊成一片。</p>
     */
    private static final int DOCK_BACKGROUND = 0xFF1A1C1F;
    private static final int DOCK_TEXT = 0xFFF0F1F2;
    /** 朝向桌面那一侧的极氪橙亮边，单位 dp。 */
    private static final float DOCK_EDGE_DP = 3f;

    /**
     * 贴边收起时那条窄边上画什么。
     *
     * <p><b>不画画面。</b>72px 宽的一条里既看不出什么，还要相机一直多推一路流 ——
     * 推流本身在贴边时就停掉了（见 {@link #syncDockState()}），这里画的是替代品：
     * 写上名字，这条边才说得清自己是谁，否则屏幕边上就是一条没来由的深色条。</p>
     *
     * <p>字是<b>那一串英文</b>，三个语言都一样：72px 宽的一条上放的是应用在车上的
     * 「标记」，不是一句要读的话，换成别的语言反而认不出来。整体转 90 度而不是逐字竖排。</p>

     * <p>配色是<b>固定的深色底 + 近白色字 + 朝向桌面那一侧的一条极氪橙</b>，不跟日夜模式走：
     * 这条边浮在车机桌面上，背后是什么不可控，和自己的设置页一致并不能保证和背后分得开。
     * 深色底对付亮壁纸，橙边对付深色壁纸。</p>
     */
    private void drawDockedLabel(Canvas canvas, int width, int height) {
        float left = 0f;
        if (params != null && RearViewTouchModel.dockedAt(params.x, params.width, screenWidth())
                == RearViewTouchModel.Dock.LEFT) {
            // 往左藏，露出来的是窗口的右边那一条
            left = Math.max(0f, width - PEEK_WIDTH_PX);
        }
        float right = Math.min(width, left + PEEK_WIDTH_PX);
        scrimPaint.setColor(DOCK_BACKGROUND);
        canvas.drawRect(left, 0, right, height, scrimPaint);

        // 亮边画在朝向桌面的那一侧，不是贴屏幕外沿那一侧 ——
        // 外沿紧挨着屏幕边框，画了也看不见；要划清界限的是它和桌面相接的这条边。
        // 深色底对付亮壁纸，这条橙边对付深色壁纸，两头都不会糊
        float edge = DOCK_EDGE_DP * getResources().getDisplayMetrics().density;
        scrimPaint.setColor(androidx.core.content.ContextCompat.getColor(
                getContext(), R.color.energy));
        if (left > 0) {
            // 往左藏：露出的是窗口右边那条，朝向桌面的是它的右沿
            canvas.drawRect(right - edge, 0, right, height, scrimPaint);
        } else {
            canvas.drawRect(left, 0, left + edge, height, scrimPaint);
        }

        String label = getContext().getString(R.string.mirror_dock_label);
        if (label.isEmpty() || height <= 0) {
            return;
        }
        float centerX = (left + right) / 2f;
        labelPaint.setColor(DOCK_TEXT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        // 整体转 90 度，不逐字竖排 —— 这一条永远是那串英文，拆开竖排既难读也难看
        // 字号先由窄边的宽度定（0.62 的高度加上上下留白，正好把 72px 用满），
        // 窗口很矮时再由高度收 —— 转了 90 度之后，整串字的长度是沿着窗口高度排的
        labelPaint.setTextSize(Math.max(1f,
                Math.min(PEEK_WIDTH_PX * 0.62f, height * 1.7f / label.length())));
        int save = canvas.save();
        canvas.rotate(90f, centerX, height / 2f);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        canvas.drawText(label, centerX, height / 2f - (fm.ascent + fm.descent) / 2f, labelPaint);
        canvas.restoreToCount(save);
    }

    /**
     * 画面停了就把它盖住，换成一句「点击恢复」。
     *
     * <p>这不是省事，是安全：TextureView 会一直留着最后一帧，于是相机断了之后
     * 窗口里仍然是一幅<b>看起来像实时</b>的路面。盯着一张过时的后视镜画面变道，
     * 比看见一块黑屏危险得多 —— 黑屏至少能看出它坏了。</p>
     */
    private void drawFrozenHint(Canvas canvas, int width, int height) {
        scrimPaint.setColor(FROZEN_SCRIM);
        canvas.drawRect(0, 0, width, height, scrimPaint);
        String hint = getContext().getString(R.string.mirror_paused_tap);
        if (hint.isEmpty()) {
            return;
        }
        labelPaint.setColor(FROZEN_TEXT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Math.max(1f,
                Math.min(height * 0.17f, width / (hint.length() * 0.56f))));
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        canvas.drawText(hint, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, labelPaint);
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * 带鱼眼校正的绘制：把输出切成小格，逐格反投影。
     *
     * <p>校正是非线性的，一个矩阵表达不了整幅画面 —— 但一小格之内，用四个角
     * 定出的映射已经足够接近。于是每格用 {@code setPolyToPoly} 走一次线性映射，
     * 格子够密，拼起来看不出接缝。</p>
     *
     * <p>关键在于这样做<b>不需要 OpenGL</b>：画的还是原来那个 TextureView，
     * 相机的消费者始终只有它一个。这台车机上用 GL 自建 SurfaceTexture 顶替
     * 相机生产者是已知会崩的（见 {@link CompositeStreamGeometry} 的平台记录），
     * 而校正本身并不值得去冒那个险。</p>
     *
     * <p>代价是每帧 {@code N²} 次绘制。后视镜是一块小窗口，这个量级扛得住。</p>
     */
    private void drawCorrected(Canvas canvas, int width, int height,
                               RearViewGeometry.Viewport viewport) {
        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(plan, laneIndex, viewport);
        int divisions = FisheyeProjection.MESH_DIVISIONS;
        long drawingTime = getDrawingTime();

        for (int row = 0; row < divisions; row++) {
            float v0 = (float) row / divisions;
            float v1 = (float) (row + 1) / divisions;
            for (int column = 0; column < divisions; column++) {
                float u0 = (float) column / divisions;
                float u1 = (float) (column + 1) / divisions;

                // 目标：这一格在窗口里的四个角，顺序为左上、右上、右下、左下
                meshDest[0] = u0 * width; meshDest[1] = v0 * height;
                meshDest[2] = u1 * width; meshDest[3] = v0 * height;
                meshDest[4] = u1 * width; meshDest[5] = v1 * height;
                meshDest[6] = u0 * width; meshDest[7] = v1 * height;

                // 源：同样四个角，逐个经「取景 -> 校正 -> 该路在合成流里的位置」换算
                sourceCorner(r, u0, v0, width, height, 0);
                sourceCorner(r, u1, v0, width, height, 2);
                sourceCorner(r, u1, v1, width, height, 4);
                sourceCorner(r, u0, v1, width, height, 6);

                int save = canvas.save();
                destRect.set(meshDest[0], meshDest[1], meshDest[4], meshDest[5]);
                canvas.clipRect(destRect);
                drawMatrix.reset();
                if (drawMatrix.setPolyToPoly(meshSource, 0, meshDest, 0, 4)) {
                    canvas.concat(drawMatrix);
                    drawChild(canvas, textureView, drawingTime);
                }
                canvas.restoreToCount(save);
            }
        }
    }

    /**
     * 窗口里的一个角 → 子视图坐标系里的采样点。
     *
     * <p>三步走，顺序不能换：先按蒙版落到「校正后画面」里，再反投影回原始鱼眼画面，
     * 最后加上这一路在合成流里的偏移。蒙版之所以作用在校正之后，是因为用户是对着
     * 校正后的成像取景的 —— 框住的就该是他看到的那一块。</p>
     */
    private void sourceCorner(RearViewGeometry.ShaderRects r, float u, float v,
                              int width, int height, int offset) {
        float correctedX = r.viewOffsetX + u * r.viewScaleX;
        float correctedY = r.viewOffsetY + v * r.viewScaleY;
        FisheyeProjection.sourcePoint(correctedX, correctedY, fovDegrees, meshSource, offset);
        meshSource[offset] = (r.laneOffsetX + meshSource[offset] * r.laneScaleX) * width;
        meshSource[offset + 1] = (r.laneOffsetY + meshSource[offset + 1] * r.laneScaleY) * height;
    }

    /** 设置页改了校正开关或视野后，推到正在显示的窗口。 */
    public void applyCorrectionFromConfig() {
        fisheyeCorrection = appConfig.isRearViewFisheyeCorrection();
        fovDegrees = appConfig.getRearViewFov();
        // 两种模式下视野角度都会改变取景，所以无论开关如何都要重画
        AppLog.i(TAG, "鱼眼校正 " + (fisheyeCorrection ? "开，视野 " + fovDegrees + "°" : "关"));
        invalidate();
    }
}
