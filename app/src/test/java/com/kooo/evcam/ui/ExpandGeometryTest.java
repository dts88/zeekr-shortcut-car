package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ExpandGeometry} 的单元测试。
 *
 * <p>过渡的起点是「这一块此刻在屏幕上的位置」。算错的话，看到的就是画面从别处长出来 ——
 * 三路布局里环视那一格以前正是这样。所以把「看得见的那块落在哪」钉住。</p>
 */
public class ExpandGeometryTest {

    private static final float EPS = 1e-3f;
    /** 铺满之后的预览区，屏幕坐标 {left, top, width, height}。 */
    private static final float[] FULL = {100f, 50f, 1600f, 900f};

    @Test
    public void atTheLaidOutRectNothingIsTransformed() {
        ExpandGeometry frame = ExpandGeometry.frame(FULL, FULL);
        assertEquals(1f, frame.scale, EPS);
        assertEquals(0f, frame.translationX, EPS);
        assertEquals(0f, frame.translationY, EPS);
        assertEquals(0f, frame.clipLeft, EPS);
        assertEquals(0f, frame.clipTop, EPS);
        assertEquals(1600f, frame.clipRight, EPS);
        assertEquals(900f, frame.clipBottom, EPS);
    }

    /** 这一帧看得见的那块，在屏幕上正好落在要显示的矩形上。 */
    @Test
    public void theVisiblePartLandsExactlyOnTheShownRect() {
        float[][] cases = {
                {140f, 80f, 560f, 400f},    // 环视里左上那一格
                {1100f, 60f, 580f, 420f},   // 右边一列里的座舱
                {300f, 200f, 200f, 800f},   // 竖长的一块
        };
        for (float[] shown : cases) {
            ExpandGeometry frame = ExpandGeometry.frame(FULL, shown);
            assertEquals(shown[0], FULL[0] + frame.translationX + frame.scale * frame.clipLeft, EPS);
            assertEquals(shown[1], FULL[1] + frame.translationY + frame.scale * frame.clipTop, EPS);
            assertEquals(shown[0] + shown[2],
                    FULL[0] + frame.translationX + frame.scale * frame.clipRight, EPS);
            assertEquals(shown[1] + shown[3],
                    FULL[1] + frame.translationY + frame.scale * frame.clipBottom, EPS);
        }
    }

    /** 等比缩放、居中裁切：画面不变形，两边裁掉的一样多，裁切框不出视图。 */
    @Test
    public void scalesUniformlyAndCropsCentred() {
        float[] shown = {1100f, 60f, 580f, 420f};
        ExpandGeometry frame = ExpandGeometry.frame(FULL, shown);
        float clipWidth = frame.clipRight - frame.clipLeft;
        float clipHeight = frame.clipBottom - frame.clipTop;
        assertEquals(580f / 420f, clipWidth / clipHeight, EPS);
        assertEquals(frame.clipLeft, 1600f - frame.clipRight, EPS);
        assertEquals(frame.clipTop, 900f - frame.clipBottom, EPS);
        assertTrue(frame.clipLeft >= -EPS && frame.clipTop >= -EPS);
        assertTrue(frame.clipRight <= 1600f + EPS && frame.clipBottom <= 900f + EPS);
    }

    @Test
    public void lerpMovesEveryEdgeProportionally() {
        float[] from = {0f, 0f, 100f, 50f};
        float[] to = {100f, 50f, 300f, 250f};
        float[] middle = ExpandGeometry.lerp(from, to, 0.5f);
        assertEquals(50f, middle[0], EPS);
        assertEquals(25f, middle[1], EPS);
        assertEquals(200f, middle[2], EPS);
        assertEquals(150f, middle[3], EPS);
    }

    /** 量不出尺寸（还没排版）时不动视图。 */
    @Test
    public void anEmptyRectLeavesTheViewAlone() {
        ExpandGeometry frame = ExpandGeometry.frame(FULL, new float[]{0f, 0f, 0f, 0f});
        assertEquals(1f, frame.scale, EPS);
        assertEquals(0f, frame.translationX, EPS);
        assertEquals(0f, frame.translationY, EPS);
    }
}
