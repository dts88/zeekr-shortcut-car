package com.kooo.evcam.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.R;

import org.junit.Test;

/**
 * {@link PlaybackViewport} 的单元测试。
 *
 * <p>取景算错的表现是「画面偏了一格」或「拉变形了」—— 都属于看着别扭但说不清
 * 哪里不对的那类问题，钉在这里比在车上盯着屏幕猜要省事得多。</p>
 */
public class PlaybackViewportTest {

    private static final float TOLERANCE = 0.01f;

    /** 一块 1600×900 的视图，放一段 2560×2560 的环视录像。 */
    private static final int VIEW_W = 1600;
    private static final int VIEW_H = 900;
    private static final int VIDEO = 2560;

    @Test
    public void tapsMapToTheExpectedQuadrant() {
        assertEquals(0, PlaybackViewport.cellAt(10, 10, VIEW_W, VIEW_H));
        assertEquals(1, PlaybackViewport.cellAt(VIEW_W - 10, 10, VIEW_W, VIEW_H));
        assertEquals(2, PlaybackViewport.cellAt(10, VIEW_H - 10, VIEW_W, VIEW_H));
        assertEquals(3, PlaybackViewport.cellAt(VIEW_W - 10, VIEW_H - 10, VIEW_W, VIEW_H));
    }

    /** 正中间算右下 —— 边界归属得是确定的，不能两格都认或都不认。 */
    @Test
    public void theExactCentreBelongsToOneQuadrant() {
        assertEquals(3, PlaybackViewport.cellAt(VIEW_W / 2f, VIEW_H / 2f, VIEW_W, VIEW_H));
    }

    @Test
    public void anInvalidViewYieldsNoCell() {
        assertEquals(PlaybackViewport.NO_CELL, PlaybackViewport.cellAt(10, 10, 0, 0));
        assertNull(PlaybackViewport.transformRects(PlaybackViewport.NO_CELL, 0, 0, VIEW_W, VIEW_H));
        assertNull(PlaybackViewport.transformRects(PlaybackViewport.NO_CELL, VIDEO, VIDEO, 0, 0));
    }

    /** 方形视频放进宽视图，应当留左右黑边而不是横向拉伸。 */
    @Test
    public void aSquareVideoIsLetterboxedRatherThanStretched() {
        float[] r = PlaybackViewport.transformRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, VIEW_W, VIEW_H);
        float destWidth = r[6] - r[4];
        float destHeight = r[7] - r[5];
        assertEquals("方形视频的目标区域也应当是方的", destHeight, destWidth, TOLERANCE);
        assertEquals("高度应当吃满视图", VIEW_H, destHeight, TOLERANCE);
        assertTrue("左右应当有黑边", r[4] > 0);
        assertEquals("应当左右居中", r[4], VIEW_W - r[6], TOLERANCE);
    }

    /** 不放大时，源矩形就是整块视图。 */
    @Test
    public void theWholePictureSourcesTheEntireView() {
        float[] r = PlaybackViewport.transformRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, VIEW_W, VIEW_H);
        assertEquals(0f, r[0], TOLERANCE);
        assertEquals(0f, r[1], TOLERANCE);
        assertEquals(VIEW_W, r[2], TOLERANCE);
        assertEquals(VIEW_H, r[3], TOLERANCE);
    }

    /** 每一格的源矩形应当正好是视图的四分之一。 */
    @Test
    public void eachQuadrantSourcesItsOwnCorner() {
        float halfWidth = VIEW_W / 2f;
        float halfHeight = VIEW_H / 2f;
        float[][] expected = {
                {0, 0}, {halfWidth, 0}, {0, halfHeight}, {halfWidth, halfHeight},
        };
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            float[] r = PlaybackViewport.transformRects(cell, VIDEO, VIDEO, VIEW_W, VIEW_H);
            assertEquals("格 " + cell + " 左边界", expected[cell][0], r[0], TOLERANCE);
            assertEquals("格 " + cell + " 上边界", expected[cell][1], r[1], TOLERANCE);
            assertEquals("格 " + cell + " 宽", halfWidth, r[2] - r[0], TOLERANCE);
            assertEquals("格 " + cell + " 高", halfHeight, r[3] - r[1], TOLERANCE);
        }
    }

    /** 放大一路与整幅显示占的位置一样大 —— 2×2 等分，比例不变。 */
    @Test
    public void zoomingKeepsTheSameDestination() {
        float[] whole = PlaybackViewport.transformRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, VIEW_W, VIEW_H);
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            float[] zoomed = PlaybackViewport.transformRects(cell, VIDEO, VIDEO, VIEW_W, VIEW_H);
            for (int i = 4; i < 8; i++) {
                assertEquals("格 " + cell + " 目标矩形应与整幅一致", whole[i], zoomed[i], TOLERANCE);
            }
        }
    }

    /** 反过来：宽视频放进窄视图，应当留上下黑边。 */
    @Test
    public void aWideVideoIsLetterboxedTopAndBottom() {
        float[] r = PlaybackViewport.transformRects(
                PlaybackViewport.NO_CELL, 1920, 1080, 800, 800);
        assertEquals("宽度应当吃满视图", 800f, r[6] - r[4], TOLERANCE);
        assertTrue("上下应当有黑边", r[5] > 0);
        assertEquals("应当上下居中", r[5], 800 - r[7], TOLERANCE);
        assertEquals("应当保持 16:9", 16f / 9f, (r[6] - r[4]) / (r[7] - r[5]), 0.01f);
    }

    /** 视图正好就是视频比例时，不该留黑边。 */
    @Test
    public void aMatchingAspectFillsTheView() {
        float[] r = PlaybackViewport.transformRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, 900, 900);
        assertEquals(0f, r[4], TOLERANCE);
        assertEquals(0f, r[5], TOLERANCE);
        assertEquals(900f, r[6], TOLERANCE);
        assertEquals(900f, r[7], TOLERANCE);
    }

    /** 右上是后方 —— 这是实车确认过的唯一一格，只有它能指向那句文案。 */
    @Test
    public void onlyTheConfirmedDirectionIsNamed() {
        assertEquals(R.string.cell_top_right_rear, PlaybackViewport.labelRes(1));
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            assertNotEquals("每一格都该有名字", 0, PlaybackViewport.labelRes(cell));
            if (cell != 1) {
                assertNotEquals("只有右上确认过是后方",
                        R.string.cell_top_right_rear, PlaybackViewport.labelRes(cell));
            }
        }
        assertEquals(R.string.zeekr_mode_grid,
                PlaybackViewport.labelRes(PlaybackViewport.NO_CELL));
    }
}
