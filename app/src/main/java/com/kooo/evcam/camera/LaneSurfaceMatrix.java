package com.kooo.evcam.camera;

import android.graphics.Matrix;
import android.graphics.RectF;

/**
 * 一条<b>整幅画面</b>的流（座舱那两路）的旋转、镜像、裁剪、缩放平移，压成一个
 * {@code TextureView.setTransform} 用的矩阵。
 *
 * <h3>为什么不是直接 setRotation</h3>
 *
 * <p>{@code View} 层面的旋转会把视图转出自己的边界，还要另算一个补偿缩放，而裁剪
 * 和平移根本表达不了。作用在 surface 矩阵上，四件事是同一次映射里的四步，
 * 也和环视那一路的画法对得上 —— 那边同样是「取源画面的哪一块、放到哪一块去」。</p>
 *
 * <h3>和环视那一路的关系</h3>
 *
 * <p>几何是同一套（见 {@code FourLaneContainer.drawLane}），但那边画的是一张
 * 四联条带里的一格，这边是整幅画面铺满一个 {@code TextureView}。等到把主界面
 * 改成可拖拽缩放的编辑面板时，两边该合成一份 —— 现在合，要把容器的绘制一起动，
 * 不值当。轴向换算这一步已经共用 {@link LaneOrientation} 了。</p>
 */
public final class LaneSurfaceMatrix {

    private LaneSurfaceMatrix() {
    }

    /**
     * 算出矩阵。{@code viewWidth × viewHeight} 是纹理铺满的那块区域 ——
     * {@code AutoFitTextureView} 已经把视图做成了缓冲区的比例，所以在视图坐标系里
     * 画面是不变形的。
     *
     * @param rotation 顺时针旋转的度数
     * @return 是否真的有变换要应用；false 时 {@code out} 是单位矩阵
     */
    public static boolean build(Matrix out, int viewWidth, int viewHeight,
                                int rotation, boolean mirrored,
                                float cropTop, float cropBottom, float cropLeft, float cropRight,
                                float scaleX, float scaleY,
                                float translateX, float translateY) {
        out.reset();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }
        int turn = LaneOrientation.normalise(rotation);
        boolean quarterTurn = LaneOrientation.quarterTurn(turn);
        // 裁剪停用期间一律当 0，和环视那一路保持一致
        boolean crop = LaneOrientation.CROP_SUPPORTED;
        LaneOrientation o = LaneOrientation.sourceSpace(turn,
                crop ? cropTop : 0f, crop ? cropBottom : 0f,
                crop ? cropLeft : 0f, crop ? cropRight : 0f,
                scaleX, scaleY, translateX, translateY);

        boolean cropped = o.cropTop != 0f || o.cropBottom != 0f
                || o.cropLeft != 0f || o.cropRight != 0f;
        boolean zoomed = o.scaleX != 1f || o.scaleY != 1f;
        boolean panned = o.translateX != 0f || o.translateY != 0f;
        if (turn == 0 && !mirrored && !cropped && !zoomed && !panned) {
            return false;
        }

        // 取源画面的哪一块（视图坐标系）
        float keepX = 1f - clampFraction(o.cropLeft) - clampFraction(o.cropRight);
        float keepY = 1f - clampFraction(o.cropTop) - clampFraction(o.cropBottom);
        if (keepX <= 0.01f || keepY <= 0.01f) {
            keepX = 1f;
            keepY = 1f;
        }
        float left = clampFraction(o.cropLeft) * viewWidth;
        float top = clampFraction(o.cropTop) * viewHeight;
        RectF window = new RectF(left, top,
                left + keepX * viewWidth, top + keepY * viewHeight);

        float safeScaleX = o.scaleX > 0.05f ? o.scaleX : 1f;
        float safeScaleY = o.scaleY > 0.05f ? o.scaleY : 1f;
        if (safeScaleX != 1f || safeScaleY != 1f) {
            // 放大 2 倍 = 只取中间一半
            float cx = window.centerX();
            float cy = window.centerY();
            float halfW = window.width() / 2f / safeScaleX;
            float halfH = window.height() / 2f / safeScaleY;
            window.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        }
        if (o.translateX != 0f || o.translateY != 0f) {
            // 画面往右挪 = 取景窗往左挪
            window.offset(-o.translateX * window.width(), -o.translateY * window.height());
        }
        if (window.width() <= 0f || window.height() <= 0f) {
            return false;
        }

        // 放到视图的哪一块：保持画面比例，转过之后仍然整幅可见（留黑边，不裁）
        float pictureAspect = window.width() / window.height();
        float shownAspect = quarterTurn ? 1f / pictureAspect : pictureAspect;
        float viewAspect = (float) viewWidth / viewHeight;
        float destWidth = viewWidth;
        float destHeight = viewHeight;
        if (shownAspect < viewAspect) {
            destWidth = viewHeight * shownAspect;
        } else if (shownAspect > viewAspect) {
            destHeight = viewWidth / shownAspect;
        }
        float cx = viewWidth / 2f;
        float cy = viewHeight / 2f;
        RectF dest = new RectF(cx - destWidth / 2f, cy - destHeight / 2f,
                cx + destWidth / 2f, cy + destHeight / 2f);
        if (quarterTurn) {
            // 转四分之一圈时长宽在旋转后才互换，所以先按互换回来的那个框映射
            float halfW = dest.height() / 2f;
            float halfH = dest.width() / 2f;
            dest.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        }

        out.setRectToRect(window, dest, Matrix.ScaleToFit.FILL);
        if (turn != 0) {
            out.postRotate(turn, cx, cy);
        }
        if (mirrored) {
            out.postScale(-1f, 1f, cx, cy);
        }
        return true;
    }

    private static float clampFraction(float value) {
        return value < 0f ? 0f : (value > 0.9f ? 0.9f : value);
    }
}
