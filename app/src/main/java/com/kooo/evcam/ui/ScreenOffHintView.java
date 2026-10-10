package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.recording.ScreenOffPlan;
import com.kooo.evcam.recording.ScreenOffRecording;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 录制键上的小字：现在熄屏的话，这段录像会怎样（「熄屏后继续录制 / 熄屏暂停，唤醒后恢复 / 熄屏后停止录制」）。
 *
 * <p>说什么由 {@link ScreenOffPlan} 定 —— 录制协调器熄屏时照的也是它，所以写的就是实际会发生的。
 * 看这几样：熄屏持续录制（和开发者的熄屏录制）、启动自动录制，车辆的哨兵模式、挡位和车速（车在走时哨兵模式读成关，
 * 照样接着录）；哪样变了就跟着换。只在录制中显示（{@link RecordButtonUi} 管显隐），看不见时不读车辆信号。</p>
 */
public class ScreenOffHintView extends TelemetryTextView {

    private AppConfig config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;

    public ScreenOffHintView(Context context) {
        this(context, null);
    }

    public ScreenOffHintView(Context context, AttributeSet attrs) {
        super(context, attrs, "record-hint");
    }

    @Override
    protected void onStart() {
        config = new AppConfig(getContext());
        configListener = config.onScreenOffPlanChanged(this::show);
    }

    @Override
    protected void onStop() {
        config.removeChangeListener(configListener);
        configListener = null;
    }

    @Override
    protected void show() {
        ScreenOffPlan plan = ScreenOffPlan.of(config, ScreenOffRecording.holdsCarAwake(getContext()),
                Telemetry.get().latest());
        if (plan == ScreenOffPlan.NEEDS_SENTRY && Telemetry.get().readings().version == 0) {
            // 还没读过一轮，哨兵模式开没开还不知道：先空着，别闪一下「需开启哨兵模式」
            setText("");
            return;
        }
        setText(plan.text);
    }
}
