package com.kooo.evcam.ui;

/**
 * 放大过渡里的一帧：视图已经按最终的样子排好，要让它看起来占着另一块矩形。
 *
 * <p>做法是<b>等比缩放加居中裁切</b>，不是把视图拉成那块矩形的形状 —— 拉伸会让画面
 * 在过渡里变形。等比缩到刚好盖住那块矩形，多出来的部分裁掉，和「填充」同一个道理。</p>
 *
 * <p>矩形都是屏幕坐标 {@code {left, top, width, height}}；结果是视图自己的缩放
 * （中心在左上角）、平移，和视图坐标里的裁切框。纯计算，见 {@code ExpandGeometryTest}。</p>
 */
public final class ExpandGeometry {

    public final float scale;
    public final float translationX;
    public final float translationY;
    public final float clipLeft;
    public final float clipTop;
    public final float clipRight;
    public final float clipBottom;

    private ExpandGeometry(float scale, float translationX, float translationY,
                           float clipLeft, float clipTop, float clipRight, float clipBottom) {
        this.scale = scale;
        this.translationX = translationX;
        this.translationY = translationY;
        this.clipLeft = clipLeft;
        this.clipTop = clipTop;
        this.clipRight = clipRight;
        this.clipBottom = clipBottom;
    }

    /**
     * @param laidOut 视图排好之后在屏幕上的矩形
     * @param shown   这一帧要让它看起来占着的矩形
     */
    public static ExpandGeometry frame(float[] laidOut, float[] shown) {
        float width = laidOut[2];
        float height = laidOut[3];
        if (width <= 0f || height <= 0f || shown[2] <= 0f || shown[3] <= 0f) {
            return new ExpandGeometry(1f, 0f, 0f, 0f, 0f, Math.max(width, 0f), Math.max(height, 0f));
        }
        // 等比缩到刚好盖住：两个方向里取大的那个
        float scale = Math.max(shown[2] / width, shown[3] / height);
        float clipWidth = shown[2] / scale;
        float clipHeight = shown[3] / scale;
        float clipLeft = (width - clipWidth) / 2f;
        float clipTop = (height - clipHeight) / 2f;
        // 裁切框的左上角要落在那块矩形的左上角上
        float translationX = shown[0] - laidOut[0] - scale * clipLeft;
        float translationY = shown[1] - laidOut[1] - scale * clipTop;
        return new ExpandGeometry(scale, translationX, translationY,
                clipLeft, clipTop, clipLeft + clipWidth, clipTop + clipHeight);
    }

    /** 两块矩形之间按 t 插值，0 是 from，1 是 to。 */
    public static float[] lerp(float[] from, float[] to, float t) {
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = from[i] + (to[i] - from[i]) * t;
        }
        return out;
    }
}
