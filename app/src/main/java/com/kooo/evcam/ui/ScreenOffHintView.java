package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.ScreenOffPlan;

/**
 * 录制键上的小字：现在熄屏的话，这段录像会怎样（「熄屏后继续录制环视 / 熄屏暂停，亮屏后恢复 / 熄屏后停止录制」）。
 *
 * <p>说什么由 {@link ScreenOffPlan#of} 定 —— 就是熄屏那一刻（屏幕黑了、车机醒着）录制协调器照的那张表，所以写的就是实际会发生的。
 * 熄屏 v3 起只看三样：熄屏持续录制（和开发者的熄屏录制）、启动自动录制、这一次录像的计划里有没有环视
 * （{@link RecordingCoordinator#surroundPlanned}）。哨兵模式、挡位改不了它（车机醒着时判不出现在停，设计 v3 §5），
 * 所以不再听车辆信号，也没有「需开启哨兵模式」那一句了。录制中、等待摄像头时显示（{@link RecordButtonUi} 管显隐）；
 * 看得见时听那几个开关，看不见就不听。</p>
 */
public class ScreenOffHintView extends AppCompatTextView {

    /** 构造完了：父类构造的时候就可能叫到 {@link #refresh}，那时字段还没赋值。 */
    private boolean ready;
    private AppConfig config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;

    public ScreenOffHintView(Context context) {
        this(context, null);
    }

    public ScreenOffHintView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ready = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        refresh();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        refresh();
    }

    /** 看得见就听开关、写上现在该写的；看不见就不听。 */
    private void refresh() {
        if (!ready) {
            return;
        }
        if (!isShown() || getWindowVisibility() != VISIBLE) {
            stop();
            return;
        }
        if (configListener == null) {
            config = new AppConfig(getContext());
            configListener = config.onScreenOffPlanChanged(this::show);
        }
        show();
    }

    private void stop() {
        if (configListener == null) {
            return;
        }
        config.removeChangeListener(configListener);
        configListener = null;
    }

    private void show() {
        setText(ScreenOffPlan.of(config, RecordingCoordinator.get(getContext()).surroundPlanned()).text);
    }
}
