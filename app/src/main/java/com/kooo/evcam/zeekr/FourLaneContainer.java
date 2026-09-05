package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

/**
 * 把一路四联合成流显示成 2x2 四宫格的容器。
 *
 * <h3>为什么是「重画子视图」而不是 OpenGL</h3>
 *
 * <p>直觉上更省事的做法，是自己在 GL 线程建一个外部纹理（OES）交给相机，
 * 再用着色器把四个画面渲染出来。<b>这条路不能走。</b>openavm-recorder 项目在真车上
 * 试过并公开记录了结果：用 GL 自建的 SurfaceTexture 顶替原本正常工作的生产者之后，
 * 预览会崩溃；他们最终改成「Camera2 只喂一个普通 TextureView，由父容器把这个子视图
 * 重复画进四个格子」，并在车上验证通过。</p>
 *
 * <p>本类采用的就是后者这套<b>已在真车验证过的结构</b>：</p>
 *
 * <pre>
 *   Camera2 ──写入──&gt; 一个<b>普通的</b> AutoFitTextureView（唯一的相机消费者，不被改造）
 *                              │
 *                              │ 父容器在 dispatchDraw 里把同一个子视图画 4 次，
 *                              │ 每次裁剪到一个格子、并把该画面的源矩形映射过去
 *                              ▼
 *                        2x2 四宫格
 * </pre>
 *
 * <p>关键点：相机链路完全没被动过。子视图就是一个标准 TextureView，
 * 上游 SingleCamera 拿到的是它自己的 SurfaceTexture，行为与其他车型一模一样。
 * 我们只改变「这个已经在正常工作的子视图怎么被画出来」。</p>
 *
 * <p>以上关于「GL 顶替生产者会崩、重画子视图可行」的结论来自 openavm-recorder 的
 * 公开源码注释，属于对该平台行为的事实性记录。本类为独立编写实现。特此致谢。</p>
 *
 * <h3>为什么用源尺寸而不是缓冲区尺寸算几何</h3>
 *
 * <p>车机 HAL 有时只声明一个较小的 Surface 提示尺寸（例如 640x480），但内部送来的
 * 仍是同一份四联合成内容，只是被压扁了。因此拆分几何必须按<b>合成流的真实尺寸</b>
 * （如 1280x5140）计算，得到归一化窗口后再套到子视图的实际绘制区域上。
 * 这样无论缓冲区多大，四个画面的位置和比例都正确。</p>
 */
public class FourLaneContainer extends ViewGroup {

    private static final String TAG = "FourLaneContainer";

    /** 每个画面在格子里的缩放方式。 */
    public enum ScaleMode {
        /** 保持画面原始比例，格子内留黑边。合成流画面是正方形，默认用这个。 */
        FIT,
        /** 填满格子，超出部分居中裁切。 */
        FILL
    }

    /** 显示模式。 */
    public enum DisplayMode {
        /** 2x2 四宫格。 */
        GRID,
        /** 只显示某一个画面，铺满整个容器。 */
        SINGLE,
        /** 不拆分，原样显示整条合成流。用于排查问题。 */
        RAW
    }

    /**
     * 一格怎么摆、怎么显示。
     *
     * <h3>为什么这里再定义一遍</h3>
     *
     * <p>配置里那份是 {@code profile.LaneLayout}。这个容器属于「怎么画」，
     * 不该反过来依赖「配置怎么存」—— 中间隔一层，配置的字段改名不会波及绘制。
     * 调用方负责翻译，就一行的事。</p>
     */
    public static final class Cell {
        /** 显示合成流里的哪一格。 */
        public int laneIndex;
        /** 在容器里的位置与大小，容器宽高的比例。 */
        public float x;
        public float y;
        public float width = 1f;
        public float height = 1f;
        /** 顺时针旋转 0/90/180/270。 */
        public int rotation;
        /** 左右镜像。 */
        public boolean mirrored;
        /** 四边各裁掉多少，这一格画面宽高的比例。 */
        public float cropTop;
        public float cropBottom;
        public float cropLeft;
        public float cropRight;
        /** 画面在格子里的缩放与平移。 */
        public float scaleX = 1f;
        public float scaleY = 1f;
        public float translateX;
        public float translateY;
    }

    /** 每一格的摆法；为空时退回 2×2 等分。 */
    private Cell[] cells;

    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destinationRect = new RectF();

    private AutoFitTextureView textureView;

    /** 合成流的真实尺寸（不是缓冲区尺寸）。 */
    private int sourceWidth;
    private int sourceHeight;
    private CompositeStreamGeometry.Plan plan;

    private ScaleMode scaleMode = ScaleMode.FIT;
    private DisplayMode displayMode = DisplayMode.GRID;
    private int focusedLane;
    /** laneOrder[格子位置] = 合成流中的画面序号。 */
    private int[] laneOrder = {0, 1, 2, 3};

    public FourLaneContainer(Context context) {
        this(context, null);
    }

    public FourLaneContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        // ViewGroup 默认不调用 onDraw，但我们要自己控制子视图的绘制
        setWillNotDraw(false);
        setClipChildren(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 布局里唯一的 TextureView 子视图就是相机的消费者
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof AutoFitTextureView) {
                textureView = (AutoFitTextureView) child;
                break;
            }
        }
        if (textureView == null) {
            AppLog.e(TAG, "布局里没有 AutoFitTextureView 子视图，四宫格无法工作");
        }
    }

    /** 相机预览用的 TextureView。上游相机管线直接用它，不要替换。 */
    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /**
     * 设置合成流的<b>真实</b>尺寸（来自 {@link ZeekrCameraLocator} 的探测结果）。
     *
     * <p>不要传预览缓冲区的尺寸——HAL 可能给一个压扁的小尺寸提示。
     * 传进来的尺寸如果不像合成流，会被忽略，以免把已经正确的几何降级掉。</p>
     */
    public void setSourceSize(Size size) {
        if (size != null) {
            setSourceSize(size.getWidth(), size.getHeight());
        }
    }

    /** @see #setSourceSize(Size) */
    public void setSourceSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!CompositeStreamGeometry.looksLikeComposite(StreamLayoutTable.compositeCameraId(), width, height)) {
            AppLog.d(TAG, "忽略非合成流尺寸 " + width + "x" + height + "（可能是 HAL 的小尺寸提示）");
            return;
        }
        if (width == sourceWidth && height == sourceHeight) {
            return;
        }
        sourceWidth = width;
        sourceHeight = height;
        rebuildPlan();
    }

    public void setScaleMode(ScaleMode mode) {
        if (mode != null && mode != scaleMode) {
            scaleMode = mode;
            invalidate();
        }
    }

    public ScaleMode getScaleMode() {
        return scaleMode;
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public int getFocusedLane() {
        return focusedLane;
    }

    /** 切到只看某一个画面。 */
    public void focusLane(int index) {
        if (index < 0 || index >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        focusedLane = index;
        displayMode = DisplayMode.SINGLE;
        invalidate();
    }

    /** 回到 2x2 四宫格。 */
    public void showGrid() {
        displayMode = DisplayMode.GRID;
        invalidate();
    }

    /** 不拆分，原样显示整条合成流（排查用）。 */
    public void showRaw() {
        displayMode = DisplayMode.RAW;
        invalidate();
    }

    /**
     * 调整画面在四宫格中的排列。
     *
     * @param order 长度为 4 的数组，order[格子位置] = 合成流中的画面序号
     */
    public void setLaneOrder(int[] order) {
        if (order == null || order.length != CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        boolean[] seen = new boolean[CompositeStreamGeometry.LANE_COUNT];
        for (int value : order) {
            if (value < 0 || value >= CompositeStreamGeometry.LANE_COUNT || seen[value]) {
                AppLog.w(TAG, "忽略非法的画面排列");
                return;
            }
            seen[value] = true;
        }
        laneOrder = order.clone();
        invalidate();
    }

    public int[] getLaneOrder() {
        return laneOrder.clone();
    }

    /**
     * 每一格摆在哪、怎么显示。
     *
     * <p>传 null 或空数组就退回 2×2 等分 —— 这也是没有配置时的样子，
     * 和这个功能存在之前完全一致。</p>
     */
    public void setCells(Cell[] value) {
        cells = value == null || value.length == 0 ? null : value.clone();
        invalidate();
    }

    /** 当前是否真的按四联合成流在拆分显示。 */
    public boolean isCompositeActive() {
        return plan != null && plan.isComposite() && displayMode != DisplayMode.RAW;
    }

    /** 当前拆分结果的可读描述，用于界面上的诊断信息。 */
    public String describePlan() {
        return plan == null ? "尚未获得合成流尺寸" : plan.toString();
    }

    private void rebuildPlan() {
        if (sourceWidth > 0 && sourceHeight > 0) {
            plan = CompositeStreamGeometry.analyse(
                    StreamLayoutTable.compositeCameraId(), sourceWidth, sourceHeight);
            AppLog.i(TAG, "合成流拆分方案: " + plan);
        } else {
            plan = null;
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // 布局与绘制
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        // 子视图铺满容器：合成流整帧被拉伸到这块区域，
        // 之后按归一化窗口取每个画面，比例由绘制阶段还原
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.measure(childWidth, childHeight);
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                child.layout(0, 0, width, height);
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        CompositeStreamGeometry.Plan current = plan;
        if (textureView == null || current == null || !current.isComposite()
                || displayMode == DisplayMode.RAW) {
            // 尺寸未知、不是合成流，或用户选了原样显示：走默认绘制
            super.dispatchDraw(canvas);
            return;
        }

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        if (displayMode == DisplayMode.SINGLE) {
            int index = Math.min(focusedLane, current.laneCount() - 1);
            drawLane(canvas, current.lane(index), cellFor(index), 0f, 0f, width, height);
            return;
        }

        Cell[] activeCells = cells;
        if (activeCells != null) {
            for (Cell cell : activeCells) {
                if (cell == null || cell.laneIndex < 0
                        || cell.laneIndex >= current.laneCount()) {
                    continue;
                }
                drawLane(canvas, current.lane(cell.laneIndex), cell,
                        cell.x * width, cell.y * height,
                        cell.width * width, cell.height * height);
            }
            return;
        }

        // 没有配置时的样子：2×2 等分
        float cellWidth = width / 2f;
        float cellHeight = height / 2f;
        for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
            int laneIndex = laneOrder[cell];
            if (laneIndex >= current.laneCount()) {
                continue;
            }
            float left = (cell % 2) * cellWidth;
            float top = (cell / 2) * cellHeight;
            drawLane(canvas, current.lane(laneIndex), null, left, top, cellWidth, cellHeight);
        }
    }

    /**
     * 裁切与缩放平移都作用在<b>源矩形</b>上，返回裁切之后这一格的真实长宽比。
     *
     * <h3>为什么动源矩形而不是目标矩形</h3>
     *
     * <p>目标矩形是「这一格在屏幕上占多大」，那是布局说了算的。要表达「这幅画面
     * 少看一点边、或者放大一点」，动的是<b>取画面的哪一块</b> —— 也就是源矩形。
     * 放大 2 倍就是只取中间一半，向右平移就是把取景窗往左挪。</p>
     */
    private float applyCropAndPan(Cell cell, float laneAspectPx) {
        float keepX = 1f - clampFraction(cell.cropLeft) - clampFraction(cell.cropRight);
        float keepY = 1f - clampFraction(cell.cropTop) - clampFraction(cell.cropBottom);
        if (keepX <= 0.01f || keepY <= 0.01f) {
            return laneAspectPx;   // 全裁光了，当作没裁
        }
        float width = sourceRect.width();
        float height = sourceRect.height();
        float left = sourceRect.left + width * clampFraction(cell.cropLeft);
        float top = sourceRect.top + height * clampFraction(cell.cropTop);
        sourceRect.set(left, top, left + width * keepX, top + height * keepY);

        float scaleX = cell.scaleX > 0.05f ? cell.scaleX : 1f;
        float scaleY = cell.scaleY > 0.05f ? cell.scaleY : 1f;
        if (scaleX != 1f || scaleY != 1f) {
            float cx = sourceRect.centerX();
            float cy = sourceRect.centerY();
            float halfW = sourceRect.width() / 2f / scaleX;
            float halfH = sourceRect.height() / 2f / scaleY;
            sourceRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        }
        if (cell.translateX != 0f || cell.translateY != 0f) {
            // 画面往右挪 = 取景窗往左挪
            sourceRect.offset(-cell.translateX * sourceRect.width(),
                    -cell.translateY * sourceRect.height());
        }
        return laneAspectPx * (keepX / keepY);
    }

    private static float clampFraction(float value) {
        return value < 0f ? 0f : (value > 0.9f ? 0.9f : value);
    }

    /** 这一格的摆法；没有配置就返回 null（走默认）。 */
    private Cell cellFor(int laneIndex) {
        if (cells == null) {
            return null;
        }
        for (Cell cell : cells) {
            if (cell != null && cell.laneIndex == laneIndex) {
                return cell;
            }
        }
        return null;
    }

    /**
     * 把合成流里的一个画面画进一个矩形区域。
     *
     * <p>做法是裁剪到目标格子，再用一个矩阵把该画面在子视图中的源矩形映射过去，
     * 然后把<b>同一个</b>子视图重新画一遍。子视图本身不知道自己被画了几次。</p>
     */
    private void drawLane(Canvas canvas, CompositeStreamGeometry.Lane lane, Cell cell,
                          float cellLeft, float cellTop, float cellWidth, float cellHeight) {
        if (cellWidth <= 0f || cellHeight <= 0f) {
            return;
        }
        // 画面在子视图坐标系中的位置：归一化窗口 x 子视图尺寸。
        // 用归一化坐标是关键——HAL 给的缓冲区可能被压扁，但比例关系不变。
        float childWidth = getWidth();
        float childHeight = getHeight();
        sourceRect.set(
                lane.u0 * childWidth,
                lane.v0 * childHeight,
                lane.u1 * childWidth,
                lane.v1 * childHeight);
        if (sourceRect.width() <= 0f || sourceRect.height() <= 0f) {
            return;
        }

        // 这一格的真实长宽比。裁切会改变它（切掉的是画面的一部分），
        // 缩放平移不会（那只是把同一幅画面挪一挪、放大一点）。
        float laneAspectPx = lane.aspect();
        if (cell != null) {
            laneAspectPx = applyCropAndPan(cell, laneAspectPx);
        }
        int rotation = cell == null ? 0 : ((cell.rotation % 360) + 360) % 360;
        boolean quarterTurn = rotation == 90 || rotation == 270;

        float destLeft = cellLeft;
        float destTop = cellTop;
        float destWidth = cellWidth;
        float destHeight = cellHeight;

        // 画面的真实比例来自源像素（合成流里是正方形），不是被压扁的缓冲区比例。
        // 转了 90°/270° 的话，占地的长宽也跟着对调。
        float laneAspect = quarterTurn && laneAspectPx > 0f ? 1f / laneAspectPx : laneAspectPx;
        float cellAspect = cellWidth / cellHeight;
        if (scaleMode == ScaleMode.FIT && laneAspect > 0f && cellAspect > 0f) {
            if (laneAspect < cellAspect) {
                destWidth = cellHeight * laneAspect;
                destLeft = cellLeft + (cellWidth - destWidth) / 2f;
            } else if (laneAspect > cellAspect) {
                destHeight = cellWidth / laneAspect;
                destTop = cellTop + (cellHeight - destHeight) / 2f;
            }
        } else if (scaleMode == ScaleMode.FILL && laneAspect > 0f && cellAspect > 0f) {
            // 填满：反过来收窄源矩形，居中裁切
            if (laneAspect < cellAspect) {
                float keep = laneAspect / cellAspect;
                float centre = sourceRect.centerY();
                float half = sourceRect.height() / 2f * keep;
                sourceRect.top = centre - half;
                sourceRect.bottom = centre + half;
            } else if (laneAspect > cellAspect) {
                float keep = cellAspect / laneAspect;
                float centre = sourceRect.centerX();
                float half = sourceRect.width() / 2f * keep;
                sourceRect.left = centre - half;
                sourceRect.right = centre + half;
            }
        }

        destinationRect.set(destLeft, destTop, destLeft + destWidth, destTop + destHeight);
        if (quarterTurn) {
            // 转四分之一圈时，先按「转之前」的形状去映射：
            // 目标框的长宽在旋转后才互换，所以这里要用互换回来的那个框
            float cx = destinationRect.centerX();
            float cy = destinationRect.centerY();
            float halfW = destinationRect.height() / 2f;
            float halfH = destinationRect.width() / 2f;
            destinationRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        }
        drawMatrix.setRectToRect(sourceRect, destinationRect, Matrix.ScaleToFit.FILL);
        if (cell != null) {
            float cx = destinationRect.centerX();
            float cy = destinationRect.centerY();
            if (rotation != 0) {
                drawMatrix.postRotate(rotation, cx, cy);
            }
            if (cell.mirrored) {
                // 左右镜像：后视看到的本来就是反的
                drawMatrix.postScale(-1f, 1f, cx, cy);
            }
        }

        int save = canvas.save();
        // 先裁到格子，避免子视图的其他部分溢出到相邻格子
        canvas.clipRect(cellLeft, cellTop, cellLeft + cellWidth, cellTop + cellHeight);
        canvas.concat(drawMatrix);
        drawChild(canvas, textureView, getDrawingTime());
        canvas.restoreToCount(save);
    }
}
