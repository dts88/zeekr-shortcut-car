package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link LaneFit} 的单元测试。
 *
 * <p>钉的是一句话：<b>裁剪不改变这一格画面的位置和大小</b>。裁完只是取的那块变了，
 * 框还在原地 —— 这正是「剪裁看起来坏了」那次报障的根。</p>
 */
public class LaneFitTest {

    private static final float EPS = 0.001f;

    /** 画面比格子窄：左右留白，上下顶满。 */
    @Test
    public void aNarrowPictureIsLetterboxedSideways() {
        float[] out = new float[4];
        LaneFit.fitCentred(1000f, 700f, 1f, out);
        assertEquals(700f, out[2], EPS);
        assertEquals(700f, out[3], EPS);
        assertEquals("左右各留一半", 150f, out[0], EPS);
        assertEquals(0f, out[1], EPS);
    }

    /** 画面比格子宽：上下留白。 */
    @Test
    public void aWidePictureIsLetterboxedVertically() {
        float[] out = new float[4];
        LaneFit.fitCentred(1000f, 700f, 2f, out);
        assertEquals(1000f, out[2], EPS);
        assertEquals(500f, out[3], EPS);
        assertEquals(0f, out[0], EPS);
        assertEquals(100f, out[1], EPS);
    }

    /** 比例正好：铺满，不留白。 */
    @Test
    public void anExactFitFillsTheCell() {
        float[] out = new float[4];
        LaneFit.fitCentred(1000f, 500f, 2f, out);
        assertEquals(1000f, out[2], EPS);
        assertEquals(500f, out[3], EPS);
        assertEquals(0f, out[0], EPS);
        assertEquals(0f, out[1], EPS);
    }

    /** 比例一样时什么都不裁。 */
    @Test
    public void coverKeepsEverythingWhenTheAspectsMatch() {
        float[] out = new float[2];
        LaneFit.cover(1.5f, 1.5f, out);
        assertEquals(1f, out[0], EPS);
        assertEquals(1f, out[1], EPS);
    }

    /**
     * 上边裁掉 20% 之后，剩下的要填满同一个框。
     *
     * <p>正方形画面裁掉 20% 的高，比例变成 1.25；框还是 1.0，
     * 所以横向要少取 1/1.25 = 0.8 —— 左右各切掉 10%。这是「框不动」的代价，
     * 换来的是裁剪之后画面不跳位置。</p>
     */
    @Test
    public void croppingTheTopAlsoTrimsTheSides() {
        float[] out = new float[2];
        LaneFit.cover(1.25f, 1f, out);
        assertEquals(0.8f, out[0], EPS);
        assertEquals(1f, out[1], EPS);
    }

    /** 反过来：内容太高，纵向少取。 */
    @Test
    public void aTallContentIsTrimmedVertically() {
        float[] out = new float[2];
        LaneFit.cover(0.5f, 1f, out);
        assertEquals(1f, out[0], EPS);
        assertEquals(0.5f, out[1], EPS);
    }

    /** 零和负数不能算出零尺寸的框。 */
    @Test
    public void degenerateInputsFallBackToTheCell() {
        float[] fit = new float[4];
        LaneFit.fitCentred(1000f, 700f, 0f, fit);
        assertEquals(1000f, fit[2], EPS);
        assertEquals(700f, fit[3], EPS);

        float[] cover = new float[2];
        LaneFit.cover(0f, 1f, cover);
        assertEquals(1f, cover[0], EPS);
        assertEquals(1f, cover[1], EPS);
    }
}
