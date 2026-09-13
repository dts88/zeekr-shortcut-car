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
     * 算出矩阵。
     *
     * <h3>为什么要知道缓冲区的形状</h3>
     *
     * <p>纹理是<b>铺满</b>视图的：视图什么形状，画面就被拉成什么形状。以前这里
     * 假设「视图已经被做成了缓冲区的比例」，所以在视图坐标系里画面不变形 ——
     * 那个假设在「适应」下成立，在「填充」下不成立：填充时视图占的是整格，
     * 而整格和画面本来就不同形。</p>
     *
     * <p>所以这里自己算一个<b>压扁系数</b> k =（缓冲区比例）×（视图高/视图宽）。
     * 视图正好是缓冲区的形状时 k = 1，下面所有算式都退回原来那一套 ——
     * 这也是这次改动不会动到「适应」的原因。</p>
     *
     * @param bufferWidth  相机给的那块缓冲区，0 表示不知道（按 k = 1 处理）
     * @param rotation 顺时针旋转的度数
     * @return 是否真的有变换要应用；false 时 {@code out} 是单位矩阵
     */
    public static boolean build(Matrix out, int viewWidth, int viewHeight,
                                int bufferWidth, int bufferHeight,
                                int rotation, boolean mirrored,
                                float cropTop, float cropBottom, float cropLeft, float cropRight,
                                float scaleX, float scaleY,
                                float translateX, float translateY, String fit) {
        out.reset();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return false;
        }
        // 视图把缓冲区拉成了自己的形状，差的这一下要补回来
        float squash = 1f;
        if (bufferWidth > 0 && bufferHeight > 0) {
            squash = ((float) bufferWidth / bufferHeight)
                    * ((float) viewHeight / viewWidth);
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
        boolean fills = com.kooo.evcam.profile.LaneLayout.FILL.equals(fit);
        if (turn == 0 && !mirrored && !cropped && !zoomed && !panned && !fills) {
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

        // 放到视图的哪一块，两档：
        //   适应 —— 整幅可见，比例不变，对不上的两边留黑
        //   填充 —— 铺满视图，比例不变，多出来的那一边居中裁掉
        float pictureAspect = window.width() / window.height() * squash;
        float shownAspect = quarterTurn ? 1f / pictureAspect : pictureAspect;
        float viewAspect = (float) viewWidth / viewHeight;
        float destWidth = viewWidth;
        float destHeight = viewHeight;
        if (com.kooo.evcam.profile.LaneLayout.FILL.equals(fit)) {
            // 反过来收窄取景窗：多出来的那一边不要
            if (shownAspect > viewAspect) {
                float keep = viewAspect / shownAspect;
                float centre = quarterTurn ? window.centerY() : window.centerX();
                float half = (quarterTurn ? window.height() : window.width()) / 2f * keep;
                if (quarterTurn) {
                    window.top = centre - half;
                    window.bottom = centre + half;
                } else {
                    window.left = centre - half;
                    window.right = centre + half;
                }
            } else if (shownAspect < viewAspect) {
                float keep = shownAspect / viewAspect;
                float centre = quarterTurn ? window.centerX() : window.centerY();
                float half = (quarterTurn ? window.width() : window.height()) / 2f * keep;
                if (quarterTurn) {
                    window.left = centre - half;
                    window.right = centre + half;
                } else {
                    window.top = centre - half;
                    window.bottom = centre + half;
                }
            }
        } else {
            if (shownAspect < viewAspect) {
                destWidth = viewHeight * shownAspect;
            } else if (shownAspect > viewAspect) {
                destHeight = viewWidth / shownAspect;
            }
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
