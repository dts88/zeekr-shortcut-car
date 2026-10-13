package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 一行字：车辆的哨兵模式此刻开没开（「哨兵模式：已开启 / 已布防 / 未开启」）。
 *
 * <p>熄屏后环视能录多久看的就是哨兵模式：开着车机一直醒着、一直录；没开的话录到车机休眠（熄屏 v3，项目所有者 2026-10-12 D3）。
 * 所以凡是和熄屏录制有关的地方都可以放它 —— 现在在「设置 → 系统 → 熄屏持续录制」那一行的右边。读的是 {@link Telemetry#latest()} 里的
 * {@code sentry}（0 关、1 开、2 布防），和信息条、车辆状态面板、熄屏判断是同一份（录制键上的小字不看它）。</p>
 */
public class SentryStatusView extends TelemetryTextView {

    public SentryStatusView(Context context) {
        this(context, null);
    }

    public SentryStatusView(Context context, AttributeSet attrs) {
        super(context, attrs, "sentry-status");
    }

    @Override
    protected void show() {
        Integer sentry = Telemetry.get().latest().sentry;
        int text;
        boolean on = false;
        if (sentry == null) {
            // 还没读过一轮是「读取中」；读过了还没有就是这台车机读不到
            text = Telemetry.get().readings().version > 0
                    ? R.string.sentry_status_unknown : R.string.sentry_status_reading;
        } else if (sentry == 2) {
            text = R.string.sentry_status_armed;
            on = true;
        } else if (sentry == 1) {
            text = R.string.sentry_status_on;
            on = true;
        } else {
            text = R.string.sentry_status_off;
        }
        setText(text);
        setTextColor(ContextCompat.getColor(getContext(), on ? R.color.energy : R.color.text_secondary));
    }
}
