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
import com.kooo.evcam.telemetry.VehicleState;

import java.util.Objects;

/**
 * 录制键上的小字：现在熄屏的话，这段录像会怎样（「熄屏后继续录制环视 /
 * 熄屏暂停，亮屏后恢复 / 熄屏后停止录制」）。
 *
 * <p>说什么由 {@link ScreenOffPlan#of} 定 —— 就是录制协调器照的那张表、同样的输入，判熄屏那一刻和之后车机要睡那一刻，
 * 所以写的就是实际会发生的：熄屏持续录制（和开发者的熄屏录制、它的唤醒锁拉不拉得住车机）、启动自动录制、这一次录像的计划里
 * 有没有环视（{@link RecordingCoordinator#surroundPlanned}）、哨兵模式和挡位（{@link Telemetry#latest}：离散信号，
 * 熄屏判断登记的核心一直在读），再加上熄屏那一刻安卓熄没熄屏 —— 车辆信号判着屏幕时是车辆先说黑、车机醒着；判不出屏幕时
 * （降级，{@link ScreenState#vehicleJudges}）熄屏就是车机下电，两下是同一下（审查 rev3 B1）。</p>
 *
 * <p>录制中、等待摄像头时显示（{@link RecordButtonUi} 管显隐）；看得见时听那几个开关和车辆信号（连接、读数变了，
 * {@link Telemetry#addListener}，只听不登记：不开浮点传感器和定位），看不见就不听。车辆信号每变一次都来（录像开着信息条时
 * 车速每 200 ms 一次），表只看哨兵模式、在开车（{@link ScreenOffPlan#driving}）和车辆信号判不判得出屏幕：这三样变了才重判。</p>
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
    /** 写着的那一句是照哪几样车辆输入判的（{@link #judgedWith}）：没判过时 false。 */
    private boolean judged;
    private Integer judgedSentry;
    private boolean judgedDriving;
    private boolean judgedVehicleJudges;

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

    /** 车辆信号变了：表看的那三样（哨兵模式、在开车、判不判得出屏幕）有一样变了才重判。 */
    private void vehicleChanged() {
        if (judged && judgedWith(Telemetry.get().latest(), ScreenState.vehicleJudges())) {
            return;
        }
        show();
    }

    /** 写着的那一句就是照这几样判的。 */
    private boolean judgedWith(VehicleState vehicle, boolean vehicleJudges) {
        return Objects.equals(vehicle.sentry, judgedSentry)
                && ScreenOffPlan.driving(vehicle.gear, vehicle.speedKmh) == judgedDriving
                && vehicleJudges == judgedVehicleJudges;
    }

    private void show() {
        if (configListener == null) {
            // 不听了之后才到的一次通知（主线程上排着的那一批）
            return;
        }
        Context context = getContext();
        VehicleState vehicle = Telemetry.get().latest();
        boolean vehicleJudges = ScreenState.vehicleJudges();
        judged = true;
        judgedSentry = vehicle.sentry;
        judgedDriving = ScreenOffPlan.driving(vehicle.gear, vehicle.speedKmh);
        judgedVehicleJudges = vehicleJudges;
        ScreenOffPlan plan = ScreenOffPlan.of(config, ScreenOffRecording.holdsCarAwake(context), vehicle,
                !vehicleJudges, RecordingCoordinator.get(context).surroundPlanned());
        if (plan != shown) {
            shown = plan;
            setText(plan.text);
        }
    }
}
