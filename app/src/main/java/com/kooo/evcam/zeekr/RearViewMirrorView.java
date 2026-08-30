package com.kooo.evcam.zeekr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
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
    private boolean dragging;
    /** 松手时要知道甩得多快，用系统自带的这个就够，不必自己算。 */
    private VelocityTracker velocityTracker;
    /** 平台认定的最小甩动速度，手感与其他应用一致。 */
    private final int minFlingVelocity;
    private boolean pinching;
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
        if (!CompositeStreamGeometry.looksLikeComposite(size.getWidth(), size.getHeight())) {
            AppLog.d(TAG, "忽略非合成流尺寸 " + size + "（多半是 HAL 的小尺寸提示）");
            return;
        }
        plan = CompositeStreamGeometry.analyse(size.getWidth(), size.getHeight());
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
        } catch (Exception e) {
            AppLog.e(TAG, "后视镜窗口添加失败", e);
        }
    }

    public void hide() {
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
        if (plan == null || !plan.isComposite()) {
            // 还不知道几何，先原样显示，总比全黑好
            super.dispatchDraw(canvas);
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
                if (event.getPointerCount() <= 2) {
                    pinching = false;
                }
                return true;

            case MotionEvent.ACTION_UP:
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
    }

    private void beginPinch(MotionEvent event) {
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
                AppConfig.REARVIEW_MIN_SIZE, screenWidth(), screenHeight());

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
                RearViewTouchModel.Dock docked = RearViewTouchModel.dockedAt(
                        params.x, params.width, screenWidth());
                if (docked != RearViewTouchModel.Dock.NONE) {
                    glideTo(RearViewTouchModel.flushX(docked, params.width, screenWidth()), 0f);
                }
            }
            return;
        }

        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP && dragging) {
            if (horizontalDrag != null && horizontalDrag) {
                if (RearViewTouchModel.isDeliberateSwipe(
                        lastDx, velocityX, LANE_SWIPE_MIN_PX, minFlingVelocity)) {
                    // 右滑走顺时针（后 左 前 右），左滑走逆时针（后 右 前 左）
                    switchLane(lastDx > 0f);
                }
            } else {
                appConfig.setRearViewPan(pan);
            }
        } else if (params != null && (dragging || pinching)) {
            settleAfterDrag(velocityX);
        }
        dragging = false;
        pinching = false;
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
