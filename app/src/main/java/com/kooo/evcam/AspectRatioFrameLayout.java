package com.kooo.evcam;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 自定义FrameLayout，保持16:10的宽高比
 * 用于视频/图片预览框
 * 会根据可用空间自适应，优先保证不超出边界
 */
public class AspectRatioFrameLayout extends FrameLayout {
    // 宽高比 16:10
    private static final float WIDTH_RATIO = 16.0f;
    private static final float HEIGHT_RATIO = 10.0f;

    public AspectRatioFrameLayout(Context context) {
        super(context);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int finalWidth, finalHeight;

        if (widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY) {
            // 两个尺寸都确定，选择能保持比例且不超出的尺寸
            float widthBasedHeight = widthSize * HEIGHT_RATIO / WIDTH_RATIO;
            float heightBasedWidth = heightSize * WIDTH_RATIO / HEIGHT_RATIO;

            if (widthBasedHeight <= heightSize) {
                // 基于宽度计算的高度不超出，使用宽度
                finalWidth = widthSize;
                finalHeight = (int) widthBasedHeight;
            } else {
                // 基于高度计算的宽度
                finalWidth = (int) heightBasedWidth;
                finalHeight = heightSize;
            }
        } else if (widthMode == MeasureSpec.EXACTLY) {
            // 宽度确定，根据宽度计算高度
            finalWidth = widthSize;
            finalHeight = (int) (widthSize * HEIGHT_RATIO / WIDTH_RATIO);
            
            // 关键修复：如果高度有上限限制（AT_MOST），确保不超过
            if (heightMode == MeasureSpec.AT_MOST && finalHeight > heightSize) {
                finalHeight = heightSize;
                finalWidth = (int) (heightSize * WIDTH_RATIO / HEIGHT_RATIO);
            }
        } else if (heightMode == MeasureSpec.EXACTLY) {
            // 高度确定，根据高度计算宽度
            finalHeight = heightSize;
            finalWidth = (int) (heightSize * WIDTH_RATIO / HEIGHT_RATIO);
            
            // 如果宽度有上限限制（AT_MOST），确保不超过
            if (widthMode == MeasureSpec.AT_MOST && finalWidth > widthSize) {
                finalWidth = widthSize;
                finalHeight = (int) (widthSize * HEIGHT_RATIO / WIDTH_RATIO);
            }
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
