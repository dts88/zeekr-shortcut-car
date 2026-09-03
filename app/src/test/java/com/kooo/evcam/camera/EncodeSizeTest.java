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

    /** 极氪的合成条带 + 四宫格：1280×5140 拼成 2560×2560。 */
    @Test
    public void compositeStripBecomesASquareGrid() {
        EncodeSize size = EncodeSize.forSource(1280, 5140, true);
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
        EncodeSize size = EncodeSize.forSource(1280, 5140, false);
        assertFalse(size.grid);
        assertTrue("高度不能超过编码器上限，实际 " + size.height, size.height <= EncodeSize.MAX_SIDE);
        assertEquals(0, size.width % 2);
        assertEquals(0, size.height % 2);
    }

    /** 普通尺寸原样通过。 */
    @Test
    public void ordinarySizePassesThrough() {
        EncodeSize size = EncodeSize.forSource(1920, 1080, true);
        assertFalse("16:9 不是条带，不该拼四宫格", size.grid);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
    }

    /** 3840×2160 在上限之内，原样通过。 */
    @Test
    public void fourKFitsWithoutScaling() {
        EncodeSize size = EncodeSize.forSource(3840, 2160, true);
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
