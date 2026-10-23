package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.InfoBarLayout;
import com.kooo.evcam.telemetry.InfoBarRenderer;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * 系统信息页每一行旁边的图标（项目所有者 2026-10-21）：这个信号勾上后在行驶信息条上画成的那一格，和录像里一样 ——
 * 同一个画法（{@link InfoBarRenderer}）、同一份车辆快照（{@link Telemetry#latest()}），照此刻的读数画。
 * 组合的格（座舱、前灯组）整格画出来；几个号对应同一格的，各自旁边都是同一格；一项带出两格的，两格都画。
 * 挡位格框的颜色是驾驶模式这类要解释的画法，在这里对着那一行的值就看得懂。
 *
 * <p>不自己登记车辆信号：所在的页面登记了（{@code VehicleInfoFragment}），读数变了它叫 {@link #invalidate}。</p>
 */
public class InfoBarCellView extends View {

    private final InfoBarLayout.Arrangement arrangement;
    private InfoBarRenderer renderer;

    public InfoBarCellView(Context context, InfoBarLayout.Cell cell) {
        super(context);
        arrangement = InfoBarLayout.single(cell);
        setContentDescription(context.getString(cell.labelRes));
        final float radius = context.getResources().getDimension(R.dimen.info_cell_icon_radius);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    /** 高度固定（{@code info_cell_icon_height}），宽按这一格的宽高比。 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = getResources().getDimensionPixelSize(R.dimen.info_cell_icon_height);
        int width = Math.round(height * (float) arrangement.width / arrangement.height);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        release();
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (renderer == null) {
            // 按控件的实际像素画，不把小图拉大
            renderer = new InfoBarRenderer(getContext(), arrangement, getWidth() / (float) arrangement.width);
        }
        renderer.renderIfDue(Telemetry.get().latest());
        canvas.drawBitmap(renderer.bitmap(), 0, 0, null);
    }

    private void release() {
        if (renderer != null) {
            renderer.recycle();
            renderer = null;
        }
    }
}
