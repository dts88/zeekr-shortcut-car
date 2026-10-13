package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 视频回看进度条正下方的一条细条：锁定的那几段涂成琥珀色（项目所有者 2026-10-03）。
 *
 * <p>系统进度条一点不动，拖动、跳转照旧；这一条只画、不接触摸。两端和进度条的轨道对齐：
 * 进度条的轨道左右各缩进它自己的内边距，这里用同样的内边距（{@link #alignTo}）。</p>
 */
public class LockedRangeStrip extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint locked = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 时间轴上的起止（毫秒），成对：[起, 止, 起, 止 …]。 */
    private long[] ranges = new long[0];
    private long total = 1;

    public LockedRangeStrip(Context context) {
        this(context, null);
    }

    public LockedRangeStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setColor(ContextCompat.getColor(context, R.color.line));
        locked.setColor(ContextCompat.getColor(context, R.color.locked));
    }

    /** 两端和这根进度条的轨道对齐。 */
    public void alignTo(View seekBar) {
        setPadding(seekBar.getPaddingLeft(), 0, seekBar.getPaddingRight(), 0);
    }

    /**
     * @param ranges 锁定的时间段，成对的起止（时间轴毫秒）
     * @param total  整条时间轴多长
     */
    public void setRanges(long[] ranges, long total) {
        this.ranges = ranges != null ? ranges : new long[0];
        this.total = Math.max(1, total);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float left = getPaddingLeft();
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float radius = height / 2f;
        canvas.drawRoundRect(left, 0, left + width, height, radius, radius, track);
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            float x0 = left + width * clamp(ranges[i]) / total;
            float x1 = left + width * clamp(ranges[i + 1]) / total;
            // 很短的一段也至少画出一个点那么宽，不然一分钟里的一个文件在长时间轴上看不见
            if (x1 - x0 < height) {
                x1 = Math.min(left + width, x0 + height);
            }
            canvas.drawRoundRect(x0, 0, x1, height, radius, radius, locked);
        }
    }

    private long clamp(long ms) {
        return Math.max(0, Math.min(ms, total));
    }
}
