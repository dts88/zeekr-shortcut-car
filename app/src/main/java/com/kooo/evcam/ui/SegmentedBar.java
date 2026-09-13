package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 一排档位，选中的那一档压着能量色。
 *
 * <h3>为什么不用弹框</h3>
 *
 * <p>配置编辑里每一个值原来都藏在一个弹框后面：点开、选一项、关掉，值才看得见。
 * 而这些值全是<b>三到六档的枚举</b> —— 档数少到可以整排摆出来，摆出来之后
 * 「现在是哪一档」和「还有哪几档」同时可见，改一下就是一次点击。</p>
 *
 * <p>这也是把「界面显示的值 = 实际生效的值」做到底：没有中间态，
 * 不存在「弹框里选了、外面还没变」这半秒。</p>
 *
 * <h3>为什么是 activated 不是 selected</h3>
 *
 * <p>{@code selected} 在列表里另有含义（整行被选中）。档位用
 * {@code activated}，和 {@code bg_segment} 里写的一致。</p>
 */
public class SegmentedBar extends LinearLayout {

    /** 选了哪一档。 */
    public interface OnPick {
        void pick(String value);
    }

    private String[] values = new String[0];
    private OnPick onPick;

    public SegmentedBar(Context context) {
        this(context, null);
    }

    public SegmentedBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_segment_track);
        int pad = dp(3);
        setPadding(pad, pad, pad, pad);
    }

    /**
     * 摆出这几档。
     *
     * @param labels   每一档显示什么
     * @param values   每一档存下去是什么，长度必须和 labels 一致
     * @param current  现在是哪一档；不在 values 里就一档都不高亮
     */
    public void bind(String[] labels, String[] values, String current, OnPick onPick) {
        if (labels == null || values == null || labels.length != values.length) {
            throw new IllegalArgumentException("labels and values must pair up");
        }
        this.values = values;
        this.onPick = onPick;
        removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            addView(segment(labels[i], values[i], values[i].equals(current)));
        }
    }

    private TextView segment(String label, String value, boolean active) {
        TextView view = new TextView(getContext());
        LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        view.setLayoutParams(params);
        view.setGravity(Gravity.CENTER);
        int padV = dp(13);
        view.setPadding(dp(6), padV, dp(6), padV);
        view.setMaxLines(1);
        view.setText(label);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.text_body));
        view.setBackgroundResource(R.drawable.bg_segment);
        view.setActivated(active);
        view.setTextColor(ContextCompat.getColor(getContext(),
                active ? R.color.on_energy : R.color.text_primary));
        view.setOnClickListener(v -> {
            if (onPick != null) {
                onPick.pick(value);
            }
        });
        return view;
    }

    /** 现在这一排里有没有这个值，用来判断「已细调」。 */
    public boolean has(String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
