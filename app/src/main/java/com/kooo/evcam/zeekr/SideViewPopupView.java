package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

/**
 * 打转向灯弹出的侧视窗：只显示左或右那一路，不能拖、不能点。
 *
 * <p>画法和超级后视镜一样（见 {@link RearViewMirrorView} 的类说明）：子视图是普通 TextureView，
 * 本容器在 {@code dispatchDraw} 里按 {@link RearViewGeometry#combinedSourceRect} 把那一路放大重画，
 * 不新建 GL 管线；「拉直」开着时走同一套分片反投影（{@link FisheyeMesh}），
 * 只是虚拟相机先转了个角度（{@link SideViewProjection}）。侧视不镜像。</p>
 *
 * <h3>三种状态</h3>
 *
 * <ul>
 *   <li>没挂：没有窗口，不占相机。</li>
 *   <li><b>备着</b>（{@link #attachHidden}）：窗口挂着但完全透明（窗口 alpha 0），相机照常往里推帧 ——
 *       打灯时只要把 alpha 拨回 1，不用重建会话。<b>不用 INVISIBLE</b>：TextureView 不画就不取帧，
 *       共享流上一个不取帧的消费者可能把整条流（包括录像）拖住。</li>
 *   <li>显示：alpha 1，在对应那一边。</li>
 * </ul>
 *
 * <p>位置：屏幕中线是两边的分界，左侧那一路的右边缘贴中线，右侧那一路的左边缘贴中线；
 * 大小和上下位置在设置里调。{@code FLAG_NOT_TOUCHABLE}：点击穿过去，不挡底下的导航。</p>
 */
public class SideViewPopupView extends ViewGroup {

    private static final String TAG = "SideViewPopup";

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;
    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;

    private WindowManager.LayoutParams params;
    private boolean attached;
    private boolean visible;
    private CompositeStreamGeometry.Plan plan;
    private int lane = LaneCycle.LEFT;
    private boolean straighten;
    private int viewFov;
    private int yaw;
    private int pitch;
    private final FisheyeMesh.SourceMap turnedMap = (u, v, out) ->
            SideViewProjection.sourcePoint(u, v, viewFov, yaw, pitch, lane, out, 0);

    public SideViewPopupView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        readConfig();
    }

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
        AppLog.i(TAG, "侧视取景: " + plan + " 第 " + lane + " 路");
        invalidate();
    }

    /** 知不知道合成流的几何；不知道时画面是黑的。 */
    public boolean hasGeometry() {
        return plan != null && plan.isComposite();
    }

    /** 窗口挂着（备着或显示中）。 */
    public boolean isAttached() {
        return attached;
    }

    /** 正显示在屏幕上。 */
    public boolean isShowing() {
        return attached && visible;
    }

    public int lane() {
        return lane;
    }

    // ------------------------------------------------------------------ 窗口

    /** 挂上但不显示：给相机一个推帧的地方，打灯时马上能出画面。 */
    public void attachHidden(int side) {
        attachAt(side, false);
    }

    /** 显示 {@code side} 那一路；没挂就挂上，备着就拨亮，显示着就换边。 */
    public void show(int side) {
        attachAt(side, true);
    }

    /** 收起来但窗口留着、相机照推（备着）。 */
    public void conceal() {
        if (!attached || !visible) {
            return;
        }
        visible = false;
        applyVisibility(false);
        update();
    }

    /** 整个拿掉。 */
    public void detach() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "侧视窗移除失败: " + e);
        }
        attached = false;
        visible = false;
    }

    /** 设置页改了大小 / 位置 / 拉直：套用到挂着的窗口上。 */
    public void applyConfig() {
        readConfig();
        if (attached) {
            place(lane);
            update();
        }
        invalidate();
    }

    private void readConfig() {
        straighten = appConfig.isSidePopupStraighten();
        viewFov = appConfig.getSidePopupFov();
        yaw = appConfig.getSidePopupYaw();
        pitch = appConfig.getSidePopupPitch();
    }

    private void attachAt(int side, boolean show) {
        boolean sideChanged = side != lane;
        lane = side;
        if (attached) {
            boolean visibilityChanged = show != visible;
            visible = show;
            applyVisibility(show);
            if (sideChanged) {
                place(side);
            }
            if (sideChanged || visibilityChanged) {
                update();
            }
            if (sideChanged) {
                invalidate();
            }
            return;
        }
        params = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        applyVisibility(show);
        place(side);
        try {
            windowManager.addView(this, params);
            attached = true;
            visible = show;
        } catch (Exception e) {
            AppLog.e(TAG, "侧视窗添加失败", e);
        }
    }

    /**
     * 显示时不透明、接点击；备着时全透明、点击穿过去。
     *
     * <p><b>显示时不能带 FLAG_NOT_TOUCHABLE</b>：安卓 12 起，带这个标志的悬浮窗会被系统把不透明度
     * 压到 0.8（为了让点击能穿过去，b/218777508）—— PR 作者实车上看就是「有点透明」（2026-10-01）。
     * 代价是弹着的那一两秒里，窗口下面那块点不到。备着时 alpha 0，不受那条限制，照旧让点击穿过去，
     * 不然 D 档时屏幕上会有一块看不见又点不动的区域。</p>
     */
    private void applyVisibility(boolean show) {
        params.alpha = show ? 1f : 0f;
        if (show) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
    }

    /** 弹着时点在窗口上的手势吃掉，什么也不做：窗口不能拖、不能点。 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return true;
    }

    private void update() {
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "侧视窗更新失败: " + e);
        }
    }

    /**
     * 大小和位置。边长按屏幕高度的百分比，最多半个屏幕宽（两边在中线相接，不能超过一半）；
     * 上下位置是剩余高度里的百分比，0 贴顶、100 贴底。
     */
    private void place(int side) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int size = Math.round(Math.min(h * appConfig.getSidePopupSizePercent() / 100f, w / 2f));
        size = Math.max(AppConfig.REARVIEW_MIN_SIZE, size);
        int center = w / 2;
        params.width = size;
        params.height = size;
        params.x = side == LaneCycle.LEFT ? center - size : center;
        params.y = Math.round(Math.max(0, h - size) * appConfig.getSidePopupVerticalPercent() / 100f);
    }

    // ------------------------------------------------------------------ 布局与绘制

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
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
        if (!hasGeometry()) {
            // 还不知道几何：子视图照画（TextureView 不画就不取帧，onSurfaceTextureUpdated 也不来，
            // 服务就没机会补上尺寸 —— 窗口会一直黑），再盖成黑的，不露出四路挤在一起的整条合成流
            super.dispatchDraw(canvas);
            canvas.drawColor(0xFF000000);
            return;
        }
        int save = canvas.save();
        if (LaneCycle.isMirrored(lane)) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        if (straighten) {
            // 虚拟相机转过去（往后、往上）再拉直，见 SideViewProjection
            RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(
                    plan, lane, RearViewGeometry.Viewport.full());
            mesh.prepare(FisheyeProjection.MESH_DIVISIONS,
                    r.laneOffsetX * width, r.laneOffsetY * height,
                    r.laneScaleX * width, r.laneScaleY * height, turnedMap);
            mesh.draw(canvas, 0f, 0f, width, height, paintTexture);
        } else {
            // 不拉直：只能在原图里裁一块挪一挪（见 SideViewAim），角度折算成那边的百分比
            int zoomPercent = Math.round(180f / viewFov * 100f);
            int backPercent = Math.round(yaw / SideViewProjection.MAX_YAW_DEGREES * 100f);
            int upPercent = Math.round(pitch / SideViewProjection.MAX_PITCH_DEGREES * 100f);
            RearViewGeometry.Viewport viewport = SideViewAim.viewport(lane, zoomPercent, backPercent, upPercent);
            float[] rect = RearViewGeometry.combinedSourceRect(plan, lane, viewport);
            sourceRect.set(rect[0] * width, rect[1] * height,
                    (rect[0] + rect[2]) * width, (rect[1] + rect[3]) * height);
            destRect.set(0, 0, width, height);
            drawMatrix.setRectToRect(sourceRect, destRect, Matrix.ScaleToFit.FILL);
            canvas.clipRect(destRect);
            canvas.concat(drawMatrix);
            drawChild(canvas, textureView, getDrawingTime());
        }
        canvas.restoreToCount(save);
    }

    private void drawTextureOnce(Canvas canvas) {
        drawChild(canvas, textureView, getDrawingTime());
    }
}
