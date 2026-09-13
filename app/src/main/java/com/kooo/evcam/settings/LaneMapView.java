package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.profile.LaneLayout;

import java.util.Collections;
import java.util.List;

/**
 * 摆位舞台：每一格按它在配置里的位置和大小画，<b>直接拖、直接拉</b>。
 *
 * <h3>为什么是拖，不是填四个小数</h3>
 *
 * <p>位置和大小原来是一个弹框里的四个数：x 0.00、y 0.00、宽 0.50、高 0.50。
 * 那四个数没有一个是人心里想的东西 —— 想的是「把座舱那一格挪到右下角、再小一点」。
 * 拖拽是这件事唯一自然的手势：手指落在哪，格子就在哪。</p>
 *
 * <p>反过来说，帧率码率这类<b>有名字的档位</b>拖不出来，那些留在分段控件上。
 * 拖拽解决空间，控件解决档位，两者是补集。</p>
 *
 * <h3>吸附</h3>
 *
 * <p>拖到贴近整数、二分、四分，或者贴近另一格的边时会吸住，并画一条参考线。
 * 车机是触摸屏，手指精度到不了 0.01 —— 没有吸附的话，「看起来对齐了」和
 * 「真的对齐了」永远差那么一点点。</p>
 */
public class LaneMapView extends View {

    /** 点了哪一格（配置里 lanes 的下标）。 */
    public interface OnLaneTap {
        void onTap(int index);
    }

    /** 拖完了：这一格的位置或大小变了。 */
    public interface OnLaneMoved {
        void onMoved(int index);
    }

    private static final float ASPECT = 1.4f;

    /** 一格最小占多大。再小就点不着，也不像一路画面。 */
    private static final float MIN_SIZE = 0.12f;

    /** 吸附阈值，占整块舞台的比例。 */
    private static final float SNAP = 0.02f;

    /** 可以吸的那几条固定线：边、二分、四分。 */
    private static final float[] GUIDES = {0f, 0.25f, 0.5f, 0.75f, 1f};

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chosen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final RectF rect = new RectF();
    private final float density;

    private List<LaneLayout> lanes = Collections.emptyList();
    private int selected = -1;
    private OnLaneTap listener;
    private OnLaneMoved moveListener;
    private boolean editable;

    /** 正在拖的那一格；-1 表示没在拖。 */
    private int dragIndex = -1;
    /** -1 = 整格挪，0..3 = 拉四个角（左上、右上、左下、右下）。 */
    private int dragCorner = -1;
    private float grabX;
    private float grabY;
    private boolean moved;
    private float guideX = Float.NaN;
    private float guideY = Float.NaN;

    public LaneMapView(Context context) {
        this(context, null);
    }

    public LaneMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        frame.setColor(ContextCompat.getColor(context, R.color.preview_frame_background));
        cell.setColor(ContextCompat.getColor(context, R.color.surface));
        chosen.setColor(ContextCompat.getColor(context, R.color.energy_quiet));
        outline.setColor(ContextCompat.getColor(context, R.color.energy));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3f * density);
        handle.setColor(ContextCompat.getColor(context, R.color.surface));
        handleEdge.setColor(ContextCompat.getColor(context, R.color.energy));
        handleEdge.setStyle(Paint.Style.STROKE);
        handleEdge.setStrokeWidth(2.5f * density);
        guide.setColor(ContextCompat.getColor(context, R.color.energy));
        guide.setStrokeWidth(1.5f * density);
        name.setColor(ContextCompat.getColor(context, R.color.text_primary));
        name.setTextAlign(Paint.Align.CENTER);
        name.setTextSize(context.getResources().getDimension(R.dimen.text_body));
        note.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        note.setTextAlign(Paint.Align.CENTER);
        note.setTextSize(context.getResources().getDimension(R.dimen.text_micro));
        setClickable(true);
    }

    /** 画哪几格、选中哪一格、点了告诉谁。 */
    public void bind(List<LaneLayout> value, int selectedIndex, OnLaneTap onTap) {
        lanes = value == null ? Collections.emptyList() : value;
        selected = selectedIndex;
        listener = onTap;
        invalidate();
    }

    /** 能不能拖。拖完调 {@code onMoved}，让外面把那几个数和保存状态跟上。 */
    public void setEditable(boolean value, @Nullable OnLaneMoved onMoved) {
        editable = value;
        moveListener = onMoved;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.min(Math.round(width / ASPECT), Math.round(420 * density));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float pad = 2f * density;
        area.set(pad, pad, getWidth() - pad, getHeight() - pad);
        canvas.drawRoundRect(area, 8f * density, 8f * density, frame);
        for (int i = 0; i < lanes.size(); i++) {
            laneRect(lanes.get(i), rect);
            boolean isChosen = i == selected;
            canvas.drawRoundRect(rect, 6f * density, 6f * density, isChosen ? chosen : cell);
            if (isChosen) {
                canvas.drawRoundRect(rect, 6f * density, 6f * density, outline);
            }
            LaneLayout lane = lanes.get(i);
            String title = lane.laneIndex >= 0
                    ? CameraNames.ofLane(getContext(), lane.laneIndex)
                    : getContext().getString(R.string.editor_whole_frame);
            float centreY = rect.centerY();
            canvas.drawText(title, rect.centerX(), centreY, name);
            String detail = lane.rotation + "°"
                    + (lane.mirrored ? " · " + getContext().getString(R.string.editor_mirror) : "");
            canvas.drawText(detail, rect.centerX(), centreY + note.getTextSize() * 1.6f, note);
        }

        // 吸住了就画一条线出来，否则「吸没吸住」只能靠手感猜
        if (!Float.isNaN(guideX)) {
            float x = area.left + guideX * area.width();
            canvas.drawLine(x, area.top, x, area.bottom, guide);
        }
        if (!Float.isNaN(guideY)) {
            float y = area.top + guideY * area.height();
            canvas.drawLine(area.left, y, area.right, y, guide);
        }

        if (editable && selected >= 0 && selected < lanes.size()) {
            laneRect(lanes.get(selected), rect);
            float r = 9f * density;
            corner(canvas, rect.left, rect.top, r);
            corner(canvas, rect.right, rect.top, r);
            corner(canvas, rect.left, rect.bottom, r);
            corner(canvas, rect.right, rect.bottom, r);
        }
    }

    /** 四个角上的把手：白底橙圈，压在格子边上。 */
    private void corner(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, handle);
        canvas.drawCircle(x, y, radius, handleEdge);
    }

    /** 一格在这张图里的矩形；格子之间留 3dp 缝。 */
    private void laneRect(LaneLayout lane, RectF out) {
        float gap = 3f * density;
        float left = area.left + lane.x * area.width();
        float top = area.top + lane.y * area.height();
        out.set(left + gap, top + gap,
                left + lane.width * area.width() - gap,
                top + lane.height * area.height() - gap);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(event.getX(), event.getY());
            case MotionEvent.ACTION_MOVE:
                if (dragIndex >= 0) {
                    onDrag(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return onUp();
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean onDown(float x, float y) {
        guideX = Float.NaN;
        guideY = Float.NaN;
        moved = false;
        dragIndex = -1;
        dragCorner = -1;
        if (area.width() <= 0 || area.height() <= 0) {
            return false;
        }
        // 先看有没有落在选中那一格的角上 —— 角比格子小，得优先判
        if (editable && selected >= 0 && selected < lanes.size()) {
            laneRect(lanes.get(selected), rect);
            int corner = cornerAt(x, y, rect);
            if (corner >= 0) {
                dragIndex = selected;
                dragCorner = corner;
                holdTouch(true);
                return true;
            }
        }
        // 从后往前找：后画的盖在上面，点到的是看得见的那一格
        for (int i = lanes.size() - 1; i >= 0; i--) {
            laneRect(lanes.get(i), rect);
            if (rect.contains(x, y)) {
                if (i != selected && listener != null) {
                    listener.onTap(i);
                }
                if (editable) {
                    dragIndex = i;
                    LaneLayout lane = lanes.get(i);
                    grabX = (x - area.left) / area.width() - lane.x;
                    grabY = (y - area.top) / area.height() - lane.y;
                    holdTouch(true);
                }
                return true;
            }
        }
        // 空白处不接手，这样摸在图上还能滑动列表
        return false;
    }

    /**
     * 拖的时候把触摸抢住。
     *
     * <p>这张图在设置列表里。不抢的话，手指稍微往下一偷，
     * 列表就把这一串事件拿走去滚屏，格子拖到一半断在那里。</p>
     */
    private void holdTouch(boolean hold) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(hold);
        }
    }

    /** 手指落在哪个角上；不在任何角上返回 -1。 */
    private int cornerAt(float x, float y, RectF box) {
        float reach = 24f * density;
        float[][] corners = {
                {box.left, box.top}, {box.right, box.top},
                {box.left, box.bottom}, {box.right, box.bottom}};
        for (int i = 0; i < corners.length; i++) {
            if (Math.abs(x - corners[i][0]) <= reach && Math.abs(y - corners[i][1]) <= reach) {
                return i;
            }
        }
        return -1;
    }

    private void onDrag(float x, float y) {
        if (dragIndex >= lanes.size()) {
            // 拖到一半被重新 bind 了，而新的那份格子更少
            dragIndex = -1;
            return;
        }
        LaneLayout lane = lanes.get(dragIndex);
        float fx = (x - area.left) / area.width();
        float fy = (y - area.top) / area.height();
        guideX = Float.NaN;
        guideY = Float.NaN;
        if (dragCorner < 0) {
            moveTo(lane, fx - grabX, fy - grabY);
        } else {
            resizeTo(lane, fx, fy);
        }
        moved = true;
        invalidate();
    }

    private void moveTo(LaneLayout lane, float x, float y) {
        // 左边没吸住才试右边：挪到贴着另一格时，贴的往往是自己的右边
        float left = snap(x, lane, true);
        if (left == x) {
            left = snap(x + lane.width, lane, true) - lane.width;
        }
        float top = snap(y, lane, false);
        if (top == y) {
            top = snap(y + lane.height, lane, false) - lane.height;
        }
        lane.x = clamp(left, 0f, 1f - lane.width);
        lane.y = clamp(top, 0f, 1f - lane.height);
    }

    private void resizeTo(LaneLayout lane, float fx, float fy) {
        float left = lane.x;
        float top = lane.y;
        float right = lane.x + lane.width;
        float bottom = lane.y + lane.height;
        boolean west = dragCorner == 0 || dragCorner == 2;
        boolean north = dragCorner == 0 || dragCorner == 1;
        if (west) {
            left = clamp(snap(fx, lane, true), 0f, right - MIN_SIZE);
        } else {
            right = clamp(snap(fx, lane, true), left + MIN_SIZE, 1f);
        }
        if (north) {
            top = clamp(snap(fy, lane, false), 0f, bottom - MIN_SIZE);
        } else {
            bottom = clamp(snap(fy, lane, false), top + MIN_SIZE, 1f);
        }
        lane.x = left;
        lane.y = top;
        lane.width = right - left;
        lane.height = bottom - top;
    }

    /**
     * 贴近一条参考线就吸住，并记下那条线画出来。
     *
     * <p>参考线有两类：固定的（边、二分、四分）和别的格子的两条边。
     * 多条都在范围内时取<b>最近的那条</b>，不是第一条 ——
     * 否则四分线会把手指从旁边那一格的边上拽走。</p>
     *
     * @param horizontal true 看横向（x），false 看纵向（y）
     */
    private float snap(float value, LaneLayout moving, boolean horizontal) {
        float best = value;
        float gap = SNAP;
        for (float candidate : GUIDES) {
            float distance = Math.abs(value - candidate);
            if (distance <= gap) {
                gap = distance;
                best = candidate;
            }
        }
        for (LaneLayout other : lanes) {
            if (other == moving) {
                continue;
            }
            float near = horizontal ? other.x : other.y;
            float far = near + (horizontal ? other.width : other.height);
            if (Math.abs(value - near) <= gap) {
                gap = Math.abs(value - near);
                best = near;
            }
            if (Math.abs(value - far) <= gap) {
                gap = Math.abs(value - far);
                best = far;
            }
        }
        if (best != value) {
            if (horizontal) {
                guideX = best;
            } else {
                guideY = best;
            }
        }
        return best;
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private boolean onUp() {
        holdTouch(false);
        boolean changed = moved && dragIndex >= 0;
        int index = dragIndex;
        dragIndex = -1;
        dragCorner = -1;
        moved = false;
        guideX = Float.NaN;
        guideY = Float.NaN;
        invalidate();
        if (changed && moveListener != null) {
            // 摆到下一帧：听的人会把整个设置列表重搭，
            // 而此刻还在这张图自己的触摸分发里
            post(() -> moveListener.onMoved(index));
        } else if (!changed) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
