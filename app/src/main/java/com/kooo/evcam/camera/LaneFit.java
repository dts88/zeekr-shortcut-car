package com.kooo.evcam.camera;

/**
 * 一格画面摆在一个框里的两条算术。
 *
 * <h3>为什么裁剪不该让画面跳位置</h3>
 *
 * <p>原来的做法是：裁掉一块之后按<b>剩下那块</b>的比例重新摆一次。于是「上边裁
 * 20%」的结果是整幅画面变小、上下多出两条黑边、位置也挪了 —— 用户要的是少看一点
 * 车头，得到的是一格缩水。看上去就像裁剪坏了。</p>
 *
 * <p>现在框按<b>没裁之前</b>的比例定，裁完的部分把同一个框填满，多出来的那一边
 * 居中裁掉，不拉伸 —— 和超级后视镜填满窗口的做法一致。代价说清楚：裁上下也会
 * 从左右各切掉一点，因为框的形状没变。</p>
 *
 * <p>纯函数，不碰 Android，可以单独测。</p>
 */
public final class LaneFit {

    private LaneFit() {
    }

    /**
     * 把比例为 {@code aspect} 的画面居中放进 {@code cellW × cellH}，保持比例。
     *
     * @param out 写入 {left, top, width, height}
     */
    public static void fitCentred(float cellW, float cellH, float aspect, float[] out) {
        float width = cellW;
        float height = cellH;
        if (aspect > 0f && cellW > 0f && cellH > 0f) {
            float cellAspect = cellW / cellH;
            if (aspect < cellAspect) {
                width = cellH * aspect;
            } else if (aspect > cellAspect) {
                height = cellW / aspect;
            }
        }
        out[0] = (cellW - width) / 2f;
        out[1] = (cellH - height) / 2f;
        out[2] = width;
        out[3] = height;
    }

    /**
     * 比例为 {@code contentAspect} 的内容要填满比例为 {@code targetAspect} 的框，
     * 横纵各该保留多少（0–1）。多出来的那一边居中裁掉。
     *
     * @param out 写入 {keepX, keepY}，其中至少一个是 1
     */
    public static void cover(float contentAspect, float targetAspect, float[] out) {
        out[0] = 1f;
        out[1] = 1f;
        if (contentAspect <= 0f || targetAspect <= 0f) {
            return;
        }
        if (contentAspect > targetAspect) {
            // 内容太宽：横向少取一点
            out[0] = targetAspect / contentAspect;
        } else if (contentAspect < targetAspect) {
            // 内容太高：纵向少取一点
            out[1] = contentAspect / targetAspect;
        }
    }
}
