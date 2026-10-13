package com.kooo.evcam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatTextView;

import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 一行跟着车辆信号换的字。自己管自己：看得见（挂在窗口上、窗口和它自己都可见）就向 {@link Telemetry}
 * 登记、听读数，看不见就注销。离散信号（哨兵模式、挡位……）进程活着一直在读（熄屏判断登记的核心，{@link Telemetry#keepCore}），
 * 这里的登记另外开浮点传感器和定位；最后一个走了，浮点和定位就停。子类只管「现在该写什么」（{@link #show}），
 * 要听设置的在 {@link #onStart} / {@link #onStop} 里挂、摘。
 *
 * <p>现在有一处：{@link SentryStatusView}（设置里「熄屏持续录制」那一行的右边）。录制键上的小字（{@link ScreenOffHintView}）
 * 不登记：它用到的哨兵模式、挡位是离散的，核心一直在读，只听不开浮点和定位。
 * {@link VehicleStatusPanel} 是画出来的，同一个规矩自己写了一份。</p>
 */
public abstract class TelemetryTextView extends AppCompatTextView implements Telemetry.Listener {

    private final String user;
    private boolean active;

    protected TelemetryTextView(Context context, AttributeSet attrs, String user) {
        super(context, attrs);
        this.user = user;
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

    private void refresh() {
        if (user == null) {
            // 父类构造的时候就可能叫到这里，那时字段还没赋值
            return;
        }
        if (!isShown() || getWindowVisibility() != VISIBLE) {
            stop();
            return;
        }
        if (!active) {
            active = true;
            onStart();
            Telemetry.get().addListener(this);
            Telemetry.get().acquire(getContext(), user);
        }
        show();
    }

    private void stop() {
        if (!active) {
            return;
        }
        active = false;
        Telemetry.get().removeListener(this);
        Telemetry.get().release(user);
        onStop();
    }

    @Override
    public final void onReadingsChanged(Readings readings) {
        show();
    }

    /** 开始看得见了（在登记之前）。 */
    protected void onStart() {
    }

    /** 看不见了（注销之后）。 */
    protected void onStop() {
    }

    /** 按 {@link Telemetry#latest()} 和设置把字换成现在该写的。只在看得见的时候叫。 */
    protected abstract void show();
}
