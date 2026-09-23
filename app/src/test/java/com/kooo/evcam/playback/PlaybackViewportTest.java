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
        assertNull(PlaybackViewport.imageRects(PlaybackViewport.NO_CELL, 0, 0, VIEW_W, VIEW_H));
        assertNull(PlaybackViewport.imageRects(PlaybackViewport.NO_CELL, VIDEO, VIDEO, 0, 0));
    }

    /**
     * 照片这边的源矩形用<b>图片像素</b>，不是视图坐标。
     *
     * <p>这一条就是那个 bug 的形状：把视图坐标当图片坐标喂给 ImageView 的矩阵，
     * 画面只是挪了挪位置，该放大的一点没大。</p>
     */
    @Test
    public void imageRectsSourceTheImagesOwnPixels() {
        float[] r = PlaybackViewport.imageRects(3, VIDEO, VIDEO, VIEW_W, VIEW_H);
        assertEquals("右下那一格从图片正中间开始", VIDEO / 2f, r[0], TOLERANCE);
        assertEquals(VIDEO / 2f, r[1], TOLERANCE);
        assertEquals("一直到图片的右下角", VIDEO, r[2], TOLERANCE);
        assertEquals(VIDEO, r[3], TOLERANCE);
    }

    /** 放大一格，画面得真的大一倍 —— 2×2 里的一格占的边长正好是整张的一半。 */
    @Test
    public void zoomingAnImageCellActuallyEnlargesIt() {
        float[] whole = PlaybackViewport.imageRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, VIEW_W, VIEW_H);
        float wholeScale = (whole[6] - whole[4]) / (whole[2] - whole[0]);
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            float[] zoomed = PlaybackViewport.imageRects(cell, VIDEO, VIDEO, VIEW_W, VIEW_H);
            float scale = (zoomed[6] - zoomed[4]) / (zoomed[2] - zoomed[0]);
            assertEquals("第 " + cell + " 格应当正好放大一倍", wholeScale * 2f, scale, TOLERANCE);
        }
    }

    /** 放大前后占的那块屏幕是同一块，画面不会跳到别处去。 */
    @Test
    public void zoomingAnImageKeepsTheSameDestination() {
        float[] whole = PlaybackViewport.imageRects(
                PlaybackViewport.NO_CELL, VIDEO, VIDEO, VIEW_W, VIEW_H);
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            float[] zoomed = PlaybackViewport.imageRects(cell, VIDEO, VIDEO, VIEW_W, VIEW_H);
            for (int i = 4; i < 8; i++) {
                assertEquals("目标矩形不该动", whole[i], zoomed[i], TOLERANCE);
            }
        }
    }

    /**
     * 两套取景的源在不同的坐标系里，不能互相替用。
     *
     * <p>写成测试是因为它们长得太像：同样的参数、同样的返回，
     * 只有源的单位不一样 —— 混用了编译器不会说话，屏幕上也只是「有点不对」。</p>
     */
    @Test
    public void theTwoViewportsDoNotShareACoordinateSpace() {
        float[] forTexture = PlaybackViewport.transformRects(3, VIDEO, VIDEO, VIEW_W, VIEW_H);
        float[] forImage = PlaybackViewport.imageRects(3, VIDEO, VIDEO, VIEW_W, VIEW_H);
        assertNotEquals("TextureView 那套的源是视图坐标", forImage[0], forTexture[0], TOLERANCE);
        assertEquals("视图坐标里右下格从视图中线起", VIEW_W / 2f, forTexture[0], TOLERANCE);
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

    /**
     * 四格按方向命名，而且和超级后视镜是同一套映射。
     *
     * <p>左上 前、右上 后、左下 左、右下 右 —— 后视镜按「后 → 左 → 前 → 右」
     * 顺时针遍历，只给「后」做镜像，两边对不上的话就是有一边错了。</p>
     */
    @Test
    public void cellsAreNamedByDirection() {
        assertEquals(R.string.zeekr_lane_front, PlaybackViewport.labelRes(0));
        assertEquals(R.string.zeekr_lane_back, PlaybackViewport.labelRes(1));
        assertEquals(R.string.zeekr_lane_left, PlaybackViewport.labelRes(2));
        assertEquals(R.string.zeekr_lane_right, PlaybackViewport.labelRes(3));

        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int cell = 0; cell < PlaybackViewport.CELL_COUNT; cell++) {
            assertNotEquals("每一格都该有名字", 0, PlaybackViewport.labelRes(cell));
            assertTrue("四个名字不能重复", seen.add(PlaybackViewport.labelRes(cell)));
        }
        assertEquals(R.string.zeekr_mode_grid,
                PlaybackViewport.labelRes(PlaybackViewport.NO_CELL));
    }
}
