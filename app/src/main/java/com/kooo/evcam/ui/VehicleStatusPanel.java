package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.InfoBar;
import com.kooo.evcam.telemetry.InfoBarLayout;
import com.kooo.evcam.telemetry.InfoBarRenderer;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 车辆状态面板（设置 → 界面 → 车辆状态，试验性）：主界面动作栏里、鱼眼校正下面那块，
 * 用信息条的图标显示几项想随时看到的状态 —— 现在是喇叭、闪远光、双闪（用户 2026-10-03 定）
 * （{@link InfoBarLayout#panel()} 定放哪几格）。
 *
 * <p>和信息条是同一套：同一个画法（{@link InfoBarRenderer}）、同一份车辆快照（{@link Telemetry#latest()}）、
 * 同一条规则：没验证的信号不画进来（只有开发者能在系统信息里勾上），没数据的各自划掉。不同的只是摆法和在哪显示。</p>
 *
 * <p>自己管自己，所在的界面不用接线（同 {@link FisheyeToggleButton}）：开关关着就不占地方；
 * 开着、而且窗口看得见时才向 {@link Telemetry} 登记（"vehicle-status"），看不见或关掉就注销 ——
 * 没别人在用的话车辆信号就停下来。读数一变就重画，不轮询。</p>
 */
public class VehicleStatusPanel extends View implements Telemetry.Listener {

    private static final String USER = "vehicle-status";

    private final AppConfig config;
    private final InfoBarLayout.Arrangement arrangement = InfoBarLayout.panel();
    private InfoBarRenderer renderer;
    private boolean active;
    /** 拿住它：SharedPreferences 只弱引用监听器。 */
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    public VehicleStatusPanel(Context context) {
        this(context, null);
    }

    public VehicleStatusPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        config = new AppConfig(context);
        final float radius = context.getResources().getDimension(R.dimen.corner_radius);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        listener = config.onVehicleStatusChanged(this::refresh);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        config.removeChangeListener(listener);
        listener = null;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        refresh();
    }

    /** 开关和窗口可见性一变就对一次：该占地方就占，该收信号就收。 */
    private void refresh() {
        boolean enabled = config.isVehicleStatusEnabled();
        setVisibility(enabled ? VISIBLE : GONE);
        boolean shouldRun = enabled && isAttachedToWindow() && getWindowVisibility() == VISIBLE;
        if (shouldRun && !active) {
            active = true;
            Telemetry.get().addListener(this);
            Telemetry.get().acquire(getContext(), USER);
        } else if (!shouldRun && active) {
            stop();
        }
        // 开关、窗口可见性变了（或者开发者模式开关过）：下次画的时候重建
        renderer = null;
        invalidate();
    }

    private void stop() {
        if (!active) {
            return;
        }
        active = false;
        Telemetry.get().removeListener(this);
        Telemetry.get().release(USER);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        invalidate();
    }

    /** 宽度跟动作栏，高度按面板的宽高比算；剩下的高度不够就整块按比例缩小。 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float ratio = (float) arrangement.height / arrangement.width;
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * ratio);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int room = MeasureSpec.getSize(heightMeasureSpec);
            if (height > room) {
                height = room;
                width = Math.round(room / ratio);
            }
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        renderer = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (renderer == null || renderer.width() != getWidth()) {
            if (renderer != null) {
                renderer.recycle();
            }
            // 按控件的实际像素画，不把小图拉大：图标在主界面上和录像里一样清楚
            float scale = getWidth() / (float) arrangement.width;
            renderer = new InfoBarRenderer(getContext(), arrangement, scale);
        }
        renderer.renderIfDue(Telemetry.get().latest());
        canvas.drawBitmap(renderer.bitmap(), 0, 0, null);
    }
}
