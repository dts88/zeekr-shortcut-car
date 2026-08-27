package com.kooo.evcam;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 自定义FrameLayout，保持16:9的宽高比
 * 用于竖屏模式下的预览区容器，确保预览区不被压缩
 * 根据宽度计算高度，保证16:9比例
 */
public class AspectRatio16x9Container extends FrameLayout {
    // 宽高比 16:9
    private static final float WIDTH_RATIO = 16.0f;
    private static final float HEIGHT_RATIO = 9.0f;

    public AspectRatio16x9Container(Context context) {
        super(context);
    }

    public AspectRatio16x9Container(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatio16x9Container(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int finalWidth, finalHeight;

        // 竖屏模式下，通常宽度是确定的，根据宽度计算16:9的高度
        if (widthMode == MeasureSpec.EXACTLY || widthMode == MeasureSpec.AT_MOST) {
            // 基于宽度计算高度（16:9）
            finalWidth = widthSize;
            finalHeight = (int) (widthSize * HEIGHT_RATIO / WIDTH_RATIO);
            
            // 如果高度有限制，确保不超出
            if (heightMode == MeasureSpec.EXACTLY || heightMode == MeasureSpec.AT_MOST) {
                if (finalHeight > heightSize) {
                    // 高度超出限制，基于高度反算宽度
                    finalHeight = heightSize;
                    finalWidth = (int) (heightSize * WIDTH_RATIO / HEIGHT_RATIO);
                }
            }
        } else if (heightMode == MeasureSpec.EXACTLY || heightMode == MeasureSpec.AT_MOST) {
            // 宽度不确定但高度确定，基于高度计算宽度
            finalHeight = heightSize;
            finalWidth = (int) (heightSize * WIDTH_RATIO / HEIGHT_RATIO);
        } else {
            // 都不确定，使用默认
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int newWidthSpec = MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY);
        int newHeightSpec = MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY);
        super.onMeasure(newWidthSpec, newHeightSpec);
    }
}
