package com.kooo.evcam.zeekr;

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
import android.view.View;
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
 * 中间三分之一上下滑调整取景范围（窗口不动）。双指缩放窗口大小，拖到边缘自动贴边。</p>
 */
public class RearViewMirrorView extends ViewGroup {

    private static final String TAG = "RearViewMirror";

    /** 贴边后仍然露出的宽度，用来把它再拖回来。 */
    private static final int PEEK_WIDTH_PX = 72;
    /**
     * 距离屏幕边缘多少像素以内算贴边。
     *
     * <p><b>必须大于 {@link #PEEK_WIDTH_PX}</b>，否则这个条件永远不成立：
     * 拖动时 {@code clampX} 已经把 x 限制在最多 {@code screenW - PEEK}，
     * 也就是右边缘最近只能到距屏幕边 PEEK 像素处。阈值比 PEEK 小的话，
     * 窗口再怎么往边上拖也进不了判定范围 —— 第一版就是这么写的，
     * 所以贴边隐藏一次都没触发过。</p>
     */
    private static final int DOCK_THRESHOLD_PX = PEEK_WIDTH_PX + 80;
    /** 超过这个位移才算拖动，避免点一下就漂移。 */
    private static final int DRAG_SLOP_PX = 12;

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
    private boolean dragging;
    private boolean pinching;
    private float pinchStartSpan;
    private int pinchStartSize;

    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();

    public RearViewMirrorView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.crop = appConfig.getRearViewCrop();

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
        int size = appConfig.getRearViewSize();
        params = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        int savedX = appConfig.getRearViewX();
        int savedY = appConfig.getRearViewY();
        if (savedX < 0 || savedY < 0) {
            // 默认落在右上角，和车内后视镜的位置感一致
            savedX = screenWidth() - size - 40;
            savedY = 40;
        }
        params.x = RearViewTouchModel.clampX(savedX, size, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(savedY, size, screenHeight());

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

        float[] rect = RearViewGeometry.combinedSourceRect(plan, laneIndex, crop);

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

    // ------------------------------------------------------------------ 手势

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTouch(event);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    beginPinch(event);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
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
                endTouch();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void beginTouch(MotionEvent event) {
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
        pinchStartSize = params != null ? params.width : appConfig.getRearViewSize();
    }

    private void updatePinch(MotionEvent event) {
        if (params == null || pinchStartSpan <= 0f) {
            return;
        }
        float span = spanOf(event);
        if (span <= 0f) {
            return;
        }
        int size = RearViewTouchModel.scaledSize(
                pinchStartSize, span / pinchStartSpan,
                AppConfig.REARVIEW_MIN_SIZE, AppConfig.REARVIEW_MAX_SIZE);
        if (size == params.width) {
            return;
        }
        params.width = size;
        params.height = size;
        params.x = RearViewTouchModel.clampX(params.x, size, screenWidth(), PEEK_WIDTH_PX);
        params.y = RearViewTouchModel.clampY(params.y, size, screenHeight());
        applyLayout();
    }

    private void updateDrag(MotionEvent event) {
        float dx = event.getRawX() - touchStartX;
        float dy = event.getRawY() - touchStartY;
        if (!dragging && Math.abs(dx) < DRAG_SLOP_PX && Math.abs(dy) < DRAG_SLOP_PX) {
            return;
        }
        dragging = true;

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

    private void endTouch() {
        if (activeZone == RearViewTouchModel.Zone.ADJUST_CROP && dragging) {
            appConfig.setRearViewCrop(crop);
        } else if (params != null && (dragging || pinching)) {
            // 松手时如果贴到了边缘，就把它吸过去
            RearViewTouchModel.Dock dock = RearViewTouchModel.dockFor(
                    params.x, params.width, screenWidth(), DOCK_THRESHOLD_PX);
            if (dock != RearViewTouchModel.Dock.NONE) {
                params.x = RearViewTouchModel.dockedX(
                        dock, params.x, params.width, screenWidth(), PEEK_WIDTH_PX);
                applyLayout();
                AppLog.d(TAG, "后视镜已贴边: " + dock + "，露出 " + PEEK_WIDTH_PX + "px");
            }
            appConfig.setRearViewPosition(params.x, params.y);
            appConfig.setRearViewSize(params.width);
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

    // ------------------------------------------------------------------
    // 鱼眼校正尚未接入。
    //
    // 每一路都是鱼眼镜头拍的，畸变很大，理应先校正再取景 —— 但校正是非线性的，
    // 矩阵做不了，必须上着色器。而这台车机上「GL 顶替相机生产者」是已知会崩的做法，
    // 现有的 FisheyeCorrector 走的正是那条路，且从来没有在这台车上跑过。
    //
    // 所以先把纯裁切这条安全路径做通、在车上验证，再单独处理校正，
    // 而不是把两个不确定性绑在一起上车。
    // ------------------------------------------------------------------
}
