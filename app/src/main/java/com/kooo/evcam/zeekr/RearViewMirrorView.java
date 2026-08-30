package com.kooo.evcam.zeekr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
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
 * 中间三分之一上下滑调整取景范围（窗口不动）。双指缩放窗口大小，推出屏幕一半或朝边上甩一下即贴边隐藏。</p>
 *
 * <p>画面做<b>左右镜像</b>，和真正的后视镜一致；预览、录制、回放都不受影响。</p>
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

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;

    private WindowManager.LayoutParams params;
    private boolean attached;

    /** 合成流的真实尺寸决定拆分几何，不能用缓冲区尺寸（HAL 可能给个压扁的提示值）。 */
    private CompositeStreamGeometry.Plan plan;
    private int laneIndex = RearViewGeometry.REAR_CELL;
    private RearViewGeometry.Crop crop;

    // 手势状态
    private RearViewTouchModel.Zone activeZone = RearViewTouchModel.Zone.MOVE_WINDOW;
    private float touchStartX;
    private float touchStartY;
    private int windowStartX;
    private int windowStartY;
    private RearViewGeometry.Crop cropAtTouchStart;
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
    /** 取景调整模式：显示整路画面 + 蒙版框，手势含义与平时不同。 */
    private boolean framingMode;
    private long touchDownAtMs;
    private boolean pinching;
    private float pinchStartSpan;
    private int pinchStartWidth;
    private int pinchStartHeight;

    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RearViewMirrorView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.crop = appConfig.getRearViewCrop();
        this.minFlingVelocity =
                ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        this.fisheyeCorrection = appConfig.isRearViewFisheyeCorrection();
        this.fovDegrees = appConfig.getRearViewFov();

        setBackgroundColor(0xFF000000);

        textureView = new AutoFitTextureView(context);
        addView(textureView);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(4f);
        framePaint.setColor(0xFFFF3B30);
        dimPaint.setColor(0x99000000);
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

    /** 四宫格排列可能被用户调过，后方是哪一路要跟着走。 */
    public void setLaneOrder(int[] laneOrder) {
        laneIndex = RearViewGeometry.rearLaneIndex(laneOrder);
        invalidate();
    }

    // ------------------------------------------------------------------ 窗口

    public void show() {
        if (attached) {
            return;
        }
        int width = appConfig.getRearViewWidth();
        int height = appConfig.getRearViewHeight();
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
     * 进出取景调整模式。
     *
     * <p>进入时显示整路画面并画出蒙版框，退出时把框存下来。手势含义随之改变，
     * 这正是把它做成「模式」而不是又一种手势的原因 —— 同一块小窗上再塞第四种手势，
     * 用起来会互相打架。</p>
     */
    public void setFramingMode(boolean on) {
        if (framingMode == on) {
            return;
        }
        framingMode = on;
        if (!on) {
            appConfig.setRearViewCrop(crop);
        }
        AppLog.i(TAG, on ? "进入取景调整模式（拖动移动框、双指缩放框、点一下确认）"
                : "退出取景调整模式，取景已保存");
        invalidate();
    }

    public boolean isFramingMode() {
        return framingMode;
    }

    /** 设置页改了尺寸后，直接套用到正在显示的窗口上。 */
    public void applySizeFromConfig() {
        if (params == null) {
            return;
        }
        params.width = appConfig.getRearViewWidth();
        params.height = appConfig.getRearViewHeight();
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

        // 取景模式下显示整路画面，蒙版画在上面 —— 要框的东西必须看得见，
        // 否则等于蒙着眼睛调整取景。
        float[] rect = framingMode
                ? RearViewGeometry.combinedSourceRect(plan, laneIndex,
                        RearViewGeometry.Crop.full())
                : RearViewGeometry.combinedSourceRect(plan, laneIndex, crop);

        // 左右镜像。后视镜照出来的本来就是反的 —— 看到有车从画面右侧靠近，
        // 手就该往左让，这个对应关系是开车时的肌肉记忆，不镜像会反过来。
        //
        // 只作用于这个悬浮窗：预览、录制、回放拿到的都还是原始画面，
        // 镜像是「怎么看」的问题，不是「存什么」的问题。
        int mirrorSave = canvas.save();
        canvas.scale(-1f, 1f, width / 2f, height / 2f);

        if (fisheyeCorrection) {
            drawCorrected(canvas, width, height,
                    framingMode ? RearViewGeometry.Crop.full() : crop);
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

        // 取景框画在镜像之外：框是用来操作的，跟手的方向必须和手指一致，
        // 跟着画面一起翻会变成「往右拖、框往左走」。
        if (framingMode) {
            drawFramingOverlay(canvas, width, height);
        }
    }

    /**
     * 画出蒙版框，框外压暗。
     *
     * <p>压暗的是「会被裁掉」的部分，所以框里就是最终会显示的内容 ——
     * 不用想象，直接看到。</p>
     */
    private void drawFramingOverlay(Canvas canvas, int width, int height) {
        float left = crop.x * width;
        float top = crop.y * height;
        float right = (crop.x + crop.width) * width;
        float bottom = (crop.y + crop.height) * height;

        canvas.drawRect(0, 0, width, top, dimPaint);
        canvas.drawRect(0, bottom, width, height, dimPaint);
        canvas.drawRect(0, top, left, bottom, dimPaint);
        canvas.drawRect(right, top, width, bottom, dimPaint);
        canvas.drawRect(left, top, right, bottom, framePaint);
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
        touchDownAtMs = android.os.SystemClock.elapsedRealtime();
        activeZone = RearViewTouchModel.zoneFor(event.getX(), getWidth());
        touchStartX = event.getRawX();
        touchStartY = event.getRawY();
        windowStartX = params != null ? params.x : 0;
        windowStartY = params != null ? params.y : 0;
        cropAtTouchStart = crop;
        dragging = false;
        pinching = false;
    }

    private void beginPinch(MotionEvent event) {
        pinching = true;
        dragging = false;
        pinchStartSpan = spanOf(event);
        pinchStartWidth = params != null ? params.width : appConfig.getRearViewWidth();
        pinchStartHeight = params != null ? params.height : appConfig.getRearViewHeight();
    }

    private void updatePinch(MotionEvent event) {
        if (params == null || pinchStartSpan <= 0f) {
            return;
        }
        float span = spanOf(event);
        if (span <= 0f) {
            return;
        }

        if (framingMode) {
            // 取景模式：捏合改的是蒙版框的大小，不是窗口
            crop = cropAtTouchStart.scaledAboutCenter(pinchStartSpan / span);
            invalidate();
            return;
        }
        // 宽高一起缩，保持当前比例 —— 捏合是「放大缩小」，不该顺手改变形状
        float factor = span / pinchStartSpan;
        int width = RearViewTouchModel.scaledSize(pinchStartWidth, factor,
                AppConfig.REARVIEW_MIN_SIZE, AppConfig.REARVIEW_MAX_SIZE);
        int height = RearViewTouchModel.scaledSize(pinchStartHeight, factor,
                AppConfig.REARVIEW_MIN_SIZE, AppConfig.REARVIEW_MAX_SIZE);
        if (width == params.width && height == params.height) {
            return;
        }
        params.width = width;
        params.height = height;
        params.x = RearViewTouchModel.clampX(params.x, width, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(params.y, height, screenHeight());
        applyLayout();
    }

    private void updateDrag(MotionEvent event) {
        float dx = event.getRawX() - touchStartX;
        float dy = event.getRawY() - touchStartY;
        if (!dragging && Math.abs(dx) < DRAG_SLOP_PX && Math.abs(dy) < DRAG_SLOP_PX) {
            return;
        }
        dragging = true;

        if (framingMode) {
            // 取景模式：拖动整个蒙版框，横竖都动
            float dxNorm = dx / Math.max(1, getWidth());
            float dyNorm = dy / Math.max(1, getHeight());
            crop = new RearViewGeometry.Crop(
                    cropAtTouchStart.x + dxNorm, cropAtTouchStart.y + dyNorm,
                    cropAtTouchStart.width, cropAtTouchStart.height);
            invalidate();
            return;
        }

        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP) {
            // 中间三分之一：只调取景范围，窗口不动，左右方向也不变
            float shift = RearViewTouchModel.cropShiftForDrag(
                    dy, getHeight(), cropAtTouchStart.height);
            crop = cropAtTouchStart.shiftedVertically(shift);
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
        long heldMs = android.os.SystemClock.elapsedRealtime() - touchDownAtMs;

        if (!dragging && !pinching) {
            if (heldMs >= LONG_PRESS_MS) {
                // 长按进出取景模式
                setFramingMode(!framingMode);
            } else if (framingMode) {
                // 取景模式里点一下 = 确认
                setFramingMode(false);
            }
            return;
        }

        if (framingMode) {
            appConfig.setRearViewCrop(crop);
            invalidate();
            dragging = false;
            pinching = false;
            return;
        }

        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP && dragging) {
            appConfig.setRearViewCrop(crop);
        } else if (params != null && (dragging || pinching)) {
            // 只有「明显是想收起来」才贴边：推出去一半，或者朝边上甩一下。
            // 窗口还整个在屏幕里的话一定不贴边 —— 以前是按「离边多近」判断的，
            // 于是画面明明还完整可见就被吸走了。
            RearViewTouchModel.Dock dock = RearViewTouchModel.deliberateDock(
                    params.x, params.width, screenWidth(), velocityX, minFlingVelocity);
            if (dock != RearViewTouchModel.Dock.NONE) {
                params.x = RearViewTouchModel.dockedX(
                        dock, params.x, params.width, screenWidth(), PEEK_WIDTH_PX);
                applyLayout();
                AppLog.d(TAG, "后视镜已贴边: " + dock + "，露出 " + PEEK_WIDTH_PX + "px");
            }
            appConfig.setRearViewPosition(params.x, params.y);
            appConfig.setRearViewSize(params.width, params.height);
        }
        dragging = false;
        pinching = false;
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
                               RearViewGeometry.Crop effectiveCrop) {
        RearViewGeometry.ShaderRects r =
                RearViewGeometry.toShaderRects(plan, laneIndex, effectiveCrop);
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

                // 源：同样四个角，逐个经「蒙版 -> 校正 -> 该路在合成流里的位置」换算
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
        float correctedX = r.cropOffsetX + u * r.cropScaleX;
        float correctedY = r.cropOffsetY + v * r.cropScaleY;
        FisheyeProjection.sourcePoint(correctedX, correctedY, fovDegrees, meshSource, offset);
        meshSource[offset] = (r.laneOffsetX + meshSource[offset] * r.laneScaleX) * width;
        meshSource[offset + 1] = (r.laneOffsetY + meshSource[offset + 1] * r.laneScaleY) * height;
    }

    /** 设置页改了校正开关或视野后，推到正在显示的窗口。 */
    public void applyCorrectionFromConfig() {
        fisheyeCorrection = appConfig.isRearViewFisheyeCorrection();
        fovDegrees = appConfig.getRearViewFov();
        AppLog.i(TAG, "鱼眼校正 " + (fisheyeCorrection ? "开，视野 " + fovDegrees + "°" : "关"));
        invalidate();
    }
}
