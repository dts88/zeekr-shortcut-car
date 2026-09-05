package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link EncodeSize} 的单元测试。
 *
 * <p>这段规则有两个使用方：录制链路按它配置编码器，设置界面按它算目标码率。
 * 钉住它，界面上写的那个码率才可能是真的。</p>
 */
public class EncodeSizeTest {

    /** 合成流那一路。拆不拆要看相机 + 分辨率，所以测试里得先登记。 */
    private static final String COMPOSITE = "2";

    @org.junit.Before
    public void registerCompositeCamera() {
        com.kooo.evcam.zeekr.StreamLayoutTable.setCompositeCameraId(COMPOSITE);
    }

    @org.junit.After
    public void clearCompositeCamera() {
        com.kooo.evcam.zeekr.StreamLayoutTable.reset();
    }

    /** 极氪的合成条带 + 四宫格：1280×5140 拼成 2560×2560。 */
    @Test
    public void compositeStripBecomesASquareGrid() {
        EncodeSize size = EncodeSize.forSource(COMPOSITE, 1280, 5140, true);
        assertTrue("应当走四宫格重排", size.grid);
        assertEquals(2560, size.width);
        assertEquals(2560, size.height);
    }

    /**
     * 选「原始长条」时不重排。
     *
     * <p>5140 超过编码器 4096 的上限，所以会被等比缩小 —— 这正是四宫格
     * 顺带解决掉的那个问题。</p>
     */
    @Test
    public void rawStripIsScaledDownToTheEncoderCeiling() {
        EncodeSize size = EncodeSize.forSource(COMPOSITE, 1280, 5140, false);
        assertFalse(size.grid);
        assertTrue("高度不能超过编码器上限，实际 " + size.height, size.height <= EncodeSize.MAX_SIDE);
        assertEquals(0, size.width % 2);
        assertEquals(0, size.height % 2);
    }

    /** 座舱那一路的普通尺寸原样通过 —— 它不是合成流，不拆。 */
    @Test
    public void ordinarySizePassesThrough() {
        EncodeSize size = EncodeSize.forSource("0", 1920, 1080, true);
        assertFalse("座舱不该拼四宫格", size.grid);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
    }

    /**
     * 合成流那一路的同一个尺寸会拆。
     *
     * <p>这一路在任何分辨率下给的都是同一份四格内容，所以 1920×1080 也是四格 ——
     * 每格 1920×270，2×2 拼出来 3840×540。</p>
     */
    @Test
    public void theSameSizeSplitsOnTheCompositeCamera() {
        EncodeSize size = EncodeSize.forSource(COMPOSITE, 1920, 1080, true);
        assertTrue(size.grid);
        assertEquals(3840, size.width);
        assertEquals(540, size.height);
    }

    /**
     * 3840×2160 的四宫格：单格 3840×540，2×2 是 7680×1080，超宽要缩。
     *
     * <p>顺带说明一件事：这个尺寸<b>不比 1280×5140 清晰</b>。它的单格只有
     * 540 行，而 1280×5140 的单格是 1280×1280 —— 分辨率数字更大，
     * 每一路拿到的像素反而更少。</p>
     */
    @Test
    public void theFourKGridIsWideAndGetsScaledDown() {
        EncodeSize size = EncodeSize.forSource(COMPOSITE, 3840, 2160, true);
        assertTrue("应当走四宫格重排", size.grid);
        assertTrue("宽不能超过编码器上限，实际 " + size.width, size.width <= EncodeSize.MAX_SIDE);
        assertEquals("2x2 的宽高比应等于单格宽高比 3840:540 = 7.11",
                7.11f, size.width / (float) size.height, 0.1f);
    }

    /** 座舱那两路的 3840×2160 原样通过 —— 它们不是合成流，不拆。 */
    @Test
    public void fourKOnACabinCameraPassesThrough() {
        EncodeSize size = EncodeSize.forSource("0", 3840, 2160, true);
        assertFalse(size.grid);
        assertEquals(3840, size.width);
        assertEquals(2160, size.height);
    }

    /** 尺寸非法时不要抛，返回 0 让调用方自己决定怎么显示。 */
    @Test
    public void invalidSourceYieldsZero() {
        assertEquals(0, EncodeSize.forSource(0, 0, true).width);
        assertEquals(0, EncodeSize.forSource(-1, 100, false).width);
    }
}
