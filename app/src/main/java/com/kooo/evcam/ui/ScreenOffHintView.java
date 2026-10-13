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
import com.kooo.evcam.recording.ScreenOffRecording;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 录制键上的小字：现在熄屏的话，这段录像会怎样（「熄屏后继续录制环视 / 熄屏暂停，亮屏后恢复 / 熄屏后停止录制」）。
 *
 * <p>说什么由 {@link ScreenOffPlan#of} 定 —— 就是下一次熄屏那一刻录制协调器照的那张表、同样的输入，所以写的就是实际会发生的：
 * 熄屏持续录制（和开发者的熄屏录制、它的唤醒锁拉不拉得住车机）、启动自动录制、这一次录像的计划里有没有环视
 * （{@link RecordingCoordinator#surroundPlanned}），再加上那一刻安卓熄没熄屏 —— 车辆信号判着屏幕时是车辆先说黑、车机醒着，
 * 哨兵模式、挡位改不了它；判不出屏幕时（降级，{@link ScreenState#vehicleJudges}）熄屏就是车机下电，照「车机要睡」那一行算上
 * 哨兵模式、挡位（审查 rev3 B1：以前一律按车机醒着说「继续录制」，降级时熄屏那一下却现在停）。</p>
 *
 * <p>录制中、等待摄像头时显示（{@link RecordButtonUi} 管显隐）；看得见时听那几个开关和车辆信号（连接、读数变了，
 * {@link Telemetry#addListener}，只听不登记：不开浮点传感器和定位），看不见就不听。</p>
 */
public class ScreenOffHintView extends AppCompatTextView {

    /** 构造完了：父类构造的时候就可能叫到 {@link #refresh}，那时字段还没赋值。 */
    private boolean ready;
    private AppConfig config;
    private SharedPreferences.OnSharedPreferenceChangeListener configListener;
    /** 车辆信号的连接、读数变了（熄屏判断那一份先收到，这里看到的是判过的）。 */
    private final Telemetry.Listener vehicleListener = readings -> vehicleChanged();
    /** 此刻写着的那一句：一样就不重写。 */
    private ScreenOffPlan shown;
    /** 写着的那一句是车辆信号判着屏幕时算的（哨兵模式、挡位改不了它）。 */
    private boolean shownWhileJudged;

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

    /** 看得见就听开关和车辆信号、写上现在该写的；看不见就不听。 */
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
            Telemetry.get().addListener(vehicleListener);
        }
        show();
    }

    private void stop() {
        if (configListener == null) {
            return;
        }
        config.removeChangeListener(configListener);
        configListener = null;
        Telemetry.get().removeListener(vehicleListener);
    }

    /**
     * 车辆信号变了：判着屏幕、写着的那一句也是判着屏幕时算的，哨兵模式、挡位改不了它，不重判 —— 读数每变一次都来
     * （录像开着信息条时车速每 200 ms 一次）。判不判得出变了、判不出时（哨兵模式、挡位算数）才重判。
     */
    private void vehicleChanged() {
        if (shownWhileJudged && ScreenState.vehicleJudges()) {
            return;
        }
        show();
    }

    private void show() {
        if (configListener == null) {
            // 不听了之后才到的一次通知（主线程上排着的那一批）
            return;
        }
        Context context = getContext();
        boolean judged = ScreenState.vehicleJudges();
        ScreenOffPlan plan = ScreenOffPlan.of(config, ScreenOffRecording.holdsCarAwake(context),
                Telemetry.get().latest(), !judged, RecordingCoordinator.get(context).surroundPlanned());
        shownWhileJudged = judged;
        if (plan != shown) {
            shown = plan;
            setText(plan.text);
        }
    }
}
