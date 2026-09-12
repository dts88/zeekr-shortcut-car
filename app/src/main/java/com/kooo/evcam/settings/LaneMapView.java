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
 * 配置编辑里的格子图：每一格按它在配置里的位置和大小画，点一格选它。
 *
 * <p>以前四格各有一组设置、一路竖排，改的是哪一格只能看分组标题。
 * 这里把格子本身画出来 —— 正在改的那一格描能量色、铺浅底，
 * 位置和大小改了，这张图也跟着变，改完什么样一眼就看得到。</p>
 *
 * <p>比例按主界面的预览区（大约 1.4 : 1）画，最高不超过 320dp。</p>
 */
public class LaneMapView extends View {

    /** 点了哪一格（配置里 lanes 的下标）。 */
    public interface OnLaneTap {
        void onTap(int index);
    }

    private static final float ASPECT = 1.4f;

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chosen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF area = new RectF();
    private final RectF rect = new RectF();
    private final float density;

    private List<LaneLayout> lanes = Collections.emptyList();
    private int selected = -1;
    private OnLaneTap listener;

    public LaneMapView(Context context) {
        this(context, null);
    }

    public LaneMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        frame.setColor(ContextCompat.getColor(context, R.color.line));
        cell.setColor(ContextCompat.getColor(context, R.color.surface));
        chosen.setColor(ContextCompat.getColor(context, R.color.energy_quiet));
        outline.setColor(ContextCompat.getColor(context, R.color.energy));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3f * density);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.min(Math.round(width / ASPECT), Math.round(320 * density));
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
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            // 从后往前找：后画的盖在上面，点到的是看得见的那一格
            for (int i = lanes.size() - 1; i >= 0; i--) {
                laneRect(lanes.get(i), rect);
                if (rect.contains(event.getX(), event.getY())) {
                    if (listener != null && i != selected) {
                        listener.onTap(i);
                    }
                    performClick();
                    return true;
                }
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
