package com.kooo.evcam;

import android.graphics.Matrix;

/**
 * 预览画面矫正工具类
 * 对主界面预览 TextureView 的画面进行缩放/平移矫正
 * 在基础变换（旋转等）之后叠加应用，每路摄像头参数独立
 */
public final class PreviewCorrection {
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 8.0f;
    private static final float MIN_TRANSLATE = -5.0f;
    private static final float MAX_TRANSLATE = 5.0f;

    private PreviewCorrection() {}

    /**
     * 将预览矫正参数叠加到已有的 Matrix 上
     * 应在基础变换（旋转/缩放）之后调用
     *
     * @param matrix     已包含基础变换的 Matrix（会被就地修改）
     * @param appConfig  配置
     * @param cameraPos  摄像头位置（front/back/left/right）
     * @param viewWidth  TextureView 宽度
     * @param viewHeight TextureView 高度
     */
    public static void postApply(Matrix matrix, AppConfig appConfig, String cameraPos,
                                 int viewWidth, int viewHeight) {
        if (matrix == null || appConfig == null || cameraPos == null) return;
        if (!appConfig.isPreviewCorrectionEnabled()) return;
        if (viewWidth <= 0 || viewHeight <= 0) return;

        float scaleX = clamp(appConfig.getPreviewCorrectionScaleX(cameraPos), MIN_SCALE, MAX_SCALE);
        float scaleY = clamp(appConfig.getPreviewCorrectionScaleY(cameraPos), MIN_SCALE, MAX_SCALE);
        float translateX = clamp(appConfig.getPreviewCorrectionTranslateX(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
        float translateY = clamp(appConfig.getPreviewCorrectionTranslateY(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);

        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        matrix.postScale(scaleX, scaleY, centerX, centerY);
        matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
